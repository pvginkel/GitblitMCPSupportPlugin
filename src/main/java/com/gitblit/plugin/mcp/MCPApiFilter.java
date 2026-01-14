/*
 * Gitblit MCP Support Plugin
 * HTTP filter that routes MCP API requests to handlers
 */
package com.gitblit.plugin.mcp;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.fortsoft.pf4j.Extension;

import com.gitblit.extensions.HttpRequestFilter;
import com.gitblit.manager.IAuthenticationManager;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.mcp.handlers.CommitSearchHandler;
import com.gitblit.plugin.mcp.handlers.FileHandler;
import com.gitblit.plugin.mcp.handlers.FileSearchHandler;
import com.gitblit.plugin.mcp.handlers.FilesHandler;
import com.gitblit.plugin.mcp.handlers.FindFilesHandler;
import com.gitblit.plugin.mcp.handlers.ReposHandler;
import com.gitblit.plugin.mcp.handlers.RequestHandler;
import com.gitblit.plugin.mcp.util.ResponseWriter;
import com.gitblit.servlet.GitblitContext;

@Extension
public class MCPApiFilter extends HttpRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MCPApiFilter.class);
    private static final String API_PATH = "/api/.mcp-internal";

    // Handlers
    private final RequestHandler reposHandler;
    private final RequestHandler filesHandler;
    private final RequestHandler fileHandler;
    private final RequestHandler fileSearchHandler;
    private final RequestHandler commitSearchHandler;
    private final RequestHandler findFilesHandler;

    public MCPApiFilter() {
        this.reposHandler = new ReposHandler();
        this.filesHandler = new FilesHandler();
        this.fileHandler = new FileHandler();
        this.fileSearchHandler = new FileSearchHandler();
        this.commitSearchHandler = new CommitSearchHandler();
        this.findFilesHandler = new FindFilesHandler();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();

        // Only handle requests to our API endpoint
        if (!uri.startsWith(API_PATH)) {
            chain.doFilter(request, response);
            return;
        }

        // Set CORS headers for API access
        httpResponse.setHeader("Access-Control-Allow-Origin", "*");
        httpResponse.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        httpResponse.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");

        // Handle preflight requests
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            httpResponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // Only allow GET requests
        if (!"GET".equalsIgnoreCase(httpRequest.getMethod())) {
            ResponseWriter.writeError(httpResponse, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                "Method not allowed. Only GET requests are supported.");
            return;
        }

        try {
            handleRequest(httpRequest, httpResponse);
        } catch (Exception e) {
            log.error("Error processing MCP API request: " + uri, e);
            ResponseWriter.writeError(httpResponse, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Internal server error: " + e.getMessage());
        }
    }

    private void handleRequest(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        // Get managers
        IGitblit gitblit = GitblitContext.getManager(IGitblit.class);
        IAuthenticationManager authManager = GitblitContext.getManager(IAuthenticationManager.class);

        // Authenticate user (supports Basic auth, API tokens, etc.)
        UserModel user = authManager.authenticate(request);
        if (user == null) {
            user = UserModel.ANONYMOUS;
        }

        // Extract endpoint path (after /api/.mcp-internal)
        String uri = request.getRequestURI();
        String endpoint = uri.substring(API_PATH.length());

        // Remove leading slash if present
        if (endpoint.startsWith("/")) {
            endpoint = endpoint.substring(1);
        }

        // Route to appropriate handler
        RequestHandler handler = getHandler(endpoint);

        if (handler == null) {
            ResponseWriter.writeError(response, HttpServletResponse.SC_NOT_FOUND,
                "Unknown endpoint: " + endpoint);
            return;
        }

        log.debug("MCP API: user={}, endpoint={}", user.username, endpoint);
        handler.handle(request, response, gitblit, user);
    }

    private RequestHandler getHandler(String endpoint) {
        // Handle empty endpoint
        if (endpoint.isEmpty()) {
            return null;
        }

        // Route based on endpoint
        switch (endpoint) {
            case "repos":
                return reposHandler;
            case "files":
                return filesHandler;
            case "file":
                return fileHandler;
            case "find":
                return findFilesHandler;
            case "search/files":
                return fileSearchHandler;
            case "search/commits":
                return commitSearchHandler;
            default:
                return null;
        }
    }
}
