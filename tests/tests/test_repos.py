"""
Tests for GET /api/.mcp-internal/repos endpoint.
"""
import pytest


class TestReposEndpoint:
    """Tests for the /repos endpoint."""

    def test_list_all_repos(self, api_client):
        """Test listing all repositories."""
        response = api_client.repos()
        assert response.status_code == 200

        data = response.json()
        assert "repositories" in data
        assert "pagination" in data
        assert isinstance(data["repositories"], list)
        assert "totalCount" in data["pagination"]
        assert "hasNextPage" in data["pagination"]

    def test_list_repos_with_query(self, api_client):
        """Test filtering repositories by name."""
        # First get all repos to find a name to search for
        all_repos = api_client.repos().json()
        if not all_repos["repositories"]:
            pytest.skip("No repositories available for testing")

        # Get first repo name and search for part of it
        first_repo = all_repos["repositories"][0]["name"]
        search_term = first_repo[:3]  # First 3 characters

        response = api_client.repos(query=search_term)
        assert response.status_code == 200

        data = response.json()
        # All results should contain the search term (case-insensitive)
        for repo in data["repositories"]:
            assert search_term.lower() in repo["name"].lower()

    def test_list_repos_with_limit(self, api_client):
        """Test pagination limit."""
        response = api_client.repos(limit=2)
        assert response.status_code == 200

        data = response.json()
        assert len(data["repositories"]) <= 2

    def test_list_repos_pagination(self, api_client):
        """Test cursor-based pagination."""
        # Get first page
        first_page = api_client.repos(limit=1).json()
        if first_page["pagination"]["totalCount"] < 2:
            pytest.skip("Not enough repositories for pagination test")

        # Get second page using cursor
        cursor = first_page["pagination"]["endCursor"]
        second_page = api_client.repos(limit=1, after=cursor).json()

        assert second_page["repositories"]
        # Second page should have different repos
        if first_page["repositories"] and second_page["repositories"]:
            assert first_page["repositories"][0]["name"] != second_page["repositories"][0]["name"]

    def test_repo_info_fields(self, api_client):
        """Test that repository info contains expected fields."""
        response = api_client.repos(limit=1)
        assert response.status_code == 200

        data = response.json()
        if not data["repositories"]:
            pytest.skip("No repositories available")

        repo = data["repositories"][0]
        assert "name" in repo
        assert "description" in repo
        assert "hasCommits" in repo
        # lastChange may be null for empty repos
        assert "lastChange" in repo
