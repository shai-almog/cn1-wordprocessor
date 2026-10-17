package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class FieldsTest {
  @Test
  public void atomicFieldSurvivesFormattingClipboardCaseAndUndo() {
    Session s = new Session(Document.empty());
    s.replace("a");
    InlineField field = new InlineField("TITLE", "Quarterly report", false, false);
    s.insertField(field);
    s.replace("z");
    assertEquals("a\ufffcz", s.document().paragraph(0).text.toString());
    assertEquals("aQuarterly reportz", s.document().plainText());
    s.select(0, 1, 2);
    s.format("bold", "true");
    assertSame(field, s.document().paragraph(0).text.fieldAt(1));
    assertTrue(s.document().paragraph(0).text.styleAt(1).bold);
    assertEquals("Quarterly report", s.selectedText());
    Document clipboard = s.copySelection();
    s.select(0, 3, 3);
    s.paste(clipboard);
    assertSame(field, s.document().paragraph(0).text.fieldAt(3));
    s.select(0, 0, 4);
    s.changeCase(true);
    assertEquals("AQuarterly reportZQuarterly report", s.document().plainText());
    s.select(0, 4, 4);
    s.deleteBackward();
    assertEquals(3, s.document().paragraph(0).text.length());
    s.undo();
    assertSame(field, s.document().paragraph(0).text.fieldAt(3));
  }

  @Test
  public void updatesPreserveCommentsCellSelectionAndOneUndo() {
    Session s = new Session(Document.empty());
    s.replace("AB");
    s.select(0, 1, 2);
    s.addComment("A", "B");
    s.select(0, 1, 1);
    s.insertField(new InlineField("PAGE", "1", false, false));
    assertEquals(2, s.document().comments.get(0).start);
    Document before = s.document();
    s.mapFields((id, at, f) -> f.result("1000"));
    assertEquals(2, s.document().comments.get(0).start);
    assertEquals(2, s.selection().caret);
    s.undo();
    assertSame(before, s.document());
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    s.insertField(new InlineField("TITLE", "Old", false, false));
    s.mapFields((id, at, f) -> f.result("New"));
    assertTrue(s.inCell());
    assertEquals(1, s.selection().caret);
    assertEquals("New", s.editingDocument().paragraph(0).text.plainText());
    s.changeField(0, new InlineField("TITLE", "Locked", true, true));
    assertTrue(s.editingDocument().paragraph(0).text.fieldAt(0).locked);
  }
}
