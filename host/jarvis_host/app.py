from __future__ import annotations

import base64
import secrets
import time
from typing import Annotated

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .store import Store

app = FastAPI(title="JARVIS Secure Host", version="0.1.0")
store = Store()

# Short-lived challenges are kept in memory. They are intentionally single-use.
challenges: dict[str, tuple[str, float]] = {}


class PairRequest(BaseModel):
    code: str = Field(pattern=r"^\d{8}$")
    device_name: str = Field(min_length=1, max_length=80)
    public_key_pem: str = Field(min_length=50, max_length=4096)


class PairResponse(BaseModel):
    device_id: str
    challenge: str


class ChallengeResponse(BaseModel):
    challenge: str
    expires_in: int


class AuthenticateRequest(BaseModel):
    device_id: str
    signature_b64: str


def _load_public_key(pem: str):
    key = serialization.load_pem_public_key(pem.encode("utf-8"))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("Only ECDSA P-256 public keys are accepted")
    return key


def _new_challenge(device_id: str) -> str:
    challenge = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode("ascii")
    challenges[device_id] = (challenge, time.time() + 60)
    return challenge


@app.get("/health")
def health():
    return {"status": "online", "service": "jarvis-host", "version": app.version}


@app.post("/pair", response_model=PairResponse)
def pair(request: PairRequest):
    if not store.consume_pairing(request.code):
        raise HTTPException(status_code=401, detail="Pairing code is invalid, expired, or already used")
    try:
        _load_public_key(request.public_key_pem)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="Invalid P-256 public key") from exc

    device_id = store.add_device(request.device_name.strip(), request.public_key_pem)
    challenge = _new_challenge(device_id)
    return PairResponse(device_id=device_id, challenge=challenge)


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
        signature = base64.urlsafe_b64decode(request.signature_b64.encode("ascii"))
        key.verify(signature, challenge_value.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    except Exception as exc:
        raise HTTPException(status_code=401, detail="Invalid device signature") from exc

    # v0.1 deliberately returns a placeholder. A later version will mint a
    # short-lived signed session token with scopes instead of exposing tools here.
    return {"authenticated": True, "device_id": request.device_id}


@app.get("/devices")
def devices(authorization: Annotated[str | None, Header()] = None):
    # Local/admin UI only in v0.1. Do not expose this route through the public gateway.
    if authorization != "Bearer LOCAL_ADMIN":
        raise HTTPException(status_code=403, detail="Admin authentication required")
    return [dict(row) for row in store.list_devices()]


@app.post("/devices/{device_id}/revoke")
def revoke(device_id: str, authorization: Annotated[str | None, Header()] = None):
    if authorization != "Bearer LOCAL_ADMIN":
        raise HTTPException(status_code=403, detail="Admin authentication required")
    if not store.revoke_device(device_id):
        raise HTTPException(status_code=404, detail="Device not found")
    return {"revoked": True, "device_id": device_id}
