"""
Tests for POST /api/.mcp-internal/repos/refresh endpoint.
"""
import os
import subprocess
from concurrent.futures import ThreadPoolExecutor

# Put into the repositories folder after Gitblit started. Named to sort after
# the seeds, so tests taking the first repository keep taking a seed; cloned
# without gitblit.indexBranch, so the Lucene indexer leaves them alone.
ADDED_REPO = "pvginkel/UnlistedAtStart.git"
ADDED_CONCURRENTLY_REPO = "pvginkel/UnlistedAtStartToo.git"
SOURCE_REPO = "pvginkel/GitSyncDeploy.git"


def add_to_folder(repositories_folder, name):
    subprocess.run(
        [
            "git", "clone", "-q", "--bare", "--no-hardlinks",
            os.path.join(repositories_folder, SOURCE_REPO),
            os.path.join(repositories_folder, name),
        ],
        check=True,
    )


def listed_names(api_client):
    data = api_client.repos(limit=100).json()
    assert not data["limitHit"]
    return {repo["name"] for repo in data["repositories"]}


class TestReposRefreshEndpoint:
    """Tests for the /repos/refresh endpoint."""

    def test_refresh_lists_repository_added_after_start(self, api_client, repositories_folder):
        """Test a repository put into the folder after start is listed only
        after a refresh, the rest stay listed, and a second refresh adds
        nothing."""
        add_to_folder(repositories_folder, ADDED_REPO)

        before = listed_names(api_client)
        assert SOURCE_REPO in before
        assert ADDED_REPO not in before

        response = api_client.refresh_repos()
        assert response.status_code == 200
        assert response.json() == {"addedCount": 1, "added": [ADDED_REPO]}

        after = listed_names(api_client)
        assert after == before | {ADDED_REPO}

        response = api_client.refresh_repos()
        assert response.status_code == 200
        assert response.json() == {"addedCount": 0, "added": []}
        assert listed_names(api_client) == after

    def test_concurrent_refreshes_add_once(self, api_client, repositories_folder):
        """Test two refreshes at once add the new repository once between
        them."""
        add_to_folder(repositories_folder, ADDED_CONCURRENTLY_REPO)
        before = listed_names(api_client)
        assert ADDED_CONCURRENTLY_REPO not in before

        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda _: api_client.refresh_repos(), range(2)))

        assert [r.status_code for r in responses] == [200, 200]
        answers = sorted((r.json() for r in responses), key=lambda a: a["addedCount"])
        assert answers == [
            {"addedCount": 0, "added": []},
            {"addedCount": 1, "added": [ADDED_CONCURRENTLY_REPO]},
        ]
        assert listed_names(api_client) == before | {ADDED_CONCURRENTLY_REPO}

    def test_refresh_answers_post_only(self, api_client):
        """Test a GET of the refresh route is no route."""
        response = api_client.get("repos/refresh")
        assert response.status_code == 404
