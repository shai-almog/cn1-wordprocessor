package com.codenameone.wordprocessor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Cli {
  public static void main(String[] args) {
    // Headless: no Display, but crypto (encrypted documents) runs on the JavaSE port.
    // The port logs on construction; keep stdout clean for the MCP protocol.
    var stdout = System.out;
    System.setOut(System.err);
    com.codename1.io.Util.setImplementation(new com.codename1.impl.javase.JavaSEPort());
    System.setOut(stdout);
    try {
      run(args);
    } catch (Exception e) {
      System.err.println("wordprocessor: " + e.getMessage());
      System.exit(1);
    }
  }

  static void run(String[] args) throws Exception {
    if (args.length == 0 || "help".equals(args[0])) {
      System.out.println(
          """
          CN1 Word Processor
            commands
            new OUT.cn1doc
            inspect INPUT
            text INPUT
            convert INPUT OUTPUT [--allow-conversion]
            run INPUT MACRO.json OUTPUT [--params JSON] [--preview] [--allow-conversion]
            mcp --root DIRECTORY
          Existing output files are not overwritten by the CLI.""");
      return;
    }
    switch (args[0]) {
      case "commands":
        for (Commands.Descriptor d : DocumentFiles.commands().descriptors())
          System.out.println(
              com.codename1.io.JSONParser.toJson(
                  Json.object(
                      "id", d.id, "description", d.description, "inputSchema", d.schema())));
        return;
      case "new":
        require(args, 2);
        save(Paths.get(args[1]), ProjectFile.write(Document.empty(), null));
        return;
      case "inspect":
      case "text":
        require(args, 2);
        DocumentFiles.Opened opened = DocumentFiles.read(Paths.get(args[1]));
        System.out.println(
            "text".equals(args[0])
                ? opened.document.plainText()
                : com.codename1.io.JSONParser.toJson(
                    Json.object("document", Commands.inspect(new Session(opened.document)), "warnings", opened.warnings)));
        return;
      case "convert":
        require(args, 3);
        DocumentFiles.Opened input = DocumentFiles.read(Paths.get(args[1]));
        Path target = Paths.get(args[2]);
        save(
            target,
            DocumentFiles.write(
                input.document,
                input.source,
                DocumentFiles.extension(target),
                has(args, "--allow-conversion")));
        return;
      case "run":
        require(args, 4);
        DocumentFiles.Opened source = DocumentFiles.read(Paths.get(args[1]));
        var session = new Session(source.document);
        var macro =
            Json.parse(new String(Files.readAllBytes(Paths.get(args[2])), "UTF-8"));
        var params = new LinkedHashMap<String, String>();
        for (int i = 4; i < args.length; i++)
          if ("--params".equals(args[i])) {
            if (++i == args.length) throw new IllegalArgumentException("Missing --params JSON");
            for (Map.Entry<String, Object> e : Json.parse(args[i]).entrySet()) {
              if (!(e.getValue() instanceof String))
                throw new IllegalArgumentException("Parameters must be strings");
              params.put(e.getKey(), (String) e.getValue());
            }
          }
        var preview = new Macros(DocumentFiles.commands()).preview(session, macro, params, null);
        if (has(args, "--preview")) {
          System.out.println(
              com.codename1.io.JSONParser.toJson(
                  Json.object(
                      "before",
                      preview.before,
                      "after",
                      preview.after,
                      "changes",
                      preview.changes,
                      "baseRevision",
                      preview.baseRevision)));
          return;
        }
        preview.apply(session);
        Path output = Paths.get(args[3]);
        save(
            output,
            DocumentFiles.write(
                session.document(),
                source.source,
                DocumentFiles.extension(output),
                has(args, "--allow-conversion")));
        return;
      case "mcp":
        if (args.length != 3 || !"--root".equals(args[1]))
          throw new IllegalArgumentException("MCP requires --root DIRECTORY");
        new McpServer(Paths.get(args[2])).serve(System.in, System.out);
        return;
      default:
        throw new IllegalArgumentException("Unknown command: " + args[0]);
    }
  }

  private static void require(String[] args, int count) {
    if (args.length < count) throw new IllegalArgumentException("Missing arguments; run help");
  }

  private static boolean has(String[] args, String value) {
    for (String s : args) if (value.equals(s)) return true;
    return false;
  }

  static void save(Path target, byte[] data) throws IOException {
    target = target.toAbsolutePath();
    if (Files.exists(target)) throw new IOException("Output exists: " + target);
    Path temp = Files.createTempFile(target.getParent(), ".wordprocessor-", ".tmp");
    try {
      try (java.nio.channels.FileChannel channel =
          java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(temp, target);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
