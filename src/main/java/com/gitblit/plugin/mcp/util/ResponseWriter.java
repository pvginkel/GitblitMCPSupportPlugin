/*
 * Gitblit MCP Support Plugin
 */
package com.gitblit.plugin.mcp.util;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletResponse;

import com.gitblit.plugin.mcp.model.ErrorResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Utility class for writing JSON responses.
 */
public class ResponseWriter {

    private static final Gson gson = new GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .create();

    /**
     * Write a successful JSON response.
     */
    public static void writeJson(HttpServletResponse response, Object data) throws IOException {
        writeJson(response, HttpServletResponse.SC_OK, data);
    }

    /**
     * Write a JSON response with the given status.
     */
    public static void writeJson(HttpServletResponse response, int status, Object data) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(status);

        PrintWriter writer = response.getWriter();
        writer.write(gson.toJson(data));
        writer.flush();
    }

    /**
     * Write an error response.
     */
    public static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(status);

        ErrorResponse error = new ErrorResponse(message, status);

        PrintWriter writer = response.getWriter();
        writer.write(gson.toJson(error));
        writer.flush();
    }
}
