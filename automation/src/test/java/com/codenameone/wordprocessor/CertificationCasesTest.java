package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Direct calls for commands no other test exercises, so ReplayRecorder captures a case for each
 * that scripts/native-certify.py replays in the native app.
 */
public class CertificationCasesTest {
  private final Commands c = new Commands();

  private Object run(Session s, String id, Object... kv) {
    return c.execute(s, id, Json.object(kv));
  }

  private static Session doc(String text) {
    Session s = new Session(Document.empty());
    s.replace(text);
    return s;
  }

  @Test
  public void referenceTablesUpdateAndRemove() {
    Session s = doc("Intro");
    s.applyStyle("Heading1");
    run(s, "references.toc");
    String with = s.document().plainText();
    run(s, "references.updateToc");
    run(s, "references.removeToc");
    assertNotEquals(with, s.document().plainText());
    Session f = doc("Chart");
    run(f, "references.caption", "label", "Figure", "text", "Sales");
    run(f, "references.tableOfFigures", "label", "Figure");
    run(f, "references.updateFigures");
    assertTrue(f.document().plainText().contains("Sales"));
    Session a = doc("Smith v. Jones");
    a.select(0, 0, 14);
    run(a, "references.markCitation", "entry", "Smith v. Jones");
    run(a, "references.tableOfAuthorities");
    run(a, "references.updateAuthorities");
    assertTrue(a.document().plainText().contains("Smith"));
  }

  @Test
  public void stylesDelete() {
    Session s = doc("Body");
    run(s, "styles.create", "id", "Callout", "name", "Callout");
    run(s, "styles.delete", "id", "Callout");
    assertNull(s.document().styles.get("Callout"));
  }

  @Test
  public void mailingsPrevious() {
    Session s = doc("Dear ");
    run(s, "mailings.recipients", "csv", "Name\nAda\nGrace");
    run(s, "mailings.next");
    run(s, "mailings.previous");
  }

  @Test
  public void pasteFromOfficeClipboard() {
    Session s = doc("alpha beta");
    s.select(0, 0, 5);
    run(s, "edit.copy");
    s.select(0, 10, 10);
    run(s, "edit.pasteMerge", "index", 0);
    assertEquals("alpha betaalpha", s.document().plainText().trim());
  }

  @Test
  public void headerNavigation() {
    Session s = doc("Body");
    run(s, "insert.header", "text", "One");
    s.sectionBreak("nextPage");
    run(s, "layout.linkToPrevious", "story", "header");
    run(s, "hf.previous");
    run(s, "hf.next");
  }

  @Test
  public void positionAndSelectObjects() {
    Session s = doc("Text");
    Object r = run(s, "insert.shape", "shape", "ellipse");
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Picture) block = i;
    assertEquals("right", run(s, "arrange.position", "block", block, "position", "middleRight"));
    assertEquals("square", ((Picture) s.document().blocks.get(block)).drawing.get("wrap", ""));
    s.select(0, 0, 0);
    run(s, "draw.select", "block", block);
    assertEquals(block + 1, s.selection().block);
    assertEquals("square", run(s, "arrange.wrap", "wrap", "square"));
  }
}
