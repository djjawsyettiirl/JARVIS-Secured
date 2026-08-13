import uvicorn
from jarvis_host.app import app, store

if __name__ == "__main__":
    code = store.create_pairing()
    print("JARVIS pairing code (one use, expires in 5 minutes):", code)
    print("Starting host on 0.0.0.0:8765 (use HTTPS/WSS via a reverse proxy for internet exposure).")
    uvicorn.run(app, host="0.0.0.0", port=8765)
