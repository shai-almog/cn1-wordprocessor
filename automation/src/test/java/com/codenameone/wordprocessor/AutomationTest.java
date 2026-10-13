package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class AutomationTest {
  @Test
  public void previewIsIsolatedAndUndoable() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Dear {{name}},");
    Commands c = new Commands();
    Map<String, Object> macro =
        Json.parse(
            "{\"version\":1,\"steps\":[{\"command\":\"template.fill\",\"args\":{\"values\":{\"name\":\"${recipient}\"}}}]}");
    Macros.Preview p =
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
    Session s = new Session(Document.empty());
    Commands c = new Commands();
    Map<String, Object> macro =
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
    Commands c = new Commands();
    Session s = new Session(Document.empty());
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
    Session s = new Session(Document.empty());
    s.replace("a a\na a");
    Commands c = new Commands();
    assertEquals(4, c.execute(s, "replace.all", Json.object("text", "a", "replacement", "long")));
    assertEquals("long long\nlong long", s.document().plainText());
    s.undo();
    assertEquals("a a\na a", s.document().plainText());
  }

  @Test
  public void templateDoesNotRecursivelyExpandValues() {
    Session s = new Session(Document.empty());
    s.replace("{{a}} {{b}}");
    new Commands()
        .execute(s, "template.fill", Json.object("values", Json.object("a", "{{b}}", "b", "B")));
    assertEquals("{{b}} B", s.document().plainText());
  }

  @Test
  public void jsonEscapesControlsAndUnicode() throws Exception {
    String value = "line\n\t\"\\ 😀";
    assertEquals(value, Json.parse(Json.write(Json.object("value", value))).get("value"));
  }

  @Test
  public void formattingOnlyPreviewIsVisible() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("words");
    Map<String, Object> macro =
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
    Macros.Preview p =
        new Macros(new Commands()).preview(s, macro, Collections.<String, String>emptyMap(), null);
    assertEquals(p.before, p.after);
    assertTrue(p.changes.get(0).contains("formatting changed"));
  }
}
