package com.codenameone.wordprocessor;

import java.util.*;

/** EDT-confined adapter to the current editor session, with stale-state detection. */
public final class LiveCommands {
  private final Commands commands;
  private Session lastSession;
  private Document lastDocument;
  private String lastPosition;
  private long version;

  public LiveCommands(Commands commands) {
    this.commands = commands;
  }

  public String stateId(Session session) {
    Selection s = session.selection();
    String position =
        session.revision()
            + ":"
            + s.anchorBlock
            + ":"
            + s.anchor
            + ":"
            + s.block
            + ":"
            + s.caret
            + ":"
            + session.cellBlock()
            + ":"
            + session.cellRow()
            + ":"
            + session.cellColumn()
            + ":"
            + session.reviewAuthor()
            + ":"
            + session.trackFormatting()
            + ":"
            + (session.cellSelection() == null ? "" : session.cellSelection().key());
    Document doc = session.document();
    if (session != lastSession || doc != lastDocument || !position.equals(lastPosition)) {
      version++;
      lastSession = session;
      lastDocument = doc;
      lastPosition = position;
    }
    return Long.toString(version);
  }

  public Map<String, Object> snapshot(Session session) {
    Selection s = session.selection();
    return Json.object(
        "stateId",
        stateId(session),
        "document",
        commands.execute(session, "document.inspect", Collections.<String, Object>emptyMap()),
        "text",
        session.document().plainText(),
        "selection",
        Json.object(
            "anchorBlock", s.anchorBlock, "anchor", s.anchor, "block", s.block, "caret", s.caret),
        "cell",
        Json.object(
            "block", session.cellBlock(), "row", session.cellRow(), "column", session.cellColumn()),
        "cellSelection",
        cellSelection(session),
        "trackChanges",
        session.document().trackChanges,
        "trackFormatting",
        session.trackFormatting(),
        "reviewAuthor",
        session.reviewAuthor());
  }

  private Object cellSelection(Session session) {
    CellSelection x = session.cellSelection();
    return x == null
        ? null
        : Json.object(
            "block",
            x.block,
            "anchorRow",
            x.anchorRow,
            "anchorColumn",
            x.anchorColumn,
            "focusRow",
            x.focusRow,
            "focusColumn",
            x.focusColumn,
            "firstRow",
            x.firstRow,
            "lastRow",
            x.lastRow,
            "firstColumn",
            x.firstColumn,
            "lastColumn",
            x.lastColumn);
  }

  public Map<String, Object> execute(
      Session session, String expected, String command, Map<String, Object> args) {
    if (!stateId(session).equals(expected))
      throw new IllegalStateException(
          "Document or selection changed; read word_snapshot and retry with its stateId");
    Object result = commands.execute(session, command, args);
    return Json.object("result", result, "state", snapshot(session));
  }
}
