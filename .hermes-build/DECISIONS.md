# Decisions

- Use GitHub Actions as the build authority; do not substitute a local Android build.
- Treat `build-tarjs-prd.yml` as the broad acceptance workflow because it exercises architecture contracts, UI navigation harness, rclone architecture, importer tests, full JVM regression tests, APK content, and artifact upload.
- Keep the existing source-reconstruction pipeline intact until current-HEAD evidence proves it inadequate.
- Do not report success from the older build-debug run alone; it does not prove every PRD function.
