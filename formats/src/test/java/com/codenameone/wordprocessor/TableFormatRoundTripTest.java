package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import java.io.*;
import org.junit.Test;

public class TableFormatRoundTripTest {
  @Test
  public void malformedTableFormatDimensionsReportCheckedImportErrors() throws Exception {
    Session s = new Session(Document.empty());
    s.insertTable(1, 1);
    String original = DocumentXml.write(s.document());
    for (String broken :
        new String[] {
          original.replace("<column width=", "<column width=\"1\"/><column width="),
          original.replace("<tableFormat headers=\"0\">", "<tableFormat headers=\"0\"><row/>"),
          original.replace("<cell background=", "<cell/><cell background=")
        }) {
      try {
        DocumentXml.read(broken);
        fail("Malformed dimensions accepted");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("Invalid document"));
      }
    }
  }

  @Test
  public void appearanceGeometryHeadersAndMergesSurviveNativeAndDocx() throws Exception {
    Session s = new Session(Document.empty());
    s.insertTable(4, 3);
    s.setCell(1, 0, 0, "Heading");
    s.setCell(1, 1, 1, "Bottom aligned");
    Table t = (Table) s.document().blocks.get(1);
    TableFormat.Cell cell = new TableFormat.Cell(0xcceeff, 0x445566, 1.5f, 8, 5, "bottom");
    s.formatTable(
        1, t.format.cell(1, 1, cell).columnFraction(1, .6f).minHeight(1, 80).headerRows(1));
    s.mergeCells(1, 2, 0, 2, 2);
    Document d = s.document();
    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened read = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(read.warnings.toString(), read.warnings.isEmpty());
    Table copy = (Table) read.document.blocks.get(1);
    assertEquals(.6, copy.format.columnFraction(1), .001);
    assertEquals(80, copy.format.minHeight(1), 0);
    assertEquals(1, copy.format.headerRows);
    TableFormat.Cell c = copy.format.cell(1, 1);
    assertEquals(cell.background, c.background);
    assertEquals(cell.borderColor, c.borderColor);
    assertEquals(cell.borderWidth, c.borderWidth, 0);
    assertEquals(cell.horizontalPadding, c.horizontalPadding, 0);
    assertEquals(cell.verticalPadding, c.verticalPadding, 0);
    assertEquals("bottom", c.vertical);
    assertEquals(2, copy.span(3, 1).rows);
    String html = TextFormats.write(d, RichTextFormat.HTML);
    assertTrue(html.contains("<thead>"));
    assertTrue(html.contains("background:#cceeff"));
    assertTrue(html.contains("vertical-align:bottom"));
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("1.500 w"));
    assertTrue(pdf.contains("0.800 0.933 1.000 rg"));
  }
}
