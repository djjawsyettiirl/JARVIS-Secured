# JARVIS Security Architecture

## Threat model

The Windows host may be reachable from the public internet. We therefore treat every remote connection as untrusted. A pairing code is an enrollment credential only; it is never a long-term password.

## Pairing

1. Host creates a cryptographically random, short-lived pairing record.
2. The UI displays a human-enterable code. The server stores only a salted hash of the code.
3. Android generates an ECDSA P-256 key pair locally. The private key never leaves Android.
4. Android submits the pairing code plus its public key and a device name.
5. The host verifies the code, marks it used, and registers the public key.
6. The host returns a device identifier and an authentication challenge.
7. Android signs the challenge with its private key. The host verifies the signature.

A pairing code expires after five minutes and is invalid after one successful enrollment attempt.

## Normal authentication

After enrollment, the Android client does not send the pairing code. It requests a fresh challenge and signs it with its device private key. The host verifies the signature against the registered public key and issues a short-lived session token.

## Transport

Production deployments should use HTTPS/WSS with a real TLS certificate. The application should not expose its raw development server directly to the internet. Put the gateway behind a reverse proxy or secure tunnel, and keep the Windows control plane bound to localhost/internal interfaces where possible.

## Revocation

Each registered device has an active/revoked state. Revocation immediately invalidates future authentication and session refreshes for that device.

## Permissions

Pairing does not grant unrestricted PC control. Device capabilities are separately authorized. Initial v0.1 has no dangerous computer-control endpoint. Future tools should use explicit permission scopes and confirmation for destructive actions.
