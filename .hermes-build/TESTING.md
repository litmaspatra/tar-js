# Testing and UI Evidence

## Architecture gate
- Native Kotlin app sources: IMPLEMENTED (`app/src/main/java/**/*.kt`)
- Compose + Material 3: IMPLEMENTED in Gradle
- WebView/Java/JavaScript primary app: CI forbidden
- Native Lottie dependency: PRESENT; actual sticker composable/device rendering: PENDING

## Automated evidence
| Check | Status | Evidence |
|---|---|---|
| Local Gradle tests | BLOCKED | Java/Gradle unavailable on Android host |
| GitHub unit tests | PASS | Run 36739064738, commit 70531e7929b01478d90d9c65f573722af797e5b5 |
| GitHub lint | PASS | Run 36739064738 |
| GitHub debug APK | PASS | Run 36739064738; artifact `TAR-JS-debug-70531e7929b01478d90d9c65f573722af797e5b5` |
| APK downloaded/hash/content | PASS | 16,914,780 bytes; SHA-256 `30cfbeeb59fd159d262be73c4231ee666dc7923a09d79cfacd1a1aa1e990da3e`; provenance commit matches; contains classes.dex and no web assets |
| TGS gzip core test | IMPLEMENTED | `TarJsCoreTest.tgsIsDecompressed` |
| identity-side core test | IMPLEMENTED | `TarJsCoreTest.ownerIsRightAndOtherIsLeft` |
| Rclone encrypted-config wrong-password retry | PASS (CI-covered) | `ci/rclone_reset_test.go`; wrong unlock cannot dump, correct retry dumps both remotes |
| Local B2/crypt/rclone contract | PASS (host-verified; CI job added) | `.github/workflows/build-debug.yml` `phase2-local-contract`; local filesystem object-store double, encrypted crypt paths, listremotes/listing/nested result/media copy/cat/delete |
| Real B2 integration | BLOCKED | `phase2-crypt-b2` requires intentionally absent `B2_ACCOUNT_ID`, `B2_APPLICATION_KEY`, `B2_BUCKET`; local contract is not equivalent |
| SAF recursive result.json instrumentation fixture | IMPLEMENTED (CI job added; run pending) | `FixtureDocumentsProvider` + `SafArchiveSourceInstrumentedTest`, 5s test timeout |
| SAF emulator/device gate | BLOCKED pending CI run | emulator invocation now uses no-window/no-audio/no-boot-anim, disabled animations, and a 15m bounded Gradle timeout |

## Screen matrix
| Screen | Light/dark | widths | back/insets | states | accessibility/touch | Result |
|---|---|---|---|---|---|---|
| Lock/setup | UNVERIFIED | UNVERIFIED | UNVERIFIED | error/success coded | UNVERIFIED | UNVERIFIED |
| Welcome/home | UNVERIFIED | UNVERIFIED | UNVERIFIED | empty coded | UNVERIFIED | UNVERIFIED |
| Chat list | UNVERIFIED | UNVERIFIED | UNVERIFIED | empty coded | UNVERIFIED | UNVERIFIED |
| Chat/search | UNVERIFIED | UNVERIFIED | UNVERIFIED | empty/no-match coded | UNVERIFIED | UNVERIFIED |
| Source/import/indexing | BLOCKED | BLOCKED | BLOCKED | progress not yet fully surfaced | BLOCKED | BLOCKED |
| Media/profile/settings | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED | BLOCKED |

No screen is marked PASS without rendered device evidence.
- Phase 2 unit-test evidence: UNVERIFIED — `gradle --no-daemon testDebugUnitTest` could not start (`gradle: command not found`).
- Phase 2 lint evidence: UNVERIFIED — local Gradle executable unavailable.
- Phase 2 debug APK evidence: UNVERIFIED — local Gradle executable unavailable; do not use the existing 16.9 MB scaffold APK as Phase 2 evidence.
- Phase 2 device/UI evidence: UNVERIFIED — no emulator/device rendering workflow available in this workspace.