package com.codenameone.wordprocessor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** MCP 2025-06-18 stdio server. Single session; explicit filesystem root; no network listener. */
public final class McpServer {
  private final Path root;
  private final Commands commands = DocumentFiles.commands();
  private Session session = new Session(Document.empty());
  private Docx.Opened source;
  private final Map<String, Macros.Preview> previews = new LinkedHashMap<>();
  private long nextPreview;
  private boolean initialized, ready;

  public McpServer(Path root) throws IOException {
    this.root = root.toRealPath();
    if (!Files.isDirectory(this.root)) throw new IOException("MCP root is not a directory");
  }

  public void serve(InputStream in, OutputStream out) throws IOException {
    var reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
    var writer = new OutputStreamWriter(out, "UTF-8");
    while (true) {
      var line = readLine(reader);
      if (line == null) break;
      Map<String, Object> reply;
      try {
        reply = handle(Json.parse(line));
      } catch (Exception e) {
        reply = error(null, -32700, "Invalid JSON request");
      }
      if (reply != null) {
        writer.write(com.codename1.io.JSONParser.toJson(reply));
        writer.write('\n');
        writer.flush();
      }
    }
  }

  private String readLine(Reader reader) throws IOException {
    var b = new StringBuilder();
    int c;
    while ((c = reader.read()) != -1) {
      if (c == '\n') return b.toString();
      if (b.length() >= 8 * 1024 * 1024) throw new IOException("MCP request exceeds size limit");
      b.append((char) c);
    }
    return b.length() == 0 ? null : b.toString();
  }

  public Map<String, Object> handle(Map<String, Object> request) {
    var id = request.get("id");
    String method = request.get("method") instanceof String ? (String) request.get("method") : "";
    if (!"2.0".equals(request.get("jsonrpc")) || method.isEmpty())
      return error(id, -32600, "Invalid request");
    if (!request.containsKey("id")) {
      if ("notifications/initialized".equals(method) && initialized) ready = true;
      return null;
    }
    Map<String, Object> params =
        request.get("params") instanceof Map
            ? (Map<String, Object>) request.get("params")
            : Json.object();
    if ("initialize".equals(method)) {
      if (initialized) return error(id, -32600, "Already initialized");
      initialized = true;
      return result(
          id,
          Json.object(
              "protocolVersion",
              "2025-06-18",
              "capabilities",
              Json.object("tools", Json.object("listChanged", false)),
              "serverInfo",
              Json.object("name", "cn1-wordprocessor", "version", "0.1.0")));
    }
    if ("ping".equals(method)) return result(id, Json.object());
    if (!ready) return error(id, -32002, "Initialize the session first");
    if ("tools/list".equals(method)) return result(id, Json.object("tools", tools()));
    if (!"tools/call".equals(method)) return error(id, -32601, "Method not found");
    if (!(params.get("name") instanceof String)
        || params.containsKey("arguments") && !(params.get("arguments") instanceof Map))
      return error(id, -32602, "Invalid tool parameters");
    var tool = (String) params.get("name");
    Map<String, Object> args =
        params.get("arguments") instanceof Map
            ? (Map<String, Object>) params.get("arguments")
            : Json.object();
    try {
      var value = call(tool, args);
      return result(
          id,
          Json.object(
              "content",
              Arrays.asList(Json.object("type", "text", "text", com.codename1.io.JSONParser.toJson(value))),
              "isError",
              false));
    } catch (Exception ex) {
      return result(
          id,
          Json.object(
              "content",
              Arrays.asList(
                  Json.object(
                      "type",
                      "text",
                      "text",
                      ex.getMessage() == null ? "Tool failed" : ex.getMessage())),
              "isError",
              true));
    }
  }

  private Object call(String name, Map<String, Object> args) throws Exception {
    if ("open_document".equals(name)) {
      var path = path(string(args, "path"), true);
      DocumentFiles.Opened opened = DocumentFiles.read(path);
      session = new Session(opened.document);
      source = opened.source;
      previews.clear();
      return Json.object("document", Commands.inspect(session), "warnings", opened.warnings);
    }
    if ("save_document".equals(name)) {
      var path = path(string(args, "path"), false);
      byte[] bytes =
          DocumentFiles.write(
              session.document(),
              source,
              DocumentFiles.extension(path),
              Boolean.TRUE.equals(args.get("allowConversion")));
      Cli.save(path, bytes);
      return Json.object("saved", root.relativize(path).toString());
    }
    if ("preview_macro".equals(name)) {
      var macro = args.get("macro");
      if (!(macro instanceof Map)) throw new IllegalArgumentException("macro must be an object");
      var preview =
          new Macros(commands)
              .preview(
                  session,
                  (Map<String, Object>) macro,
                  Collections.<String, String>emptyMap(),
                  null);
      if (previews.size() >= 16) previews.remove(previews.keySet().iterator().next());
      String key = Long.toString(++nextPreview);
      previews.put(key, preview);
      return Json.object(
          "preview",
          key,
          "baseRevision",
          preview.baseRevision,
          "before",
          preview.before,
          "after",
          preview.after,
          "changes",
          preview.changes);
    }
    if ("apply_preview".equals(name)) {
      var key = string(args, "preview");
      var preview = previews.get(key);
      if (preview == null) throw new IllegalArgumentException("Unknown preview");
      preview.apply(session);
      previews.remove(key);
      return Commands.inspect(session);
    }
    return commands.execute(session, name.replace('_', '.'), args);
  }

  private String string(Map<String, Object> args, String key) {
    var v = args.get(key);
    if (!(v instanceof String))
      throw new IllegalArgumentException("Missing string argument: " + key);
    return (String) v;
  }

  private Path path(String value, boolean exists) throws IOException {
    var p = root.resolve(value).normalize();
    if (!p.startsWith(root)) throw new IOException("Path outside automation root");
    if (exists) p = p.toRealPath();
    else {
      if (Files.exists(p)) throw new IOException("Output already exists");
      var parent = p.getParent().toRealPath();
      p = parent.resolve(p.getFileName());
    }
    if (!p.startsWith(root)) throw new IOException("Symlink escapes automation root");
    return p;
  }

  private List<Object> tools() {
    var tools = new ArrayList<Object>();
    for (Commands.Descriptor d : commands.descriptors())
      tools.add(
          Json.object(
              "name",
              d.id.replace('.', '_'),
              "description",
              d.description,
              "inputSchema",
              d.schema()));
    tools.add(
        tool(
            "open_document",
            "Open a document inside the configured root",
            Json.object("path", Json.object("type", "string")),
            "path"));
    tools.add(
        tool(
            "save_document",
            "Save a new file inside the configured root",
            Json.object(
                "path",
                Json.object("type", "string"),
                "allowConversion",
                Json.object("type", "boolean")),
            "path"));
    tools.add(
        tool(
            "preview_macro",
            "Preview a command macro without mutating the document",
            Json.object("macro", Json.object("type", "object")),
            "macro"));
    tools.add(
        tool(
            "apply_preview",
            "Apply a current preview as one undo step",
            Json.object("preview", Json.object("type", "string")),
            "preview"));
    return tools;
  }

  private Map<String, Object> tool(
      String name, String description, Map<String, Object> properties, String required) {
    return Json.object(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        Json.object(
            "type",
            "object",
            "properties",
            properties,
            "required",
            Arrays.asList(required),
            "additionalProperties",
            false));
  }

  private static Map<String, Object> result(Object id, Object result) {
    return Json.object("jsonrpc", "2.0", "id", id, "result", result);
  }

  private static Map<String, Object> error(Object id, int code, String message) {
    return Json.object(
        "jsonrpc", "2.0", "id", id, "error", Json.object("code", code, "message", message));
  }
}
