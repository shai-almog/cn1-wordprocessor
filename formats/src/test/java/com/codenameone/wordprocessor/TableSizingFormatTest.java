package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import java.io.*;
import org.junit.Test;

public class TableSizingFormatTest {
  private Document fixture(String unit, float width, String align, float indent, boolean fixed) {
    Session s = new Session(Document.empty());
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "alpha");
    Table t = (Table) s.document().blocks.get(1);
    s.formatTable(
        1,
        t.format
            .column(0, 40)
            .column(1, 80)
            .sizing(new TableFormat.Sizing(unit, width, align, indent, fixed)));
    return s.document();
  }

  @Test
  public void nativeAndDocxPreserveAllSizingModes() throws Exception {
    for (String unit : new String[] {"percent", "points", "auto"})
      for (boolean fixed : new boolean[] {true, false})
        for (String align : new String[] {"left", "center", "right"}) {
          Document d =
              fixture(
                  unit,
                  "percent".equals(unit) ? 60 : "points".equals(unit) ? 180 : 0,
                  align,
                  -12,
                  fixed);
          assertEquals(
              DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          Docx.write(d, out);
          Docx.Opened imported = Docx.read(new ByteArrayInputStream(out.toByteArray()));
          assertTrue(imported.warnings.toString(), imported.warnings.isEmpty());
          Table t = (Table) imported.document.blocks.get(1);
          TableFormat.Sizing size = t.format.sizing;
          assertEquals(unit, size.unit);
          assertEquals(align, size.align);
          assertEquals(-12, size.indent, 0);
          assertEquals(fixed, size.fixed);
          assertEquals(((Table) d.blocks.get(1)).format.sizing.width, size.width, 0);
          assertEquals(1f / 3, t.format.columnFraction(0), .001);
          if ("auto".equals(unit)) assertEquals(120, t.format.preferredWidth(500), .1);
        }
  }

  @Test
  public void htmlUsesPreferredGeometryAndAutomaticMargins() throws Exception {
    String html =
        TextFormats.write(fixture("percent", 60, "center", 12, true), RichTextFormat.HTML);
    assertTrue(html.contains("width:60.0%;table-layout:fixed;margin-left:auto;margin-right:auto"));
    html = TextFormats.write(fixture("points", 180, "left", 12, false), RichTextFormat.HTML);
    assertTrue(html.contains("width:180.0pt;table-layout:auto;margin-left:12.0pt"));
  }

  @Test
  public void oldNativeTablesKeepFullWidthFixedGeometry() throws Exception {
    Session s = new Session(Document.empty());
    s.insertTable(1, 1);
    String xml = DocumentXml.write(s.document());
    assertFalse(xml.contains("widthUnit="));
    Table t = (Table) DocumentXml.read(xml).blocks.get(1);
    assertEquals("percent", t.format.sizing.unit);
    assertEquals(100, t.format.sizing.width, 0);
    assertTrue(t.format.sizing.fixed);
  }

  @Test
  public void pdfUsesCenteredPreferredWidth() throws Exception {
    Document d =
        fixture("points", 120, "center", 0, true).page(new PageSpec(240, 300, 20, 20, 20, 20));
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf, pdf.contains("60.000 ")); // left edge: margin 20 + (200 - 120)/2
    LayoutEngine.Result layout = new LayoutEngine(new PdfMetrics()).layout(d, 0);
    assertEquals(60, layout.pages.get(0).boxes.get(0).x, .001);
    assertEquals(40, layout.pages.get(0).boxes.get(0).width, .001);
  }
}
