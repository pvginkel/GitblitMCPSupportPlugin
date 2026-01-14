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
- **Docker** - Containerized builds
- **Gson** - JSON serialization
- **JGit 4.11.9** - Git repository operations

## Build Commands

```bash
# Build Docker image containing the plugin ZIP
./scripts/build.sh

# Extract plugin from Docker image
docker run --rm gitblit-initializer:latest cat /plugins/mcp-support-plugin-1.0.0.zip > plugin.zip

# Local Maven build (requires Gitblit JAR installed)
mvn install:install-file -Dfile=lib/gitblit-1.10.0.jar -DpomFile=lib/gitblit-1.10.0.pom
mvn clean package -DskipTests
```

**Build output:** `target/mcp-support-plugin-1.0.0.zip`

## Running Tests

Tests are in Python using pytest:

```bash
cd tests
poetry install
poetry run pytest              # Run all tests
poetry run pytest -v           # Verbose output
poetry run pytest tests/test_repos.py  # Specific file

# Use custom Gitblit server
GITBLIT_URL=http://localhost:8080 poetry run pytest
```

Default test server: `http://10.1.2.3`

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
│   └── CommitSearchHandler.java # GET /search/commits
├── model/                     # Response DTOs for JSON serialization
└── util/
    └── ResponseWriter.java    # JSON response helper
```

## API Endpoints

Base path: `/api/.mcp-internal`

| Endpoint          | Method | Description                                                             |
|-------------------|--------|-------------------------------------------------------------------------|
| `/repos`          | GET    | List repositories (params: query, limit, after)                         |
| `/files`          | GET    | List files in repo (params: repo, path, revision)                       |
| `/file`           | GET    | Read file content (params: repo, path, revision, startLine, endLine)    |
| `/find`           | GET    | Find files by glob pattern (params: pathPattern, repos, revision, limit) |
| `/search/files`   | GET    | Search file contents (params: query, repos, pathPattern, branch, count, contextLines) |
| `/search/commits` | GET    | Search commits (params: query, repos, authors, branch, count)           |

**Search Behavior:** When no `branch` parameter is provided, searches are automatically restricted to each repository's default branch to avoid duplicate results from multiple branches.

## Key Patterns

**Handler Pattern:** Each endpoint has a dedicated handler class implementing `RequestHandler` interface with `handle(HttpServletRequest, HttpServletResponse, UserModel)` method.

**Authentication:** Uses Gitblit's `IAuthenticationManager`. Unauthenticated users get `UserModel.ANONYMOUS`. Check `user.canView(repository)` for access control.

**Error Handling:** Use `ResponseWriter.writeError(response, statusCode, message)` for JSON error responses.

**Pagination:** Cursor-based using repository/file name as cursor. Default limit: 50, max limit: 100.

**Search:** Builds Lucene queries with format `type:blob/commit AND (query) AND filters...`

## Important Constants

- `DEFAULT_LIMIT = 50` - Default pagination/result limit
- `MAX_LIMIT = 100` - Maximum pagination limit (repos, files); 200 for /find endpoint
- `DEFAULT_COUNT = 25` - Default search results count
- `MAX_COUNT = 100` - Maximum search results count
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

These must be installed to local Maven repo before building without Docker.

## Known Issues

The `pathPattern` parameter in `/search/files` may cause HTML error responses instead of JSON when certain patterns are used. See `plugin_issue.md` for details.
