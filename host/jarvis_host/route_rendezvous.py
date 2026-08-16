from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import time
import urllib.request

import keyring
from fastapi import APIRouter, Header

from . import tunnel
from .app import _authenticated_device
from .store import Store

router = APIRouter()
store = Store()

BROKER = os.environ.get("JARVIS_ROUTE_BROKER", "https://ntfy.sh").rstrip("/")
KEYRING_SERVICE = "JARVIS-Secured-route-handoff"
GENERATION_USER = "route-generation"
LAST_URL_USER = "route-last-url"


def _key_user(device_id: str, suffix: str) -> str:
    digest = hashlib.sha256(device_id.encode("utf-8")).hexdigest()[:24]
    return f"{digest}-{suffix}"


def _secret(device_id: str) -> str:
    user = _key_user(device_id, "secret")
    value = keyring.get_password(KEYRING_SERVICE, user)
    if value:
        return value
    value = secrets.token_urlsafe(32)
    keyring.set_password(KEYRING_SERVICE, user, value)
    return value


def _topic(device_id: str) -> str:
    user = _key_user(device_id, "topic")
    value = keyring.get_password(KEYRING_SERVICE, user)
    if value:
        return value
    value = "jarvis-route-" + secrets.token_hex(20)
    keyring.set_password(KEYRING_SERVICE, user, value)
    return value


def _generation() -> int:
    try:
        return int(keyring.get_password(KEYRING_SERVICE, GENERATION_USER) or "0")
    except ValueError:
        return 0


def _set_generation(value: int) -> None:
    keyring.set_password(KEYRING_SERVICE, GENERATION_USER, str(value))


def _sign(secret: str, generation: int, timestamp: int, remote_url: str) -> str:
    canonical = f"{generation}\n{timestamp}\n{remote_url}".encode("utf-8")
    digest = hmac.new(secret.encode("utf-8"), canonical, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def _publish(device_id: str, remote_url: str, generation: int) -> None:
    if not remote_url.startswith("https://"):
        return
    secret = _secret(device_id)
    topic = _topic(device_id)
    timestamp = int(time.time())
    payload = {
        "type": "jarvis_route_update",
        "generation": generation,
        "timestamp": timestamp,
        "remote_url": remote_url,
        "signature": _sign(secret, generation, timestamp, remote_url),
    }
    request = urllib.request.Request(
        f"{BROKER}/{topic}",
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={"Content-Type": "text/plain; charset=utf-8", "User-Agent": "JARVIS/0.5.6"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.status >= 300:
            raise RuntimeError(f"Route broker returned HTTP {response.status}")


def publish_if_changed(force: bool = False) -> bool:
    remote_url = (tunnel.public_url or "").strip().rstrip("/")
    if not remote_url.startswith("https://"):
        return False
    previous = keyring.get_password(KEYRING_SERVICE, LAST_URL_USER) or ""
    if not force and previous == remote_url:
        return False

    # Do not mark a route as delivered until every active paired device has had
    # its signed update accepted by the rendezvous service. If the service is
    # temporarily unavailable, the host loop retries this same generation.
    generation = _generation() + 1
    active_devices = [device for device in store.list_devices() if not device["revoked"]]
    for device in active_devices:
        _publish(str(device["device_id"]), remote_url, generation)

    _set_generation(generation)
    keyring.set_password(KEYRING_SERVICE, LAST_URL_USER, remote_url)
    return True


def publish_current_to_device(device_id: str) -> None:
    remote_url = (tunnel.public_url or "").strip().rstrip("/")
    if not remote_url.startswith("https://"):
        return
    generation = _generation()
    if generation <= 0:
        generation = 1
        _publish(device_id, remote_url, generation)
        _set_generation(generation)
        keyring.set_password(KEYRING_SERVICE, LAST_URL_USER, remote_url)
        return
    _publish(device_id, remote_url, generation)


@router.post("/route-rendezvous/register")
def register_route_rendezvous(authorization: str | None = Header(default=None)):
    device_id = _authenticated_device(authorization)
    secret = _secret(device_id)
    topic = _topic(device_id)
    try:
        publish_current_to_device(device_id)
    except Exception:
        pass
    return {
        "broker": BROKER,
        "topic": topic,
        "secret": secret,
        "generation": _generation(),
        "remote_url": (tunnel.public_url or "").strip().rstrip("/"),
    }
