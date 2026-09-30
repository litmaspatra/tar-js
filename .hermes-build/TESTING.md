# Testing and UI Evidence

## GitHub workflow evidence

| Check | Status | Evidence |
|---|---|---|
| Existing baseline build-debug | PASS | Run 36725224481: reconstruction, frontend syntax, narrow layout test, dummy export, rclone AAR, encrypted media round-trip, unit tests, debug APK, native library verification, and artifact upload all passed. |
| Full PRD QA on current HEAD | PASS | Run 36732938633 at d7f6918; all 23 substantive workflow steps passed, including importer integration, full JVM regression, APK/runtime verification, and upload. |

## Current-head PRD QA evidence

- Workflow: https://github.com/litmaspatra/tar-js/actions/runs/36732938633
- Commit: d7f6918c5d6154d42dd83d32f3ff1041e0f57787
- Artifact: TAR-JS-background-progress-QA-debug, artifact ID 11107165148, 25,425,502 bytes, not expired
- APK SHA-256 printed by workflow: 1b6571ff78a14d1ca99b8ae5bc564c144ef5ef3cae9b6720ddbb1dd5c4d02c36
- Verification included native `lib/arm64-v8a/libgojni.so`, exact AAR-to-APK native hash comparison, patched web assets, importer integration tests, full JVM regression tests, and UI/architecture contracts.

## Screen matrix

Rendered emulator/device inspection is not available in this workspace. Until performed, each affected screen remains UNVERIFIED rather than inferred from source.

| Screen/flow | Light/dark | Widths | Navigation/back | States/input/insets | Accessibility/touch/contrast | Result |
|---|---|---|---|---|---|---|
| Archive/home | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Remote/password setup | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Folder/import/indexing | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Chat/message browsing and search | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Chat appearance/photo/overflow | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |

Skipped/one-off styling: none assessed yet.
