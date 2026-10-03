# MCP Support Plugin Tests

Test suite for the Gitblit MCP Support Plugin REST API.

The suite drives the API over HTTP against a **running Gitblit with this plugin
deployed**. In KubeCoder, `kc project test` (from the repository root) builds the
plugin and runs `scripts/local-gitblit-test.sh`, which starts a throwaway
Gitblit 1.10.0 with the plugin on `localhost:8089`, seeded with this
environment's three checkouts, and runs the suite against it. Arguments to the
script go to pytest. The sections below are for running the suite by hand
against a Gitblit you can reach.

## Setup

```bash
cd tests
cexec python poetry install   # or plain `poetry install` outside KubeCoder
```

## Running Tests

Inside a KubeCoder environment, every `poetry` command below needs a
`cexec python` prefix, because Poetry lives in the `python` tool container.

```bash
# Run all tests
poetry run pytest

# Run with verbose output
poetry run pytest -v

# Run specific test file
poetry run pytest tests/test_repos.py

# Run specific test
poetry run pytest tests/test_repos.py::TestReposEndpoint::test_list_all_repos
```

## Configuration

The test suite connects to a Gitblit server. Configure the URL via environment variable:

```bash
export GITBLIT_URL=http://10.1.2.3
poetry run pytest
```

Or set it in `pyproject.toml` under `[tool.pytest.ini_options]`.

## Test Coverage

- **test_repos.py** - Tests for `GET /api/.mcp-internal/repos`
- **test_files.py** - Tests for `GET /api/.mcp-internal/files`
- **test_file.py** - Tests for `GET /api/.mcp-internal/file`
- **test_search_files.py** - Tests for `GET /api/.mcp-internal/search/files`
- **test_search_commits.py** - Tests for `GET /api/.mcp-internal/search/commits`
- **test_health.py** - Tests for `GET /api/.mcp-internal/health`
