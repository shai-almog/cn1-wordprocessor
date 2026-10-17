package com.codenameone.wordprocessor;

import com.codename1.ui.Form;
import com.codename1.ui.util.UITimer;
import java.io.*;

/** Opt-in benchmark instrumentation, applied only to an isolated build by prepare.py. */
final class NativeBenchmark {
  static NativeBenchmark current;
  static String input = System.getenv("NATIVE_BENCH_INPUT");
  final Session session;
  final EditorSurface editor;
  final Runnable changed;
  final String out = System.getenv("NATIVE_BENCH_OUT");
  final String original;
  long readyAt, last;
  int step;
  boolean ready, done, waiting;
  String operation;
  long operationStart;
  UITimer timer;

  static Document load() {
    if (input == null) return null;
    try {
      long t = System.nanoTime();
      Document doc;
      try (InputStream in = new FileInputStream(input)) {
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
    timer = new UITimer(() -> tick());
    timer.schedule(25, true, f);
  }

  static void emit(String event, Object... values) {
    java.util.Map<String, Object> row =
        Json.object("event", event, "wallMs", System.currentTimeMillis());
    for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
    System.out.println("NATIVE_BENCH " + Json.write(row));
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
      emit("paint", "operation", b.operation, "ms", (System.nanoTime() - b.operationStart) / 1e6);
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
    if (System.getenv("NATIVE_BENCH_WORKLOAD") == null) return;
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
        try (OutputStream bytes = new FileOutputStream(out + "/saved-" + step + ".docx")) {
          Docx.write(session.document(), bytes);
        }
        editor.repaint();
      } else if (step < 112) {
        operation = "reopenDocx";
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        try (InputStream bytes = new FileInputStream(out + "/saved-102.docx")) {
          Document document = Docx.read(bytes).document;
          if (!original.equals(document.plainText()))
            throw new IllegalStateException("DOCX text mismatch");
          session.loadDocument(document);
          changed.run();
          editor.refresh();
        }
      } else if (step < 115) {
        operation = "exportPdf";
        operationStart = System.nanoTime();
        emit("operationStart", "operation", operation);
        byte[] pdf = PdfExporter.write(session.document());
        try (OutputStream bytes = new FileOutputStream(out + "/export-" + step + ".pdf")) {
          bytes.write(pdf);
        }
        editor.repaint();
      } else {
        if (!original.equals(session.document().plainText()))
          throw new IllegalStateException("Undo text mismatch");
        emit("done", "textRestored", true, "pages", editor.layout().pages.size());
        done = true;
        timer.cancel();
        return;
      }
      emit("operation", "operation", operation, "ms", (System.nanoTime() - operationStart) / 1e6);
      waiting = true;
      step++;
    } catch (Throwable e) {
      emit("error", "message", e.toString());
      done = true;
      timer.cancel();
    }
  }
}
