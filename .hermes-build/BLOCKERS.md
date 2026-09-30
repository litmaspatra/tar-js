# Blockers

- Phase 2 is ACTIVE but BLOCKED: the local workspace has no `gradle` executable and no project Gradle wrapper, so the new Kotlin sources and tests cannot be compiled or executed here.
- `RcloneConfigParser` now covers UTF-8 BOMs, blank lines, `#`/`;` full-line comments, remotes, and crypt backend references; it does not yet perform rclone's encrypted-config password unlock or remote listing I/O.
- `KeystoreSecretStore` provides Android Keystore AES-GCM storage for a remembered rclone config password, but it is not wired into an import/settings UI and cannot be device-tested here.
- `SafArchiveSource` provides persistable read permission, recursive `result.json` discovery, and read-only opening; SAF UI wiring and device verification remain pending.
- Device rendering is unavailable; the UI evidence matrix remains UNVERIFIED.
- Foreground indexing, native media composables, profile crop, bidirectional paging, Superpowers, Ponytail, and authoritative Phase 2 GitHub APK provenance remain blocked behind this gate.
