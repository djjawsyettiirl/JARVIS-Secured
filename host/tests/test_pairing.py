from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from jarvis_host.app import app, store


def key_pem():
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return key, public


def test_pairing_is_single_use(monkeypatch, tmp_path):
    monkeypatch.setattr(store, "path", tmp_path / "test.db")
    store._init()
    code = store.create_pairing(ttl_seconds=300)
    _, public = key_pem()
    client = TestClient(app)
    payload = {"code": code, "device_name": "Test Phone", "public_key_pem": public}
    first = client.post("/pair", json=payload)
    assert first.status_code == 200
    second = client.post("/pair", json=payload)
    assert second.status_code == 401


def test_unknown_device_cannot_authenticate():
    client = TestClient(app)
    response = client.post("/auth/challenge?device_id=does-not-exist")
    assert response.status_code == 401
