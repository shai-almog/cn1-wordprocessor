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
  public void portableMetricsMatchPdfFacesAndEncoding() throws Exception {
    var metrics = new PdfMetrics();
    var normal = TextStyle.DEFAULT.with("size", "10");
    // AFM advances: H=722, e=556, l=222, o=556; bold H=722,e=556,l=278,o=611.
    assertEquals(22.78f, metrics.width("Hello", normal), .001f);
    assertEquals(24.45f, metrics.width("Hello", normal.with("bold", "true")), .001f);
    assertEquals(
        metrics.width("Hello", normal),
        metrics.width("Hello", normal.with("italic", "true")),
        .001f);
    assertEquals(5.56f, metrics.width("€", normal), .001f);
    assertEquals(metrics.width(" ", normal), metrics.width("\t", normal), .001f);
    var s = new Session(Document.empty());
    s.replace("Résumé € (PDF) and café");
    assertTrue(new String(PdfExporter.write(s.document()), "ISO-8859-1").contains("/Helvetica"));
    s.replace("שלום");
    try {
      PdfExporter.write(s.document());
      fail("Unrepresentable text must still be rejected");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("does not cover"));
    }
  }

  @Test
  public void pdfHasAccurateCrossReferencesAndEscapedText() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Text (with parentheses) and \\ slash");
    var pdf =
        new String(PdfExporter.write(s.document().page, layout(s.document())), "ISO-8859-1");
    assertTrue(pdf.startsWith("%PDF-1.4"));
    assertTrue(pdf.contains("\\(with parentheses\\)"));
    int start = pdf.lastIndexOf("startxref\n") + 10;
    int offset = Integer.parseInt(pdf.substring(start, pdf.indexOf('\n', start)));
    assertTrue(pdf.substring(offset).startsWith("xref\n"));
    var lines = pdf.substring(offset).split("\n");
    int count = Integer.parseInt(lines[1].split(" ")[1]);
    for (int i = 1; i < count; i++) {
      int location = Integer.parseInt(lines[i + 2].substring(0, 10));
      assertTrue(pdf.substring(location).startsWith(i + " 0 obj"));
    }
  }

  @Test
  public void nestedBulletLevelsExportWithoutUnsupportedGlyphFailure() throws Exception {
    var s = new Session(Document.empty());
    s.replace("First\nSecond\nThird");
    s.selectRange(0, 0, 2, 5);
    s.paragraph("list", "bullet");
    for (int i = 0; i < 3; i++) {
      s.select(i, 0, 0);
      s.paragraph("listLevel", String.valueOf(i));
    }
    var pdf = new String(PdfExporter.write(s.document()), "ISO-8859-1");
    for (String text : new String[] {"First", "Second", "Third"})
      assertTrue(pdf.contains("(" + text + ") Tj"));
    assertTrue("Hollow circle marker", pdf.contains("h S\n"));
    assertTrue("Square marker", pdf.contains("re f\n"));
  }

  @Test
  public void unsupportedGlyphIsRejectedRatherThanDropped() throws Exception {
    var s = new Session(Document.empty());
    s.replace("שלום");
    try {
      PdfExporter.write(s.document().page, layout(s.document()));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("does not cover"));
    }
  }

  @Test
  public void overflowingHeadersCannotSilentlyExportTruncatedPdf() throws Exception {
    var text = new StringBuilder("Header");
    for (int i = 0; i < 100; i++) text.append("\nToo much");
    var d =
        Document.empty()
            .furniture(
                PageFurniture.EMPTY.story(
                    "header",
                    new Sequence<Paragraph>()
                        .append(new Paragraph("h", RichText.of(text.toString())))));
    try {
      PdfExporter.write(d);
      fail("A truncated document must not be exported");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("Header on page 1 does not fit"));
    }
  }
}
