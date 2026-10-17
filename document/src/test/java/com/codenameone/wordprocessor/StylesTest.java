package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class StylesTest {
  private ParagraphStyle style(String name, float size) {
    return new ParagraphStyle(
        name,
        name,
        new Paragraph("style", new RichText()).with("after", "10"),
        TextStyle.DEFAULT.with("size", "" + size));
  }

  @Test
  public void applyingUpdatingAndDeletingPreserveDirectFormattingAndUndo() {
    Session s = new Session(Document.empty());
    s.replace("Alpha bold\nBeta");
    s.defineStyle(style("Custom", 18));
    s.selectRange(0, 0, 1, 4);
    s.applyStyle("Custom");
    s.select(0, 6, 10);
    s.format("bold", "true");
    s.select(0, 0, 0);
    s.paragraph("after", "24");
    Document before = s.document();
    s.defineStyle(style("Custom", 24));
    assertEquals(24, s.document().paragraph(0).text.styleAt(0).size, 0);
    assertTrue(s.document().paragraph(0).text.styleAt(8).bold);
    assertEquals(24, s.document().paragraph(0).after, 0);
    assertEquals(10, s.document().paragraph(1).after, 0);
    s.undo();
    assertSame(before, s.document());
    s.redo();
    s.deleteStyle("Custom");
    assertFalse(s.document().styles.containsKey("Custom"));
    assertEquals("Normal", s.document().paragraph(0).style);
    assertEquals(24, s.document().paragraph(0).text.styleAt(0).size, 0);
    s.undo();
    assertTrue(s.document().styles.containsKey("Custom"));
  }

  @Test
  public void cellStyleUpdatesUseGlobalDefinitionsAndUndoRestoresCellSelection() {
    Session s = new Session(Document.empty());
    s.defineStyle(style("Custom", 18));
    s.replace("Body");
    s.applyStyle("Custom");
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    s.replace("Cell");
    s.applyStyle("Custom");
    s.select(0, 2, 2);
    s.defineStyle(style("Custom", 22));
    assertTrue(s.inCell());
    assertEquals(2, s.selection().caret);
    assertEquals(22, s.editingDocument().paragraph(0).text.styleAt(0).size, 0);
    assertEquals(22, s.document().paragraph(0).text.styleAt(0).size, 0);
    s.undo();
    assertTrue(s.inCell());
    assertEquals(18, s.editingDocument().paragraph(0).text.styleAt(0).size, 0);
    assertEquals(18, s.document().styles.get("Custom").text.size, 0);
    s.redo();
    assertEquals(22, s.document().styles.get("Custom").text.size, 0);
  }

  @Test
  public void emptyParagraphStyleControlsNewTypingAndPageChangesKeepDefinitions() {
    Session s = new Session(Document.empty());
    s.defineStyle(style("Custom", 20));
    s.applyStyle("Custom");
    s.replace("Typed");
    assertEquals(20, s.document().paragraph(0).text.styleAt(0).size, 0);
    assertSame(
        s.document().styles,
        s.document().title("Renamed").page(PageSpec.A4).comments(s.document().comments).styles);
    try {
      s.deleteStyle("Normal");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
