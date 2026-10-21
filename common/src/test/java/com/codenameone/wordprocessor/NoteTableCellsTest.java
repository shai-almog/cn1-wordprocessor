package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

/**
 * Note table cells entered from the page, PAGEREF to bookmarks in cells and notes, and the
 * Wordcraft table-row rules (at-least insets, legacy placement).
 */
public class NoteTableCellsTest {
  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String s, TextStyle style) {
            return s.length() * 6;
          }

          public float height(TextStyle style) {
            return 12;
          }
        });
  }

  private static Session noteWithTable(String kind) {
    var s = new Session(Document.empty());
    s.replace("Body ");
    s.insertNote(kind, "Note text", "");
    s.enterNote(Notes.all(s.document()).get(0).id);
    s.insertTable(2, 2);
    s.leaveCellLevel();
    s.leaveCell();
    return s;
  }

  private static int tableIndex(Document d) {
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i) instanceof Table) return i;
    return -1;
  }

  private static void entersNoteCells(String kind) {
    var s = noteWithTable(kind);
    var note = Notes.all(s.document()).get(0);
    int table = tableIndex(new Document(note.blocks, s.document().page, "", 1));
    assertTrue(table > 0);
    LayoutEngine.Line hit = null;
    for (LayoutEngine.Page p : engine().layout(s.document(), 0).pages)
      for (LayoutEngine.Line l : p.allLines())
        if (l.noteCell != null && l.noteCell[1] == 1 && l.noteCell[2] == 1) hit = l;
    assertNotNull(kind, hit);
    assertEquals(note.id, hit.note);
    assertArrayEquals(new int[] {table, 1, 1}, hit.noteCell);
    s.enterNoteCell(hit.note, hit.noteCell);
    assertEquals(note.id, s.enclosingNote());
    assertArrayEquals(hit.noteCell, s.cellPath());
    s.replace("typed");
    s.leaveCell();
    var stored = (Table) Notes.all(s.document()).get(0).blocks.get(table);
    assertEquals("typed", stored.blocks(1, 1).get(0) instanceof Paragraph p ? p.text.toString() : "");
    // Entering it again from another cell of the same note.
    s.enterNoteCell(note.id, new int[] {table, 0, 0});
    s.enterNoteCell(note.id, hit.noteCell);
    assertArrayEquals(hit.noteCell, s.cellPath());
    s.leaveCell();
  }

  @Test
  public void footnoteTableCellIsEnteredFromItsLines() {
    entersNoteCells("footnote");
  }

  @Test
  public void endnoteTableCellIsEnteredFromItsLines() {
    entersNoteCells("endnote");
  }

  private static int pageOf(LayoutEngine.Result r, String paragraphId) {
    for (LayoutEngine.Page p : r.pages)
      for (LayoutEngine.Line l : p.allLines()) if (paragraphId.equals(l.paragraphId)) return p.number;
    return -1;
  }

  private static String fieldResult(Document d) {
    for (InlineField f : d.paragraph(0).text.fields())
      if (f.instruction.startsWith("PAGEREF")) return f.result;
    return null;
  }

  @Test
  public void pageRefReachesCellAndNoteBookmarksOnTheirPages() {
    var s = new Session(Document.empty().page(new PageSpec(300, 160, 10, 10, 10, 10)));
    var text = new StringBuilder("Start");
    for (int i = 0; i < 14; i++) text.append("\nFiller ").append(i);
    s.replace(text.toString());
    s.insertTable(2, 2);
    int table = tableIndex(s.document());
    s.setCell(table, 1, 1, "cell target");
    s.enterCell(table, 1, 1);
    s.select(0, 5, 11);
    s.addBookmark("InCell");
    s.leaveCell();
    var cellId = ((Paragraph) ((Table) s.document().blocks.get(table)).blocks(1, 1).get(0)).id;
    int cellPage = pageOf(engine().layout(s.document(), 0), cellId);
    assertTrue("cell on a later page", cellPage > 1);
    s.select(0, 5, 5);
    CrossReferences.insert(s, "bookmark", "InCell", "page", engine());
    assertEquals(Integer.toString(cellPage), fieldResult(s.document()));

    // A footnote anchored on the last page: its bookmark's page is the note's page.
    int last = s.document().blocks.size() - 1;
    var lastPara = s.document().paragraph(last);
    s.select(last, lastPara.text.length(), lastPara.text.length());
    s.insertNote("footnote", "Deep note", "");
    var note = Notes.all(s.document()).get(0);
    s.enterNote(note.id);
    s.select(0, 5, 9);
    s.addBookmark("InNote");
    s.leaveCell();
    var layout = engine().layout(s.document(), 0);
    int notePage = pageOf(layout, Notes.all(s.document()).get(0).paragraphs.get(0).id);
    assertTrue("note on a later page", notePage > 1);
    s.select(0, 0, 0);
    CrossReferences.insert(s, "bookmark", "InNote", "page", engine());
    String first = null;
    for (InlineField f : s.document().paragraph(0).text.fields())
      if (f.instruction.startsWith("PAGEREF InNote")) first = f.result;
    assertEquals(Integer.toString(notePage), first);
  }

  @Test
  public void atLeastRowsAddCellMargins() {
    var s = new Session(Document.empty());
    s.insertTable(2, 1);
    int index = tableIndex(s.document());
    var t = (Table) s.document().blocks.get(index);
    float pad = t.format.cell(0, 0).verticalPadding;
    assertTrue(pad > 0);
    var c = t.format.cell(0, 0);
    float border = c.side(TableFormat.Cell.TOP) ? c.borderWidth : 0;
    assertTrue(border > 0);
    s.formatTable(index, t.format.minHeight(0, 30).minHeight(1, 30));
    var page = engine().layout(s.document(), 0).pages.get(0);
    float height = -1, last = -1;
    for (LayoutEngine.Box b : page.boxes) {
      if (b.row == 0) height = b.height;
      if (b.row == 1) last = b.height;
    }
    // Wordcraft's insets: the margins and the top border (the last row's bottom border too).
    assertEquals(30 + 2 * pad + border, height, .01f);
    assertEquals(30 + 2 * pad + 2 * border, last, .01f);
  }

  @Test
  public void legacyDocumentsPlaceTablesByTheirCellText() throws Exception {
    var s = new Session(Document.empty());
    s.insertTable(1, 1);
    var bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    var current = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals("", current.properties.setting(Docx.COMPAT_SETTING, ""));
    var legacy = current.properties(current.properties.withSetting(Docx.COMPAT_SETTING, "12"));
    float pad = ((Table) current.blocks.get(tableIndex(current))).format.cell(0, 0).horizontalPadding;
    float x0 = engine().layout(current, 0).pages.get(0).boxes.get(0).x;
    float x1 = engine().layout(legacy, 0).pages.get(0).boxes.get(0).x;
    assertEquals(x0 - pad, x1, .01f);
    // The mode survives DOCX and native round trips.
    bytes = new ByteArrayOutputStream();
    Docx.write(legacy, bytes);
    var back = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals("12", back.properties.setting(Docx.COMPAT_SETTING, ""));
    assertEquals(
        "12", DocumentXml.read(DocumentXml.write(legacy)).properties.setting(Docx.COMPAT_SETTING, ""));
  }
}
