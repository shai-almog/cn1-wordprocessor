package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class LiveCommandsTest {
  @Test
  public void liveEditsShareUndoAndRejectStaleSelections() {
    var s = new Session(Document.empty());
    var live = new LiveCommands(new Commands());
    var before = live.stateId(s);
    live.execute(s, before, "text.insert", Json.object("text", "Hello"));
    assertEquals("Hello", s.document().plainText());
    try {
      live.execute(s, before, "text.insert", Json.object("text", "stale"));
      fail();
    } catch (IllegalStateException expected) {
    }
    var current = live.stateId(s);
    s.select(0, 0, 0);
    assertFalse(current.equals(live.stateId(s)));
    s.undo();
    assertEquals("", s.document().plainText());
  }

  @Test
  public void replacementSessionCannotReuseARevisionToken() {
    var live = new LiveCommands(new Commands());
    Session first = new Session(Document.empty()), second = new Session(Document.empty());
    var id = live.stateId(first);
    try {
      live.execute(second, id, "text.insert", Json.object("text", "wrong document"));
      fail();
    } catch (IllegalStateException expected) {
    }
    assertEquals("", second.document().plainText());
    assertEquals(live.stateId(second), live.stateId(second));
  }

  @Test
  public void validationFailureDoesNotChangeLiveDocument() {
    var s = new Session(Document.empty());
    var live = new LiveCommands(new Commands());
    var id = live.stateId(s);
    try {
      live.execute(s, id, "text.insert", Json.object("text", 42));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(id, live.stateId(s));
    assertEquals("", s.document().plainText());
  }
}
