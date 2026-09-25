# JARVIS Security Policy

## Official update trust

Official JARVIS builds accept updates only from the JARVIS Secured release channel.

The 2.2.1 Beta trust design requires:

1. the official embedded update-verification public key;
2. a signed release manifest;
3. SHA-256 hashes matching every distributed artifact;
4. the expected platform signing identity;
5. a version newer than the installed version (anti-rollback);
6. matching release version and source commit across companion packages.

The release private key must never be committed to this repository or shipped in an application. Only the repository owner controls that key and release approval.

Forks can modify their own source, but they cannot create updates trusted by official JARVIS installations without the official private signing credentials.

## Personal data

Public builds must not contain developer accounts, API keys, tokens, pairing databases, messages, location history, private memory, uploaded media, custom voice samples, or other local development data.

Runtime personal data belongs in the operating system's per-user application-data location, outside the installed application directory and outside source control.

## Reporting vulnerabilities

Do not publish credentials, tokens, private keys, personal information, or exploit payloads in a public issue. Report the minimum information needed to reproduce the problem.
