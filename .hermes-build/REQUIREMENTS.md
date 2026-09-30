# TAR-JS Requirements Audit

Source of truth: repository CI PRD workflow `.github/workflows/build-tarjs-prd.yml` and its exact patch input `ci/tarjs_prd_v1.patch.gz.b64`.

Required behavior families to verify:
- Telegram export import and SQLite integration, including single-chat and full-account ownership behavior.
- Background indexing service, progress/state notifications, rescan/import flows, and return-to-background behavior.
- rclone encrypted configuration, unlock/password handling, remote/path selection, encrypted archive/media round-trip, and embedded gomobile runtime.
- Archive browsing, chat/message paging, FTS/search, media and sticker handling, chat avatar/photo changes, and navigation/back behavior.
- Security/navigation hardening and safe user-facing error/recovery states.
- UI interactions: side swapping, folder selection, remember-me, chat appearance, overflow actions, index status, and native bridge back behavior.
- Debug APK contains the expected native runtime and patched web assets.

Explicit current concern: previous APK was reported to fail after b2crypt; the full PRD QA workflow is mandatory evidence before acceptance.
