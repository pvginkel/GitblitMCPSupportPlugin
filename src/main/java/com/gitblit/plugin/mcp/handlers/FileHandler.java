/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.handlers;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.gitblit.manager.IGitblit;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.model.FileContentResponse;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.utils.JGitUtils;
import com.gitblit.utils.StringUtils;

/**
 * Handler for GET /api/.mcp-internal/file
 * Reads file content from a repository.
 */
public class FileHandler implements RequestHandler {

    private static final int MAX_FILE_SIZE = 128 * 1024; // 128KB

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

        String path = request.getParameter("path");
        if (StringUtils.isEmpty(path)) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Missing required parameter: path");
            return;
        }

        // Parse optional parameters
        String revision = request.getParameter("revision");
        int startLine = parseIntParam(request, "startLine", 1);
        int endLine = parseIntParam(request, "endLine", Integer.MAX_VALUE);

        // Validate line parameters
        if (startLine < 1) startLine = 1;
        if (endLine < startLine) endLine = startLine;

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

            // Get raw bytes first to check size and detect binary
            byte[] rawContent = JGitUtils.getByteContent(repository, commit.getTree(), path, false);

            if (rawContent == null) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "File not found: " + path);
                return;
            }

            // Check size limit
            if (rawContent.length > MAX_FILE_SIZE) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "File exceeds maximum size of 128KB");
                return;
            }

            // Check for binary content
            if (isBinary(rawContent)) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Binary file cannot be displayed");
                return;
            }

            // Convert to string
            String content = new String(rawContent, "UTF-8");

            // Split into lines and apply line range
            String[] lines = content.split("\n", -1);

            StringBuilder result = new StringBuilder();
            int actualEndLine = Math.min(endLine, lines.length);

            for (int i = startLine - 1; i < actualEndLine; i++) {
                if (i >= 0 && i < lines.length) {
                    result.append((i + 1)).append(": ").append(lines[i]).append("\n");
                }
            }

            ResponseWriter.writeJson(response, new FileContentResponse(result.toString()));

        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * Check if content appears to be binary by looking for null bytes.
     */
    private boolean isBinary(byte[] content) {
        // Check first 8000 bytes for null bytes (common binary indicator)
        int checkLength = Math.min(content.length, 8000);
        for (int i = 0; i < checkLength; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
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
