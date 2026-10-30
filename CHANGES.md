# Changes from upstream

Base: devexpert-io/play-store-mcp @ b36fad39b4b636fb1948dc86372e03458661b3d6 (MIT).

1. Production and open testing (beta) are unreachable: deploy only to `internal`/`alpha`, promote only `internal` -> `alpha`.
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
