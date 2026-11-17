/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.ReposRefreshResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;

/**
 * Handler for POST /api/.mcp-internal/repos/refresh
 * Adds to Gitblit's repository list every repository in its repositories
 * folder that a start would list and the list lacks.
 *
 * With git.cacheRepositoryList on, Gitblit scans the folder once, at start;
 * after that a repository joins the list only when its model is loaded on a
 * cache miss (RepositoryManager.getRepositoryModel), which this handler does
 * for each unlisted one. It clears no cache: every listing in the meantime is
 * the whole list, before or after one more repository.
 */
public class ReposRefreshHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(ReposRefreshHandler.class);

    // Two refreshes at once would load one unlisted repository's model on two
    // threads. ObjectCache.updateObject sets an entry's date before its
    // object, so RepositoryManager.updateLastChangeFields on the second thread
    // can find the size entry current and unbox its null object.
    private static final Object REFRESH_LOCK = new Object();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       IGitblit gitblit, UserModel user) throws IOException {

        ReposRefreshResponse result = new ReposRefreshResponse();
        result.added = new ArrayList<>();

        synchronized (REFRESH_LOCK) {
            for (String name : scanRepositoriesFolder(gitblit)) {
                if (gitblit.hasRepository(name)) {
                    continue;
                }
                RepositoryModel model = gitblit.getRepositoryModel(name);
                if (model == null) {
                    continue;
                }
                result.addedCount++;
                if (user.canView(model)) {
                    result.added.add(model.name);
                }
            }
        }

        if (result.addedCount > 0) {
            log.info("Listed {} repositories added since Gitblit started", result.addedCount);
        }

        ResponseWriter.writeJson(response, result);
    }

    /**
     * The scan RepositoryManager.getRepositoryList() makes at start, with the
     * same settings, sorted.
     */
    private static List<String> scanRepositoriesFolder(IGitblit gitblit) {
        IStoredSettings settings = gitblit.getSettings();
        List<String> names = JGitUtils.getRepositoryList(gitblit.getRepositoriesFolder(),
            settings.getBoolean(Keys.git.onlyAccessBareRepositories, false),
            settings.getBoolean(Keys.git.searchRepositoriesSubfolders, true),
            settings.getInteger(Keys.git.searchRecursionDepth, -1),
            settings.getStrings(Keys.git.searchExclusions));
        StringUtils.sortRepositorynames(names);
        return names;
    }
}
