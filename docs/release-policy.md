# Cross-platform release policy

JARVIS uses the root `VERSION` file as the only release-version authority.

## Required rules

1. Windows, Android Play, and Android Direct ship under the same semantic version.
2. Feature additions increment the minor version unless compatibility requires a major increment.
3. Bug fixes, security fixes, dependency updates, and removal of unused or hidden functions increment the patch version.
4. No workflow, package, UI, or updater may hard-code a competing release version.
5. Release artifacts must be produced from the same commit and include that commit in their build identity.
6. Android package IDs may differ by distribution, but their human-readable version must match Windows.
7. Protocol changes require compatibility tests for Windows-to-Play, Windows-to-Direct, and standalone Android.
8. A platform may skip publication only when its artifact is unchanged and the signed release manifest still records the shared version and source commit.
9. No release is distributed until Android lint/tests, Windows tests/smoke test, package signing, and the version-consistency job pass.
10. Removed code must be documented in release notes and checked for routes, permissions, workers, update hooks, and stored-data migrations before deletion.
