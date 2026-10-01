# Blockers

- The deterministic local contract is intentionally not a real B2 integration: it uses rclone's local backend as a safe object-store double with mock-only credentials and is evidence only for crypt/path/listing/copy behavior.
- Real B2 remains BLOCKED because `B2_ACCOUNT_ID`, `B2_APPLICATION_KEY`, and `B2_BUCKET` are absent by policy; do not add dummy values to that gate.
- SAF now has a minimal in-process `DocumentsProvider` fixture and a 5-second-bounded recursive `result.json` instrumentation test. GitHub emulator execution is still unverified until the updated workflow runs; local device rendering is unavailable.
- SAF CI root cause from run `36755428362`: Gradle compilation was not reached because `reactivecircus/android-emulator-runner@v2` repeatedly polled `sys.boot_completed` and ended with `Timeout waiting for emulator to boot` at the action's default 600-second boot timeout. This is an emulator startup-time blocker, not a test assertion failure. The targeted follow-up changes only the action boot timeout to 1200 seconds; do not repeat identical retries.
- Phase 2 remains BLOCKED overall: the provider/UI wiring, rendered device evidence, foreground indexing, native media composables, profile crop, bidirectional paging, Superpowers, Ponytail, and authoritative Phase 2 GitHub APK provenance remain incomplete.
