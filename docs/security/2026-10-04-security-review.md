# TAR-JS security review — 2026-10-04

## Outcome

The requested 34 controls were checked against the Android application, build files, workflow, tracked files, and Git history. Three concrete issues were confirmed and fixed: the PIN attempt limit was not enforced by the verifier, raw exception details could reach the UI, and a reusable directory-backed media resolver lacked canonical path containment. No unresolved critical or high-severity finding remains in the reviewed source.

The app is local-first: it has no application server, user accounts, admin panel, payment system, browser JavaScript, JWTs, webhooks, or publicly reachable database/API. Controls that require those components are marked **N/A**, rather than being presented as false passes.

## Checklist results

| # | Control | Result | Evidence |
|---:|---|---|---|
| 1 | Public `.env` files | Pass | Current tracked-file and Git-history filename scans found no `.env` files. |
| 2 | Hardcoded secrets | Pass | High-confidence key/private-key scans found no credentials. The workflow reads B2 values from GitHub Actions secrets; its literal passwords are isolated dummy test fixtures. |
| 3 | Weak authentication | Fixed | The optional PIN is salted PBKDF2-SHA256, expires after 15 minutes, and now enforces a 30-second verifier-level lockout after five failures (`AppLock.kt`). The four-digit format remains an explicit product requirement and is described as an app PIN, not account authentication. |
| 4 | Missing authorization check | N/A | There is no multi-user backend or privileged route. The optional lock is enforced by the navigation state and by the PIN verifier, not only by button visibility. |
| 5 | Cross-user access | N/A | TAR-JS has no accounts or cross-user object store; databases, caches, preferences, and copied configs are in the Android app sandbox. |
| 6 | Open database permissions | Pass | SQLite is created in app-private storage and is not exposed through a content provider or network listener (`ArchiveDb.kt`). |
| 7 | Cloud service misconfiguration | N/A | No Firebase, Supabase, S3 SDK, or app-owned bucket configuration exists. Rclone remotes are supplied and controlled by the device owner. External bucket policy cannot be assessed from this repository. |
| 8 | Unprotected admin route | N/A | No server or admin route exists. |
| 9 | Exposed production debug tools | Pass | No debug activity, developer endpoint, WebView console, stack-trace screen, or manifest `debuggable` override exists. |
| 10 | Logs leak secrets | Fixed | Passwords were never logged; media and rclone failure logs now use fixed categories without paths, filenames, exceptions, or tokens (`MediaResolver.kt`, `TarVm.kt`). |
| 11 | Verbose production errors | Fixed | UI/service/search/import paths now cross a tested fixed-message boundary and do not display raw exception messages (`UserFacingError.kt`). |
| 12 | Secrets in Git | Pass | High-confidence secret patterns, private-key markers, keystore names, Google service files, and `.env` history were scanned with no credential finding. |
| 13 | Secrets in JavaScript | N/A | There is no JavaScript or WebView application code. |
| 14 | Client-only security | N/A | This is an offline client with no TAR-JS server. Relevant local controls are enforced in storage/path/authentication code, not only in Compose UI. |
| 15 | Input validation | Pass | PINs, rclone paths, selected configs, FTS terms, archive-relative paths, config size, and expanded TGS size are validated or bounded. Telegram JSON is parsed with streaming readers for large exports. |
| 16 | SQL injection | Pass | User/archive values use SQLite bind arguments or compiled-statement bindings. FTS terms are reduced to Unicode letters, numbers, and underscore before a bound `MATCH` argument (`ArchiveDb.kt`). |
| 17 | NoSQL injection | N/A | No NoSQL database or query language is used. |
| 19 | XSS | N/A | Messages are rendered as native Compose text; there is no HTML renderer or WebView script context. |
| 20 | CSRF | N/A | No cookie-authenticated web endpoint exists. |
| 21 | Insecure file uploads | N/A | TAR-JS does not upload files to a server. Local imports use Android's system document picker with read-only access; rclone configs are copied into app-private storage with a 2 MiB limit. |
| 22 | Path traversal | Fixed | SAF and rclone paths reject `.`/`..`; the local media resolver now canonicalizes root/candidate paths and rejects escapes, covered by a regression test (`MediaResolver.kt`). |
| 23 | SSRF | N/A | There is no server-side request capability. Network access is through the device owner's imported rclone configuration, without a privileged TAR-JS server network. |
| 24 | Broken password reset | N/A | No account or reset flow exists. The optional local PIN can be disabled only from in-app settings while unlocked. |
| 25 | Weak session management | N/A | No server sessions or tokens exist. The optional local unlock expires after 15 minutes. |
| 26 | JWT secrets | N/A | JWTs are not used. |
| 27 | Permissive CORS | N/A | No HTTP server/browser API exists. |
| 28 | Rate limits | N/A | No hosted API exists. Local PIN guessing now has an enforced attempt lockout. |
| 29 | Exposed environments | N/A | No staging/test web environment is deployed by this repository. |
| 30 | Default credentials | Pass | No production default username, password, API key, or remote is embedded. Literal workflow credentials are dummy integration-test fixtures only. |
| 31 | Unsigned webhooks | N/A | No webhook receiver exists. |
| 32 | Frontend-only payment checks | N/A | No billing or subscription flow exists. |
| 33 | IDOR / BOLA | N/A | No multi-user object API exists; chat IDs are keys in the single device-owner's app-private database. |
| 34 | APIs plus user input | Pass | User-controlled rclone remote paths are normalized and segment-validated before calls; SAF media traversal is segment-validated; database inputs are bound. |
| 35 | Exposed logs | Fixed | Same evidence as item 10; log statements carry only non-sensitive fixed categories. |
| 36 | Exposed source maps | N/A | No browser JavaScript bundle or source maps are produced. |

The supplied numbering omits item 18; this report preserves the user's numbering exactly.

## Additional hardening applied

- Android backup and device-transfer extraction are explicitly disabled for app files, databases, preferences, and external app data, in addition to `allowBackup="false"` (`AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`).
- Imported rclone configuration and expanded animated-sticker JSON have explicit size limits.
- Remembered rclone passwords use Android Keystore AES-GCM; plaintext is not written to preferences or logs.

## Verification scope and limitations

The review combines source inspection, current-tree and Git-history secret scans, focused security regression tests, the full JVM unit suite, Android lint, a local debug build, and independent APK signature verification. Connected-device installation was prepared but could not be executed from the sandbox because its required `C:\.android` folder-creation permission was not granted. The prebuilt `gomobile.aar`/rclone implementation is treated as a binary dependency; TAR-JS call boundaries were reviewed, but a full source audit of rclone itself is outside this repository review. Cloud bucket policies and the user's imported rclone configuration are also outside repository visibility.
