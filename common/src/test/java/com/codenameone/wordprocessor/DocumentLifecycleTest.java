package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class DocumentLifecycleTest {
  @Test
  public void loadingAnotherDocumentDropsTheOldOnesPasswordAndHistory() {
    var s = new Session(Document.empty());
    OfficeCommands.password(s, "secret");
    var appWide = new SessionMap<String>();
    appWide.put(s, "kept");
    s.loadDocument(Document.empty().title("B"));
    assertNull("a password belongs to the document it protected", OfficeCommands.password(s));
    assertEquals("application state survives New/Open", "kept", appWide.get(s));
  }
}
