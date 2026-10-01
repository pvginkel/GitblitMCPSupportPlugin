/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants.SearchObjectType;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.FileSearchResponse;
import com.gitblit.plugin.mcp.search.IndexSearch;
import com.gitblit.plugin.mcp.search.MatchLocator;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/.mcp-internal/search/files
 * Searches file contents using Lucene index.
 */
public class FileSearchHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(FileSearchHandler.class);

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_CONTEXT_LINES = 10;
    private static final int MAX_CONTEXT_LINES = 200;

    private static final Set<String> HIT_FIELDS = IndexSearch.fields(
        IndexSearch.FIELD_PATH, IndexSearch.FIELD_BRANCH, IndexSearch.FIELD_COMMIT);
    private static final Set<String> CONTENT_FIELD = IndexSearch.fields(IndexSearch.FIELD_CONTENT);

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

        // Parse optional parameters
        String reposParam = request.getParameter("repos");
        String pathPattern = request.getParameter("pathPattern");
        String branch = request.getParameter("branch");
        int contextLines = parseIntParam(request, "contextLines", DEFAULT_CONTEXT_LINES);
        if (contextLines > MAX_CONTEXT_LINES) contextLines = MAX_CONTEXT_LINES;
        if (contextLines < 1) contextLines = DEFAULT_CONTEXT_LINES;

        // Parse pagination parameters (support 'count' as deprecated alias for 'limit')
        int limit = parseIntParam(request, "limit", -1);
        if (limit < 0) {
            limit = parseIntParam(request, "count", DEFAULT_LIMIT);  // Backward compatibility
        }
        int offset = parseIntParam(request, "offset", 0);

        // Check if this is a wildcard-only query (e.g., "*")
        boolean isWildcardQuery = isWildcardOnlyQuery(query);

        // Wildcard queries require at least one filter to prevent unbounded results
        if (isWildcardQuery) {
            boolean hasFilter = !StringUtils.isEmpty(reposParam) ||
                               !StringUtils.isEmpty(pathPattern) ||
                               !StringUtils.isEmpty(branch);
            if (!hasFilter) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Wildcard queries require at least one filter: repos, pathPattern, or branch");
                return;
            }
        }

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

        // Compile path pattern for post-filtering. The path field is
        // tokenized, so a glob cannot be expressed as a Lucene query.
        Pattern pathRegex = null;
        if (!StringUtils.isEmpty(pathPattern)) {
            pathRegex = globToRegex(pathPattern);
        }

        // Determine repositories to search
        List<RepositoryModel> searchRepos = getSearchRepositories(gitblit, user, reposParam);
        if (searchRepos.isEmpty()) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "No accessible indexed repositories found");
            return;
        }

        // Each repository is searched on the explicit branch, or else on its
        // own default branch
        Query typeFilter = IndexSearch.type(SearchObjectType.blob.name());
        Query branchFilter = StringUtils.isEmpty(branch) ? null : IndexSearch.phrase(IndexSearch.FIELD_BRANCH, branch);
        List<IndexSearch.Target> targets = new ArrayList<>();
        for (RepositoryModel model : searchRepos) {
            List<Query> filters = new ArrayList<>();
            filters.add(typeFilter);
            if (branchFilter != null) {
                filters.add(branchFilter);
            } else if (!StringUtils.isEmpty(model.HEAD)) {
                filters.add(IndexSearch.phrase(IndexSearch.FIELD_BRANCH, model.HEAD));
            }
            targets.add(new IndexSearch.Target(model.name, filters));
        }

        String executedQuery = isWildcardQuery ? "type:blob" : "type:blob AND (" + query + ")";
        log.info("File search: user={}, query='{}', repos={}, branch='{}', pathPattern='{}', offset={}",
                 user.username, executedQuery, searchRepos.size(), branch, pathPattern, offset);

        // Fetch enough results to cover offset + limit, plus extra when filtering
        int fetchCount = offset + limit;
        if (pathRegex != null) fetchCount = fetchCount * 4;  // Fetch extra when filtering
        if (fetchCount > MAX_LIMIT * 4) fetchCount = MAX_LIMIT * 4;

        // Build response
        FileSearchResponse searchResponse = new FileSearchResponse();
        searchResponse.query = executedQuery;
        searchResponse.results = new ArrayList<>();

        // Track filtered count when using pathPattern
        int filteredCount = 0;
        int skipped = 0;

        MatchLocator locator = isWildcardQuery ? null : new MatchLocator(userQuery, IndexSearch.FIELD_CONTENT);

        try (IndexSearch.Result results = IndexSearch.search(targets, userQuery, fetchCount)) {
            for (IndexSearch.Hit hit : results.hits) {
                Document doc = hit.document(HIT_FIELDS);
                String path = doc.get(IndexSearch.FIELD_PATH);

                // Apply path pattern filter
                if (pathRegex != null && (path == null || !pathRegex.matcher(path).matches())) {
                    continue;
                }

                filteredCount++;

                // Skip results before offset
                if (skipped < offset) {
                    skipped++;
                    continue;
                }

                // Stop adding results if we have enough
                if (searchResponse.results.size() >= limit) {
                    continue;  // Keep counting filtered results for totalCount
                }

                FileSearchResponse.FileSearchResult fileResult = new FileSearchResponse.FileSearchResult();
                fileResult.repository = hit.repository;
                fileResult.path = path;
                fileResult.branch = doc.get(IndexSearch.FIELD_BRANCH);
                fileResult.commitId = doc.get(IndexSearch.FIELD_COMMIT);
                fileResult.chunks = new ArrayList<>();

                // Context chunk from the indexed content (skip for wildcard
                // queries to reduce response size)
                if (locator != null) {
                    try {
                        String content = hit.document(CONTENT_FIELD).get(IndexSearch.FIELD_CONTENT);
                        if (content != null) {
                            fileResult.chunks.add(buildChunk(content, locator, contextLines));
                        }
                    } catch (IOException e) {
                        log.warn("Failed to build context for {}:{}: {}", hit.repository, path, e.getMessage());
                    }
                }

                searchResponse.results.add(fileResult);
            }

            // Set totalCount and limitHit based on filtering
            if (pathRegex != null) {
                // When filtering, use the filtered count
                searchResponse.totalCount = filteredCount;
                searchResponse.limitHit = (offset + searchResponse.results.size()) < filteredCount;
            } else {
                // Without filtering, use Lucene's total
                searchResponse.totalCount = results.totalHits;
                searchResponse.limitHit = (offset + searchResponse.results.size()) < searchResponse.totalCount;
            }
        }

        ResponseWriter.writeJson(response, searchResponse);
    }

    /**
     * Get the accessible, indexed repositories to search.
     */
    private List<RepositoryModel> getSearchRepositories(IGitblit gitblit, UserModel user, String reposParam) {
        Map<String, RepositoryModel> available = new LinkedHashMap<>();
        for (RepositoryModel model : gitblit.getRepositoryModels(user)) {
            if (model.hasCommits && !ArrayUtils.isEmpty(model.indexedBranches)) {
                available.put(model.name, model);
            }
        }

        if (StringUtils.isEmpty(reposParam)) {
            return new ArrayList<>(available.values());
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
     * A chunk of context around the line that best matches the query, or
     * around the first line when no line does (e.g. a hit on the path).
     */
    private FileSearchResponse.Chunk buildChunk(String content, MatchLocator locator, int contextLines)
            throws IOException {
        String[] lines = content.split("\n", -1);

        int matchLine = Math.max(0, locator.findLine(content, IndexSearch.analyzer(), IndexSearch.FIELD_CONTENT));

        // Calculate context range
        int halfContext = contextLines / 2;
        int startLine = Math.max(0, matchLine - halfContext);
        int endLine = Math.min(lines.length, matchLine + halfContext + 1);

        // Build chunk content with line numbers
        StringBuilder chunkContent = new StringBuilder();
        for (int i = startLine; i < endLine; i++) {
            chunkContent.append(i + 1).append(": ").append(lines[i]).append("\n");
        }

        return new FileSearchResponse.Chunk(startLine + 1, endLine, chunkContent.toString());
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
     * Convert a glob pattern to a regex Pattern.
     * Supports * (any chars) and ? (single char) wildcards.
     * A pattern without a slash matches the file name at any depth.
     */
    private Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        if (glob.indexOf('/') < 0) {
            regex.append("(?:.*/)?");
        }
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '.':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '\\':
                case '^':
                case '$':
                case '|':
                case '+':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
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
