package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class ColumnsFormatTest {
  private static Document sample() {
    Session s = new Session(Document.empty());
    s.replace("Intro");
    s.sectionBreak("continuous");
    s.replace("Left column");
    s.enterParagraph();
    s.replace("Right column");
    s.columnBreak();
    s.columns(new Columns(2, 24, true, new float[] {150, 24, 300, 0}), "section");
    return s.document();
  }

  @Test
  public void nativeAndDocxKeepColumnsSeparatorWidthsAndBreaks() throws Exception {
    Document d = sample();
    Columns c = d.lastSection.columns;
    assertEquals(2, c.count);
    assertTrue(c.separator);
    assertFalse(c.equalWidth());
    assertEquals(Columns.ONE, d.sectionAt(0).raw.columns);

    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    Document back = opened.document;
    assertEquals(c, back.lastSection.columns);
    int breaks = 0;
    for (Block b : back.blocks)
      if (b instanceof Paragraph && ((Paragraph) b).format.columnBreak) breaks++;
    assertEquals(1, breaks);
    assertEquals(d.plainText(), back.plainText());
  }

  @Test
  public void applyToDocumentSetsEverySection() {
    Session s = new Session(Document.empty());
    s.replace("a");
    s.sectionBreak("nextPage");
    s.replace("b");
    s.columns(Columns.equal(3, 18, false), "document");
    for (Section.Range r : s.document().sectionRanges()) assertEquals(3, r.raw.columns.count);
    s.undo();
    for (Section.Range r : s.document().sectionRanges()) assertEquals(1, r.raw.columns.count);
  }
}
