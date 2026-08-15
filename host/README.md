# Windows host

## Run

From the repository root:

```powershell
py -m pip install -r host/requirements.txt
py host/run_host.py
```

The host prints an 8-digit one-time pairing code. It expires after five minutes and is invalid after one successful pairing.

Paired devices are retained in `%LOCALAPPDATA%\JARVIS\jarvis.db`. Existing
portable-build data in `%PROGRAMDATA%\JARVIS\jarvis.db` is copied forward on
first launch. Restarting either app does not require a new pairing code: Android
uses its hardware-backed key to obtain a fresh one-hour assistant session.

Find the PC's LAN IPv4 address with:

```powershell
ipconfig
```

Enter `http://PC_IP:8765` in the Android app. If Windows Firewall asks, allow the Python host on your **Private** network only.

## What the LAN test proves

- PC generates a one-time code.
- Android generates an ECDSA P-256 key in Android Keystore.
- Android submits the public key plus the code.
- PC consumes the code and registers the device.
- PC sends a challenge.
- Android signs it with its private key.
- PC verifies the signature.

## Important security note

`run_host.py` is a development gateway. Do **not** expose the raw Uvicorn HTTP listener directly to the public internet or port-forward 8765. The Android test build allows cleartext HTTP specifically for the LAN test. Before internet use, replace this with TLS/WSS, short-lived sessions, device-scoped permissions, and a hardened gateway.

## Host tests

```powershell
cd host
py -m pytest
```
