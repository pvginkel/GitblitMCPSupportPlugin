"""
Tests for GET /api/.mcp-internal/files endpoint.
"""
import pytest


class TestFilesEndpoint:
    """Tests for the /files endpoint."""

    @pytest.fixture
    def test_repo(self, api_client):
        """Get a repository with commits for testing."""
        repos = api_client.repos().json()
        for repo in repos["repositories"]:
            if repo["hasCommits"]:
                return repo["name"]
        pytest.skip("No repository with commits available")

    def test_list_root_directory(self, api_client, test_repo):
        """Test listing files at repository root."""
        response = api_client.files(repo=test_repo)
        assert response.status_code == 200

        data = response.json()
        assert "files" in data
        assert isinstance(data["files"], list)

    def test_list_files_structure(self, api_client, test_repo):
        """Test that file entries have correct structure."""
        response = api_client.files(repo=test_repo)
        assert response.status_code == 200

        data = response.json()
        if not data["files"]:
            pytest.skip("Repository root is empty")

        for entry in data["files"]:
            assert "path" in entry
            assert "isDirectory" in entry
            # Directories should end with /
            if entry["isDirectory"]:
                assert entry["path"].endswith("/")
                assert entry.get("size") is None
            else:
                # Files should have size
                assert "size" in entry

    def test_list_subdirectory(self, api_client, test_repo):
        """Test listing files in a subdirectory."""
        # First find a directory
        root = api_client.files(repo=test_repo).json()
        directory = None
        for entry in root["files"]:
            if entry["isDirectory"]:
                directory = entry["path"].rstrip("/")
                break

        if not directory:
            pytest.skip("No subdirectory in repository root")

        response = api_client.files(repo=test_repo, path=directory)
        assert response.status_code == 200

        data = response.json()
        assert "files" in data

    def test_list_files_with_revision(self, api_client, test_repo):
        """Test listing files at a specific revision."""
        # Test with HEAD (default)
        response = api_client.files(repo=test_repo, revision="HEAD")
        assert response.status_code == 200

    def test_missing_repo_parameter(self, api_client):
        """Test error when repo parameter is missing."""
        response = api_client.get("files")
        assert response.status_code == 400

        data = response.json()
        assert "error" in data

    def test_nonexistent_repo(self, api_client):
        """Test error for non-existent repository."""
        response = api_client.files(repo="nonexistent-repo-12345.git")
        assert response.status_code == 404

    def test_invalid_revision(self, api_client, test_repo):
        """Test error for invalid revision."""
        response = api_client.files(repo=test_repo, revision="invalid-revision-xyz")
        assert response.status_code == 400

    def test_directories_listed_first(self, api_client, test_repo):
        """Test that directories are listed before files."""
        response = api_client.files(repo=test_repo)
        data = response.json()

        if len(data["files"]) < 2:
            pytest.skip("Not enough entries to test ordering")

        # Find first non-directory
        first_file_index = None
        for i, entry in enumerate(data["files"]):
            if not entry["isDirectory"]:
                first_file_index = i
                break

        if first_file_index is None:
            pytest.skip("No files in directory")

        # All entries before first file should be directories
        for i in range(first_file_index):
            assert data["files"][i]["isDirectory"]

    def test_nonexistent_path_returns_404(self, api_client, test_repo):
        """Test that nonexistent paths return 404 instead of empty list.

        Regression test for issue where nonexistent paths returned {"files": []}
        instead of a NOT_FOUND error, making it impossible to distinguish between
        'directory exists but is empty' vs 'directory does not exist'.
        """
        response = api_client.files(
            repo=test_repo,
            path="nonexistent/path/that/does/not/exist/xyz123"
        )

        # Should return 404 Not Found
        assert response.status_code == 404, \
            f"Nonexistent path should return 404, got {response.status_code}"

        # Should return valid JSON error
        data = response.json()
        assert "error" in data, "Response should contain error message"
        assert "not found" in data["error"].lower(), \
            f"Error should mention 'not found': {data['error']}"
