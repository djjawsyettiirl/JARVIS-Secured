# JARVIS Android client

## Install the public Direct beta

1. Open the [JARVIS GitHub Releases page](https://github.com/djjawsyettiirl/JARVIS-Secured/releases) on your Android phone.
2. Download the asset named **Android Direct APK** for the published version.
3. Open the APK. If Android asks, allow your browser or file manager to install apps from that source, then return and tap **Install**.
4. Open **Assistant Jarvis** and review Android's permission prompts. Allow only the capabilities you want to use.
5. On Windows, generate a one-time pairing code under **Settings → Pairing**. Enter the Windows PC's LAN address and the code in Android JARVIS.

The **Direct Edition** is the unrestricted Android beta build. Google Play distribution is temporarily shelved. See the [Windows and Android install guide](../docs/install.md) for pairing and troubleshooting.

## Build

Open the `android/` folder in Android Studio. Let Gradle sync, then connect the Motorola Edge 2024 with USB debugging enabled and press Run.

## LAN pairing test

1. On Windows, clone this repository and install the host requirements:

   `py -m pip install -r host/requirements.txt`

2. Start the host:

   `py host/run_host.py`

3. Open Windows JARVIS Settings and generate a one-time 8-digit pairing code valid for five minutes.
4. Find the Windows PC's LAN IPv4 address with `ipconfig`.
5. In the Android app enter `http://PC_IP:8765` and the displayed code.
6. Press **PAIR WITH JARVIS**.

The Android app creates an ECDSA P-256 key in Android Keystore. The private key stays on the phone. The one-time code is consumed after successful enrollment, and the phone then proves possession of its private key by signing the host challenge.

## Voice activation

Open **Settings → Voice activation** inside JARVIS after pairing. **Enable always listening** requests microphone and notification access, then starts a user-visible microphone foreground service. Wake-word detection uses the bundled Vosk English model entirely on the phone. Android's speech recognizer starts only after “Jarvis” is detected and stops after the command. The persistent notification identifies active listening and includes a Stop action.

Use **Set as default assistant** to open Android's assistant-role consent screen. Once selected, the phone's configured assistant gesture or button opens JARVIS voice input. **Allow background battery use** opens Android's battery-exemption confirmation; this is optional but helps manufacturers that aggressively stop background services.

Offline wake detection uses additional battery compared with an OEM hardware/DSP hotword detector. The short command-recognition step prefers Android's on-device recognizer and can fall back to the phone's configured recognition service.

## Limited mode without Windows

With the `offline_search` device capability enabled, JARVIS transfers the SerpAPI key over the remote HTTPS connection and stores it encrypted by Android Keystore. If the Windows host becomes unreachable, Android keeps that encrypted copy and labels itself **Limited mode**. Direct SerpAPI Web, Images, and Videos tabs, Maps directions/searches, email composition, and Calendar event creation remain available. Home messages and host-only integrations queue or wait for reconnection.

When JARVIS is selected as Android's default assistant, wake listening is automatic and managed by the system assistant role. Android intentionally offers microphone access as **Allow only while using the app**; the active assistant role and foreground listening notification provide the supported persistent path. Manual Enable/Stop controls appear only when JARVIS is not the default assistant.

The Android permission-readiness panel checks microphone, notifications, optional precise location and calendar awareness, private APK installation, and background battery status. It intentionally does not request camera, nearby-device, contacts, call-log, SMS, or storage access. Maps, email composition, and Calendar event creation use Android's secure app intents rather than reading those apps' private data.

### Important

Cleartext HTTP exists only for same-network pairing. Never port-forward port 8765. Remote connections use the generated HTTPS tunnel plus device-bound authentication and short-lived scoped sessions.
