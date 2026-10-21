package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/** Selections running from body text into and across (nested) tables, as Wordcraft's Pos ranges. */
public class MixedSelectionTest {
  private static final int[] BODY = {}, OUTER = {1, 0, 0}, INNER = {1, 0, 0, 1, 0, 0};

  private static String innerText(Session s) {
    return NestedTableTest.inner(s).cell(0, 0).get(0).text.toString();
  }

  @Test
  public void textCopyAndOrder() {
    var s = NestedTableTest.sample();
    s.selectAcross(BODY, 0, 2, INNER, 0, 3);
    assertNotNull(s.mixedSelection());
    assertArrayEquals(INNER, s.cellPath());
    assertEquals("dy\nouter\ninn", s.selectedText());
    var copy = s.copySelection();
    assertEquals(3, copy.blocks.size());
    assertEquals("inn", ((Paragraph) copy.blocks.get(2)).text.toString());
    // Backwards: anchor in the cell, focus in the body.
    s.selectAcross(INNER, 0, 3, BODY, 0, 2);
    assertFalse(s.inCell());
    assertEquals("dy\nouter\ninn", s.selectedText());
    // Same story collapses to an ordinary selection.
    s.selectAcross(BODY, 0, 0, BODY, 0, 2);
    assertNull(s.mixedSelection());
    assertEquals("Bo", s.selectedText());
  }

  @Test
  public void deleteAcrossIsOneUndoStep() {
    var s = NestedTableTest.sample();
    s.selectAcross(BODY, 0, 2, INNER, 0, 3);
    s.deleteBackward();
    assertNull(s.mixedSelection());
    assertEquals("Bo", s.document().paragraph(0).text.toString());
    assertEquals("", NestedTableTest.outer(s).cell(0, 0).get(0).text.toString());
    assertEquals("er", innerText(s));
    assertFalse(s.inCell());
    assertEquals(2, s.selection().caret);
    s.undo();
    assertEquals("Body", s.document().paragraph(0).text.toString());
    assertEquals("inner", innerText(s));
    assertNotNull(s.mixedSelection());
  }

  @Test
  public void typingReplacesAndTablesBetweenAreRemoved() {
    var s = new Session(Document.empty());
    s.replace("A");
    s.insertTable(1, 1);
    s.replace("t1");
    s.leaveCell();
    int after = s.document().blocks.size() - 1;
    s.select(after, 0, 0);
    s.insertTable(1, 1);
    s.replace("t2");
    s.leaveCell();
    int second = -1;
    for (int i = s.document().blocks.size() - 1; i >= 0; i--)
      if (s.document().blocks.get(i) instanceof Table) {
        second = i;
        break;
      }
    s.selectAcross(BODY, 0, 1, new int[] {second, 0, 0}, 0, 1);
    s.typeText("x", 0);
    assertEquals("Ax", s.document().paragraph(0).text.toString());
    int tables = 0;
    for (Block b : s.document().blocks) if (b instanceof Table) tables++;
    assertEquals(1, tables);
    s.undo();
    tables = 0;
    for (Block b : s.document().blocks) if (b instanceof Table) tables++;
    assertEquals(2, tables);
  }

  @Test
  public void characterAndParagraphFormatting() {
    var s = NestedTableTest.sample();
    s.selectAcross(BODY, 0, 2, INNER, 0, 3);
    s.format("bold", "true");
    assertNotNull(s.mixedSelection());
    var body = s.document().paragraph(0).text;
    assertFalse(body.styleAt(0).bold);
    assertTrue(body.styleAt(2).bold);
    assertTrue(NestedTableTest.outer(s).cell(0, 0).get(0).text.styleAt(0).bold);
    var inner = NestedTableTest.inner(s).cell(0, 0).get(0).text;
    assertTrue(inner.styleAt(2).bold);
    assertFalse(inner.styleAt(3).bold);
    s.paragraph("align", "center");
    assertEquals(
        "center", NestedTableTest.inner(s).cell(0, 0).get(0).align);
    assertEquals("center", s.document().paragraph(0).align);
    s.undo();
    s.undo();
    assertFalse(NestedTableTest.inner(s).cell(0, 0).get(0).text.styleAt(0).bold);
  }

  @Test
  public void findAndTableCommandsWorkInNestedCells() {
    var s = NestedTableTest.sample();
    var c = new Commands();
    var matches = Commands.find(s.document(), "inner");
    assertEquals(1, matches.size());
    s.leaveCell();
    Commands.selectMatch(s, matches.get(0));
    assertArrayEquals(INNER, s.cellPath());
    assertEquals("inner", s.selectedText());
    assertEquals("inner", Commands.matchedParagraph(s.document(), matches.get(0)).text.toString());
    assertFalse(Commands.followsSelection(s, matches.get(0)));
    c.execute(s, "table.deleteRows");
    assertEquals(1, NestedTableTest.inner(s).rows());
    assertEquals(2, NestedTableTest.outer(s).rows());
    assertEquals(2, s.cellDepth());
    c.execute(s, "table.deleteTable");
    for (Block b : NestedTableTest.outer(s).blocks(0, 0)) assertFalse(b instanceof Table);
    assertEquals(2, NestedTableTest.outer(s).rows());
  }
}
