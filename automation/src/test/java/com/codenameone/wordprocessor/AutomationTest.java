package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class AutomationTest {
  @Test
  public void fieldCommandAndInvalidInstructionRollback() {
    var s = new Session(Document.empty());
    var c = new Commands();
    c.execute(s, "field.insert", Json.object("instruction", "TITLE", "result", "Report"));
    assertEquals("Report", s.document().plainText());
    var before = s.document();
    try {
      c.execute(s, "field.insert", Json.object("instruction", ""));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    s.undo();
    assertEquals("", s.document().plainText());
  }

  @Test
  public void findAndReplaceReachRichMergedCellParagraphsWithOneUndo() {
    var c = new Commands();
    var s = new Session(Document.empty());
    s.replace("needle body");
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "needle first\nneedle second");
    s.setCell(1, 0, 1, "needle last");
    s.mergeCells(1, 0, 0, 1, 2);
    var before = s.document();
    var matches = Commands.find(before, "needle");
    assertEquals(4, matches.size());
    Commands.selectMatch(s, matches.get(2));
    assertTrue(s.inCell());
    assertEquals(1, s.selection().block);
    assertEquals(
        4,
        c.execute(s, "replace.all", Json.object("text", "needle", "replacement", "replacement")));
    assertFalse(s.document().plainText().contains("needle"));
    assertEquals(4, Commands.find(s.document(), "replacement").size());
    s.undo();
    assertEquals(before.plainText(), s.document().plainText());
    assertTrue(s.inCell());
    assertEquals(1, s.selection().block);
  }

  @Test
  public void previewIsIsolatedAndUndoable() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Dear {{name}},");
    var c = new Commands();
    var macro =
        Json.parse(
            "{\"version\":1,\"steps\":[{\"command\":\"template.fill\",\"args\":{\"values\":{\"name\":\"${recipient}\"}}}]}");
    var p =
        new Macros(c).preview(s, macro, Collections.singletonMap("recipient", "Ada"), null);
    assertEquals("Dear {{name}},", s.document().plainText());
    assertEquals("Dear Ada,", p.after);
    p.apply(s);
    assertEquals(p.after, s.document().plainText());
    s.undo();
    assertEquals(p.before, s.document().plainText());
    try {
      p.apply(s);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void cancelledMacroRollsBack() throws Exception {
    var s = new Session(Document.empty());
    var c = new Commands();
    var macro =
        Json.parse(
            "{\"version\":1,\"steps\":[{\"command\":\"text.insert\",\"args\":{\"text\":\"first\"}},{\"command\":\"text.insert\",\"args\":{\"text\":\"second\"}}]}");
    final int[] count = {0};
    try {
      new Macros(c).run(s, macro, Collections.<String, String>emptyMap(), () -> ++count[0] > 1);
      fail();
    } catch (IllegalStateException expected) {
    }
    assertEquals("", s.document().plainText());
    assertFalse(s.canUndo());
  }

  @Test
  public void unknownArgumentsAndCommandsRejected() {
    var c = new Commands();
    var s = new Session(Document.empty());
    try {
      c.execute(s, "text.insert", Json.object("text", "x", "extra", true));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    try {
      c.execute(s, "missing");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals("", s.document().plainText());
  }

  @Test
  public void replacementIsOneUndo() {
    var s = new Session(Document.empty());
    s.replace("a a\na a");
    var c = new Commands();
    assertEquals(4, c.execute(s, "replace.all", Json.object("text", "a", "replacement", "long")));
    assertEquals("long long\nlong long", s.document().plainText());
    s.undo();
    assertEquals("a a\na a", s.document().plainText());
  }

  @Test
  public void templateDoesNotRecursivelyExpandValues() {
    var s = new Session(Document.empty());
    s.replace("{{a}} {{b}}");
    new Commands()
        .execute(s, "template.fill", Json.object("values", Json.object("a", "{{b}}", "b", "B")));
    assertEquals("{{b}} B", s.document().plainText());
  }

  @Test
  public void jsonEscapesControlsAndUnicode() throws Exception {
    var value = "line\n\t\"\\ 😀";
    assertEquals(value, Json.parse(Json.write(Json.object("value", value))).get("value"));
  }

  @Test
  public void formattingOnlyPreviewIsVisible() throws Exception {
    var s = new Session(Document.empty());
    s.replace("words");
    var macro =
        Json.object(
            "version",
            1,
            "steps",
            Arrays.asList(
                Json.object(
                    "command",
                    "selection.set",
                    "args",
                    Json.object("block", 0, "start", 0, "end", 5)),
                Json.object(
                    "command",
                    "format.set",
                    "args",
                    Json.object("property", "bold", "value", "true"))));
    var p =
        new Macros(new Commands()).preview(s, macro, Collections.<String, String>emptyMap(), null);
    assertEquals(p.before, p.after);
    assertTrue(p.changes.get(0).contains("formatting changed"));
  }
}
