# JARVIS Android client

## Build

Open the `android/` folder in Android Studio. Let Gradle sync, then connect the Motorola Edge 2024 with USB debugging enabled and press Run.

## LAN pairing test

1. On Windows, clone this repository and install the host requirements:

   `py -m pip install -r host/requirements.txt`

2. Start the host:

   `py host/run_host.py`

3. The Windows console prints a one-time 8-digit pairing code valid for five minutes.
4. Find the Windows PC's LAN IPv4 address with `ipconfig`.
5. In the Android app enter `http://PC_IP:8765` and the displayed code.
6. Press **PAIR WITH JARVIS**.

The Android app creates an ECDSA P-256 key in Android Keystore. The private key stays on the phone. The one-time code is consumed after successful enrollment, and the phone then proves possession of its private key by signing the host challenge.

## Voice activation

Open **Settings → Voice activation** inside JARVIS after pairing. **Enable always listening** requests microphone and notification access, then starts a user-visible microphone foreground service. Say “Jarvis” followed by a command. The persistent notification always identifies active listening and includes a Stop action.

Use **Set as default assistant** to open Android's assistant-role consent screen. Once selected, the phone's configured assistant gesture or button opens JARVIS voice input. **Allow background battery use** opens Android's battery-exemption confirmation; this is optional but helps manufacturers that aggressively stop background services.

Continuous speech recognition consumes more battery than a hardware/DSP hotword detector and may use the phone's configured online speech-recognition provider when on-device recognition is unavailable.

### Important

This build allows cleartext HTTP only to make the first LAN test easy. It is **not** an internet deployment. Do not port-forward 8765 or expose this development listener to the public internet. The next milestone is TLS/WSS, short-lived sessions, and scoped permissions.
