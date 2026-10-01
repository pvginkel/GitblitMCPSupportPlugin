/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.lucene.index.IndexWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.HealthResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.plugin.mcp.util.LuceneServiceAccess;
import com.gitblit.service.LuceneService;

/**
 * Handler for GET /api/.mcp-internal/health
 * Finds Lucene index writers that have closed themselves and drops them, so
 * the next search or index run reopens them.
 *
 * Gitblit caches one IndexWriter per repository and never replaces a closed
 * one. Once a writer dies (e.g. its write.lock fails validation), every
 * search spanning that repository logs an exception and returns no hits with
 * a 200, so a search cannot detect it. The writers sit in LuceneService's
 * private map, read through LuceneServiceAccess.
 */
public class HealthHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       IGitblit gitblit, UserModel user) throws IOException {

        LuceneService lucene = LuceneServiceAccess.getLuceneService();
        Map<String, IndexWriter> writers = LuceneServiceAccess.getWriters(lucene);

        HealthResponse result = new HealthResponse();
        result.deadIndexes = new ArrayList<>();

        for (Map.Entry<String, IndexWriter> entry : writers.entrySet()) {
            String repository = entry.getKey();
            IndexWriter writer = entry.getValue();

            if (writer.isOpen()) {
                result.openIndexes++;
                continue;
            }

            String cause = describe(writer.getTragicException());
            log.warn("Lucene index writer for {} is closed ({}); dropping it so it reopens",
                repository, cause);

            // close(repository) drops the writer and the searcher opened on
            // it. Only LuceneService.close() removes writers, under the same
            // lock, so the entry still being ours means it is still dead.
            synchronized (lucene) {
                if (writers.get(repository) == writer) {
                    lucene.close(repository);
                }
            }

            result.deadIndexCount++;
            RepositoryModel model = gitblit.getRepositoryModel(repository);
            if (model != null && user.canView(model)) {
                result.deadIndexes.add(new HealthResponse.DeadIndex(repository, cause));
            }
        }

        result.healthy = result.deadIndexCount == 0;

        ResponseWriter.writeJson(response,
            result.healthy ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            result);
    }

    private static String describe(Throwable tragedy) {
        if (tragedy == null) {
            return "closed without a tragic exception";
        }
        return tragedy.getClass().getSimpleName() + ": " + tragedy.getMessage();
    }
}
