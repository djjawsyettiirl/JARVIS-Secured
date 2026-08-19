# JARVIS Secured

A secure, multi-device personal AI assistant. The Windows host is the primary node; Android devices pair to it using one-time enrollment codes and device-bound public-key authentication.

## v0.4 capabilities

- Windows host with FastAPI gateway
- One-time, expiring pairing codes
- Android client pairing flow
- Device-bound ECDSA public keys
- Authenticated challenge/response after pairing
- Device revocation
- Pairing persistence across Windows and Android restarts
- Short-lived authenticated assistant sessions
- Voice input and spoken replies on Windows and Android
- Local alarms, Google Maps actions, and Google-ready Gmail/Calendar integration
- Automatic Cloudflare Quick Tunnel startup in the packaged Windows build
- Live internet search through your private SearXNG server with optional SerpAPI fallback
- Permission-controlled SerpAPI credential sync for direct Android search when the Windows host is unreachable
- General voice-directed messaging between Android and the Windows home client

See `docs/architecture.md` for the protocol design.
