# Phase 2 deterministic dummy feature matrix

This matrix is fixture/contract evidence only. It does not prove connectivity to Backblaze B2.

| Area | Fixture or test | Status |
|---|---|---|
| rclone config | `plain-rclone.conf`: BOM, comments, blank lines, local backend + nested crypt remote | PASS host; CI contract |
| rclone encrypted config | `encrypted-rclone.conf.fixture`: deterministic non-production crypt options | FIXTURE PRESENT; secret unlock remains CI Go test |
| remote contract | nested `dummyb2:telegram`, copy/list/cat/delete and encrypted path names | PASS host + CI |
| archive shapes | `full-account-result.json` with 2 chats/8 messages; `single-chat-result.json` | FIXTURE/PARSE PASS |
| message semantics | owner/receiver/group, reply, forward, edited, service, rich text | FIXTURE PRESENT; importer test pending |
| media matrix | photo/video/audio/voice/file, static WebP, gzipped TGS, WebM | FIXTURE PRESENT; lazy rendering not implemented |
| missing media | missing path must recover without crash | UNVERIFIED; no recovery test exists |
| large archive | progress/background/resume/dedup | BLOCKED; no resumable importer implementation |
| search/paging | chat-scoped old-result search and bidirectional paging | BLOCKED; current query is local LIKE with no paging |
| passcode | wrong/cancel/lock | PARTIAL; wrong verification is coded, cancel/lock instrumentation absent |
| keystore | remembered password | PARTIAL; storage class exists, import/settings wiring absent |
| profile | crop/name/swap persistence | BLOCKED; feature not implemented |
| navigation/states | back/loading/error | PARTIAL; loading/error code exists, rendered emulator evidence pending |
| SAF | recursive `result.json` provider fixture | CI instrumentation gate |

The Phase 2 gate remains BLOCKED until every row marked BLOCKED/PARTIAL/UNVERIFIED is implemented and tested, or a true environment blocker is recorded. Real B2 remains a separate credential-gated check and must never be represented by the local contract.
