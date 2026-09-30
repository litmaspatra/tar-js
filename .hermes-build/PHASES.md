# TAR-JS Phases

## Phase 0 — reset/bootstrap/architecture (ACTIVE)
Acceptance: old source/workflow artifacts removed or retired; clean Kotlin/Compose/Material 3 project; PRD audit and architecture gate documented; CI verifies no Java/JavaScript/WebView and builds from checked-out commit.

## Phase 1 — navigation/design/security (BLOCKED)
Acceptance: welcome, passcode lock, source picker, settings, back behavior, loading/empty/error/success states, UI audit and device evidence.

## Phase 2 — import/data/rclone/security (BLOCKED)
Acceptance: full/single exports, private snapshot, robust rclone encrypted config/password handling, SAF/rclone navigation, no source mutation, tests.

## Phase 3 — indexing/background/persistence (BLOCKED)
Acceptance: streaming/batched import, foreground service notification, resumable state, duplicate prevention, persistent DB, progress tests.

## Phase 4 — chat/media/search/paging (BLOCKED)
Acceptance: identity sides, swap persistence, rich/media/sticker rendering including native Lottie TGS, lazy cache, scoped search, bidirectional paging, UI/integration tests.

## Phase 5 — QA/release (BLOCKED)
Acceptance: full PRD matrix, light/dark/device verification, lint/tests/build, Superpowers, Ponytail, GitHub APK download/hash/content/provenance verification.

Only one phase may be active. No completion claim on compile alone.
