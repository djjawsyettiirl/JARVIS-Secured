from __future__ import annotations

import base64
import secrets
import time
from collections import defaultdict, deque

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .store import Store
from .assistant import respond
from .updater import updater

app = FastAPI(title="JARVIS Secure Host", version="0.4.1")
store = Store()
challenges: dict[str, tuple[str, float]] = {}
sessions: dict[str, tuple[str, float]] = {}
_pair_attempts: dict[str, deque[float]] = defaultdict(deque)


class PairRequest(BaseModel):
    code: str = Field(pattern=r"^\d{8}$")
    device_name: str = Field(min_length=1, max_length=80)
    public_key_pem: str = Field(min_length=50, max_length=4096)


class PairResponse(BaseModel):
    device_id: str
    challenge: str
    scopes: list[str]


class ChallengeResponse(BaseModel):
    challenge: str
    expires_in: int


class AuthenticateRequest(BaseModel):
    device_id: str
    signature_b64: str


class AssistantRequest(BaseModel):
    message: str = Field(min_length=1, max_length=1000)


class MessageRequest(BaseModel):
    body: str = Field(min_length=1, max_length=2000)


def _load_public_key(pem: str):
    key = serialization.load_pem_public_key(pem.encode("utf-8"))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("Only ECDSA P-256 public keys are accepted")
    return key


def _new_challenge(device_id: str) -> str:
    challenge = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii").rstrip("=")
    challenges[device_id] = (challenge, time.time() + 60)
    return challenge


def _new_session(device_id: str) -> tuple[str, int]:
    ttl = 60 * 60
    token = secrets.token_urlsafe(32)
    sessions[token] = (device_id, time.time() + ttl)
    return token, ttl


def _authenticated_device(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Authentication required")
    token = authorization.removeprefix("Bearer ").strip()
    record = sessions.get(token)
    if not record or record[1] < time.time():
        sessions.pop(token, None)
        raise HTTPException(status_code=401, detail="Session expired")
    device_id = record[0]
    row = store.get_device(device_id)
    if not row or row["revoked"]:
        sessions.pop(token, None)
        raise HTTPException(status_code=401, detail="Unknown or revoked device")
    return device_id


def _decode_signature_base64(value: str) -> bytes:
    """Decode Android signature text while accepting URL-safe or standard Base64."""
    normalized = value.strip()
    normalized += "=" * (-len(normalized) % 4)
    try:
        return base64.b64decode(normalized.encode("ascii"), altchars=b"-_", validate=True)
    except Exception:
        return base64.b64decode(normalized.encode("ascii"), validate=True)


def _normalize_ecdsa_signature(signature: bytes) -> bytes:
    """Return ASN.1 DER ECDSA signature bytes.

    Android normally emits DER for SHA256withECDSA, but some providers/devices can
    expose IEEE-P1363 r||s bytes. cryptography expects DER, so accept both formats.
    """
    if len(signature) == 64:
        r = int.from_bytes(signature[:32], "big")
        s = int.from_bytes(signature[32:], "big")
        return encode_dss_signature(r, s)
    return signature


def _client_ip(request: Request) -> str:
    # Cloudflare sets this header on tunneled requests. On LAN, fall back to the socket peer.
    cf_ip = request.headers.get("cf-connecting-ip")
    if cf_ip:
        return cf_ip.strip()
    return request.client.host if request.client else "unknown"


def _check_pair_rate_limit(request: Request) -> None:
    ip = _client_ip(request)
    now = time.time()
    window = _pair_attempts[ip]
    while window and window[0] < now - 60:
        window.popleft()
    if len(window) >= 10:
        raise HTTPException(status_code=429, detail="Too many pairing attempts. Wait one minute and try again.")
    window.append(now)


@app.get("/health")
def health():
    return {"status": "online", "service": "jarvis-host", "version": app.version}


@app.post("/pair", response_model=PairResponse)
def pair(request: PairRequest, http_request: Request):
    _check_pair_rate_limit(http_request)
    try:
        _load_public_key(request.public_key_pem)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Invalid P-256 public key") from exc
    if not store.consume_pairing(request.code):
        raise HTTPException(status_code=401, detail="Pairing code is invalid, expired, or already used")
    device_id = store.add_device(request.device_name.strip(), request.public_key_pem)
    challenge = _new_challenge(device_id)
    return PairResponse(device_id=device_id, challenge=challenge, scopes=store.get_scopes(device_id))


@app.post("/auth/challenge", response_model=ChallengeResponse)
def challenge(device_id: str):
    row = store.get_device(device_id)
    if not row or row["revoked"]:
        raise HTTPException(status_code=401, detail="Unknown or revoked device")
    return ChallengeResponse(challenge=_new_challenge(device_id), expires_in=60)


@app.post("/auth/verify")
def verify(request: AuthenticateRequest):
    row = store.get_device(request.device_id)
    if not row or row["revoked"]:
        raise HTTPException(status_code=401, detail="Unknown or revoked device")
    record = challenges.pop(request.device_id, None)
    if not record or record[1] < time.time():
        raise HTTPException(status_code=401, detail="Challenge expired or missing")
    challenge_value, _ = record
    try:
        key = _load_public_key(row["public_key_pem"])
        signature = _normalize_ecdsa_signature(_decode_signature_base64(request.signature_b64))
        key.verify(signature, challenge_value.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    except Exception as exc:
        raise HTTPException(status_code=401, detail="Invalid device signature") from exc
    token, expires_in = _new_session(request.device_id)
    return {
        "authenticated": True,
        "device_id": request.device_id,
        "scopes": store.get_scopes(request.device_id),
        "session_token": token,
        "expires_in": expires_in,
    }


@app.post("/assistant")
def assistant(request: AssistantRequest, authorization: str | None = Header(default=None)):
    device_id = _authenticated_device(authorization)
    if "chat" not in store.get_scopes(device_id):
        raise HTTPException(status_code=403, detail="This device does not have the chat capability")
    try:
        return respond(request.message, set(store.get_scopes(device_id)))
    except Exception as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/messages")
def send_message(request: MessageRequest, authorization: str | None = Header(default=None)):
    device_id = _authenticated_device(authorization)
    if "messaging" not in store.get_scopes(device_id):
        raise HTTPException(status_code=403, detail="This device does not have the messaging capability")
    return {"message_id": store.send_message(device_id, request.body.strip()), "sent": True}


@app.get("/messages")
def messages(authorization: str | None = Header(default=None)):
    device_id = _authenticated_device(authorization)
    if "messaging" not in store.get_scopes(device_id):
        raise HTTPException(status_code=403, detail="This device does not have the messaging capability")
    return store.device_messages(device_id)


@app.get("/updates/android/status")
def android_update_status(authorization: str | None = Header(default=None)):
    device_id = _authenticated_device(authorization)
    if "software_updates" not in store.get_scopes(device_id):
        raise HTTPException(status_code=403, detail="This device does not have the software_updates capability")
    return {"available": updater.android_apk.is_file()}


@app.get("/updates/android")
def android_update(authorization: str | None = Header(default=None)):
    device_id = _authenticated_device(authorization)
    if "software_updates" not in store.get_scopes(device_id):
        raise HTTPException(status_code=403, detail="This device does not have the software_updates capability")
    if not updater.android_apk.is_file():
        raise HTTPException(status_code=404, detail="No Android update is staged on the home host")
    return FileResponse(updater.android_apk, media_type="application/vnd.android.package-archive", filename="JARVIS-update.apk")

# IMPORTANT: device administration routes intentionally live only on the
# separate localhost-bound admin app (port 8766). The public gateway exposes
# only health, pairing, challenge, and verification endpoints.
