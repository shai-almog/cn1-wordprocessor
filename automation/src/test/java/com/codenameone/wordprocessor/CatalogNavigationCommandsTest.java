package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class CatalogNavigationCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static Paragraph para(Session s, int i) {
    return (Paragraph) s.document().blocks.get(i);
  }

  @Test
  public void caretSelectionAndFind() {
    Session s = new Session(Document.empty());
    s.replace("One two. Three four.");
    s.enterParagraph();
    s.replace("five two");
    run(s, "caret.docStart");
    assertEquals(0, s.selection().block);
    assertEquals(0, s.selection().caret);
    run(s, "caret.wordRight");
    assertEquals(4, s.selection().caret);
    assertEquals("two", ((String) run(s, "select.word")).trim());
    s.select(0, 12, 12);
    assertEquals("Three four.", run(s, "select.sentence"));
    run(s, "caret.docEnd");
    assertEquals(1, s.selection().block);
    assertEquals(Boolean.TRUE, run(s, "edit.findNext", "text", "TWO"));
    assertEquals(0, s.selection().block);
    assertEquals(4, s.selection().start());
    assertEquals(Boolean.TRUE, run(s, "edit.findPrevious", "text", "two"));
    assertEquals(1, s.selection().block);
    s.select(1, 4, 4);
    run(s, "text.deleteWordBack");
    assertEquals(" two", para(s, 1).text.plainText());
    assertEquals(1, run(s, "edit.undoTo", "count", 1));
    assertEquals("five two", para(s, 1).text.plainText());
  }

  @Test
  public void fieldsLockUnlinkAndStep() {
    Session s = new Session(Document.empty());
    s.replace("Page ");
    run(s, "insert.pageNumber");
    s.replace(" end");
    run(s, "caret.docStart");
    assertEquals(Boolean.TRUE, run(s, "fields.next"));
    assertEquals(5, s.selection().start());
    run(s, "fields.lock");
    assertTrue(para(s, 0).text.fieldAt(5).locked);
    run(s, "fields.toggleCode");
    assertTrue(para(s, 0).text.fieldAt(5).showCode);
    assertEquals(1, run(s, "fields.unlink"));
    assertNull(para(s, 0).text.fieldAt(5));
    assertEquals("Page 1 end", para(s, 0).text.plainText());
  }

  @Test
  public void outlineSectionAndTableProperties() {
    Session s = new Session(Document.empty());
    s.replace("Topic");
    run(s, "outline.promote");
    assertEquals("Heading9", para(s, 0).style);
    run(s, "outline.promoteToHeading1");
    run(s, "outline.demote");
    assertEquals("Heading2", para(s, 0).style);
    run(s, "outline.demoteToBody");
    assertEquals("Normal", para(s, 0).style);

    run(s, "layout.size", "name", "Letter");
    assertEquals(612, s.activeSection().raw.page.width, .01);
    run(s, "layout.differentFirstPage");
    assertTrue(s.activeSection().raw.furniture.differentFirst);

    run(s, "insert.table", "rows", 3, "cols", 2);
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Table) block = i;
    s.enterCell(block, 0, 1);
    run(s, "table.repeatHeader");
    run(s, "table.shading", "color", "ffeeaa");
    run(s, "table.cellMargins", "horizontal", 9);
    Table t = (Table) s.document().blocks.get(block);
    assertEquals(1, t.format.headerRows);
    assertEquals(0xffeeaa, t.format.cell(0, 1).background);
    assertEquals(9, t.format.cell(0, 1).horizontalPadding, .01);
    assertEquals(0xffffff, t.format.cell(0, 0).background);
  }

  @Test
  public void tableBorderGalleryAndStyles() {
    Session s = new Session(Document.empty());
    run(s, "insert.table", "rows", 3, "cols", 3);
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Table) block = i;
    s.enterCell(block, 0, 0);
    s.selectCells(block, 0, 0, 2, 2);
    run(s, "table.borders", "kind", "none");
    run(s, "table.borders", "kind", "outside", "color", "ff0000", "width", 1.5);
    TableFormat f = ((Table) s.document().blocks.get(block)).format;
    assertTrue(f.cell(0, 0).side(TableFormat.Cell.TOP));
    assertTrue(f.cell(0, 0).side(TableFormat.Cell.LEFT));
    assertFalse(f.cell(0, 0).side(TableFormat.Cell.RIGHT));
    assertFalse(f.cell(1, 1).side(TableFormat.Cell.TOP));
    assertTrue(f.cell(2, 2).side(TableFormat.Cell.BOTTOM));
    assertEquals(0xff0000, f.cell(2, 2).borderColor);
    run(s, "table.borders", "kind", "insideH");
    f = ((Table) s.document().blocks.get(block)).format;
    assertTrue(f.cell(1, 1).side(TableFormat.Cell.TOP));
    assertFalse(f.cell(1, 1).side(TableFormat.Cell.LEFT));
    run(s, "table.style", "style", "GridTableAccent1");
    f = ((Table) s.document().blocks.get(block)).format;
    assertEquals(0x4472c4, f.cell(0, 1).background);
    assertEquals(1, f.headerRows);
  }

  @Test
  public void headersFootersLinkingAndPosition() {
    Session s = new Session(Document.empty());
    s.replace("Body");
    run(s, "insert.header", "text", "Report\nDraft", "align", "center");
    run(s, "insert.footer", "text", "Confidential", "variant", "first");
    PageFurniture f = s.activeSection().raw.furniture;
    assertEquals(2, f.get("header").size());
    assertEquals("center", f.get("header").get(0).align);
    assertEquals("Confidential", f.get("firstFooter").get(0).text.plainText());
    try {
      // Header and footer stories number their paragraphs independently (both hf0).
      DocumentXml.read(DocumentXml.write(s.document()));
    } catch (java.io.IOException ex) {
      throw new AssertionError(ex);
    }
    s.sectionBreak("nextPage");
    assertEquals(Boolean.FALSE, run(s, "layout.linkToPrevious", "story", "header"));
    assertTrue(s.activeSection().raw.furniture.stories.containsKey("header"));
    run(s, "insert.header", "text", "Second");
    assertEquals("Report", s.document().sectionAt(0).resolved.furniture.get("header").get(0).text.plainText());
    assertEquals(Boolean.TRUE, run(s, "layout.linkToPrevious", "story", "header"));
    assertEquals("Report", s.activeSection().resolved.furniture.get("header").get(0).text.plainText());
    run(s, "insert.removeFooter");
    assertEquals(0, s.activeSection().resolved.furniture.get("firstFooter").size());
    run(s, "hf.position", "header", 30);
    assertEquals(30, s.activeSection().raw.furniture.headerDistance, .01);
  }
}
