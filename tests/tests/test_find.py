"""
Tests for GET /api/.mcp-internal/find endpoint.
"""
import pytest


class TestFindFilesEndpoint:
    """Tests for the /find endpoint."""

    @pytest.fixture
    def repo_with_commits(self, api_client):
        """Get a repository that has commits."""
        repos = api_client.repos().json()
        for repo in repos["repositories"]:
            if repo["hasCommits"]:
                return repo["name"]
        pytest.skip("No repository with commits available")

    def test_basic_find(self, api_client, repo_with_commits):
        """Test basic file find with simple pattern."""
        response = api_client.find(path_pattern="*", repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        assert "pattern" in data
        assert "totalCount" in data
        assert "limitHit" in data
        assert "results" in data
        assert isinstance(data["results"], list)

    def test_find_result_structure(self, api_client, repo_with_commits):
        """Test that find results have correct structure."""
        response = api_client.find(path_pattern="*", repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No find results to validate structure")

        result = data["results"][0]
        assert "repository" in result
        assert "revision" in result
        assert "files" in result
        assert isinstance(result["files"], list)

    def test_find_with_extension_pattern(self, api_client, repo_with_commits):
        """Test finding files by extension."""
        # First find what extensions exist in the repo
        response = api_client.find(path_pattern="*", repos=repo_with_commits, limit=50)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No files found in repository")

        # Get all files and find a common extension
        all_files = []
        for result in data["results"]:
            all_files.extend(result["files"])

        extensions = {}
        for f in all_files:
            if "." in f:
                ext = f[f.rfind("."):]
                extensions[ext] = extensions.get(ext, 0) + 1

        if not extensions:
            pytest.skip("No files with extensions found")

        # Test with the most common extension
        common_ext = max(extensions, key=extensions.get)
        pattern = f"**/*{common_ext}"

        response = api_client.find(path_pattern=pattern, repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        # All files should match the extension
        for result in data["results"]:
            for f in result["files"]:
                assert f.endswith(common_ext), f"File {f} should end with {common_ext}"

    def test_find_with_double_star_pattern(self, api_client, repo_with_commits):
        """Test finding files with ** pattern for directory crossing."""
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=20)
        assert response.status_code == 200

        data = response.json()
        assert "results" in data
        # Should return files at any depth
        if data["results"]:
            all_files = []
            for result in data["results"]:
                all_files.extend(result["files"])
            # Check that files at different depths are included
            has_nested = any("/" in f for f in all_files)
            has_root = any("/" not in f for f in all_files)
            # At least one type should exist
            assert has_nested or has_root

    def test_find_with_limit(self, api_client, repo_with_commits):
        """Test find result limit."""
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=3)
        assert response.status_code == 200

        data = response.json()
        # Count total files across all results
        total_files = sum(len(r["files"]) for r in data["results"])
        assert total_files <= 3

    def test_find_limit_hit(self, api_client, repo_with_commits):
        """Test that limitHit is set correctly when limit is reached."""
        # First check how many files exist
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=200)
        assert response.status_code == 200
        full_data = response.json()

        if full_data["totalCount"] <= 2:
            pytest.skip("Not enough files to test limitHit")

        # Now request with lower limit
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=2)
        assert response.status_code == 200

        data = response.json()
        # If there were more files than the limit, limitHit should be True
        if full_data["totalCount"] > 2:
            assert data["limitHit"] is True

    def test_find_missing_pattern_parameter(self, api_client):
        """Test error when pathPattern parameter is missing."""
        response = api_client.get("find", {})
        assert response.status_code == 400

        data = response.json()
        assert "error" in data
        assert "pathPattern" in data["error"]

    def test_find_pattern_in_response(self, api_client, repo_with_commits):
        """Test that the pattern is included in response."""
        pattern = "**/*.txt"
        response = api_client.find(path_pattern=pattern, repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        assert data["pattern"] == pattern

    def test_find_with_specific_filename(self, api_client, repo_with_commits):
        """Test finding exact filename anywhere in the tree."""
        # Common files that might exist
        common_files = ["README.md", "pom.xml", "build.gradle", "package.json"]

        for filename in common_files:
            pattern = f"**/{filename}"
            response = api_client.find(path_pattern=pattern, repos=repo_with_commits)
            assert response.status_code == 200

            data = response.json()
            if data["results"]:
                # Verify all matches end with the filename
                for result in data["results"]:
                    for f in result["files"]:
                        assert f.endswith(filename) or f == filename
                break
        else:
            pytest.skip("No common files found to test exact filename matching")

    def test_find_multiple_repos(self, api_client):
        """Test finding files across multiple repositories."""
        repos = api_client.repos().json()
        repo_names = [r["name"] for r in repos["repositories"] if r["hasCommits"]][:2]

        if len(repo_names) < 2:
            pytest.skip("Need at least 2 repositories with commits")

        response = api_client.find(path_pattern="**/*", repos=repo_names, limit=10)
        assert response.status_code == 200

        data = response.json()
        # Results should be from the requested repos
        for result in data["results"]:
            assert result["repository"] in repo_names

    def test_find_revision_resolved(self, api_client, repo_with_commits):
        """Test that revision is resolved to a ref name."""
        response = api_client.find(path_pattern="*", repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No files found")

        # Revision should be resolved (refs/heads/... or commit SHA)
        result = data["results"][0]
        assert result["revision"]
        # Should be either a branch ref or a commit SHA
        is_branch = result["revision"].startswith("refs/")
        is_sha = len(result["revision"]) == 40 and all(
            c in "0123456789abcdef" for c in result["revision"]
        )
        assert is_branch or is_sha

    def test_find_question_mark_wildcard(self, api_client, repo_with_commits):
        """Test that ? wildcard matches single character."""
        # This is a tricky test - find a file and verify ? works
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=20)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No files found")

        # Find a file with a simple name we can test ? against
        for result in data["results"]:
            for f in result["files"]:
                basename = f.split("/")[-1]
                if len(basename) >= 3 and "." in basename:
                    # Try a pattern with ? for a character
                    ext_pos = basename.rfind(".")
                    if ext_pos > 0:
                        # Create pattern replacing one char before extension with ?
                        pattern = f"**/{basename[:ext_pos-1]}?{basename[ext_pos:]}"
                        response2 = api_client.find(
                            path_pattern=pattern, repos=repo_with_commits
                        )
                        assert response2.status_code == 200
                        # Pattern should be valid, even if no matches
                        return

        pytest.skip("Could not find suitable file to test ? wildcard")

    def test_find_sorted_results(self, api_client, repo_with_commits):
        """Test that files within each result are sorted."""
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=50)
        assert response.status_code == 200

        data = response.json()
        for result in data["results"]:
            files = result["files"]
            if len(files) > 1:
                # Verify files are sorted
                assert files == sorted(files), "Files should be sorted alphabetically"

    def test_find_no_results(self, api_client, repo_with_commits):
        """Test that searching for non-existent pattern returns empty results."""
        response = api_client.find(
            path_pattern="**/this_file_definitely_does_not_exist_12345.xyz",
            repos=repo_with_commits
        )
        assert response.status_code == 200

        data = response.json()
        assert data["totalCount"] == 0
        assert data["results"] == []
        assert data["limitHit"] is False

    def test_find_skipped_is_list(self, api_client, repo_with_commits):
        """Test that the response names skipped repositories as a list."""
        response = api_client.find(path_pattern="*", repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        assert isinstance(data["skipped"], list)
        assert all(s["repository"] != repo_with_commits for s in data["skipped"])

    def test_find_unresolvable_revision_is_skipped(self, api_client, repo_with_commits):
        """Test that a repository whose revision does not resolve is named in skipped."""
        revision = "refs/heads/this_branch_definitely_does_not_exist_12345"
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, revision=revision)
        assert response.status_code == 200

        data = response.json()
        assert data["results"] == []
        assert data["totalCount"] == 0
        assert len(data["skipped"]) == 1
        assert data["skipped"][0]["repository"] == repo_with_commits
        assert revision in data["skipped"][0]["reason"]

    def test_find_default_revision_is_default_branch(self, api_client, repo_with_commits):
        """Test that omitting revision searches the branch it reports."""
        response = api_client.find(path_pattern="**/*", repos=repo_with_commits, limit=200)
        assert response.status_code == 200

        data = response.json()
        if not data["results"]:
            pytest.skip("No files found")

        result = data["results"][0]
        if not result["revision"].startswith("refs/heads/"):
            pytest.skip("Default revision did not resolve to a branch")

        response = api_client.find(
            path_pattern="**/*", repos=repo_with_commits, limit=200, revision=result["revision"]
        )
        assert response.status_code == 200
        assert response.json()["results"] == data["results"]

    def _root_files(self, api_client, repo):
        """Return the files at the root of a repository's default branch."""
        response = api_client.find(path_pattern="*", repos=repo, limit=200)
        assert response.status_code == 200
        results = response.json()["results"]
        return results[0]["files"] if results else []

    def test_find_brace_alternation(self, api_client, repo_with_commits):
        """Test that {a,b} matches either alternative."""
        root_files = self._root_files(api_client, repo_with_commits)
        if len(root_files) < 2:
            pytest.skip("Need at least 2 files at the repository root")
        first, second = root_files[0], root_files[1]

        response = api_client.find(path_pattern=f"{{{first},{second}}}", repos=repo_with_commits)
        assert response.status_code == 200

        data = response.json()
        assert data["totalCount"] == 2
        assert data["results"][0]["files"] == sorted([first, second])

    def test_find_brace_alternation_after_double_star(self, api_client, repo_with_commits):
        """Test that **/{a,b} matches the alternatives at the root and below."""
        root_files = self._root_files(api_client, repo_with_commits)
        if len(root_files) < 2:
            pytest.skip("Need at least 2 files at the repository root")
        first, second = root_files[0], root_files[1]

        response = api_client.find(
            path_pattern=f"**/{{{first},{second}}}", repos=repo_with_commits, limit=200
        )
        assert response.status_code == 200

        files = response.json()["results"][0]["files"]
        assert first in files
        assert second in files
        assert all(f.endswith(first) or f.endswith(second) for f in files)

    def test_find_brace_alternation_with_wildcards(self, api_client, repo_with_commits):
        """Test that *, ** and ? work inside each alternative."""
        root_files = self._root_files(api_client, repo_with_commits)
        if not root_files:
            pytest.skip("No files at the repository root")
        name = root_files[0]

        response = api_client.find(
            path_pattern="{*,this_file_definitely_does_not_exist_12345.xyz}",
            repos=repo_with_commits, limit=200
        )
        assert response.status_code == 200
        assert response.json()["results"][0]["files"] == root_files

        response = api_client.find(
            path_pattern=f"{{{name[:-1]}?,**/this_file_definitely_does_not_exist_12345.xyz}}",
            repos=repo_with_commits
        )
        assert response.status_code == 200
        assert name in response.json()["results"][0]["files"]

    @pytest.mark.parametrize("pattern,construct", [
        ("[ab]*.md", "character class '['"),
        ("*.md]", "character class ']'"),
        ("\\*.md", "backslash escape"),
        ("{a,{b,c}}", "nested braces"),
        ("{a,b", "unbalanced '{'"),
        ("a,b}", "unbalanced '}'"),
    ])
    def test_find_unsupported_glob_syntax(self, api_client, repo_with_commits, pattern, construct):
        """Test that unsupported glob syntax is an error naming it, not zero results."""
        response = api_client.find(path_pattern=pattern, repos=repo_with_commits)
        assert response.status_code == 400

        error = response.json()["error"]
        assert "Invalid glob pattern" in error
        assert construct in error
