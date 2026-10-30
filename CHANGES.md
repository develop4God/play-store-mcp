# Changes from upstream

Base: devexpert-io/play-store-mcp @ b36fad39b4b636fb1948dc86372e03458661b3d6 (MIT).

Planned hardening (tracked here as it lands):

1. Production track is not reachable; only an allowlist of testing tracks.
2. Required arguments fail loudly instead of falling back to defaults.
3. Deploy and promote run as a dry run unless `confirm=true`.
4. Version code is read from the upload response, not trusted from the caller.
5. No file logging, no key path in logs, Google client logs at WARN.
