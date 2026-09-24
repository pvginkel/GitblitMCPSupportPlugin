/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.model;

import java.util.List;

/**
 * Response DTO for /health endpoint.
 */
public class HealthResponse {
    public boolean healthy;
    public int openIndexes;
    public int deadIndexCount;
    public List<DeadIndex> deadIndexes;

    public static class DeadIndex {
        public String repository;
        public String cause;

        public DeadIndex(String repository, String cause) {
            this.repository = repository;
            this.cause = cause;
        }
    }
}
