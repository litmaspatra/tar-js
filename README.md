# TAR-JS

TAR-JS is a private Android reader for large Telegram JSON exports. It indexes chats locally, supports fast search and media playback, and can read local folders or encrypted rclone-backed archives.

## Highlights

- Local, background chat indexing
- Jump to any date, the first or latest message, with continuous scroll paging
- Archive-wide search with sender, date and media filters
- Per-chat media gallery and bookmarked messages
- Optional encrypted local backup of the archive index (restoring merges without deleting anything)
- Chats are never deletable in the app, and cloud (rclone/Backblaze) access is strictly read-only
- Photos, videos, voice notes, files, and static or animated stickers
- Telegram-style light and dark themes
- Optional four-digit app lock
- No analytics, advertising, or remote database

## Requirements

- Android 8.0 or newer
- 64-bit ARM device
- Telegram JSON export, optionally stored behind an rclone configuration

## Build

The GitHub Actions workflow builds the pinned rclone Android library, runs the Android checks, and produces the APK. Signing keys and generated binaries are deliberately excluded from Git.

See the GitHub Releases page for version `1.0.0`.
