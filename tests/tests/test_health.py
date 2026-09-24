"""
Tests for GET /api/.mcp-internal/health endpoint.
"""


class TestHealthEndpoint:
    """Tests for the /health endpoint."""

    def test_health_shape(self, api_client):
        """Test the response carries the index writer summary."""
        response = api_client.get("health")
        assert response.status_code in (200, 503)

        data = response.json()
        assert isinstance(data["healthy"], bool)
        assert isinstance(data["openIndexes"], int)
        assert isinstance(data["deadIndexCount"], int)
        assert isinstance(data["deadIndexes"], list)

    def test_status_matches_body(self, api_client):
        """Test 200 means no dead index writers and 503 means some."""
        response = api_client.get("health")
        data = response.json()

        assert data["healthy"] == (response.status_code == 200)
        assert data["healthy"] == (data["deadIndexCount"] == 0)
        assert len(data["deadIndexes"]) <= data["deadIndexCount"]
        for dead in data["deadIndexes"]:
            assert dead["repository"]
            assert dead["cause"]

    def test_dead_writers_are_dropped(self, api_client):
        """Test a dead writer reported once is gone on the next call."""
        first = api_client.get("health").json()
        second = api_client.get("health").json()

        still_dead = {d["repository"] for d in second["deadIndexes"]}
        for dead in first["deadIndexes"]:
            assert dead["repository"] not in still_dead
