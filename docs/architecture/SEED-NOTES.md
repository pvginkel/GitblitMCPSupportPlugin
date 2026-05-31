# Seed notes — `gitblit-initializer` producer (Gitblit MCP Support Plugin)

Hand-authored (the session-limit interrupted the headless run; finished directly
by the operator orchestration). Mode: hand-authored, uuid4 minted once.
`introduced: 2026-01-12` (repo first commit).

## Identity
- Repo `GitblitMCPSupportPlugin.git` (the former `GitblitSearchApiPlugin` was renamed;
  the build kept working via a GitHub redirect, now fixed in the Jenkinsfile). Builds
  image `registry:5000/gitblit-initializer` — the "initializer" name is historical;
  the artifact is the MCP support plugin.

## Elements
- `app:gitblit-initializer` «SoftwareProduct» — the Gitblit plugin.
- `svc:gitblit-mcp-internal-api` — the `/api/.mcp-internal` REST surface (repos, files,
  file, find-files, file-search, commit-search) it installs into Gitblit.
- `if:gitblit-mcp-internal` — consumer surface for the Gitblit MCP server.

## Relations
- plugin —Realization→ its internal API service.
- interface —Assignment→ that service.
- `ss:gitblit,5b20c5e2-…` —Serving→ the plugin (it runs inside the Gitblit instance;
  cross-producer ss:gitblit resolved by UUID from the published dataset).

## Open questions
- **Consumer edge ownership**: `gitblit-mcp-server` (separate producer, already seeded)
  is the consumer of `svc:gitblit-mcp-internal-api`. That edge should be authored on the
  mcp-server side referencing this svc UUID — left for a follow-up so we don't re-touch
  mcp-server's artifact here. (mcp-server currently models `cap:source-control`, which is
  the right logical edge; the concrete plugin-API edge is the implementation wire.)
- No outbound dependencies (the plugin only serves requests inside Gitblit).
