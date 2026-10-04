# TAR-JS

TAR-JS is a private Android reader for large Telegram JSON exports. It indexes chats locally, supports fast search and media playback, and can read local folders or encrypted rclone-backed archives.

## Highlights

- Local, background chat indexing
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
