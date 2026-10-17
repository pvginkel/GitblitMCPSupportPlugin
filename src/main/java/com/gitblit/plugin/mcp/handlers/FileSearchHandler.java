/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
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
import com.gitblit.plugin.mcp.util.DefaultBranch;
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
    /** The most chunks a file's result carries. */
    private static final int MAX_CHUNKS = 10;
    /** The most hits a search checks against a path pattern. */
    private static final int MAX_SCAN = 10000;

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
        // own default branch, which RepositoryModel.HEAD may name wrongly
        Query typeFilter = IndexSearch.type(SearchObjectType.blob.name());
        Query branchFilter = StringUtils.isEmpty(branch) ? null : IndexSearch.phrase(IndexSearch.FIELD_BRANCH, branch);
        List<IndexSearch.Target> targets = new ArrayList<>();
        for (RepositoryModel model : searchRepos) {
            List<Query> filters = new ArrayList<>();
            filters.add(typeFilter);
            if (branchFilter != null) {
                filters.add(branchFilter);
            } else {
                String defaultBranch = DefaultBranch.of(gitblit, model);
                if (!StringUtils.isEmpty(defaultBranch)) {
                    filters.add(IndexSearch.phrase(IndexSearch.FIELD_BRANCH, defaultBranch));
                }
            }
            targets.add(new IndexSearch.Target(model.name, filters));
        }

        String executedQuery = isWildcardQuery ? "type:blob" : "type:blob AND (" + query + ")";
        log.info("File search: user={}, query='{}', repos={}, branch='{}', pathPattern='{}', offset={}",
                 user.username, executedQuery, searchRepos.size(), branch, pathPattern, offset);

        // Build response
        FileSearchResponse searchResponse = new FileSearchResponse();
        searchResponse.query = executedQuery;
        searchResponse.results = new ArrayList<>();

        // Track filtered count when using pathPattern
        int filteredCount = 0;
        int skipped = 0;

        MatchLocator locator = isWildcardQuery ? null : new MatchLocator(userQuery, IndexSearch.FIELD_CONTENT);

        // Fetch enough hits to cover offset + limit. The path pattern can
        // only be checked hit by hit, so when filtering, search again for
        // more hits until offset + limit pass it, the hits run out, or
        // MAX_SCAN hits have been checked. Each search checks only the hits
        // the one before it did not reach.
        int wanted = offset + limit;
        int fetchCount = Math.min(pathRegex != null ? wanted * 4 : wanted, MAX_SCAN);
        int scanned = 0;
        int totalHits;

        while (true) {
            try (IndexSearch.Result results = IndexSearch.search(targets, userQuery, fetchCount)) {
                totalHits = results.totalHits;
                for (int i = scanned; i < results.hits.size(); i++) {
                    IndexSearch.Hit hit = results.hits.get(i);
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

                    // Context chunks from the indexed content (skip for wildcard
                    // queries to reduce response size)
                    if (locator != null) {
                        try {
                            String content = hit.document(CONTENT_FIELD).get(IndexSearch.FIELD_CONTENT);
                            if (content != null) {
                                fileResult.chunks.addAll(buildChunks(content, locator, contextLines));
                            }
                        } catch (IOException e) {
                            log.warn("Failed to build context for {}:{}: {}", hit.repository, path, e.getMessage());
                        }
                    }

                    searchResponse.results.add(fileResult);
                }
                scanned = Math.max(scanned, results.hits.size());
            }

            if (filteredCount >= wanted || scanned >= totalHits || fetchCount >= MAX_SCAN) {
                break;
            }
            fetchCount = Math.min(fetchCount * 4, MAX_SCAN);
        }

        // Set totalCount and limitHit based on filtering
        if (pathRegex != null) {
            // When filtering, use the filtered count: the matches among the
            // hits checked, a lower bound when hits were left unchecked
            searchResponse.totalCount = filteredCount;
            searchResponse.limitHit = scanned < totalHits
                || (offset + searchResponse.results.size()) < filteredCount;
        } else {
            // Without filtering, use Lucene's total
            searchResponse.totalCount = totalHits;
            searchResponse.limitHit = (offset + searchResponse.results.size()) < searchResponse.totalCount;
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
     * The chunks of context around the lines that match the query, in line
     * order, or one around the first line when no line does (e.g. a hit on
     * the path).
     *
     * Each chunk has the window of contextLines around its first match line.
     * A match line the chunk before already shows adds no chunk of its own,
     * and a window that would overlap the chunk before slides past its end,
     * so chunks never share a line and never grow past one window. Over
     * MAX_CHUNKS, the chunks holding the lines that match the most parts of
     * the query are kept, the earliest of those on a tie.
     */
    private List<FileSearchResponse.Chunk> buildChunks(String content, MatchLocator locator, int contextLines)
            throws IOException {
        String[] lines = content.split("\n", -1);

        SortedMap<Integer, Integer> matches =
            locator.findLines(content, IndexSearch.analyzer(), IndexSearch.FIELD_CONTENT);
        if (matches.isEmpty()) {
            matches = new TreeMap<>(Collections.singletonMap(0, 0));
        }

        // Calculate context ranges
        int halfContext = contextLines / 2;
        List<Window> windows = new ArrayList<>();
        int shownEnd = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> match : matches.entrySet()) {
            int matchLine = match.getKey();
            if (matchLine < shownEnd) {
                Window last = windows.get(windows.size() - 1);
                last.score = Math.max(last.score, match.getValue());
                continue;
            }
            int startLine = matchLine - halfContext;
            int endLine = matchLine + halfContext + 1;
            if (startLine < shownEnd) {
                endLine += shownEnd - startLine;
                startLine = shownEnd;
            }
            Window window = new Window(Math.max(0, startLine), Math.min(lines.length, endLine), match.getValue());
            windows.add(window);
            shownEnd = window.end;
        }

        if (windows.size() > MAX_CHUNKS) {
            List<Window> best = new ArrayList<>(windows);
            best.sort((a, b) -> a.score != b.score
                ? Integer.compare(b.score, a.score) : Integer.compare(a.start, b.start));
            windows = new ArrayList<>(best.subList(0, MAX_CHUNKS));
            windows.sort((a, b) -> Integer.compare(a.start, b.start));
        }

        // Build chunk content with line numbers
        List<FileSearchResponse.Chunk> chunks = new ArrayList<>();
        for (Window window : windows) {
            StringBuilder chunkContent = new StringBuilder();
            for (int i = window.start; i < window.end; i++) {
                chunkContent.append(i + 1).append(": ").append(lines[i]).append("\n");
            }
            chunks.add(new FileSearchResponse.Chunk(window.start + 1, window.end, chunkContent.toString()));
        }
        return chunks;
    }

    /**
     * The 0-based lines of a chunk, end exclusive, and the best score of the
     * match lines it shows.
     */
    private static final class Window {
        final int start;
        final int end;
        int score;

        Window(int start, int end, int score) {
            this.start = start;
            this.end = end;
            this.score = score;
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
