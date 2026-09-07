# Gitblit MCP Support Plugin

A Gitblit plugin that provides REST API endpoints for the Gitblit MCP Server integration.

## Building

Requires JDK 8 or later and Maven. Gitblit 1.10.0 is in no public Maven
repository, so its JAR ships in `lib/` and has to be seeded into the local
repository before the build can resolve it:

```bash
mvn install:install-file -Dfile=lib/gitblit-1.10.0.jar -DpomFile=lib/gitblit-1.10.0.pom
mvn clean package -DskipTests
```

The plugin ZIP is written to `target/mcp-support-plugin-1.0.0.zip`.

In a KubeCoder environment both steps are curated verbs — `kc project setup`
and `kc project build`, from the repository root — and Maven runs in the `java`
tool container.

## Installation

Copy the ZIP to your Gitblit plugins directory and restart Gitblit.

## API Endpoints

Base path: `/api/.mcp-internal`

### GET /repos

List repositories accessible to the authenticated user.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `query` | No | - | Filter by name (substring match) |
| `limit` | No | 50 | Max results (max 100) |
| `after` | No | - | Pagination cursor |

### GET /files

List files and directories at a path within a repository.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `repo` | Yes | - | Repository name |
| `path` | No | `/` | Directory path |
| `revision` | No | HEAD | Branch, tag, or commit SHA |

### GET /file

Read file content from a repository.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `repo` | Yes | - | Repository name |
| `path` | Yes | - | File path |
| `revision` | No | HEAD | Branch, tag, or commit SHA |
| `startLine` | No | 1 | First line (1-indexed) |
| `endLine` | No | EOF | Last line (1-indexed, inclusive) |

Returns 400 if file exceeds 128KB or is binary.

### GET /search/files

Search file contents using Lucene index.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `query` | Yes | - | Lucene search query |
| `repos` | No | all | Comma-separated repository names |
| `pathPattern` | No | - | File path filter (e.g., `*.java`) |
| `branch` | No | - | Branch filter |
| `count` | No | 25 | Max results (max 100) |

### GET /search/commits

Search commit history using Lucene index.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `repos` | Yes | - | Comma-separated repository names |
| `messageTerms` | No | - | Comma-separated terms (OR logic) |
| `authors` | No | - | Comma-separated author names (OR logic) |
| `branch` | No | - | Branch filter |
| `count` | No | 25 | Max results (max 100) |

At least one of `messageTerms` or `authors` must be provided.

## Authentication

The API supports the same authentication methods as Gitblit:
- HTTP Basic Authentication
- Session cookies

Anonymous access works if repositories allow it.

## CORS

All endpoints include CORS headers allowing access from any origin.
