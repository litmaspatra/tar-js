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
| GitHub unit tests | PASS | Run 36738087347, commit 5e7736315cc3920ce67a7cf6d483f23441010f05 |
| GitHub lint | PASS | Run 36738087347 |
| GitHub debug APK | PASS | Run 36738087347; artifact `TAR-JS-debug-5e7736315cc3920ce67a7cf6d483f23441010f05` |
| APK downloaded/hash/content | PASS | 16,914,780 bytes; SHA-256 `3ff2588cc2fb67924cf92f6ca78e7cdb444c087e388a0c03b84270880e0e7f41`; provenance commit matches; contains classes.dex and no web assets |
| TGS gzip core test | IMPLEMENTED | `TarJsCoreTest.tgsIsDecompressed` |
| identity-side core test | IMPLEMENTED | `TarJsCoreTest.ownerIsRightAndOtherIsLeft` |
| archive cache isolation test | IMPLEMENTED | `TarJsCoreTest.cacheKeySeparatesArchives` |

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
