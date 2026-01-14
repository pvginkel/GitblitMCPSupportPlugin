/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.FindFilesResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/.mcp-internal/find
 * Finds files matching a glob pattern across repositories using Git tree walking.
 */
public class FindFilesHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(FindFilesHandler.class);

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       IGitblit gitblit, UserModel user) throws IOException {

        // Parse required parameters
        String pathPattern = request.getParameter("pathPattern");
        if (StringUtils.isEmpty(pathPattern)) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Missing required parameter: pathPattern");
            return;
        }

        // Parse and validate glob pattern
        Pattern matcher;
        try {
            matcher = globToRegex(pathPattern);
        } catch (PatternSyntaxException e) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid glob pattern: " + e.getMessage());
            return;
        }

        // Parse optional parameters
        String reposParam = request.getParameter("repos");
        String revisionParam = request.getParameter("revision");
        int limit = parseIntParam(request, "limit", DEFAULT_LIMIT);
        if (limit < 1) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        // Get accessible repositories
        List<String> repos = getAccessibleRepositories(gitblit, user, reposParam);

        if (repos.isEmpty()) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "No accessible repositories found");
            return;
        }

        // Sort repositories alphabetically for predictable results
        Collections.sort(repos);

        log.info("Find files: user={}, pattern='{}', repos={}, limit={}",
                 user.username, pathPattern, repos.size(), limit);

        // Build response
        FindFilesResponse result = new FindFilesResponse();
        result.pattern = pathPattern;
        result.results = new ArrayList<>();
        int totalCount = 0;
        boolean limitHit = false;

        // Process each repository
        for (String repoName : repos) {
            if (limitHit) break;

            Repository repository = null;
            RevWalk revWalk = null;
            TreeWalk treeWalk = null;

            try {
                repository = gitblit.getRepository(repoName);
                if (repository == null) continue;

                // Resolve revision
                String revision = revisionParam != null ? revisionParam : "HEAD";
                ObjectId commitId = repository.resolve(revision);
                if (commitId == null) continue;

                revWalk = new RevWalk(repository);
                RevCommit commit = revWalk.parseCommit(commitId);

                // Resolve the reference name for display
                String resolvedRef = resolveRef(repository, revision, commitId);

                List<String> matches = new ArrayList<>();

                treeWalk = new TreeWalk(repository);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);

                while (treeWalk.next()) {
                    if (totalCount >= limit) {
                        limitHit = true;
                        break;
                    }

                    String path = treeWalk.getPathString();
                    if (matcher.matcher(path).matches()) {
                        matches.add(path);
                        totalCount++;
                    }
                }

                if (!matches.isEmpty()) {
                    // Sort file paths within each repository
                    Collections.sort(matches);
                    result.results.add(new FindFilesResponse.FindFilesResult(repoName, resolvedRef, matches));
                }

            } finally {
                if (treeWalk != null) {
                    treeWalk.close();
                }
                if (revWalk != null) {
                    revWalk.close();
                }
                if (repository != null) {
                    repository.close();
                }
            }
        }

        result.totalCount = totalCount;
        result.limitHit = limitHit;

        ResponseWriter.writeJson(response, result);
    }

    /**
     * Get list of accessible repositories.
     */
    private List<String> getAccessibleRepositories(IGitblit gitblit, UserModel user, String reposParam) {
        // Get all accessible repositories
        List<String> available = new ArrayList<>();
        for (RepositoryModel model : gitblit.getRepositoryModels(user)) {
            if (model.hasCommits) {
                available.add(model.name);
            }
        }

        if (StringUtils.isEmpty(reposParam)) {
            return available;
        }

        // Filter to requested repositories
        List<String> requested = Arrays.asList(reposParam.split(","));
        List<String> result = new ArrayList<>();
        for (String repo : requested) {
            String trimmed = repo.trim();
            if (available.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Resolve a revision to a human-readable reference name.
     */
    private String resolveRef(Repository repository, String revision, ObjectId commitId) throws IOException {
        // If it was HEAD, try to find the actual branch name
        if ("HEAD".equals(revision)) {
            Ref head = repository.exactRef("HEAD");
            if (head != null && head.isSymbolic()) {
                return head.getTarget().getName();
            }
        }

        // Try to find matching branch
        for (Ref ref : repository.getRefDatabase().getRefs("refs/heads/").values()) {
            if (ref.getObjectId() != null && ref.getObjectId().equals(commitId)) {
                return ref.getName();
            }
        }

        // Return the commit SHA
        return commitId.getName();
    }

    /**
     * Convert a glob pattern to a regex Pattern.
     * Supports:
     *   * - matches any characters except /
     *   ** - matches any characters including /
     *   ? - matches a single character except /
     */
    private Pattern globToRegex(String glob) throws PatternSyntaxException {
        StringBuilder regex = new StringBuilder();
        regex.append("^");

        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);

            if (c == '*') {
                // Check for **
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // ** matches anything including /
                    regex.append(".*");
                    i += 2;
                    // Skip trailing / after ** (e.g., **/ matches any path prefix)
                    if (i < glob.length() && glob.charAt(i) == '/') {
                        i++;
                    }
                } else {
                    // * matches anything except /
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                // ? matches single char except /
                regex.append("[^/]");
                i++;
            } else if (c == '.' || c == '(' || c == ')' || c == '[' || c == ']' ||
                       c == '{' || c == '}' || c == '\\' || c == '^' || c == '$' ||
                       c == '|' || c == '+') {
                // Escape regex special chars
                regex.append("\\").append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }

        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    private int parseIntParam(HttpServletRequest request, String name, int defaultValue) {
        String value = request.getParameter(name);
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
