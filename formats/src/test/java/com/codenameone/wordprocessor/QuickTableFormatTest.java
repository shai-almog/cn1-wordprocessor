package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class QuickTableFormatTest {
  @Test
  public void templatesKeepContentHeadersAndMaterializedStylesInNativeAndDocx() throws Exception {
    for (String kind : new String[] {"tabular", "matrix", "calendar"}) {
      Session s = new Session(Document.empty());
      s.replace("before after");
      s.select(0, 7, 7);
      s.insertQuickTable(kind);
      Document d = s.document();
      assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      Docx.write(d, bytes);
      Docx.Opened reopened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
      assertTrue(reopened.warnings.toString(), reopened.warnings.isEmpty());
      assertEquals("before ", reopened.document.paragraph(0).text.toString());
      assertEquals("after", reopened.document.paragraph(2).text.toString());
      Table original = (Table) d.blocks.get(1), copy = (Table) reopened.document.blocks.get(1);
      assertEquals(original.rows(), copy.rows());
      assertEquals(original.columns(), copy.columns());
      assertEquals(1, copy.format.headerRows);
      for (int r = 0; r < original.rows(); r++)
        for (int c = 0; c < original.columns(); c++) {
          assertEquals(Table.cellText(original.cell(r, c)), Table.cellText(copy.cell(r, c)));
          assertEquals(original.format.cell(r, c).background, copy.format.cell(r, c).background);
          assertEquals(original.format.cell(r, c).borderColor, copy.format.cell(r, c).borderColor);
          if (!original.cell(r, c).get(0).text.toString().isEmpty()) {
            assertEquals(
                original.cell(r, c).get(0).text.styleAt(0).bold,
                copy.cell(r, c).get(0).text.styleAt(0).bold);
            assertEquals(
                original.cell(r, c).get(0).text.styleAt(0).color,
                copy.cell(r, c).get(0).text.styleAt(0).color);
          }
        }
    }
  }
}
