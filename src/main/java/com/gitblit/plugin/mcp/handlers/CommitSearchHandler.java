/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants.SearchObjectType;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.CommitSearchResponse;
import com.gitblit.plugin.mcp.search.IndexSearch;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/.mcp-internal/search/commits
 * Searches commit history using Lucene index.
 */
public class CommitSearchHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CommitSearchHandler.class);

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private static final Set<String> COMMIT_FIELDS = IndexSearch.fields(
        IndexSearch.FIELD_COMMIT, IndexSearch.FIELD_AUTHOR, IndexSearch.FIELD_COMMITTER,
        IndexSearch.FIELD_DATE, IndexSearch.FIELD_SUMMARY, IndexSearch.FIELD_CONTENT,
        IndexSearch.FIELD_BRANCH);

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

        // Check if this is a wildcard-only query (e.g., "*")
        // Allowed since repos is required, which prevents unbounded results
        boolean isWildcardQuery = isWildcardOnlyQuery(query);

        // Parse optional parameters
        String authors = request.getParameter("authors");
        String branch = request.getParameter("branch");

        // Parse pagination parameters (support 'count' as deprecated alias for 'limit')
        int limit = parseIntParam(request, "limit", -1);
        if (limit < 0) {
            limit = parseIntParam(request, "count", DEFAULT_LIMIT);  // Backward compatibility
        }
        int offset = parseIntParam(request, "offset", 0);

        // Cap limit and ensure offset is non-negative
        if (limit < 1) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        if (offset < 0) offset = 0;

        Query userQuery;
        try {
            userQuery = IndexSearch.parse(query);
        } catch (ParseException e) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid query: " + e.getMessage());
            return;
        }

        // Determine repositories to search
        List<RepositoryModel> searchRepos = getSearchRepositories(gitblit, user, reposParam);
        if (searchRepos.isEmpty()) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "No accessible indexed repositories found");
            return;
        }

        StringBuilder executedQuery = new StringBuilder("type:commit");
        if (!isWildcardQuery) {
            executedQuery.append(" AND (").append(query).append(")");
        }

        // Authors filter (OR logic), each author name as a phrase
        Query authorsFilter = null;
        if (!StringUtils.isEmpty(authors)) {
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            executedQuery.append(" AND (");
            String[] authorList = authors.split(",");
            for (int i = 0; i < authorList.length; i++) {
                String author = authorList[i].trim();
                builder.add(IndexSearch.phrase(IndexSearch.FIELD_AUTHOR, author), BooleanClause.Occur.SHOULD);
                if (i > 0) executedQuery.append(" OR ");
                executedQuery.append("author:\"").append(author).append("\"");
            }
            executedQuery.append(")");
            authorsFilter = builder.build();
        }

        // Each repository is searched on the explicit branch, or else on its
        // own default branch
        Query typeFilter = IndexSearch.type(SearchObjectType.commit.name());
        Query branchFilter = StringUtils.isEmpty(branch) ? null : IndexSearch.phrase(IndexSearch.FIELD_BRANCH, branch);
        List<IndexSearch.Target> targets = new ArrayList<>();
        for (RepositoryModel model : searchRepos) {
            List<Query> filters = new ArrayList<>();
            filters.add(typeFilter);
            if (authorsFilter != null) {
                filters.add(authorsFilter);
            }
            if (branchFilter != null) {
                filters.add(branchFilter);
            } else if (!StringUtils.isEmpty(model.HEAD)) {
                filters.add(IndexSearch.phrase(IndexSearch.FIELD_BRANCH, model.HEAD));
            }
            targets.add(new IndexSearch.Target(model.name, filters));
        }

        log.info("Commit search: user={}, query='{}', repos={}, branch='{}', offset={}",
                 user.username, executedQuery, searchRepos.size(), branch, offset);

        // Build response
        CommitSearchResponse searchResponse = new CommitSearchResponse();
        searchResponse.query = executedQuery.toString();
        searchResponse.commits = new ArrayList<>();

        // Fetch enough results to cover offset + limit
        try (IndexSearch.Result results = IndexSearch.search(targets, userQuery, offset + limit)) {
            searchResponse.totalCount = results.totalHits;

            for (int i = offset; i < results.hits.size(); i++) {
                IndexSearch.Hit hit = results.hits.get(i);
                Document doc = hit.document(COMMIT_FIELDS);

                CommitSearchResponse.CommitInfo commitInfo = new CommitSearchResponse.CommitInfo();
                commitInfo.repository = hit.repository;
                commitInfo.commit = doc.get(IndexSearch.FIELD_COMMIT);
                commitInfo.author = doc.get(IndexSearch.FIELD_AUTHOR);
                commitInfo.committer = doc.get(IndexSearch.FIELD_COMMITTER);
                commitInfo.date = formatDate(doc.get(IndexSearch.FIELD_DATE));
                commitInfo.message = doc.get(IndexSearch.FIELD_CONTENT);
                commitInfo.branch = doc.get(IndexSearch.FIELD_BRANCH);

                // Extract title (first line of message)
                String message = commitInfo.message != null ? commitInfo.message : doc.get(IndexSearch.FIELD_SUMMARY);
                if (message != null) {
                    int newlineIndex = message.indexOf('\n');
                    commitInfo.title = newlineIndex >= 0 ? message.substring(0, newlineIndex) : message;
                }

                searchResponse.commits.add(commitInfo);
            }
        }

        // Set limitHit based on whether more results exist
        searchResponse.limitHit = (offset + searchResponse.commits.size()) < searchResponse.totalCount;

        ResponseWriter.writeJson(response, searchResponse);
    }

    /**
     * Get the accessible, indexed repositories to search.
     */
    private List<RepositoryModel> getSearchRepositories(IGitblit gitblit, UserModel user, String reposParam) {
        Map<String, RepositoryModel> available = new HashMap<>();
        for (RepositoryModel model : gitblit.getRepositoryModels(user)) {
            if (model.hasCommits && !ArrayUtils.isEmpty(model.indexedBranches)) {
                available.put(model.name, model);
            }
        }

        // Filter to requested repositories
        List<RepositoryModel> result = new ArrayList<>();
        for (String repo : reposParam.split(",")) {
            RepositoryModel model = available.get(repo.trim());
            if (model != null) {
                result.add(model);
            }
        }
        return result;
    }

    /**
     * Gitblit stores the commit date as a minute-resolution DateTools string.
     */
    private String formatDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            Date date = DateTools.stringToDate(value);
            synchronized (dateFormat) {
                return dateFormat.format(date);
            }
        } catch (java.text.ParseException e) {
            return null;
        }
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

    /**
     * Check if a query consists only of wildcards and whitespace.
     * Such queries cause Lucene errors and should be rejected.
     */
    private boolean isWildcardOnlyQuery(String query) {
        String stripped = query.replaceAll("[\\s*?]+", "");
        return stripped.isEmpty();
    }
}
