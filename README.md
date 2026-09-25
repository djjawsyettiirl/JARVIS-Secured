# JARVIS Secured — 2.2.1 Beta

JARVIS is a multi-device AI assistant built around a Windows host and a securely paired Android companion.

> **Current public beta: 2.2.1-beta**
>
> **Beta software:** expect bugs. Please report reproducible issues through GitHub Issues.

## Download / beta testing

The goal of 2.2.1 Beta is to make testing straightforward for people who do not want to build JARVIS from source.

- **Windows:** a proper installer is being prepared from the packaged Windows binary.
- **Android direct beta:** a signed APK is the direct-download testing build.
- **Google Play distribution:** temporarily shelved; Play-specific development is kept under `temporary-development/` until resumed.
- Use only packages produced by this repository's signed build workflows.

When public GitHub Release assets are published, use the **Releases** page rather than downloading source-code archives.

## What works today

### Windows and Android

- Shared Ask JARVIS interface with voice input and spoken replies.
- Locally rendered live 3D avatars with idle, listening, thinking, and speaking states.
- Picture attachments.
- Installed voice selection and private custom voice-sample controls.
- Branded launch screens.
- Web, Images, and Videos search.
- Private SearXNG search with optional SerpAPI fallback.
- Private messaging between paired devices.
- Matched Windows and Android update system.
- Permission-gated Coding Mode with diff preview and explicit approval before writes.

### Android

- Keyboard-safe chat layout.
- One-time secure pairing with automatic reconnection.
- Offline “Jarvis” wake-phrase detection through a foreground service.
- Android default-assistant role support.
- Microphone-session ownership tracking.
- Limited mode for selected actions when Windows is unreachable.
- Separately controlled awareness categories.
- Android Keystore protection for transferred credentials.

### Security and privacy

- Pairing codes are single-use and expire after five minutes.
- Android devices create their own ECDSA P-256 key pair; private keys remain on-device.
- Signed challenges and short-lived sessions authenticate paired devices.
- Devices can be revoked or permanently forgotten from Windows.
- Sensitive awareness data stays on Android by default.
- Screen context is opt-in.
- Coding projects are confined to the configured JARVIS workspace.

## Quick start

### Windows

Install the 2.2.1 Beta Windows package when it appears under GitHub Releases, launch JARVIS, then open **Settings** for pairing, search, permissions, and updates.

Developers can use [host/README.md](host/README.md).

### Android direct beta

Install the matching signed direct APK from the same 2.2.1 Beta release. In Windows JARVIS, generate a pairing code, then enter the host address and eight-digit code on Android.

See [android/README.md](android/README.md) for development and LAN testing.

## Internet search

JARVIS supports private SearXNG and an optional SerpAPI fallback. Configure these under **Settings → Internet search** on Windows.

## Coding Mode

Windows hosts Coding Mode so Android can request project changes without compiling on the phone. Proposals return a diff and expiring change ID; no file is written until the exact change is approved.

## Updates

Windows and Android builds share the repository `VERSION` and build identity. Mismatched packages are rejected by the update path.

For the public beta, official updates use an owner-controlled trust chain: embedded verification key → signed release manifest → artifact SHA-256 verification → platform signing identity → anti-rollback/version checks. Release signing credentials are never stored in the repository or distributed application. See [SECURITY.md](SECURITY.md).

## Current limitations

- This is beta software and has not yet been packaged as a mainstream consumer installer/store release.
- Wake-phrase recognition is local, while command transcription depends on Android's installed speech-recognition service.
- Android always-listening mode remains visible through its foreground-service notification.
- Host-dependent messaging and integrations wait or queue while Windows is unreachable.
- Google Play publishing is temporarily shelved while direct beta distribution is prioritized.

## Temporarily shelved development

Anything intentionally paused should live under [`temporary-development/`](temporary-development/) rather than being scattered through active release paths. See its README for the current shelved-development inventory.

## Repository layout

- `host/` — active Windows host and desktop application.
- `android/` — active Android direct-beta companion.
- `docs/` — active protocol, architecture, remote-access, and permission documentation.
- `temporary-development/` — paused/shelved development kept together until resumed.
- `.github/workflows/` — build and validation automation.

## Feedback

Beta testers: please include your platform, JARVIS version, what you expected, what happened, and reproduction steps when reporting a problem.

JARVIS 2.2.1 Beta is under active development. Public builds are intended to start clean: developer accounts, credentials, pairing state, messages, private memory, local uploads, and other personal runtime data are not part of the distributed package.
