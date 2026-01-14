"""
Pytest configuration and fixtures for MCP Support Plugin tests.
"""

import os
import pytest
import requests


@pytest.fixture(scope="session")
def base_url():
    """Base URL for the Gitblit server."""
    return os.environ.get("GITBLIT_URL", "http://10.1.2.3")


@pytest.fixture(scope="session")
def api_url(base_url):
    """Base URL for the MCP API."""
    return f"{base_url}/api/.mcp-internal"


@pytest.fixture(scope="session")
def session():
    """Requests session for making API calls."""
    s = requests.Session()
    # Add any auth headers here if needed
    # s.auth = ("username", "password")
    return s


@pytest.fixture(scope="session")
def api_client(session, api_url):
    """API client helper."""

    class APIClient:
        def __init__(self, session, base_url):
            self.session = session
            self.base_url = base_url

        def get(self, endpoint, params=None):
            """Make a GET request to the API."""
            url = f"{self.base_url}/{endpoint}"
            response = self.session.get(url, params=params)
            return response

        def repos(self, query=None, limit=None, after=None):
            """GET /repos endpoint."""
            params = {}
            if query:
                params["query"] = query
            if limit:
                params["limit"] = limit
            if after:
                params["after"] = after
            return self.get("repos", params or None)

        def files(self, repo, path=None, revision=None):
            """GET /files endpoint."""
            params = {"repo": repo}
            if path:
                params["path"] = path
            if revision:
                params["revision"] = revision
            return self.get("files", params)

        def file(self, repo, path, revision=None, start_line=None, end_line=None):
            """GET /file endpoint."""
            params = {"repo": repo, "path": path}
            if revision:
                params["revision"] = revision
            if start_line:
                params["startLine"] = start_line
            if end_line:
                params["endLine"] = end_line
            return self.get("file", params)

        def search_files(
            self, query, repos=None, path_pattern=None, branch=None, count=None
        ):
            """GET /search/files endpoint."""
            params = {"query": query}
            if repos:
                params["repos"] = repos if isinstance(repos, str) else ",".join(repos)
            if path_pattern:
                params["pathPattern"] = path_pattern
            if branch:
                params["branch"] = branch
            if count:
                params["count"] = count
            return self.get("search/files", params)

        def search_commits(self, query, repos, authors=None, branch=None, count=None):
            """GET /search/commits endpoint."""
            params = {
                "query": query,
                "repos": repos if isinstance(repos, str) else ",".join(repos),
            }
            if authors:
                params["authors"] = (
                    authors if isinstance(authors, str) else ",".join(authors)
                )
            if branch:
                params["branch"] = branch
            if count:
                params["count"] = count
            return self.get("search/commits", params)

    return APIClient(session, api_url)
