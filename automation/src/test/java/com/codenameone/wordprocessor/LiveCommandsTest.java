package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class LiveCommandsTest {
  @Test
  public void liveEditsShareUndoAndRejectStaleSelections() {
    Session s = new Session(Document.empty());
    LiveCommands live = new LiveCommands(new Commands());
    String before = live.stateId(s);
    live.execute(s, before, "text.insert", Json.object("text", "Hello"));
    assertEquals("Hello", s.document().plainText());
    try {
      live.execute(s, before, "text.insert", Json.object("text", "stale"));
      fail();
    } catch (IllegalStateException expected) {
    }
    String current = live.stateId(s);
    s.select(0, 0, 0);
    assertFalse(current.equals(live.stateId(s)));
    s.undo();
    assertEquals("", s.document().plainText());
  }

  @Test
  public void replacementSessionCannotReuseARevisionToken() {
    LiveCommands live = new LiveCommands(new Commands());
    Session first = new Session(Document.empty()), second = new Session(Document.empty());
    String id = live.stateId(first);
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
    Session s = new Session(Document.empty());
    LiveCommands live = new LiveCommands(new Commands());
    String id = live.stateId(s);
    try {
      live.execute(s, id, "text.insert", Json.object("text", 42));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(id, live.stateId(s));
    assertEquals("", s.document().plainText());
  }
}
