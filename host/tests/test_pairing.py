import base64

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from fastapi.testclient import TestClient

from jarvis_host.app import app, store


def key_pem():
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return key, public


def fresh_store(monkeypatch, tmp_path):
    monkeypatch.setattr(store, "path", tmp_path / "test.db")
    store._init()


def pair(client, key, public, code):
    response = client.post(
        "/pair",
        json={"code": code, "device_name": "Test Phone", "public_key_pem": public},
    )
    assert response.status_code == 200
    return response.json()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def test_pairing_is_single_use(monkeypatch, tmp_path):
    fresh_store(monkeypatch, tmp_path)
    code = store.create_pairing(ttl_seconds=300)
    _, public = key_pem()
    client = TestClient(app)
    payload = {"code": code, "device_name": "Test Phone", "public_key_pem": public}
    first = client.post("/pair", json=payload)
    assert first.status_code == 200
    second = client.post("/pair", json=payload)
    assert second.status_code == 401


def test_der_ecdsa_signature_authenticates(monkeypatch, tmp_path):
    fresh_store(monkeypatch, tmp_path)
    client = TestClient(app)
    key, public = key_pem()
    result = pair(client, key, public, store.create_pairing(ttl_seconds=300))
    signature = key.sign(result["challenge"].encode(), ec.ECDSA(hashes.SHA256()))
    response = client.post(
        "/auth/verify",
        json={"device_id": result["device_id"], "signature_b64": b64url(signature)},
    )
    assert response.status_code == 200
    assert response.json()["authenticated"] is True


def test_paired_identity_authenticates_from_a_different_address(monkeypatch, tmp_path):
    """A route or IP change must never require pairing the device again."""
    fresh_store(monkeypatch, tmp_path)
    client = TestClient(app)
    key, public = key_pem()
    code = store.create_pairing(ttl_seconds=300)
    paired = client.post(
        "/pair",
        headers={"cf-connecting-ip": "198.51.100.10"},
        json={"code": code, "device_name": "Roaming Phone", "public_key_pem": public},
    ).json()
    challenge = client.post(
        f"/auth/challenge?device_id={paired['device_id']}",
        headers={"cf-connecting-ip": "203.0.113.20"},
    ).json()["challenge"]
    signature = key.sign(challenge.encode(), ec.ECDSA(hashes.SHA256()))
    response = client.post(
        "/auth/verify",
        headers={"cf-connecting-ip": "203.0.113.20"},
        json={"device_id": paired["device_id"], "signature_b64": b64url(signature)},
    )

    assert response.status_code == 200
    assert response.json()["authenticated"] is True
    assert "routes" in response.json()


def test_p1363_android_signature_authenticates(monkeypatch, tmp_path):
    fresh_store(monkeypatch, tmp_path)
    client = TestClient(app)
    key, public = key_pem()
    result = pair(client, key, public, store.create_pairing(ttl_seconds=300))
    der = key.sign(result["challenge"].encode(), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    p1363 = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    response = client.post(
        "/auth/verify",
        json={"device_id": result["device_id"], "signature_b64": b64url(p1363)},
    )
    assert response.status_code == 200
    assert response.json()["authenticated"] is True


def test_unknown_device_cannot_authenticate():
    client = TestClient(app)
    response = client.post("/auth/challenge?device_id=does-not-exist")
    assert response.status_code == 401


def test_authenticated_assistant_requires_device_capability(monkeypatch, tmp_path):
    fresh_store(monkeypatch, tmp_path)
    client = TestClient(app)
    key, public = key_pem()
    result = pair(client, key, public, store.create_pairing(ttl_seconds=300))
    signature = key.sign(result["challenge"].encode(), ec.ECDSA(hashes.SHA256()))
    auth = client.post(
        "/auth/verify",
        json={"device_id": result["device_id"], "signature_b64": b64url(signature)},
    )
    token = auth.json()["session_token"]

    denied = client.post(
        "/assistant",
        headers={"Authorization": f"Bearer {token}"},
        json={"message": "navigate to Seattle"},
    )
    assert denied.status_code == 400
    assert "maps capability" in denied.json()["detail"]

    store.set_scopes(result["device_id"], ["chat", "maps"])
    allowed = client.post(
        "/assistant",
        headers={"Authorization": f"Bearer {token}"},
        json={"message": "navigate to Seattle"},
    )
    assert allowed.status_code == 200
    assert allowed.json()["action"]["type"] == "open_url"


def test_assistant_rejects_missing_session():
    client = TestClient(app)
    response = client.post("/assistant", json={"message": "hello"})
    assert response.status_code == 401


def authenticate_device(client, key, pairing_result):
    signature = key.sign(pairing_result["challenge"].encode(), ec.ECDSA(hashes.SHA256()))
    response = client.post(
        "/auth/verify",
        json={"device_id": pairing_result["device_id"], "signature_b64": b64url(signature)},
    )
    return response.json()["session_token"]


def test_paired_devices_can_exchange_messages(monkeypatch, tmp_path):
    fresh_store(monkeypatch, tmp_path)
    client = TestClient(app)
    first_key, first_public = key_pem()
    second_key, second_public = key_pem()
    first = pair(client, first_key, first_public, store.create_pairing())
    second = pair(client, second_key, second_public, store.create_pairing())
    store.set_scopes(first["device_id"], ["chat", "messaging", "location_share"])
    store.set_scopes(second["device_id"], ["chat", "messaging"])
    first_token = authenticate_device(client, first_key, first)
    second_token = authenticate_device(client, second_key, second)

    sent = client.post(
        "/messages",
        headers={"Authorization": f"Bearer {first_token}"},
        json={"body": "I'm here: https://www.google.com/maps/search/?api=1&query=47.6,-122.3"},
    )
    received = client.get("/messages", headers={"Authorization": f"Bearer {second_token}"})

    assert sent.status_code == 200
    assert received.status_code == 200
    assert received.json()[0]["sender_name"] == "Test Phone"
    assert "google.com/maps" in received.json()[0]["body"]
