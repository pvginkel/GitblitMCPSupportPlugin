/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.model;

import java.util.List;

/**
 * Response DTO for POST /repos/refresh.
 */
public class ReposRefreshResponse {
    public int addedCount;
    public List<String> added;
}
