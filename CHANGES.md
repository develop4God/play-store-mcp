# Changes from upstream

Base: devexpert-io/play-store-mcp @ b36fad39b4b636fb1948dc86372e03458661b3d6 (MIT).

1. Production is writable only as a held draft: `deploy_app`/`update_release_notes` on `production` save a draft release and commit with `changesNotSentForReview=true`, so nothing reaches Google review or users until a human sends it for review in the Play Console. Promotion never targets production. Testing tracks: deploy to `internal`/`alpha`/`beta`, promote only `internal` -> `alpha`, `internal` -> `beta`, `alpha` -> `beta`.
2. Required arguments fail loudly (`InvalidRequestException`); no silent defaults.
3. Deploy and promote run as a dry run unless `confirm=true`.
4. The version code is read from the Play upload response, not supplied by the caller.
5. Release notes are per language (`{"en-US": "...", "es-ES": "..."}`), not hard-coded `en-US`.
6. `get_releases` returns real JSON (version codes, status, rollout fraction); fake timestamps removed.
7. A failed edit is deleted instead of left open.
8. Logging: stderr only (stdout carries the MCP protocol), no file log, Google client logs at WARN, key path never logged.
9. Untrusted `gradle-wrapper.jar` replaced with one matching Gradle's official checksum (8.14.3).
10. Removed dead code (`PlayStoreService` "mock data" fallback, unused `defaultTrack`).
11. Added unit tests with a fake `PlayPublisher`; no credentials or network needed.
12. The track policy is enforced in the request types' `init`, so it cannot be bypassed by direct construction or `copy`.
13. Existing draft, staged and halted releases on a track are preserved; only a completed release is superseded. Only completed releases can be promoted.
14. Staged rollout (`rolloutPercentage`) is rejected: testing tracks do not support it.
15. `apkPath` must be absolute and is canonicalised; JSON `null` is rejected instead of becoming the text "null".
16. The dry run states that credentials, permissions, version code and track state are not checked.
17. The server refuses to start if the key file is missing or readable by group/others.
18. Coroutine cancellation is no longer swallowed; cleanup failures no longer mask the real result.
19. New `update_release_notes` tool (dry run by default) replaces the notes of an existing release on a testing track.
