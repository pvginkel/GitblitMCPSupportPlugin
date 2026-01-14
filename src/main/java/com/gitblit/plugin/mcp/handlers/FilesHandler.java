/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.PathModel;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.FileListResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/.mcp-internal/files
 * Lists files and directories at a path within a repository.
 */
public class FilesHandler implements RequestHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       IGitblit gitblit, UserModel user) throws IOException {

        // Parse required parameters
        String repoName = request.getParameter("repo");
        if (StringUtils.isEmpty(repoName)) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Missing required parameter: repo");
            return;
        }

        // Parse optional parameters
        String path = request.getParameter("path");
        if (StringUtils.isEmpty(path) || path.equals("/")) {
            path = "";
        }
        // Normalize path - remove trailing slash
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String revision = request.getParameter("revision");

        // Check repository access
        RepositoryModel repoModel = gitblit.getRepositoryModel(repoName);
        if (repoModel == null || !user.canView(repoModel)) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                "Repository not found: " + repoName);
            return;
        }

        Repository repository = null;
        try {
            repository = gitblit.getRepository(repoName);
            if (repository == null) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "Repository not found: " + repoName);
                return;
            }

            // Resolve revision to commit
            RevCommit commit = JGitUtils.getCommit(repository, revision);
            if (commit == null) {
                if (!StringUtils.isEmpty(revision)) {
                    ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                        "Cannot resolve revision: " + revision);
                } else {
                    ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                        "Repository has no commits");
                }
                return;
            }

            // Get files at path
            List<PathModel> pathModels = JGitUtils.getFilesInPath(repository, path, commit);

            if (pathModels == null) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "Path not found: " + path);
                return;
            }

            // If the result is empty and path is not root, verify the path exists
            // JGitUtils.getFilesInPath returns empty list for both empty directories
            // and nonexistent paths, so we need to check if the path actually exists
            if (pathModels.isEmpty() && !path.isEmpty()) {
                if (!pathExistsInTree(repository, commit, path)) {
                    ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                        "Path not found: " + path);
                    return;
                }
            }

            // Separate directories and files, then sort each group
            List<FileListResponse.FileInfo> directories = new ArrayList<>();
            List<FileListResponse.FileInfo> files = new ArrayList<>();

            for (PathModel pm : pathModels) {
                boolean isDir = pm.isTree();
                String name = pm.name;

                if (isDir) {
                    directories.add(new FileListResponse.FileInfo(name + "/", true, null));
                } else {
                    files.add(new FileListResponse.FileInfo(name, false, pm.size));
                }
            }

            // Sort alphabetically
            Comparator<FileListResponse.FileInfo> comparator = new Comparator<FileListResponse.FileInfo>() {
                @Override
                public int compare(FileListResponse.FileInfo a, FileListResponse.FileInfo b) {
                    return a.path.compareToIgnoreCase(b.path);
                }
            };
            Collections.sort(directories, comparator);
            Collections.sort(files, comparator);

            // Build response - directories first, then files
            FileListResponse result = new FileListResponse();
            result.files = new ArrayList<>();
            result.files.addAll(directories);
            result.files.addAll(files);

            ResponseWriter.writeJson(response, result);

        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * Check if a path exists in the repository tree as a directory.
     */
    private boolean pathExistsInTree(Repository repository, RevCommit commit, String path) {
        TreeWalk treeWalk = null;
        try {
            treeWalk = TreeWalk.forPath(repository, path, commit.getTree());
            if (treeWalk == null) {
                return false;
            }
            // Check if it's a directory (tree)
            return treeWalk.getFileMode(0) == FileMode.TREE;
        } catch (IOException e) {
            return false;
        } finally {
            if (treeWalk != null) {
                treeWalk.close();
            }
        }
    }
}
