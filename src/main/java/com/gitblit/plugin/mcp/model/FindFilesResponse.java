/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.model;

import java.util.List;

/**
 * Response DTO for /find endpoint.
 */
public class FindFilesResponse {
    public String pattern;
    public int totalCount;
    public boolean limitHit;
    public List<FindFilesResult> results;

    public static class FindFilesResult {
        public String repository;
        public String revision;
        public List<String> files;

        public FindFilesResult(String repository, String revision, List<String> files) {
            this.repository = repository;
            this.revision = revision;
            this.files = files;
        }
    }
}
