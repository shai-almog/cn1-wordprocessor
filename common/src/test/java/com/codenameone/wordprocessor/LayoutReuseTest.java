package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.io.File;
import org.junit.Test;

public class LayoutReuseTest {
  @Test public void typingInLargeImportedDocumentRemeasuresOneParagraph() throws Exception {
    var f = new File("../artifacts/1000-paragraphs.docx");
    org.junit.Assume.assumeTrue(f.exists());
    var s = new Session(Docx.read(new FileInputStream(f)).document);
    var engine = new LayoutEngine(new PdfMetrics());
    engine.layout(s.document(), 0);
    LayoutEngine.Result r = null;
    for (int i = 0; i < 30; i++) {
      s.select(5, 0, 0);
      s.replace("x");
      long t = System.nanoTime();
      r = engine.layout(s.document(), 2 + i);
      if (i > 24) System.out.println("relayout ms " + (System.nanoTime() - t) / 1e6);
    }
    System.out.println("measured after edit: " + r.measuredParagraphs);
    assertTrue("measured " + r.measuredParagraphs, r.measuredParagraphs <= 2);
  }

  @Test public void reopeningAnUnchangedDocumentRemeasuresNothing() throws Exception {
    var s = new Session(Document.empty());
    var text = new StringBuilder();
    for (int i = 0; i < 40; i++) text.append(i > 0 ? "\n" : "").append("Paragraph ").append(i % 7).append(" with some words to wrap");
    s.replace(text.toString());
    var d = s.document();
    var bytes = new java.io.ByteArrayOutputStream();
    Docx.write(d, bytes);
    var engine = new LayoutEngine(new PdfMetrics());
    var first = engine.layout(Docx.read(new java.io.ByteArrayInputStream(bytes.toByteArray())).document, 0);
    var again = engine.layout(Docx.read(new java.io.ByteArrayInputStream(bytes.toByteArray())).document, 1);
    assertTrue(first.measuredParagraphs > 0);
    assertEquals(0, again.measuredParagraphs);
    assertEquals(first.pages.size(), again.pages.size());
  }
}
