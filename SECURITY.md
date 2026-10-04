# JARVIS Security Policy

## Official update trust

The Android Direct updater checks the public JARVIS GitHub Releases channel and accepts only the exact release asset URL for this repository and version. Before offering an update, it:

1. verifies the publisher-signed `UPDATE-MANIFEST.jar` against the signing certificate of the currently installed APK;
2. checks that the manifest names this repository, the matching tag and version, the Direct beta package, and the exact APK asset;
3. checks the APK SHA-256 hash, package name, publisher signing identity, and version code;
4. rejects an update whose version code is not newer than the installed build.

The release workflow signs the update manifest with the same Android publisher key used to sign the Direct APK. The private key is stored in GitHub Actions secrets; it is not committed or shipped in the app. Android still asks the user to confirm installation. A fork or copied application cannot change the trusted download source or create an update accepted by an official installation without the publisher signing credentials.

The Windows host update path remains available for compatible paired builds. Google Play builds use Play's update channel.

## Personal data

Public builds must not contain developer accounts, API keys, tokens, pairing databases, messages, location history, private memory, uploaded media, custom voice samples, or other local development data.

Runtime personal data belongs in the operating system's per-user application-data location, outside the installed application directory and outside source control.

## Reporting vulnerabilities

Do not publish credentials, tokens, private keys, personal information, or exploit payloads in a public issue. Report the minimum information needed to reproduce the problem.
