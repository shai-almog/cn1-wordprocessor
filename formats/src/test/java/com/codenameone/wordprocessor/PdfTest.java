package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class PdfTest {
  private LayoutEngine.Result layout(Document d) {
    return new LayoutEngine(
            new LayoutEngine.Metrics() {
              public float width(String s, TextStyle t) {
                return s.length() * 6;
              }

              public float height(TextStyle s) {
                return s.size;
              }
            })
        .layout(d, 0);
  }

  @Test
  public void pdfHasAccurateCrossReferencesAndEscapedText() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Text (with parentheses) and \\ slash");
    String pdf =
        new String(PdfExporter.write(s.document().page, layout(s.document())), "ISO-8859-1");
    assertTrue(pdf.startsWith("%PDF-1.4"));
    assertTrue(pdf.contains("\\(with parentheses\\)"));
    int start = pdf.lastIndexOf("startxref\n") + 10;
    int offset = Integer.parseInt(pdf.substring(start, pdf.indexOf('\n', start)));
    assertTrue(pdf.substring(offset).startsWith("xref\n"));
    String[] lines = pdf.substring(offset).split("\n");
    int count = Integer.parseInt(lines[1].split(" ")[1]);
    for (int i = 1; i < count; i++) {
      int location = Integer.parseInt(lines[i + 2].substring(0, 10));
      assertTrue(pdf.substring(location).startsWith(i + " 0 obj"));
    }
  }

  @Test
  public void unsupportedGlyphIsRejectedRatherThanDropped() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("שלום");
    try {
      PdfExporter.write(s.document().page, layout(s.document()));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("does not cover"));
    }
  }
}
