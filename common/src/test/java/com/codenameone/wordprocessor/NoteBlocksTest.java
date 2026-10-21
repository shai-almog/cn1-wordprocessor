package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

/** Footnote/endnote stories hold tables and pictures, as Wordcraft's note parts do. */
public class NoteBlocksTest {
  private static Session noteWithBlocks(String kind) {
    var s = new Session(Document.empty());
    s.replace("Body ");
    s.insertNote(kind, "Note text", "");
    s.enterNote(Notes.all(s.document()).get(0).id);
    s.insertTable(2, 2);
    s.leaveCellLevel();
    assertNotNull(s.noteStory());
    s.insertPicture(OdtTest.PNG, 40, 20, "Logo");
    s.leaveCell();
    return s;
  }

  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String s, TextStyle t) {
            return s.length() * 6;
          }

          public float height(TextStyle t) {
            return t.size;
          }
        });
  }

  private static int count(Note note, Class<?> type) {
    int n = 0;
    for (Block b : note.blocks) if (type.isInstance(b)) n++;
    return n;
  }

  @Test
  public void tablesAndPicturesAreInsertedInANoteStory() {
    var note = Notes.all(noteWithBlocks("footnote").document()).get(0);
    assertTrue(note.hasBlocks());
    assertEquals(1, count(note, Table.class));
    assertEquals(1, count(note, Picture.class));
    assertTrue(note.plainText().contains("Note text"));
  }

  @Test
  public void noteBlocksRoundTripNativeAndDocx() throws Exception {
    var d = noteWithBlocks("footnote").document();
    var xml = DocumentXml.write(d);
    var back = Notes.all(DocumentXml.read(xml)).get(0);
    assertEquals(1, count(back, Table.class));
    assertEquals(1, count(back, Picture.class));
    assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    var docx = Notes.all(opened.document).get(0);
    assertEquals(1, count(docx, Table.class));
    assertEquals(1, count(docx, Picture.class));
    assertTrue(docx.plainText().contains("Note text"));
    for (String w : opened.warnings) assertFalse(w, w.startsWith("Note content"));
  }

  @Test
  public void footnoteTablesAndPicturesAreLaidOutInTheNoteArea() {
    var engine = engine();
    var page = engine.layout(noteWithBlocks("footnote").document(), 0).pages.get(0);
    float noteTop = Float.MAX_VALUE;
    for (LayoutEngine.Line l : page.notes) noteTop = Math.min(noteTop, l.y);
    assertFalse(page.notes.isEmpty());
    assertFalse(page.boxes.isEmpty());
    for (LayoutEngine.Box b : page.boxes) assertTrue(b.y >= noteTop - .01f);
    assertEquals(1, page.pictures.size());
    assertTrue(page.pictures.get(0).y() >= noteTop);
  }

  @Test
  public void endnoteBlocksFlowAfterTheBody() {
    var engine = engine();
    var page = engine.layout(noteWithBlocks("endnote").document(), 0).pages.get(0);
    assertFalse(page.boxes.isEmpty());
    assertEquals(1, page.pictures.size());
  }

  @Test
  public void rejectingANoteInsertionKeepsItsTable() {
    var s = new Session(Document.empty());
    s.replace("Body ");
    s.insertNote("footnote", "Note text", "");
    s.enterNote(Notes.all(s.document()).get(0).id);
    s.insertTable(1, 1);
    s.leaveCell();
    s.trackChanges(true);
    s.enterNote(Notes.all(s.document()).get(0).id);
    s.replace("x");
    s.leaveCell();
    var d = Revisions.resolve(s.document(), null, false);
    assertEquals(1, count(Notes.all(d).get(0), Table.class));
  }
}
