# Windows host

## Run

```powershell
cd host
py -m pip install -r requirements.txt
py run_host.py
```

The host prints an 8-digit one-time pairing code. It expires after five minutes and is invalid after one successful pairing.

## Important security note

`run_host.py` is a development gateway. Do **not** expose the raw Uvicorn HTTP listener directly to the public internet. For an internet deployment, put the service behind a TLS reverse proxy or secure tunnel, restrict admin routes to localhost, and add a real session-token layer before enabling JARVIS tools.

## Test

```powershell
cd host
py -m pytest
```
