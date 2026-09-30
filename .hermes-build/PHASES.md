# TAR-JS Phases

## Phase 0 — repository/bootstrap/environment validation (ACTIVE)
Acceptance: PRD source and patch inputs are present; repository is clean and state is recorded; GitHub Actions full PRD QA runs on current HEAD; all required reconstruction, contract, UI harness, rclone, unit-test, build, APK-content, and artifact-upload steps pass.

## Phase 1 — full PRD behavior verification
Acceptance: every PRD function is covered by the full PRD QA workflow and evidence, not only b2crypt; importer, indexing/background progress, remote setup, archive browsing/search, security/password handling, owner/chat/photo UI, and media/content paths pass their applicable checks.

## Phase 2 — final integration audit
Acceptance: requirement checklist, lint/static review, rendered UI evidence matrix, accessibility/theme/inset checks, privacy/legal preflight, Superpowers verification, Ponytail review/audit, regression validation, and final GitHub debug APK artifact verification pass. Any unavailable check remains UNVERIFIED/BLOCKED.
