package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;

public class WordAliasCommandsTest {
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
  public void sortParagraphsAlphabeticallyAndNumericallyAsOneUndoStep() {
    Session s = doc("pear\napple\nfig");
    run(s, "para.sort");
    assertEquals("apple\nfig\npear", s.document().plainText().trim());
    run(s, "para.sort", "descending", true);
    assertEquals("pear\nfig\napple", s.document().plainText().trim());
    Session n = doc("10 b\n9 a\n100 c");
    run(n, "para.sort", "numeric", true);
    assertEquals("9 a\n10 b\n100 c", n.document().plainText().trim());
    run(n, "edit.undo");
    assertEquals("10 b\n9 a\n100 c", n.document().plainText().trim());
  }

  @Test
  public void goToHeadingsBookmarksAndPages() {
    Session s = doc("One\nBody\nTwo");
    s.select(0, 0, 0);
    s.applyStyle("Heading1");
    s.select(2, 0, 0);
    s.applyStyle("Heading1");
    s.select(1, 0, 0);
    run(s, "insert.bookmark", "name", "mid");
    assertEquals(2, run(s, "edit.goto", "what", "heading", "target", 2));
    assertEquals(2, s.selection().block);
    run(s, "edit.goto", "what", "bookmark", "target", "mid");
    assertEquals(1, s.selection().block);
    assertEquals(1, run(s, "edit.goto", "what", "page", "target", 1));
    assertEquals(0, s.selection().block);
    try {
      run(s, "edit.goto", "what", "table");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void wordCountInfoDistributeAndPageSetup() {
    Session s = doc("Hello brave world\nAgain");
    Map<String, Object> wc = (Map<String, Object>) run(s, "review.wordCount");
    assertEquals(4, wc.get("words"));
    assertEquals(2, wc.get("paragraphs"));
    assertEquals(1, wc.get("pages"));
    assertEquals(22, wc.get("characters"));
    assertEquals(20, wc.get("charactersNoSpaces"));
    assertEquals(4, ((Map<String, Object>) run(s, "file.info")).get("words"));
    run(s, "para.distribute");
    assertEquals("distribute", s.document().paragraph(s.selection().block).align);
    run(s, "layout.orientation", "value", "landscape");
    PageSpec p = s.activeSection().raw.page;
    assertTrue(p.width > p.height);
    run(s, "layout.margins", "preset", "narrow");
    assertEquals(36f, s.activeSection().raw.page.left, 0.01);
  }

  @Test
  public void commentActionsUseTheCommentAtTheSelection() {
    Session s = doc("Needs review");
    s.select(0, 0, 5);
    run(s, "comment.add", "author", "A", "text", "Why?");
    s.select(0, 2, 2);
    run(s, "review.reply", "text", "Because");
    assertEquals(1, s.document().comments.get(0).replies.size());
    run(s, "review.resolveComment");
    assertTrue(s.document().comments.get(0).resolved);
    run(s, "review.deleteComment");
    assertTrue(s.document().comments.isEmpty());
  }

  @Test
  public void linkFieldAndEquationStructure() {
    Session s = doc("site");
    s.select(0, 0, 4);
    run(s, "insert.link", "url", "https://example.com");
    assertEquals("https://example.com", s.document().paragraph(0).text.styleAt(1).link);
    s.select(0, 4, 4);
    run(s, "insert.field", "instruction", "PAGE");
    assertTrue(DocumentXml.write(s.document()).contains("PAGE"));
    run(s, "equation.insertStructure", "kind", "fraction");
    boolean found = false;
    for (int i = 0; i < s.document().blocks.size(); i++) {
      Block b = s.document().blocks.get(i);
      if (b instanceof Picture && ((Picture) b).drawing != null && "equation".equals(((Picture) b).drawing.kind)) found = true;
    }
    assertTrue(found);
  }
}
