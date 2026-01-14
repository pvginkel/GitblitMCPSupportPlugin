/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.RepoListResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/.mcp-internal/repos
 * Lists repositories accessible to the authenticated user.
 */
public class ReposHandler implements RequestHandler {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final SimpleDateFormat dateFormat;

    public ReposHandler() {
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        this.dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       IGitblit gitblit, UserModel user) throws IOException {

        // Parse query parameters
        String query = request.getParameter("query");
        int limit = parseIntParam(request, "limit", DEFAULT_LIMIT);
        String after = request.getParameter("after");

        // Cap limit
        if (limit < 1) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;

        // Get all accessible repositories
        List<RepositoryModel> allRepos = gitblit.getRepositoryModels(user);

        // Filter by query (case-insensitive substring match on name)
        List<RepositoryModel> filteredRepos = new ArrayList<>();
        for (RepositoryModel model : allRepos) {
            if (StringUtils.isEmpty(query) ||
                model.name.toLowerCase().contains(query.toLowerCase())) {
                filteredRepos.add(model);
            }
        }

        // Sort alphabetically by name
        Collections.sort(filteredRepos, new Comparator<RepositoryModel>() {
            @Override
            public int compare(RepositoryModel a, RepositoryModel b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        int totalCount = filteredRepos.size();

        // Apply cursor-based pagination (after = repository name to start after)
        int startIndex = 0;
        if (!StringUtils.isEmpty(after)) {
            for (int i = 0; i < filteredRepos.size(); i++) {
                if (filteredRepos.get(i).name.equals(after)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        // Get page of results
        int endIndex = Math.min(startIndex + limit, filteredRepos.size());
        List<RepositoryModel> pageRepos = filteredRepos.subList(startIndex, endIndex);

        // Build response
        RepoListResponse result = new RepoListResponse();
        result.repositories = new ArrayList<>();

        for (RepositoryModel model : pageRepos) {
            String lastChange = model.lastChange != null ?
                dateFormat.format(model.lastChange) : null;

            RepoListResponse.RepoInfo info = new RepoListResponse.RepoInfo(
                model.name,
                model.description,
                lastChange,
                model.hasCommits
            );
            result.repositories.add(info);
        }

        // Build pagination info
        boolean hasNextPage = endIndex < filteredRepos.size();
        String endCursor = pageRepos.isEmpty() ? null :
            pageRepos.get(pageRepos.size() - 1).name;

        result.pagination = new RepoListResponse.Pagination(totalCount, hasNextPage, endCursor);

        ResponseWriter.writeJson(response, result);
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
