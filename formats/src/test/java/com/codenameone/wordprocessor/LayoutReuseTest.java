package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.io.File;
import org.junit.Test;

public class LayoutReuseTest {
  @Test public void typingInLargeImportedDocumentRemeasuresOneParagraph() throws Exception {
    File f = new File("../artifacts/1000-paragraphs.docx");
    org.junit.Assume.assumeTrue(f.exists());
    Session s = new Session(Docx.read(new FileInputStream(f)).document);
    LayoutEngine engine = new LayoutEngine(new PdfMetrics());
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
}
