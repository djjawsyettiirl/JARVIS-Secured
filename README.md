# JARVIS Secured

A secure, multi-device personal AI assistant. The Windows host is the primary node; Android devices pair to it using one-time enrollment codes and device-bound public-key authentication.

## v0.1 goals

- Windows host with FastAPI gateway
- One-time, expiring pairing codes
- Android client pairing flow
- Device-bound ECDSA public keys
- Authenticated challenge/response after pairing
- Device revocation

See `docs/architecture.md` for the protocol design.
