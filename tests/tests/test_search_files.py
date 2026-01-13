"""
Tests for GET /api/mcp-server/search/files endpoint.
"""
import pytest


class TestFileSearchEndpoint:
    """Tests for the /search/files endpoint."""

    @pytest.fixture
    def indexed_repo(self, api_client):
        """Get a repository that is indexed for searching."""
        repos = api_client.repos().json()
        for repo in repos["repositories"]:
            if repo["hasCommits"]:
                return repo["name"]
        pytest.skip("No indexed repository available")

    def test_basic_search(self, api_client, indexed_repo):
        """Test basic file content search."""
        # Search for a common term that should exist
        response = api_client.search_files(query="public", repos=indexed_repo)
        assert response.status_code == 200

        data = response.json()
        assert "query" in data
        assert "totalCount" in data
        assert "limitHit" in data
        assert "results" in data
        assert isinstance(data["results"], list)

    def test_search_result_structure(self, api_client, indexed_repo):
        """Test that search results have correct structure."""
        response = api_client.search_files(query="class", repos=indexed_repo)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No search results to validate structure")

        result = data["results"][0]
        assert "repository" in result
        assert "path" in result
        assert "chunks" in result
        assert isinstance(result["chunks"], list)

    def test_search_chunk_structure(self, api_client, indexed_repo):
        """Test that search result chunks have correct structure."""
        response = api_client.search_files(query="import", repos=indexed_repo)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No search results")

        for result in data["results"]:
            if result["chunks"]:
                chunk = result["chunks"][0]
                assert "startLine" in chunk
                assert "endLine" in chunk
                assert "content" in chunk
                # Line numbers should be positive integers
                assert chunk["startLine"] > 0
                assert chunk["endLine"] >= chunk["startLine"]
                break
        else:
            pytest.skip("No chunks in search results")

    def test_search_with_path_pattern(self, api_client, indexed_repo):
        """Test search with path pattern filter."""
        # First verify there are Java files by searching without pattern
        response = api_client.search_files(query="public", repos=indexed_repo)
        assert response.status_code == 200
        unfiltered_data = response.json()

        java_files_exist = any(
            r["path"].endswith(".java") for r in unfiltered_data["results"]
        )
        if not java_files_exist:
            pytest.skip("No Java files in search results to test pattern filter")

        # Now search with path pattern
        response = api_client.search_files(
            query="public",
            repos=indexed_repo,
            path_pattern="*.java"
        )
        assert response.status_code == 200

        data = response.json()
        # Must return results since we know Java files exist
        assert len(data["results"]) > 0, "Path pattern filter returned no results"
        # All results should be Java files
        for result in data["results"]:
            assert result["path"].endswith(".java")

    def test_search_with_wildcard_path_pattern_returns_results(self, api_client, indexed_repo):
        """Test that path patterns with wildcards return matching results.

        Regression test for two issues:
        1. pathPattern causing non-JSON error responses (Lucene leading wildcard bug)
        2. pathPattern being quoted, which breaks wildcard matching

        The fix uses post-filtering in the plugin to avoid Lucene's wildcard issues.
        """
        # First, find a file extension that exists in the repo by doing an unfiltered search
        response = api_client.search_files(query="public", repos=indexed_repo, count=50)
        assert response.status_code == 200
        unfiltered_data = response.json()
        unfiltered_total = unfiltered_data["totalCount"]

        if not unfiltered_data["results"]:
            pytest.skip("No search results to test path patterns")

        # Find the most common file extension in results
        extensions = {}
        for result in unfiltered_data["results"]:
            path = result["path"]
            if "." in path:
                ext = path[path.rfind("."):]
                extensions[ext] = extensions.get(ext, 0) + 1

        if not extensions:
            pytest.skip("No files with extensions found")

        # Use the most common extension
        common_ext = max(extensions, key=extensions.get)
        pattern = f"*{common_ext}"

        # Now search with the path pattern
        response = api_client.search_files(
            query="public",
            repos=indexed_repo,
            path_pattern=pattern
        )

        # Should return 200, not 500 or error page
        assert response.status_code == 200, f"Pattern '{pattern}' caused error"

        # Response should be valid JSON with expected structure
        filtered_data = response.json()
        assert "query" in filtered_data, f"Pattern '{pattern}' returned invalid response"
        assert "results" in filtered_data

        # Must return actual results (this was the bug - wildcards didn't work)
        assert len(filtered_data["results"]) > 0, \
            f"Pattern '{pattern}' returned no results but matching files exist"

        # All results should match the pattern
        for result in filtered_data["results"]:
            assert result["path"].endswith(common_ext), \
                f"Result {result['path']} does not match pattern {pattern}"

        # totalCount should reflect filtered count, not unfiltered Lucene count
        assert filtered_data["totalCount"] >= len(filtered_data["results"]), \
            "totalCount should be >= number of returned results"

        # Verify totalCount is the filtered count (should be <= unfiltered count)
        assert filtered_data["totalCount"] <= unfiltered_total, \
            f"Filtered totalCount ({filtered_data['totalCount']}) should be <= unfiltered ({unfiltered_total})"

    def test_search_with_count_limit(self, api_client, indexed_repo):
        """Test search result count limit."""
        response = api_client.search_files(query="the", repos=indexed_repo, count=3)
        assert response.status_code == 200

        data = response.json()
        assert len(data["results"]) <= 3

    def test_search_multiple_repos(self, api_client):
        """Test searching across multiple repositories."""
        repos = api_client.repos().json()
        if len(repos["repositories"]) < 2:
            pytest.skip("Need at least 2 repositories for this test")

        repo_names = [r["name"] for r in repos["repositories"][:2] if r["hasCommits"]]
        if len(repo_names) < 2:
            pytest.skip("Need at least 2 repositories with commits")

        response = api_client.search_files(query="public", repos=repo_names)
        assert response.status_code == 200

    def test_missing_query_parameter(self, api_client):
        """Test error when query parameter is missing."""
        response = api_client.get("search/files")
        assert response.status_code == 400

    def test_search_query_in_response(self, api_client, indexed_repo):
        """Test that the executed query is included in response."""
        response = api_client.search_files(query="test", repos=indexed_repo)
        assert response.status_code == 200

        data = response.json()
        # Query should include type:blob
        assert "type:blob" in data["query"]
        assert "test" in data["query"]

    def test_wildcard_only_query_returns_error(self, api_client, indexed_repo):
        """Test that wildcard-only queries return a 400 error instead of crashing.

        Regression test for issue where query='*' caused a Lucene error and
        returned an HTML error page instead of JSON.
        """
        # Test various wildcard-only patterns
        wildcard_queries = ["*", "?", "* *", "**", "*?*"]

        for query in wildcard_queries:
            response = api_client.search_files(query=query, repos=indexed_repo)

            # Should return 400 Bad Request, not 500
            assert response.status_code == 400, \
                f"Query '{query}' should return 400, got {response.status_code}"

            # Should return valid JSON error
            data = response.json()
            assert "error" in data, f"Query '{query}' should return JSON error"
            assert "wildcard" in data["error"].lower(), \
                f"Error message should mention wildcard: {data['error']}"
