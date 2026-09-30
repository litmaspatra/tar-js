# Decisions

- Rebuild uses 100% Kotlin app source, native Jetpack Compose, and Material 3. No WebView/JavaScript primary UI.
- GitHub Actions is authoritative because local Java/Gradle is unavailable.
- APK provenance must include checked-out commit, artifact name, SHA-256, and APK contents.
- Requirements are not marked complete from source inspection; rendered/device and integration checks remain UNVERIFIED until evidence exists.
- Legacy artifacts are not reusable unless explicitly revalidated against the master PRD.
