# Install JARVIS

JARVIS has a Windows host and an Android companion. Get installers only from the [official GitHub Releases page](https://github.com/djjawsyettiirl/JARVIS-Secured/releases).

## Windows

1. Download the asset named **Windows Setup**.
2. Open the downloaded `.exe` and follow the installer prompts.
3. Start **Assistant Jarvis** from the Start menu (or desktop shortcut, if selected).
4. If Windows Firewall asks, allow JARVIS on your **Private** network.
5. Open **Settings → Pairing** and create an eight-digit code when you are ready to connect your phone.

The portable **Windows Host ZIP** is provided for users who prefer to extract and run the host manually.

## Android Direct Edition

1. Open the releases page on your Android phone.
2. Download the asset named **Android Direct APK**.
3. Open the downloaded APK. If Android asks, allow your browser or file manager to install apps from that source, then return to the download and tap **Install**.
4. Open **Assistant Jarvis** and review the Android permission prompts. Allow only the features you want to use; you can change permissions later in Android Settings.
5. On Windows, create a pairing code under **Settings → Pairing**. On Android, enter the Windows PC's LAN address and the eight-digit code.

The **Direct Edition** is the unrestricted Android beta build. Google Play distribution is temporarily shelved; a Google Play Edition is shown by builds installed from Google Play when that distribution resumes.

## Pairing

Both devices should be on the same trusted Wi-Fi network for local pairing. On Windows, find the PC's LAN IPv4 address with `ipconfig`; enter that address in the Android app. The pairing code is single-use and expires after five minutes.

For internet connections outside your home network, use the app's configured secure route. Do not expose Windows port 8765 directly to the public internet.

## Downloads and integrity

Release assets are versioned and include SHA-256 checksums. For the Android Direct APK, Android verifies the package signing identity during install. Report the release version, device/Windows details, and exact error through [GitHub Issues](https://github.com/djjawsyettiirl/JARVIS-Secured/issues) if installation fails.
