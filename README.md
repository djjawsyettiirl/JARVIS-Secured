# JARVIS Secured

JARVIS is a private, multi-device assistant with a Windows home host and a paired Android companion. Windows manages device trust, internet search, messaging, and private updates. Android retains limited local abilities when the host is unavailable.

> Current private release: **1.9.0**
>
> Active development branch: **`v0.1-secure-pairing`**

## What works today

### Windows and Android

- A shared Ask JARVIS interface with voice input and spoken replies.
- Web, Images, and Videos search tabs with short, clickable results.
- Private SearXNG search with an optional SerpAPI fallback.
- Private messaging between Windows and paired Android devices.
- Signed, matched Windows and Android automatic updates.
- Custom JARVIS branding on both platforms.

### Android

- One-time secure pairing with automatic reconnection.
- Persistent offline “Jarvis” wake-phrase detection through a visible foreground service.
- Android default-assistant role support for configured gestures and buttons.
- Microphone-session ownership tracking: JARVIS yields while another app records and resumes after release.
- Android speech recognition runs only for the short command after the local wake word, eliminating continuous recognizer restart loops.
- Limited mode with direct SerpAPI search, Maps, email composition, and Calendar actions when Windows is unreachable.
- Seven separately controlled awareness categories: device, location, personal context, screen context, home devices, private memory, and proactive alerts.
- Encrypted storage of the transferred SerpAPI key using Android Keystore.

### Security and privacy

- One-time pairing codes expire after five minutes and cannot be reused.
- Each Android device creates its own ECDSA P-256 key pair; its private key never leaves the phone.
- Paired devices authenticate with signed challenges and short-lived sessions.
- Devices can be revoked or permanently forgotten from Windows.
- Sensitive awareness data stays on Android by default.
- Screen context is opt-in through an explicitly enabled accessibility service.
- Unneeded camera, contacts, call-log, SMS, and storage permissions are not requested.

## Getting started

### 1. Start the Windows host

Use the latest packaged Windows build. JARVIS opens its assistant window while the secure host and updater remain in the background. Open **Settings** to manage pairing, search, devices, permissions, and updates.

For development setup, see [host/README.md](host/README.md).

### 2. Configure internet search

Open **Settings → Internet search** in Windows JARVIS.

- **SearXNG:** Enter the complete URL of your private instance, then choose **Save and test SearXNG**.
- **SerpAPI:** Enter your key and choose **Save and test SerpAPI**. JARVIS uses it as a fallback when SearXNG is unavailable.

With the `offline_search` device capability enabled, Windows securely transfers the SerpAPI key to Android. The encrypted Android copy supports direct searches when the host cannot be reached.

Useful links: [SearXNG documentation](https://docs.searxng.org/) · [SerpAPI dashboard](https://serpapi.com/manage-api-key)

### 3. Pair Android

1. In Windows JARVIS, open **Settings → Pair a device** and generate a code.
2. Install the matching signed Android APK offered by the Windows host.
3. On Android, enter the host address and eight-digit code.
4. Approve only the JARVIS capabilities that device should receive.

The code is single-use and expires after five minutes. Restarting either app does not require pairing again. See [android/README.md](android/README.md) for development and LAN testing.

### 4. Configure voice activation

Open **Android JARVIS → Settings → Voice activation**.

- Choose **Set as default assistant** to use Android’s configured assistant gesture or button. Wake listening is managed automatically while JARVIS holds that role.
- Otherwise, choose **Enable always listening** to start the optional wake listener. Its permanent notification identifies microphone use and provides a Stop action.
- A battery-optimization exemption is optional but can help on phones that aggressively stop background services.

Android normally labels microphone access **Allow only while using the app**. For a foreground microphone service and default assistant, this is the supported permission path; Android does not provide JARVIS a separate unrestricted “always allow microphone” grant. JARVIS pauses its listener whenever another recording session takes priority.

## Updates

The Windows host checks the private release workflow every five minutes. It downloads a newer verified package in the background, closes cleanly, applies it without a console flash, and reopens. A signed Android APK is staged only when its version and source commit match the Windows build.

Android checks its paired host for that APK and displays Android’s protected installer confirmation. Older and mismatched APKs are rejected.

## Architecture

```text
Android companion
  ├─ device-bound private key
  ├─ encrypted offline SerpAPI key (optional)
  └─ local limited-mode actions
            │ signed authentication / HTTPS
            ▼
Windows home host
  ├─ device and permission control
  ├─ SearXNG / SerpAPI search
  ├─ messaging and remote routing
  └─ matched Windows + Android updater
```

The packaged host can create a Cloudflare Quick Tunnel for remote access. Every remote connection remains untrusted; the tunnel does not replace device authentication. See [docs/architecture.md](docs/architecture.md), [docs/online.md](docs/online.md), and [docs/permissions.md](docs/permissions.md).

## Current limitations

- Wake-phrase recognition runs locally with the bundled offline model; command transcription still depends on Android’s installed speech-recognition service.
- Android always-listening mode must remain visible through its foreground-service notification.
- Gmail and Calendar support opens secure Android intents; JARVIS does not read private email or calendar databases.
- Host-dependent messaging and integrations wait or queue while Windows is unreachable.

## Repository layout

- `host/` — Windows host, assistant UI, updater, search, messaging, and tests.
- `android/` — Android companion, secure pairing, limited mode, awareness, and voice activation.
- `docs/` — protocol architecture, remote access, and permission design.
- `.github/workflows/` — signed Android and packaged Windows builds.

This repository is under active private development. Install only artifacts produced by its configured signed workflows.
