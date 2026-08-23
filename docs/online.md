# Putting JARVIS Online Safely

The gateway listens on TCP 8765. Do **not** port-forward this development HTTP listener directly to the internet.

## Recommended first online test: Cloudflare Quick Tunnel

1. Install `cloudflared` on the Windows host.
2. Start JARVIS normally with `py host\run_host.py`.
3. In a second terminal run:

```powershell
cloudflared tunnel --url http://127.0.0.1:8765
```

4. Cloudflare will print a temporary `https://...trycloudflare.com` URL.
5. Put that HTTPS URL into the Android JARVIS app and pair normally.

The public URL forwards only to the JARVIS gateway. The Windows control panel remains on `127.0.0.1:8766` and is not exposed.

## Production

For a permanent deployment, use a named Cloudflare Tunnel (or another managed reverse proxy) with a stable hostname and TLS. Add rate limiting/WAF controls before exposing the gateway broadly. Never expose the local admin panel.

The Android production build should also disable cleartext HTTP and require HTTPS for non-LAN hosts. The current debug build keeps cleartext enabled because it is used for the LAN pairing test.

## Important

Online transport security and device authentication are separate layers:

- HTTPS protects the network connection in transit.
- The one-time pairing code enrolls a device.
- The Android private key proves the device's identity afterward.
- Future permission scopes will determine what an authenticated device may do.
