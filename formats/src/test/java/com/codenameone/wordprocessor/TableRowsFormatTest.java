package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class TableRowsFormatTest {
  private Document fixture(String rule, boolean allow) {
    Session s = new Session(Document.empty());
    s.insertTable(2, 1);
    s.setCell(1, 0, 0, "first line\nsecond line\nthird line");
    Table t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.row(0, new TableFormat.Row(18, rule, allow)));
    return s.document();
  }

  @Test
  public void nativeAndDocxRoundTripHeightAndBreakPolicy() throws Exception {
    for (String rule : new String[] {"auto", "atLeast", "exact"})
      for (boolean allow : new boolean[] {true, false}) {
        Document d = fixture(rule, allow);
        String xml = DocumentXml.write(d);
        assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Docx.write(d, out);
        Docx.Opened read = Docx.read(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(read.warnings.toString(), read.warnings.isEmpty());
        TableFormat.Row row = ((Table) read.document.blocks.get(1)).format.row(0);
        assertEquals(rule, row.rule);
        assertEquals(allow, row.allowBreak);
        assertEquals(rule.equals("auto") ? 0 : 18, row.height, .001);
      }
  }

  @Test
  public void zeroMinimumIsAutomaticAndTinyExactHeightSurvivesDocx() throws Exception {
    assertEquals("auto", new TableFormat.Row(0, "atLeast", false).rule);
    Session s = new Session(fixture("exact", true));
    Table t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.row(0, new TableFormat.Row(.01f, "exact", false)));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    TableFormat.Row row =
        ((Table) Docx.read(new ByteArrayInputStream(out.toByteArray())).document.blocks.get(1))
            .format.row(0);
    assertEquals("exact", row.rule);
    assertEquals(.05f, row.height, .001);
    assertFalse(row.allowBreak);
  }

  @Test
  public void newDocxUsesModernTablePlacementAndPreservesImportedCompatibility() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(fixture("exact", true), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    Map<String, byte[]> parts = parts(out.toByteArray());
    String settings = new String(parts.get("word/settings.xml"), "UTF-8");
    assertTrue(settings, settings.contains("compatibilityMode") && settings.contains("15"));
    parts.put("word/settings.xml", settings.replace("15", "14").getBytes("UTF-8"));
    out.reset();
    Docx.write(
        opened.document,
        new Docx.Opened(opened.document, Collections.<String>emptySet(), parts),
        out,
        true);
    String saved = new String(parts(out.toByteArray()).get("word/settings.xml"), "UTF-8");
    assertTrue(saved, saved.contains("compatibilityMode") && saved.contains("14"));
  }

  @Test
  public void tightMultipleSpacingSurvivesNativeAndDocxAndRejectsZero() throws Exception {
    Session s = new Session(fixture("exact", true));
    s.enterCell(1, 0, 0);
    s.paragraph("spacing", "0.5");
    assertEquals(
        .5f,
        ((Table) DocumentXml.read(DocumentXml.write(s.document())).blocks.get(1))
            .cell(0, 0)
            .get(0)
            .lineSpacing,
        0);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    assertEquals(
        .5f,
        ((Table) Docx.read(new ByteArrayInputStream(out.toByteArray())).document.blocks.get(1))
            .cell(0, 0)
            .get(0)
            .lineSpacing,
        0);
    Document before = s.document();
    try {
      s.paragraph("spacing", "0");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void legacyNativeRowsRemainMinimumHeightAndAllowBreaks() throws Exception {
    String xml = DocumentXml.write(fixture("atLeast", true));
    assertFalse(xml.contains("allowBreak="));
    assertFalse(xml.contains("rule="));
    TableFormat.Row row = ((Table) DocumentXml.read(xml).blocks.get(1)).format.row(0);
    assertEquals("atLeast", row.rule);
    assertTrue(row.allowBreak);
  }

  @Test
  public void pdfClipsOverflowRatherThanPrintingThroughNextRow() throws Exception {
    Document d = fixture("exact", true);
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains(" re W n\n"));
    assertFalse(pdf.contains("third line"));
    LayoutEngine.Result layout = new LayoutEngine(new PdfMetrics()).layout(d, 0);
    for (LayoutEngine.Page p : layout.pages)
      for (LayoutEngine.Line line : p.lines)
        if (line.block == 1 && line.row == 0)
          assertTrue(line.clipped || line.y + line.height <= p.boxes.get(0).y + 18);
  }

  private Map<String, byte[]> parts(byte[] bytes) throws Exception {
    Map<String, byte[]> parts = new LinkedHashMap<String, byte[]>();
    ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes));
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = zip.read(buffer)) != -1) out.write(buffer, 0, n);
      parts.put(entry.getName(), out.toByteArray());
    }
    zip.close();
    return parts;
  }
}
