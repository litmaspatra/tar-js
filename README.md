# TAR-JS

TAR-JS is a private, native Android reader for exported Telegram chats.

## Build

The authoritative build is `.github/workflows/build-debug.yml`. It verifies the Kotlin/Compose/Material 3 architecture, runs unit tests and lint, builds the debug APK, and uploads the APK with a commit provenance file and SHA-256.

The app is being rebuilt from `TAR-JS_Master_PRD-2.txt` in strict phases. See `.hermes-build/` for the requirement audit, phase gates, evidence matrix, and blockers.
