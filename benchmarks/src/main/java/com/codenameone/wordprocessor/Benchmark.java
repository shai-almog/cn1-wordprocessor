package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/** Headless model/layout benchmark. This deliberately does not claim input-to-present latency. */
public final class Benchmark {
  public static void main(String[] args) throws Exception {
    int paragraphs = args.length > 0 ? Integer.parseInt(args[0]) : 1000;
    int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 100;
    if (paragraphs < 1 || paragraphs > 100000 || iterations < 10 || iterations > 10000)
      throw new IllegalArgumentException("Invalid benchmark size");
    Sequence<Block> blocks = new Sequence<Block>();
    for (int i = 0; i < paragraphs; i++)
      blocks =
          blocks.append(
              new Paragraph(
                  "p" + (i + 1),
                  RichText.of(
                      "Paragraph "
                          + i
                          + ". A portable document engine should keep typing responsive as"
                          + " documents grow. This deterministic fixture measures editing and"
                          + " layout work, independently of window rendering.")));
    Document doc = new Document(blocks, PageSpec.A4, "Benchmark " + paragraphs, paragraphs + 1);
    Session session = new Session(doc);
    LayoutEngine engine =
        new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String text, TextStyle s) {
                return text.length() * s.size * .5f;
              }

              public float height(TextStyle s) {
                return s.size * 1.2f;
              }
            });
    LayoutEngine.Result layout = engine.layout(doc, 0);
    for (int i = 0; i < 40; i++) edit(session, engine, paragraphs, i);
    long[] editTimes = new long[iterations],
        layoutTimes = new long[iterations],
        saveTimes = new long[iterations],
        openTimes = new long[iterations];
    int measured = 0;
    byte[] bytes = null;
    for (int i = 0; i < iterations; i++) {
      session.select(paragraphs / 2, 0, 0);
      long start = System.nanoTime();
      session.replace("x");
      editTimes[i] = System.nanoTime() - start;
      start = System.nanoTime();
      LayoutEngine.Result result = engine.layout(session.document(), session.revision());
      layoutTimes[i] = System.nanoTime() - start;
      measured = Math.max(measured, result.measuredParagraphs);
      session.undo();
      engine.layout(session.document(), session.revision());
    }
    for (int i = 0; i < iterations; i++) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      long start = System.nanoTime();
      Docx.write(doc, out);
      saveTimes[i] = System.nanoTime() - start;
      bytes = out.toByteArray();
      start = System.nanoTime();
      Docx.read(new ByteArrayInputStream(bytes));
      openTimes[i] = System.nanoTime() - start;
    }
    Map<String, Object> result =
        Json.object(
            "schema",
            1,
            "kind",
            "headless-synthetic-metrics",
            "paragraphs",
            paragraphs,
            "pages",
            layout.pages.size(),
            "iterations",
            iterations,
            "warmupEdits",
            40,
            "java",
            System.getProperty("java.version"),
            "os",
            System.getProperty("os.name"),
            "arch",
            System.getProperty("os.arch"),
            "maxMeasuredParagraphsPerEdit",
            measured,
            "edit",
            timing(editTimes),
            "layoutAfterEdit",
            timing(layoutTimes),
            "docxWrite",
            timing(saveTimes),
            "docxRead",
            timing(openTimes),
            "docxBytes",
            bytes.length,
            "inputToPresentMeasured",
            false,
            "competitorCompared",
            false);
    System.out.println(Json.write(result));
    if (args.length > 2) {
      try (FileOutputStream out = new FileOutputStream(args[2])) {
        out.write(bytes);
      }
    }
  }

  private static void edit(Session s, LayoutEngine e, int paragraphs, int i) {
    s.select(paragraphs / 2, 0, 0);
    s.replace("x");
    e.layout(s.document(), s.revision());
    s.undo();
    e.layout(s.document(), s.revision());
  }

  private static Map<String, Object> timing(long[] times) {
    Arrays.sort(times);
    return Json.object(
        "medianMs",
        times[times.length / 2] / 1e6,
        "p95Ms",
        times[(int) Math.ceil(times.length * .95) - 1] / 1e6);
  }
}
