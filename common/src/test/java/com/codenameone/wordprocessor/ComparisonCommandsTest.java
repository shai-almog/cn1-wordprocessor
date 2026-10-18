package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ComparisonCommandsTest {
  @Test
  public void commandUndoRedoAndDocxRetainBothVersions() throws Exception {
    var s = new Session(Document.empty());
    s.replace("The cat sat.\nKeep this.");
    var before = s.document();
    var c = new Commands();
    var revised = "The dog sat.\nKeep this.\nNew line here.";
    Map<?, ?> result =
        (Map<?, ?>)
            c.execute(s, "review.compare", Json.object("text", revised, "author", "Editor"));
    assertTrue(((Number) result.get("changes")).intValue() > 0);
    var compared = s.document();
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertSame(compared, s.document());
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(compared, null, out, true);
    var imported = Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document;
    assertEquals(revised, Revisions.resolve(imported, null, true).plainText());
    assertEquals(before.plainText(), Revisions.resolve(imported, null, false).plainText());
    assertEquals(
        DocumentXml.write(compared),
        DocumentXml.write(DocumentXml.read(DocumentXml.write(compared))));
  }

  @Test
  public void tableCellComparisonRetainsGeometryAndBothTexts() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Heading");
    s.insertTable(2, 2);
    s.enterCell(1, 0, 0);
    s.replace("Old cell");
    s.leaveCell();
    var before = s.document();
    var revised = new Session(before);
    revised.enterCell(1, 0, 0);
    revised.select(0, 0, 8);
    revised.replace("Revised cell\nSecond paragraph");
    revised.leaveCell();
    var after = revised.document();
    var c = new Commands();
    c.execute(s, "review.compare", Json.object("xml", DocumentXml.write(after)));
    var compared = s.document();
    assertEquals(before.plainText(), Revisions.resolve(compared, null, false).plainText());
    assertEquals(after.plainText(), Revisions.resolve(compared, null, true).plainText());
    var bytes = new java.io.ByteArrayOutputStream();
    Docx.write(compared, null, bytes, true);
    var imported = Docx.read(new java.io.ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals(after.plainText(), Revisions.resolve(imported, null, true).plainText());
    assertEquals(before.plainText(), Revisions.resolve(imported, null, false).plainText());
    var ids = new java.util.HashSet<String>();
    for (Revisions.Change change : Revisions.list(compared)) ids.add(change.revision.id);
    assertTrue(ids.size() >= 2);
    s.undo();
    assertSame(before, s.document());
  }

  @Test
  public void failureAndCancelledPreviewPreserveOriginal() {
    var s = new Session(Document.empty());
    s.replace("Original");
    var before = s.document();
    var c = new Commands();
    for (Map<String, Object> args :
        Arrays.asList(
            Json.object("text", "new", "level", "line"),
            Json.object("text", "new", "xml", "bad"),
            Json.object("xml", "<bad/>"))) {
      try {
        c.execute(s, "review.compare", args);
        fail();
      } catch (IllegalArgumentException expected) {
        assertSame(before, s.document());
      }
    }
    var draft = s.fork();
    c.execute(draft, "review.compare", Json.object("text", "Revised"));
    assertSame(before, s.document());
    s.applyPreview(draft, s.revision());
    assertEquals("Revised", Revisions.resolve(s.document(), null, true).plainText());
    s.undo();
    assertSame(before, s.document());
  }
}
