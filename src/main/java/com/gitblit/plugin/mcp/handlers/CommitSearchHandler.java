/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants.SearchObjectType;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.SearchResult;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.CommitSearchResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/mcp-server/search/commits
 * Searches commit history using Lucene index.
 */
public class CommitSearchHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CommitSearchHandler.class);

    private static final int DEFAULT_COUNT = 25;
    private static final int MAX_COUNT = 100;

    private final SimpleDateFormat dateFormat;

    public CommitSearchHandler() {
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        this.dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       IGitblit gitblit, UserModel user) throws IOException {

        // Parse required parameters
        String query = request.getParameter("query");
        if (StringUtils.isEmpty(query)) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Missing required parameter: query");
            return;
        }

        String reposParam = request.getParameter("repos");
        if (StringUtils.isEmpty(reposParam)) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Missing required parameter: repos");
            return;
        }

        // Parse optional parameters
        String authors = request.getParameter("authors");
        String branch = request.getParameter("branch");
        int count = parseIntParam(request, "count", DEFAULT_COUNT);

        // Cap count
        if (count < 1) count = DEFAULT_COUNT;
        if (count > MAX_COUNT) count = MAX_COUNT;

        // Build Lucene query
        StringBuilder luceneQuery = new StringBuilder();
        luceneQuery.append("type:commit");

        // Add the user's query
        luceneQuery.append(" AND (").append(query).append(")");

        // Add authors filter (OR logic)
        if (!StringUtils.isEmpty(authors)) {
            String[] authorList = authors.split(",");
            luceneQuery.append(" AND (");
            for (int i = 0; i < authorList.length; i++) {
                if (i > 0) luceneQuery.append(" OR ");
                luceneQuery.append("author:").append(authorList[i].trim());
            }
            luceneQuery.append(")");
        }

        // Add branch filter
        if (!StringUtils.isEmpty(branch)) {
            luceneQuery.append(" AND branch:\"").append(branch).append("\"");
        }

        // Determine repositories to search
        List<String> searchRepos = getSearchRepositories(gitblit, user, reposParam);

        if (searchRepos.isEmpty()) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "No accessible indexed repositories found");
            return;
        }

        // Execute search
        String finalQuery = luceneQuery.toString();
        log.info("Commit search: user={}, query='{}', repos={}", user.username, finalQuery, searchRepos.size());

        List<SearchResult> results = gitblit.search(finalQuery, 1, count, searchRepos);

        // Build response
        CommitSearchResponse searchResponse = new CommitSearchResponse();
        searchResponse.query = finalQuery;
        searchResponse.totalCount = results.isEmpty() ? 0 : results.get(0).totalHits;
        searchResponse.limitHit = searchResponse.totalCount > count;
        searchResponse.commits = new ArrayList<>();

        // Process each result
        for (SearchResult sr : results) {
            // Only include commit results
            if (sr.type != SearchObjectType.commit) {
                continue;
            }

            CommitSearchResponse.CommitInfo commitInfo = new CommitSearchResponse.CommitInfo();
            commitInfo.repository = sr.repository;
            commitInfo.commit = sr.commitId;
            commitInfo.author = sr.author;
            commitInfo.committer = sr.committer;
            commitInfo.date = sr.date != null ? dateFormat.format(sr.date) : null;
            commitInfo.message = sr.summary;
            commitInfo.branch = sr.branch;

            // Extract title (first line of message)
            if (sr.summary != null) {
                int newlineIndex = sr.summary.indexOf('\n');
                commitInfo.title = newlineIndex > 0 ?
                    sr.summary.substring(0, newlineIndex) : sr.summary;
            }

            searchResponse.commits.add(commitInfo);
        }

        ResponseWriter.writeJson(response, searchResponse);
    }

    /**
     * Get list of repositories to search.
     */
    private List<String> getSearchRepositories(IGitblit gitblit, UserModel user, String reposParam) {
        // Get all accessible repositories with indexing enabled
        List<String> available = new ArrayList<>();
        for (RepositoryModel model : gitblit.getRepositoryModels(user)) {
            if (model.hasCommits && !ArrayUtils.isEmpty(model.indexedBranches)) {
                available.add(model.name);
            }
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
