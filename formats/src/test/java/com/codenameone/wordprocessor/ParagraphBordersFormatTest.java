package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class ParagraphBordersFormatTest {
  private static final ParagraphBorders BOX =
      new ParagraphBorders(
          new ParagraphBorders.Side(1.5f, 0x1f4e79, 4, "double"),
          new ParagraphBorders.Side(.5f, 0, 4, "single"),
          new ParagraphBorders.Side(1, 0xc00000, 1, "dashed"),
          ParagraphBorders.Side.NONE);

  private static Document sample() {
    Session s = new Session(Document.empty());
    s.replace("Boxed and shaded");
    s.paragraph("borders", BOX.encode());
    s.paragraph("shading", "fff2cc");
    s.enterParagraph();
    s.paragraph("borders", "");
    s.paragraph("shading", "");
    s.replace("Plain");
    return s.document();
  }

  @Test
  public void nativeAndDocxRoundTrip() throws Exception {
    Document d = sample();
    assertEquals(BOX, d.paragraph(0).format.borders);
    assertTrue(d.paragraph(1).format.borders.isEmpty());
    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals(BOX, opened.document.paragraph(0).format.borders);
    assertEquals(0xfff2cc, opened.document.paragraph(0).format.shading);
    assertEquals(-1, opened.document.paragraph(1).format.shading);
  }

  @Test
  public void layoutReservesBorderSpaceAndPdfDrawsIt() throws Exception {
    Document d = sample();
    LayoutEngine.Result r = new LayoutEngine(new PdfMetrics()).layout(d, 1);
    LayoutEngine.Page page = r.pages.get(0);
    assertEquals(1, page.frames.size());
    LayoutEngine.Frame f = page.frames.get(0);
    assertTrue(f.first && f.last);
    LayoutEngine.Line first = page.lines.get(0);
    assertEquals(f.top + 5.5f, first.y, .01);
    assertEquals(page.spec.left - 4.5f, f.x, .01);
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains(" re f"));
    assertTrue(pdf.contains("[4.000 2.000] 0 d") || pdf.contains("[4 2] 0 d"));
  }
}
