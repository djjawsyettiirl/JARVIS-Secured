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
