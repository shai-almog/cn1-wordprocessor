package com.codenameone.wordprocessor;

import com.codename1.io.FileSystemStorage;
import com.codename1.io.Util;
import com.codename1.ui.Form;
import java.io.*;
import java.util.Map;

/** Opt-in benchmark instrumentation, applied only to an isolated build by prepare.py. */
final class NativeBenchmark {
  static NativeBenchmark current;
  // run.py writes the request before each launch: {"input", "out", "workload"}. prepare.py
  // replaces the placeholder with the file's absolute path in the instrumented checkout.
  static final Map<String, Object> request = request("@NATIVE_BENCH_REQUEST@");
  static String input = request == null ? null : (String) request.get("input");
  final Session session;
  final EditorSurface editor;
  final Runnable changed;
  final String out = (String) request.get("out");
  final boolean workload = Boolean.TRUE.equals(request.get("workload"));
  final String original;
  long readyAt, last;
  int step;
  boolean ready, waiting;
  volatile boolean done;
  String operation;
  long operationStart;

  static Map<String, Object> request(String path) {
    FileSystemStorage fs = FileSystemStorage.getInstance();
    if (!fs.exists(path)) return null;
    try (InputStream in = fs.openInputStream(path)) {
      return Json.parse(new String(Util.readInputStream(in), "UTF-8"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static InputStream read(String path) throws IOException {
    return FileSystemStorage.getInstance().openInputStream(path);
  }

  static OutputStream write(String path) throws IOException {
    return FileSystemStorage.getInstance().openOutputStream(path);
  }

  static Document load() {
    if (input == null) return null;
    try {
      long t = System.nanoTime();
      Document doc;
      try (InputStream in = read(input)) {
        doc = Docx.read(in).document;
      }
      emit("open", "ms", (System.nanoTime() - t) / 1e6);
      return doc;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  NativeBenchmark(Session s, EditorSurface e, Runnable c, Form f) {
    session = s;
    editor = e;
    changed = c;
    original = s.document().plainText();
    current = this;
    // Operations are posted the way input arrives: from another thread, waking the EDT, so
    // they run in the loop iteration that paints them. A UITimer runs after paint and a
    // callSerially posted from the EDT is not a wake-up, so either adds a full idle sleep
    // that real keystrokes never pay. This matches the Wordcraft harness, which runs each
    // command inside the frame that paints it.
    Thread input = new Thread(() -> {
      while (!done) {
        try {
          Thread.sleep(25);
        } catch (InterruptedException ex) {
          return;
        }
        com.codename1.ui.Display.getInstance().callSerially(() -> tick());
      }
    });
    input.setDaemon(true);
    input.start();
  }

  static void emit(String event, Object... values) {
    java.util.Map<String, Object> row =
        Json.object("event", event, "wallMs", System.currentTimeMillis());
    for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
    System.out.println("NATIVE_BENCH " + com.codename1.io.JSONParser.toJson(row));
  }

  static long paintStart, operationEnd;

  static void paintBegin() {
    paintStart = System.nanoTime();
  }

  static void painted() {
    NativeBenchmark b = current;
    if (b == null || b.done) return;
    if (!b.ready) {
      b.ready = true;
      b.readyAt = System.currentTimeMillis();
      emit("ready", "pages", b.editor.layout().pages.size(), "characters", b.original.length());
    }
    if (b.waiting) {
      long now = System.nanoTime();
      emit("paint", "operation", b.operation, "ms", (now - b.operationStart) / 1e6,
          "untilPaintMs", (paintStart - operationEnd) / 1e6, "editorPaintMs", (now - paintStart) / 1e6);
      b.waiting = false;
      b.last = System.currentTimeMillis();
    }
  }

  void tick() {
    if (!ready
        || done
        || waiting
        || System.currentTimeMillis() - readyAt < 5000
        || System.currentTimeMillis() - last < 60) return;
    if (!workload) return;
    try {
      if (step < 100) {
        operation = step % 2 == 0 ? "insert" : "undo";
        if (step % 2 == 0) session.select(0, 0, 0);
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        if (step % 2 == 0) editor.commitText("x");
        else {
          session.undo();
          changed.run();
          editor.refresh();
        }
      } else if (step == 100 || step == 101) {
        operation = step == 100 ? "paste10000" : "undoPaste";
        session.select(0, 0, 0);
        StringBuilder paste = new StringBuilder();
        if (step == 100) for (int i = 0; i < 1000; i++) paste.append("abcdefghij");
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        if (step == 100) editor.commitText(paste.toString());
        else {
          session.undo();
          changed.run();
          editor.refresh();
        }
      } else if (step < 107) {
        operation = "saveDocx";
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        try (OutputStream bytes = write(out + "/saved-" + step + ".docx")) {
          Docx.write(session.document(), bytes);
        }
        editor.repaint();
      } else if (step < 112) {
        operation = "reopenDocx";
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        try (InputStream bytes = read(out + "/saved-102.docx")) {
          Document document = Docx.read(bytes).document;
          long read = System.nanoTime();
          if (!original.equals(document.plainText()))
            throw new IllegalStateException("DOCX text mismatch");
          long verified = System.nanoTime();
          session.loadDocument(document);
          long loaded = System.nanoTime();
          changed.run();
          long notified = System.nanoTime();
          editor.refresh();
          emit("reopenSplit", "read", (read - operationStart) / 1e6, "verify", (verified - read) / 1e6,
              "load", (loaded - verified) / 1e6, "changed", (notified - loaded) / 1e6,
              "refresh", (System.nanoTime() - notified) / 1e6);
        }
      } else if (step < 115) {
        operation = "exportPdf";
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        byte[] pdf = PdfExporter.write(session.document());
        try (OutputStream bytes = write(out + "/export-" + step + ".pdf")) {
          bytes.write(pdf);
        }
        editor.repaint();
      } else {
        if (!original.equals(session.document().plainText()))
          throw new IllegalStateException("Undo text mismatch");
        emit("done", "textRestored", true, "pages", editor.layout().pages.size());
        done = true;
        return;
      }
      operationEnd = System.nanoTime();
      emit("operation", "operation", operation, "ms", (operationEnd - operationStart) / 1e6);
      waiting = true;
      step++;
    } catch (Throwable e) {
      emit("error", "message", e.toString());
      done = true;
    }
  }
}
