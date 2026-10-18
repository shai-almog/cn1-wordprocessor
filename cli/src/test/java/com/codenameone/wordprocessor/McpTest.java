package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class McpTest {
  private McpServer server(Path root) throws Exception {
    var s = new McpServer(root);
    s.handle(
        Json.object(
            "jsonrpc",
            "2.0",
            "id",
            1,
            "method",
            "initialize",
            "params",
            Json.object("protocolVersion", "2025-06-18")));
    s.handle(Json.object("jsonrpc", "2.0", "method", "notifications/initialized"));
    return s;
  }

  private Map call(McpServer s, String tool, Map<String, Object> args) {
    return (Map)
        s.handle(
                Json.object(
                    "jsonrpc",
                    "2.0",
                    "id",
                    2,
                    "method",
                    "tools/call",
                    "params",
                    Json.object("name", tool, "arguments", args)))
            .get("result");
  }

  @Test
  public void protocolLifecycleAndDocumentRoundTrip() throws Exception {
    Path dir = Files.createTempDirectory("wp-mcp-");
    var s = server(dir);
    assertEquals(
        false, call(s, "text_insert", Json.object("text", "Hello from MCP")).get("isError"));
    assertEquals(false, call(s, "save_document", Json.object("path", "out.docx")).get("isError"));
    assertEquals(
        "Hello from MCP", DocumentFiles.read(dir.resolve("out.docx")).document.plainText());
    assertEquals(true, call(s, "save_document", Json.object("path", "out.docx")).get("isError"));
  }

  @Test
  public void traversalAndSymlinkEscapeRejected() throws Exception {
    Path dir = Files.createTempDirectory("wp-root-"),
        outside = Files.createTempDirectory("wp-outside-");
    Files.createSymbolicLink(dir.resolve("link"), outside);
    var s = server(dir);
    assertEquals(
        true, call(s, "save_document", Json.object("path", "../escape.txt")).get("isError"));
    assertEquals(
        true, call(s, "save_document", Json.object("path", "link/escape.txt")).get("isError"));
    assertFalse(Files.exists(outside.resolve("escape.txt")));
  }

  @Test
  public void stdioHasOnlyJsonResponses() throws Exception {
    Path dir = Files.createTempDirectory("wp-stdio-");
    var out = new ByteArrayOutputStream();
    new McpServer(dir)
        .serve(
            new ByteArrayInputStream(
                ("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                        """)
                    .getBytes("UTF-8")),
            out);
    var replies = out.toString("UTF-8").split("\n");
    assertEquals(2, replies.length);
    assertEquals("2.0", Json.parse(replies[0]).get("jsonrpc"));
    assertTrue(replies[1].contains("preview_macro"));
  }
}
