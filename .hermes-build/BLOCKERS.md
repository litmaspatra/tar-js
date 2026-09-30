# Blockers

- The deterministic local contract is intentionally not a real B2 integration: it uses rclone's local backend as a safe object-store double with mock-only credentials and is evidence only for crypt/path/listing/copy behavior.
- Real B2 remains BLOCKED because `B2_ACCOUNT_ID`, `B2_APPLICATION_KEY`, and `B2_BUCKET` are absent by policy; do not add dummy values to that gate.
- SAF now has a minimal in-process `DocumentsProvider` fixture and a 5-second-bounded recursive `result.json` instrumentation test. GitHub emulator execution is still unverified until the updated workflow runs; local device rendering is unavailable.
- Phase 2 remains BLOCKED overall: the provider/UI wiring, rendered device evidence, foreground indexing, native media composables, profile crop, bidirectional paging, Superpowers, Ponytail, and authoritative Phase 2 GitHub APK provenance remain incomplete.
