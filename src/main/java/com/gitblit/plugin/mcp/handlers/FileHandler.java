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

            // Get raw bytes first to locate the line range and detect binary
            byte[] rawContent = JGitUtils.getByteContent(repository, commit.getTree(), path, false);

            if (rawContent == null) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                    "File not found: " + path);
                return;
            }

            // Locate the requested lines in the raw bytes: [rangeStart, rangeEnd),
            // without the newline ending the last one. A '\n' byte is never part
            // of a multi-byte UTF-8 sequence, so the range decodes on its own.
            int rangeStart = startLine == 1 ? 0 : -1;
            int rangeEnd = rawContent.length;
            int line = 1;
            for (int i = 0; i < rawContent.length; i++) {
                if (rawContent[i] == '\n') {
                    if (line == endLine) {
                        rangeEnd = i;
                        break;
                    }
                    line++;
                    if (line == startLine) {
                        rangeStart = i + 1;
                    }
                }
            }

            // Check size limit against the requested lines, not the whole file
            if (rangeStart >= 0 && rangeEnd - rangeStart > MAX_FILE_SIZE) {
                boolean wholeFile = startLine == 1 && rangeEnd == rawContent.length;
                ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    (wholeFile ? "File exceeds" : "Requested lines exceed")
                    + " maximum size of 128KB; read fewer lines with startLine/endLine");
                return;
            }

            // Check for binary content
            if (isBinary(rawContent)) {
                ResponseWriter.writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "Binary file cannot be displayed");
                return;
            }

            // Number the requested lines; a startLine past the end reads nothing
            StringBuilder result = new StringBuilder();
            if (rangeStart >= 0) {
                String content = new String(rawContent, rangeStart, rangeEnd - rangeStart, "UTF-8");
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    result.append(startLine + i).append(": ").append(lines[i]).append("\n");
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
