/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.util.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.plugin.mcp.util.LuceneServiceAccess;
import com.gitblit.service.LuceneService;

/**
 * Searches Gitblit's per-repository Lucene indexes directly instead of through
 * LuceneService.search(), which the search endpoints cannot use:
 *
 * - It highlights every hit, and Gitblit's highlighter needs lucene-join,
 *   which Gitblit does not ship. Any wildcard or prefix term then throws a
 *   NoClassDefFoundError that LuceneService does not catch.
 * - It takes one query for all repositories, so restricting each repository
 *   to its own default branch takes one OR clause per repository, matched
 *   against every document of every repository.
 *
 * Here each repository is searched with its own filter, and the top hits are
 * merged by score. Scores come from each repository's own term statistics.
 *
 * The documents carry Gitblit's fields; the names below mirror LuceneService.
 */
public final class IndexSearch {

    public static final String FIELD_TYPE = "type";
    public static final String FIELD_PATH = "path";
    public static final String FIELD_COMMIT = "commit";
    public static final String FIELD_BRANCH = "branch";
    public static final String FIELD_SUMMARY = "summary";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_AUTHOR = "author";
    public static final String FIELD_COMMITTER = "committer";
    public static final String FIELD_DATE = "date";

    private static final Logger log = LoggerFactory.getLogger(IndexSearch.class);

    /** The analyzer Gitblit indexes with. */
    private static final Analyzer ANALYZER = new StandardAnalyzer();

    private IndexSearch() {
    }

    public static Analyzer analyzer() {
        return ANALYZER;
    }

    /**
     * Parse a user query as Gitblit does: classic syntax, leading wildcards
     * allowed, unqualified terms searching file content and commit messages.
     * A query of only wildcards matches every document.
     */
    public static Query parse(String text) throws ParseException {
        if (text.replaceAll("[\\s*?]+", "").isEmpty()) {
            return new MatchAllDocsQuery();
        }
        QueryParser parser = new QueryParser(FIELD_CONTENT, ANALYZER);
        parser.setAllowLeadingWildcard(true);
        return parser.parse(text);
    }

    public static Query type(String type) {
        return new TermQuery(new Term(FIELD_TYPE, type));
    }

    /**
     * The analyzed phrase of a value in a tokenized field, e.g. a branch or
     * an author name, as a quoted query on that field would build it.
     */
    public static Query phrase(String field, String value) {
        Query query = new QueryBuilder(ANALYZER).createPhraseQuery(field, value);
        return query != null ? query : new MatchAllDocsQuery();
    }

    /**
     * One repository to search, and the filters its hits must also match.
     */
    public static final class Target {
        final String repository;
        final List<Query> filters;

        public Target(String repository, List<Query> filters) {
            this.repository = repository;
            this.filters = filters;
        }
    }

    public static final class Hit {
        public final String repository;
        public final float score;
        private final IndexSearcher searcher;
        private final int doc;

        Hit(String repository, float score, IndexSearcher searcher, int doc) {
            this.repository = repository;
            this.score = score;
            this.searcher = searcher;
            this.doc = doc;
        }

        /** The hit's stored fields; only while its Result is open. */
        public Document document(Set<String> fields) throws IOException {
            return searcher.doc(doc, fields);
        }
    }

    /**
     * The hits of a search. Holds the index readers they come from until
     * closed.
     */
    public static final class Result implements AutoCloseable {
        /** Matching documents across all repositories searched. */
        public int totalHits;
        /** The best hits, highest score first. */
        public final List<Hit> hits = new ArrayList<>();

        private final List<IndexSearcher> acquired = new ArrayList<>();

        @Override
        public void close() {
            for (IndexSearcher searcher : acquired) {
                release(searcher);
            }
            acquired.clear();
        }
    }

    /**
     * Search each target's index for query and its filters, and return the
     * best topN hits overall. A repository whose index cannot be opened or
     * searched is left out and logged.
     */
    public static Result search(List<Target> targets, Query query, int topN) {
        LuceneService lucene = LuceneServiceAccess.getLuceneService();
        Result result = new Result();

        // Per searched repository, its name, searcher and top hits, indexed
        // by the shardIndex TopDocs.merge gives each hit.
        List<String> repositories = new ArrayList<>();
        List<IndexSearcher> searchers = new ArrayList<>();
        List<TopDocs> topDocs = new ArrayList<>();

        try {
            for (Target target : targets) {
                IndexSearcher searcher = acquire(lucene, target.repository);
                if (searcher == null) {
                    continue;
                }
                result.acquired.add(searcher);

                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(query, BooleanClause.Occur.MUST);
                for (Query filter : target.filters) {
                    builder.add(filter, BooleanClause.Occur.FILTER);
                }

                TopScoreDocCollector collector = TopScoreDocCollector.create(topN);
                try {
                    searcher.search(builder.build(), collector);
                } catch (IOException | RuntimeException e) {
                    log.warn("Search of {} failed, leaving it out: {}", target.repository, e.toString());
                    continue;
                }
                repositories.add(target.repository);
                searchers.add(searcher);
                topDocs.add(collector.topDocs());
                result.totalHits += collector.getTotalHits();
            }

            if (!topDocs.isEmpty()) {
                TopDocs merged = TopDocs.merge(topN, topDocs.toArray(new TopDocs[0]));
                for (ScoreDoc scoreDoc : merged.scoreDocs) {
                    result.hits.add(new Hit(repositories.get(scoreDoc.shardIndex), scoreDoc.score,
                        searchers.get(scoreDoc.shardIndex), scoreDoc.doc));
                }
            }
            return result;
        } catch (IOException | RuntimeException e) {
            result.close();
            throw new IllegalStateException("Search failed: " + e, e);
        }
    }

    /**
     * Gitblit's searcher for a repository, its reader referenced so that
     * Gitblit indexing new commits cannot close it while we read it. Null
     * when the index cannot be opened.
     */
    private static IndexSearcher acquire(LuceneService lucene, String repository) {
        // Gitblit replaces the searcher after closing its reader, so a reader
        // closed under us is retried with the replacement.
        for (int attempt = 0; attempt < 3; attempt++) {
            IndexSearcher searcher;
            try {
                searcher = LuceneServiceAccess.getIndexSearcher(lucene, repository);
            } catch (Exception e) {
                log.warn("Cannot open the index of {}, leaving it out: {}", repository, e.toString());
                return null;
            }
            if (searcher.getIndexReader().tryIncRef()) {
                return searcher;
            }
        }
        log.warn("The index reader of {} kept closing, leaving it out", repository);
        return null;
    }

    private static void release(IndexSearcher searcher) {
        try {
            searcher.getIndexReader().decRef();
        } catch (Exception e) {
            log.warn("Cannot release an index reader: {}", e.toString());
        }
    }

    public static Set<String> fields(String... names) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, names);
        return set;
    }
}
