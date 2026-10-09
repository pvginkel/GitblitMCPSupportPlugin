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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.FindFilesResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.JGitUtils;
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

        // Parse and validate glob pattern. Unsupported syntax is an error
        // rather than a literal match, which would answer zero results.
        Pattern matcher;
        try {
            matcher = globToRegex(pathPattern);
        } catch (IllegalArgumentException e) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid glob pattern: " + e.getMessage());
            return;
        }

        // Parse optional parameters
        String reposParam = request.getParameter("repos");
        String revisionParam = request.getParameter("revision");
        int limit = parseIntParam(request, "limit", DEFAULT_LIMIT);
        int offset = parseIntParam(request, "offset", 0);

        // Cap limit and ensure offset is non-negative
        if (limit < 1) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        if (offset < 0) offset = 0;

        // Get accessible repositories
        List<String> repos = getAccessibleRepositories(gitblit, user, reposParam);

        if (repos.isEmpty()) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "No accessible repositories found");
            return;
        }

        // Sort repositories alphabetically for predictable results
        Collections.sort(repos);

        log.info("Find files: user={}, pattern='{}', repos={}, limit={}, offset={}",
                 user.username, pathPattern, repos.size(), limit, offset);

        // Build response
        FindFilesResponse result = new FindFilesResponse();
        result.pattern = pathPattern;
        result.results = new ArrayList<>();
        result.skipped = new ArrayList<>();
        int totalMatched = 0;  // Total matches found (for totalCount)
        int skipped = 0;       // Matches skipped due to offset
        int collected = 0;     // Matches collected for result

        // Process each repository
        for (String repoName : repos) {
            Repository repository = null;
            TreeWalk treeWalk = null;

            try {
                repository = gitblit.getRepository(repoName);
                if (repository == null) {
                    result.skipped.add(new FindFilesResponse.SkippedRepository(repoName,
                        "Repository could not be opened"));
                    continue;
                }

                // Resolve revision to commit. Without a revision this is the
                // default branch, as FileHandler resolves it: HEAD, or the most
                // recently updated branch when HEAD does not resolve.
                RevCommit commit = JGitUtils.getCommit(repository, revisionParam);
                if (commit == null) {
                    result.skipped.add(new FindFilesResponse.SkippedRepository(repoName,
                        StringUtils.isEmpty(revisionParam)
                            ? "Cannot resolve default branch"
                            : "Cannot resolve revision: " + revisionParam));
                    continue;
                }

                // Resolve the reference name for display
                String resolvedRef = resolveRef(repository, revisionParam, commit);

                List<String> matches = new ArrayList<>();

                treeWalk = new TreeWalk(repository);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);

                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    if (matcher.matcher(path).matches()) {
                        totalMatched++;

                        // Skip results before offset
                        if (skipped < offset) {
                            skipped++;
                            continue;
                        }

                        // Only collect up to limit results
                        if (collected < limit) {
                            matches.add(path);
                            collected++;
                        }
                        // Continue to count totalMatched even after limit
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
                if (repository != null) {
                    repository.close();
                }
            }
        }

        result.totalCount = totalMatched;
        result.limitHit = (offset + collected) < totalMatched;

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
        // Without a revision (or with HEAD), name the branch HEAD points at,
        // provided that is where the commit came from: a HEAD whose target
        // does not exist resolved to another branch, found below
        if (StringUtils.isEmpty(revision) || "HEAD".equals(revision)) {
            Ref head = repository.exactRef("HEAD");
            if (head != null && head.isSymbolic() && commitId.equals(head.getObjectId())) {
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
     *   {a,b} - matches any one of the comma-separated alternatives
     * Character classes, backslash escapes and nested or unbalanced braces
     * are rejected with an IllegalArgumentException naming the construct.
     */
    private Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        regex.append("^");

        int braceStart = -1;  // Index of the open '{', or -1 outside braces
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
            } else if (c == '{') {
                if (braceStart >= 0) {
                    throw unsupported("nested braces '{'", i);
                }
                braceStart = i;
                regex.append("(?:");
                i++;
            } else if (c == ',' && braceStart >= 0) {
                regex.append("|");
                i++;
            } else if (c == '}') {
                if (braceStart < 0) {
                    throw unsupported("unbalanced '}'", i);
                }
                braceStart = -1;
                regex.append(")");
                i++;
            } else if (c == '[' || c == ']') {
                throw unsupported("character class '" + c + "'", i);
            } else if (c == '\\') {
                throw unsupported("backslash escape '\\'", i);
            } else if (c == '.' || c == '(' || c == ')' || c == '^' || c == '$' ||
                       c == '|' || c == '+') {
                // Escape regex special chars
                regex.append("\\").append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }

        if (braceStart >= 0) {
            throw unsupported("unbalanced '{'", braceStart);
        }

        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    private static IllegalArgumentException unsupported(String construct, int index) {
        return new IllegalArgumentException(construct + " at index " + index
            + " is not supported (supported: *, **, ?, {a,b})");
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
