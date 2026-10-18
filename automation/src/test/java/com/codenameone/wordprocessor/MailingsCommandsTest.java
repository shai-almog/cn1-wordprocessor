package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class MailingsCommandsTest {
  @Test
  public void typedListsPreviewSearchAndFinishUseOneHistoryEntry() {
    var s = new Session(Document.empty());
    var c = new Commands();
    long revision = s.revision();
    c.execute(
        s,
        "mailings.recipients",
        Json.object(
            "rows",
            Arrays.asList(
                Json.object("Name", "Ada", "City", "London"),
                Json.object("Name", "Alan", "City", "Wilmslow"))));
    assertTrue(s.revision() > revision);
    assertEquals(Arrays.asList("Name", "City"), s.mergeData().fields);
    c.execute(s, "mailings.insertField", Json.object("field", "City"));
    var before = s.document();
    c.execute(s, "mailings.findRecipient", Json.object("text", "alan"));
    assertEquals("Wilmslow", s.document().plainText());
    s.undo();
    assertSame(before, s.document());
    assertFalse(s.mergeData().preview);
    c.execute(s, "mailings.start", Json.object("kind", "directory"));
    assertEquals(
        2, ((Number) ((Map<?, ?>) c.execute(s, "mailings.finish")).get("records")).intValue());
    assertEquals("London\nWilmslow", s.document().plainText());
    assertEquals(1, new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.size());
  }

  @Test
  public void invalidRowsDoNotReplaceRecipients() {
    var s = new Session(Document.empty());
    var c = new Commands();
    c.execute(s, "mailings.recipients", Json.object("csv", "Name\nAda"));
    var before = s.mergeData();
    for (Map<String, Object> args :
        Arrays.asList(
            Json.object(
                "fields",
                Arrays.asList("Name"),
                "rows",
                Arrays.asList(Arrays.asList("Ada", "Extra"))),
            Json.object("rows", Arrays.asList(Json.object("Name", Arrays.asList("Nested")))))) {
      try {
        c.execute(s, "mailings.recipients", args);
        fail();
      } catch (IllegalArgumentException expected) {
        assertSame(before, s.mergeData());
      }
    }
  }

  @Test
  public void previewCommitIncludesRecipientStateAndStaleChangesAreRejected() {
    var s = new Session(Document.empty());
    var draft = s.fork();
    var c = new Commands();
    c.execute(draft, "mailings.recipients", Json.object("csv", "Name\nAda"));
    s.applyPreview(draft, s.revision());
    assertEquals(1, s.mergeData().rows.size());
    s.undo();
    assertEquals(0, s.mergeData().rows.size());
    long version = s.revision();
    var stale = s.fork();
    c.execute(s, "mailings.start", Json.object("kind", "directory"));
    try {
      s.applyPreview(stale, version);
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("directory", s.mergeData().kind);
    }
  }

  @Test
  public void overflowingLabelsRejectAtomically() {
    var s = new Session(Document.empty());
    var before = s.document();
    var c = new Commands();
    try {
      c.execute(
          s,
          "mailings.labels",
          Json.object("product", "letter-4x20", "text", "One\nTwo\nThree\nFour\nFive"));
      fail();
    } catch (IllegalArgumentException expected) {
      assertSame(before, s.document());
    }
  }
}
