package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class LabelSheetsTest {
  private Session recipients(int count) {
    Session s = new Session(Document.empty());
    StringBuilder csv = new StringBuilder("First Name,City\n");
    for (int i = 1; i <= count; i++) csv.append("Person ").append(i).append(",London\n");
    MailMerge.recipients(s, MergeData.csv(csv.toString(), true));
    return s;
  }

  private void verify(Document d, int count, int sheets) {
    List<String> names = new ArrayList<String>();
    Set<String> ids = new HashSet<String>();
    int tables = 0;
    for (Block b : d.blocks) {
      assertTrue(ids.add(b.id()));
      if (!(b instanceof Table)) continue;
      tables++;
      Table t = (Table) b;
      for (int r = 0; r < t.rows(); r++)
        for (int c = 0; c < t.columns(); c++)
          for (Paragraph p : t.cell(r, c)) {
            assertTrue(ids.add(p.id));
            if (p.text.plainText().startsWith("Person ")) names.add(p.text.plainText());
          }
    }
    assertEquals(sheets, tables);
    assertEquals(count, names.size());
    for (int i = 0; i < count; i++) assertEquals("Person " + (i + 1), names.get(i));
    assertEquals(sheets, new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.size());
  }

  @Test
  public void allRecipientsContinueAcrossSheetsWithoutDuplicatesOrBlankPages() throws Exception {
    for (int count : new int[] {1, 30, 31, 60, 61}) {
      Session s = recipients(count);
      Document original = s.document();
      MergeData data = s.mergeData();
      assertEquals(count, MailingSheets.labels(s, "letter-3x10", "", true, 0, 0, 0, 0));
      int pages = (count + 29) / 30;
      verify(s.document(), count, pages);
      verify(DocumentXml.read(DocumentXml.write(s.document())), count, pages);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      Docx.write(s.document(), bytes);
      verify(Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document, count, pages);
      s.undo();
      assertSame(original, s.document());
      assertSame(data, s.mergeData());
      s.redo();
      verify(s.document(), count, pages);
    }
  }

  @Test
  public void productsAndCustomGridRespectPhysicalSheetBreaks() {
    for (String product : MailingSheets.PRODUCTS) {
      Session s = recipients(81);
      assertEquals(81, MailingSheets.labels(s, product, "", true, 0, 0, 0, 0));
      int sheets = s.document().sectionRanges().size();
      verify(s.document(), 81, sheets);
    }
    Session s = recipients(13);
    MailingSheets.labels(s, "letter-3x10", "", true, 2, 3, 0, 0);
    verify(s.document(), 13, 3);
  }

  @Test
  public void singlePositionRemainsOneLabelAndEmptySourceIsAtomic() {
    Session s = recipients(61);
    assertEquals(1, MailingSheets.labels(s, "letter-3x10", "", true, 0, 0, 2, 3));
    verify(s.document(), 1, 1);
    Table t = (Table) s.document().blocks.get(0);
    assertEquals("Person 1", t.cell(1, 4).get(0).text.plainText());
    Session empty = recipients(0);
    Document before = empty.document();
    try {
      MailingSheets.labels(empty, "letter-3x10", "", true, 0, 0, 0, 0);
      fail();
    } catch (IllegalArgumentException expected) {
      assertSame(before, empty.document());
    }
  }
}
