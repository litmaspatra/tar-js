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
