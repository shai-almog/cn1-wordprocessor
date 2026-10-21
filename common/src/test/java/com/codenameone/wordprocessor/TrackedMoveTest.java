package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

/** Tracked moves, as Wordcraft cmd/moves.rs and the docx move_revisions_round_trip fixture. */
public class TrackedMoveTest {
  private static Session moved() {
    var s = new Session(Document.empty());
    s.replace("First one.\nSecond one.\nThird one.");
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(1, 0, 2, 0);
    String text = s.cut();
    s.select(0, 0, 0);
    s.pasteText(text);
    return s;
  }

  private static Set<String> names(Document d) {
    var out = new TreeSet<String>();
    for (Revisions.Change c : Revisions.list(d)) out.add(c.revision.move == null ? "-" : c.revision.move);
    return out;
  }

  @Test
  public void cutAndPasteRecordsAMoveAndEitherEndResolvesBoth() {
    var s = moved();
    assertEquals(Set.of("move1"), names(s.document()));
    boolean from = false, to = false;
    for (Revisions.Change c : Revisions.list(s.document())) {
      if (c.revision.inserted()) to = true;
      else from = true;
    }
    assertTrue(from && to);
    String toId = null;
    for (Revisions.Change c : Revisions.list(s.document())) if (c.revision.inserted()) toId = c.revision.id;
    s.resolveRevision(toId, true);
    assertTrue(Revisions.list(s.document()).isEmpty());
    assertEquals("Second one.\nFirst one.\nThird one.", s.document().plainText());
    var r = moved();
    String fromId = null;
    for (Revisions.Change c : Revisions.list(r.document())) if (!c.revision.inserted()) fromId = c.revision.id;
    r.resolveRevision(fromId, false);
    assertTrue(Revisions.list(r.document()).isEmpty());
    assertEquals("First one.\nSecond one.\nThird one.", r.document().plainText());
  }

  @Test
  public void shortCutIsNoMoveAndMarkupIsGreen() {
    var s = new Session(Document.empty());
    s.replace("alpha beta");
    s.trackChanges(true);
    s.select(0, 0, 5);
    String text = s.cut();
    s.select(0, 1, 1);
    s.pasteText(text);
    assertEquals(Set.of("-"), names(s.document()));
    var m = moved();
    for (Revisions.Change c : Revisions.list(m.document())) {
      var style = TextStyle.DEFAULT.revision(c.revision).markup();
      assertEquals(0x1a7f37, style.color);
    }
  }

  static byte[] part(byte[] docx, String name) throws IOException {
    var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(docx));
    for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; )
      if (e.getName().equals(name)) return zip.readAllBytes();
    throw new FileNotFoundException(name);
  }

  @Test
  public void docxReadsNamedRangesAndWritesThemBack() throws Exception {
    var s = moved();
    var bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    var d = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals(Set.of("move1"), names(d));
    var xml = new String(part(bytes.toByteArray(), "word/document.xml"), "UTF-8");
    assertTrue(xml.contains("<w:moveFromRangeStart") && xml.contains("<w:moveToRangeEnd"));
    assertFalse(xml.contains("<w:delText"));
    assertEquals(
        Revisions.resolve(s.document(), null, true).plainText(),
        Revisions.resolve(d, null, true).plainText());
  }
}
