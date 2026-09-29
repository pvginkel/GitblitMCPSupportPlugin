# CLAUDE.md - Project Context for Claude Code

## Project Overview

GitblitSearchApiPlugin is a Gitblit plugin that provides REST API endpoints for MCP (Model Context Protocol) Server integration. It exposes repository, file, and commit search capabilities from Gitblit via a clean REST API designed for MCP clients.

**Key Functionality:**
- List repositories accessible to authenticated users with filtering and pagination
- List files/directories at a specific path in a repository
- Read file content with line range support (max 128KB)
- Find files by glob pattern using Git tree walking
- Full-text search of file contents using Lucene indexes
- Commit history search with filtering by author and message

## Technology Stack

- **Java 8** - Target/source version
- **Gitblit 1.10.0** - Plugin platform
- **PF4J 0.9.0** - Plugin Framework for Java
- **Maven 3.9** - Build tool
- **Dockerfile** - Built in CI with kaniko into the `registry:5000/gitblit-initializer` image
- **Gson** - JSON serialization
- **JGit 4.11.9** - Git repository operations

## Build Commands

Maven lives in the `java` tool container. The curated entry points are in
`.kubecoder/project.yaml`; prefer them over ad-hoc commands, and run them from
the repository root:

```bash
kc project setup   # seed the Gitblit JAR into the local Maven repository
kc project build   # cexec java mvn clean package -DskipTests
kc project lint    # validate the architecture-as-code artifact
```

The one-off equivalents:

```bash
cexec java mvn install:install-file -Dfile=lib/gitblit-1.10.0.jar -DpomFile=lib/gitblit-1.10.0.pom
cexec java mvn clean package -DskipTests
```

**Build output:** `target/mcp-support-plugin-1.0.0.zip`

The container image `registry:5000/gitblit-initializer` is Jenkins' to build and
push. To check the Dockerfile from here without tagging anything:

```bash
kaniko --context . --no-push
```

## Running Tests

`tests/` is a pytest suite driving the plugin's REST API over HTTP against a
running Gitblit with the plugin deployed. It is deliberately **not** wired into
`kc project test`: no KubeCoder environment has such a Gitblit, and the default
`GITBLIT_URL` (`http://10.1.2.3`) is unreachable from the pod. CI builds with
`-DskipTests` for the same reason, and `src/` carries no Java tests of its own.

Against a Gitblit you can reach, Poetry lives in the `python` tool container:

```bash
cexec python sh -c 'cd tests && poetry install'
cexec python sh -c 'cd tests && GITBLIT_URL=http://your-gitblit poetry run pytest'
```

See `tests/README.md` for the suite's own layout.

## Project Structure

```
src/main/java/com/gitblit/plugin/mcp/
├── MCPSupportPlugin.java      # Plugin entry point (PF4J)
├── MCPApiFilter.java          # HTTP filter/router for /api/.mcp-internal/*
├── handlers/
│   ├── RequestHandler.java    # Handler interface
│   ├── ReposHandler.java      # GET /repos
│   ├── FilesHandler.java      # GET /files
│   ├── FileHandler.java       # GET /file
│   ├── FindFilesHandler.java  # GET /find
│   ├── FileSearchHandler.java # GET /search/files
│   ├── CommitSearchHandler.java # GET /search/commits
│   └── HealthHandler.java     # GET /health
├── model/                     # Response DTOs for JSON serialization
└── util/
    └── ResponseWriter.java    # JSON response helper
```

## API Endpoints

Base path: `/api/.mcp-internal`

| Endpoint          | Method | Description                                                             |
|-------------------|--------|-------------------------------------------------------------------------|
| `/repos`          | GET    | List repositories (params: query, limit, offset)                        |
| `/files`          | GET    | List files in repo (params: repo, path, revision, limit, offset)        |
| `/file`           | GET    | Read file content (params: repo, path, revision, startLine, endLine)    |
| `/find`           | GET    | Find files by glob pattern (params: pathPattern, repos, revision, limit, offset) |
| `/search/files`   | GET    | Search file contents (params: query, repos, pathPattern, branch, limit, offset, contextLines) |
| `/search/commits` | GET    | Search commits (params: query, repos, authors, branch, limit, offset)   |
| `/health`         | GET    | Find and drop dead Lucene index writers; 503 when it found any          |

**Search Behavior:** When no `branch` parameter is provided, searches are automatically restricted to each repository's default branch to avoid duplicate results from multiple branches.

**Find Behavior:** When no `revision` is given, `/find` resolves each repository's default branch the way `/file` does (`JGitUtils.getCommit(repository, null)`: HEAD, or the most recently updated branch when HEAD does not resolve) and reports that branch as `revision`. A repository it cannot open or resolve is not walked; it is named in `skipped` as `{repository, reason}` instead of being dropped silently.

## Key Patterns

**Handler Pattern:** Each endpoint has a dedicated handler class implementing `RequestHandler` interface with `handle(HttpServletRequest, HttpServletResponse, UserModel)` method.

**Authentication:** Uses Gitblit's `IAuthenticationManager`. Unauthenticated users get `UserModel.ANONYMOUS`. Check `user.canView(repository)` for access control.

**Error Handling:** Use `ResponseWriter.writeError(response, statusCode, message)` for JSON error responses.

**Pagination:** Offset-based using `limit` and `offset` parameters. All list/search endpoints return `totalCount` and `limitHit` fields. Default limit: 50 (repos/files) or 25 (search), max limit: 100-200 depending on endpoint.

**Search:** Builds Lucene queries with format `type:blob/commit AND (query) AND filters...`

**Health:** `HealthHandler` reads Gitblit internals by reflection: `RepositoryManager.luceneExecutor` (the `LuceneService`) and its `writers` map, one `IndexWriter` per repository. That is why `lucene-core` 5.5.2, the version Gitblit 1.10.0 ships, is a `provided` dependency. A writer that is no longer `isOpen()` is dead, and `getTragicException()` holds the cause. `LuceneService.close(repo)` drops it together with its searcher.

## Important Constants

- `DEFAULT_LIMIT = 50` - Default limit for repos (50), files (100), find (50)
- `DEFAULT_LIMIT = 25` - Default limit for search endpoints
- `MAX_LIMIT = 100` - Maximum limit for repos; 200 for files, find, search
- `MAX_FILE_SIZE = 128 * 1024` - Maximum file size for reading (128KB)
- `DEFAULT_CONTEXT_LINES = 10` - Default lines of context around search matches
- `MAX_CONTEXT_LINES = 200` - Maximum lines of context (caps contextLines parameter)

## Code Conventions

- Package: `com.gitblit.plugin.mcp.*`
- Handler naming: `{Entity}Handler.java`
- Response naming: `{Entity}Response.java` or `{Entity}ListResponse.java`
- Use `SimpleDateFormat` with `"yyyy-MM-dd'T'HH:mm:ss'Z'"` pattern and UTC timezone
- CORS: All responses include `Access-Control-Allow-Origin: *`

## Dependencies Location

Local Gitblit JAR (not in Maven Central):
- `lib/gitblit-1.10.0.jar`
- `lib/gitblit-1.10.0.pom`

`kc project setup` installs them into the local Maven repository; the
Dockerfile runs the same `mvn install:install-file` step inside its build
stage.

## Known Issues

The `pathPattern` parameter in `/search/files` may cause HTML error responses instead of JSON when certain patterns are used.
