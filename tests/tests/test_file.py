"""
Tests for GET /api/.mcp-internal/file endpoint.
"""
import pytest


class TestFileEndpoint:
    """Tests for the /file endpoint."""

    @pytest.fixture
    def test_repo(self, api_client):
        """Get a repository with commits for testing."""
        repos = api_client.repos().json()
        for repo in repos["repositories"]:
            if repo["hasCommits"]:
                return repo["name"]
        pytest.skip("No repository with commits available")

    @pytest.fixture
    def test_file(self, api_client, test_repo):
        """Get a text file for testing."""
        files = api_client.files(repo=test_repo).json()
        for entry in files["files"]:
            if not entry["isDirectory"]:
                # Try common text file extensions
                name = entry["path"].lower()
                if any(name.endswith(ext) for ext in [".txt", ".md", ".java", ".py", ".xml", ".json", ".yml", ".yaml"]):
                    return entry["path"]
        # Return first non-directory file as fallback
        for entry in files["files"]:
            if not entry["isDirectory"]:
                return entry["path"]
        pytest.skip("No files in repository")

    def test_read_file(self, api_client, test_repo, test_file):
        """Test reading a file."""
        response = api_client.file(repo=test_repo, path=test_file)
        assert response.status_code == 200

        data = response.json()
        assert "content" in data
        assert isinstance(data["content"], str)

    def test_file_content_has_line_numbers(self, api_client, test_repo, test_file):
        """Test that file content includes line numbers."""
        response = api_client.file(repo=test_repo, path=test_file)
        assert response.status_code == 200

        data = response.json()
        content = data["content"]

        # First line should start with "1: "
        if content:
            assert content.startswith("1: ")

    def test_read_file_line_range(self, api_client, test_repo, test_file):
        """Test reading specific line range."""
        # First get full file to know line count
        full_response = api_client.file(repo=test_repo, path=test_file)
        full_content = full_response.json()["content"]
        line_count = len(full_content.strip().split("\n"))

        if line_count < 3:
            pytest.skip("File too small for line range test")

        # Request lines 2-3
        response = api_client.file(repo=test_repo, path=test_file, start_line=2, end_line=3)
        assert response.status_code == 200

        data = response.json()
        lines = data["content"].strip().split("\n")

        # Should have at most 2 lines
        assert len(lines) <= 2
        # First line should be line 2
        assert lines[0].startswith("2: ")

    def test_missing_repo_parameter(self, api_client):
        """Test error when repo parameter is missing."""
        response = api_client.get("file", {"path": "test.txt"})
        assert response.status_code == 400

    def test_missing_path_parameter(self, api_client, test_repo):
        """Test error when path parameter is missing."""
        response = api_client.get("file", {"repo": test_repo})
        assert response.status_code == 400

    def test_nonexistent_file(self, api_client, test_repo):
        """Test error for non-existent file."""
        response = api_client.file(repo=test_repo, path="nonexistent-file-12345.xyz")
        assert response.status_code == 404

    def test_read_file_with_revision(self, api_client, test_repo, test_file):
        """Test reading file at specific revision."""
        response = api_client.file(repo=test_repo, path=test_file, revision="HEAD")
        assert response.status_code == 200


class TestFileSizeLimit:
    """Tests for file size limit (128KB)."""

    @pytest.fixture
    def test_repo(self, api_client):
        """Get a repository with commits for testing."""
        repos = api_client.repos().json()
        for repo in repos["repositories"]:
            if repo["hasCommits"]:
                return repo["name"]
        pytest.skip("No repository with commits available")

    def test_large_file_rejected(self, api_client, test_repo):
        """Test that files over 128KB are rejected."""
        # Find a large file if one exists
        files = api_client.files(repo=test_repo).json()
        large_file = None
        for entry in files["files"]:
            if not entry["isDirectory"] and entry.get("size", 0) > 128 * 1024:
                large_file = entry["path"]
                break

        if not large_file:
            pytest.skip("No large file available for testing")

        response = api_client.file(repo=test_repo, path=large_file)
        assert response.status_code == 400
        assert "128KB" in response.json().get("error", "")
