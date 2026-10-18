package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class CaseConversionTest {
  private Session session(String text) {
    var s = new Session(Document.empty());
    s.replace(text);
    s.select(0, 0, text.length());
    return s;
  }

  @Test
  public void deletedTextDoesNotConsumeSentenceCapitalization() {
    var s = session("OLD hELLO");
    s.trackChanges(true);
    s.select(0, 0, 4);
    s.replace("");
    s.select(0, 0, s.editingDocument().paragraph(0).text.length());
    s.changeCase("sentence");
    assertEquals("Hello", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("OLD hELLO", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void sentenceTitleAndToggleAcrossStyleBoundaries() {
    var s = session("hELLO WORLD. aGAIN!");
    s.select(0, 2, 9);
    s.format("bold", "true");
    s.select(0, 0, 18);
    s.changeCase("sentence");
    assertEquals("Hello world. Again!", s.document().plainText());
    assertTrue(s.document().paragraph(0).text.styleAt(2).bold);
    assertFalse(s.document().paragraph(0).text.styleAt(10).bold);
    s.changeCase("title");
    assertEquals("Hello World. Again!", s.document().plainText());
    s.changeCase("toggle");
    assertEquals("hELLO wORLD. aGAIN!", s.document().plainText());
    s.undo();
    assertEquals("Hello World. Again!", s.document().plainText());
  }

  @Test
  public void sentenceCaseInsideParagraphDoesNotCapitalizeFirstWord() {
    var s = session("prefix hELLO. wORLD");
    s.select(0, 7, 19);
    s.changeCase("sentence");
    assertEquals("prefix hello. World", s.document().plainText());
  }

  @Test
  public void collapsedCaretInsideWordAndCycleModes() {
    var s = session("some hello world");
    s.select(0, 7, 7);
    s.changeCase((String) null);
    assertEquals("some HELLO world", s.document().plainText());
    s.changeCase((String) null);
    assertEquals("some Hello world", s.document().plainText());
    s.changeCase((String) null);
    assertEquals("some hello world", s.document().plainText());
    s.select(0, 4, 4);
    var before = s.document();
    s.changeCase("upper");
    assertSame(before, s.document());
  }

  @Test
  public void unicodeExpansionAndSigmaKeepCommentOffsetsAndFormatting() {
    var s = session("Straße ΟΣ");
    s.select(0, 4, 5);
    s.format("bold", "true");
    s.addComment("Reviewer", "Expansion");
    s.select(0, 0, 9);
    s.changeCase("upper");
    assertEquals("STRASSE ΟΣ", s.document().plainText());
    assertEquals(4, s.document().comments.get(0).start);
    assertEquals(6, s.document().comments.get(0).end);
    assertTrue(s.document().paragraph(0).text.styleAt(5).bold);
    s.changeCase("lower");
    assertEquals("strasse ος", s.document().plainText());
  }

  @Test
  public void allModesRetainRejectionBaselineAndCellUndo() {
    for (String mode : new String[] {"sentence", "title", "toggle"}) {
      var s = session("mIXED text. hERE");
      s.trackChanges(true);
      s.select(0, 0, 16);
      s.changeCase(mode);
      assertEquals("mIXED text. hERE", Revisions.resolve(s.document(), null, false).plainText());
    }
    var s = new Session(Document.empty());
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    s.replace("cELL text");
    s.select(0, 0, 9);
    s.changeCase("title");
    assertEquals("Cell Text", s.editingDocument().plainText());
    s.undo();
    assertEquals("cELL text", s.editingDocument().plainText());
  }
}
