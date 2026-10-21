package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

/** Tracked table property changes (w:tblPrChange/w:trPrChange/w:tcPrChange, Wordcraft fmt_change). */
public class TablePropertyRevisionTest {
  private Session tracked() {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(2, 2);
    s.trackChanges(true);
    var t = (Table) s.document().blocks.get(1);
    var f = t.format.cell(1, 1, t.format.cell(1, 1).background(0xff0000))
        .row(0, new TableFormat.Row(30, "exact", false)).withProp("rtl", "true");
    s.formatTable(1, f);
    return s;
  }

  private static Table table(Document d) {
    for (Block b : d.blocks) if (b instanceof Table t) return t;
    throw new AssertionError("no table");
  }

  private static void verifyTracked(Document d) {
    var f = table(d).format;
    assertNotNull(f.revision());
    assertEquals("format", f.revision().kind);
    assertEquals(0xff0000, f.cell(1, 1).background);
    assertEquals(0xffffff, f.previous().cell(1, 1).background);
    assertEquals(30, f.minHeight(0), .01);
    assertEquals(0, f.previous().minHeight(0), .01);
    assertEquals("true", f.prop("rtl", ""));
    assertEquals("", f.previous().prop("rtl", ""));
    boolean listed = false;
    for (Revisions.Change c : Revisions.list(d)) listed |= "Table properties".equals(c.text);
    assertTrue(listed);
  }

  @Test public void editsAreTrackedAndResolved() {
    var s = tracked();
    var d = s.document();
    verifyTracked(d);
    var id = table(d).format.revision().id;
    var rejected = Revisions.resolve(d, id, false);
    assertNull(table(rejected).format.revision());
    assertEquals(0xffffff, table(rejected).format.cell(1, 1).background);
    assertFalse(table(rejected).format.row(0).rule.equals("exact"));
    var accepted = Revisions.resolve(d, id, true);
    assertNull(table(accepted).format.revision());
    assertEquals(0xff0000, table(accepted).format.cell(1, 1).background);
  }

  @Test public void untrackedEditsAreNotRecorded() {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(2, 2);
    var t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.withProp("rtl", "true"));
    assertNull(table(s.document()).format.revision());
  }

  @Test public void docxRoundTrip() throws Exception {
    var bytes = new ByteArrayOutputStream();
    Docx.write(tracked().document(), bytes);
    String xml = new String(bytes.toByteArray(), "ISO-8859-1");
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    verifyTracked(opened.document);
  }

  @Test public void nativeRoundTrip() throws Exception {
    String xml = DocumentXml.write(tracked().document());
    var d = DocumentXml.read(xml);
    assertEquals(xml, DocumentXml.write(d));
    verifyTracked(d);
  }

  /** Wordcraft keeps one fmt_change per table, row and cell: separate edits stay separate. */
  private Session twoEdits() {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(2, 2);
    s.trackChanges(true);
    var t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.cell(1, 1, t.format.cell(1, 1).background(0xff0000)));
    t = (Table) s.document().blocks.get(1);
    s.formatTable(1, t.format.row(0, new TableFormat.Row(30, "exact", false)));
    return s;
  }

  @Test public void perElementRevisions() {
    var d = twoEdits().document();
    var f = table(d).format;
    assertNull(f.tableRevision());
    assertNotNull(f.cellRevision(1, 1));
    assertNotNull(f.rowRevision(0));
    assertNull(f.cellRevision(0, 0));
    assertNotEquals(f.cellRevision(1, 1).id, f.rowRevision(0).id);
    assertEquals(2, f.revisions().size());
    int listed = 0;
    for (Revisions.Change c : Revisions.list(d))
      if (c.text.endsWith("properties")) listed++;
    assertEquals(2, listed);
    // Rejecting the cell's change restores only that cell.
    var rejected = table(Revisions.resolve(d, f.cellRevision(1, 1).id, false)).format;
    assertEquals(0xffffff, rejected.cell(1, 1).background);
    assertEquals(30, rejected.minHeight(0), .01);
    assertNotNull(rejected.rowRevision(0));
    assertNull(rejected.cellRevision(1, 1));
  }

  @Test public void perElementRoundTrips() throws Exception {
    var d = twoEdits().document();
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var f = table(Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document).format;
    assertEquals(2, f.revisions().size());
    assertNotNull(f.rowRevision(0));
    assertNotNull(f.cellRevision(1, 1));
    assertNull(f.tableRevision());
    var n = table(DocumentXml.read(DocumentXml.write(d))).format;
    assertEquals(2, n.revisions().size());
    assertEquals(f.rowRevision(0).id, n.rowRevision(0).id);
  }

  private static Table firstTable(Sequence<Block> blocks) {
    for (Block b : blocks) if (b instanceof Table t) return t;
    return null;
  }

  private static int index(Sequence<Block> blocks) {
    for (int i = 0; i < blocks.size(); i++) if (blocks.get(i) instanceof Table) return i;
    return -1;
  }

  @Test public void noteTableEditsAreTracked() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Body ");
    s.insertNote("footnote", "Note text", "");
    String id = Notes.all(s.document()).get(0).id;
    s.enterNote(id);
    s.insertTable(2, 2);
    s.leaveCell();
    s.trackChanges(true);
    var blocks = Notes.all(s.document()).get(0).blocks;
    int at = index(blocks);
    var t = firstTable(blocks);
    s.enterNoteCell(id, new int[] {at, 0, 0});
    s.formatTable(s.cellBlock(), t.format.withProp("rtl", "true"));
    assertEquals(id, s.enclosingNote());
    s.leaveCell();
    var d = s.document();
    var f = firstTable(Notes.all(d).get(0).blocks).format;
    assertNotNull(f.revision());
    assertEquals("", f.previous().prop("rtl", ""));
    boolean listed = false;
    for (Revisions.Change c : Revisions.list(d)) listed |= "Table properties".equals(c.text) && c.story.startsWith("note:");
    assertTrue(listed);
    var rejected = Revisions.resolve(d, f.revision().id, false);
    var g = firstTable(Notes.all(rejected).get(0).blocks).format;
    assertNull(g.revision());
    assertEquals("", g.prop("rtl", ""));
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    var h = firstTable(Notes.all(opened).get(0).blocks).format;
    assertNotNull(h.revision());
    assertEquals("true", h.prop("rtl", ""));
    assertEquals("", h.previous().prop("rtl", ""));
  }

  @Test public void nestedTableEditsAreTracked() throws Exception {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(2, 2);
    s.enterCell(1, 0, 0);
    s.insertTable(2, 2);
    s.leaveCell();
    s.trackChanges(true);
    s.enterCell(1, 0, 0);
    int at = index(s.document().blocks);
    var inner = firstTable(s.document().blocks);
    s.enterNestedCell(at, 0, 0);
    s.formatTable(s.cellBlock(), inner.format.cell(0, 0, inner.format.cell(0, 0).background(0x00ff00)));
    s.leaveCell();
    var outer = table(s.document());
    assertNull(outer.format.revision());
    var nested = firstTable(outer.blocks(0, 0));
    assertNotNull(nested.format.revision());
    assertEquals(0x00ff00, nested.format.cell(0, 0).background);
    assertEquals(0xffffff, nested.format.previous().cell(0, 0).background);
    var accepted = Revisions.resolve(s.document(), nested.format.revision().id, true);
    var done = firstTable(table(accepted).blocks(0, 0));
    assertNull(done.format.revision());
    assertEquals(0x00ff00, done.format.cell(0, 0).background);
    var bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertNotNull(firstTable(table(opened).blocks(0, 0)).format.revision());
  }
}
