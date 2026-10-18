package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class LineSpacingCommandsTest {
  private final Commands commands = new Commands();

  @Test
  public void commandIsAtomicUndoableAndPreservesSelection() {
    var s = new Session(Document.empty());
    s.replace("alpha\nbeta");
    s.selectRange(0, 1, 1, 3);
    var before = s.document();
    var selection = s.selection();
    commands.execute(s, "paragraph.spacing", Json.object("rule", "exact", "value", 18));
    for (Block block : s.document().blocks) {
      var p = (Paragraph) block;
      assertEquals("exact", p.format.lineRule);
      assertEquals(18, p.lineSpacing, 0);
    }
    assertSame(selection, s.selection());
    assertFalse(DocumentDiff.describe(before, s.document()).isEmpty());
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals("exact", s.document().paragraph(0).format.lineRule);
    before = s.document();
    for (Object[] bad :
        new Object[][] {{"auto", 0}, {"atLeast", -1}, {"bad", 18}, {"exact", Double.NaN}}) {
      try {
        commands.execute(s, "paragraph.spacing", Json.object("rule", bad[0], "value", bad[1]));
        fail();
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.document());
    }
  }

  @Test
  public void formattingRevisionRejectRestoresValueAndUnits() {
    var s = new Session(Document.empty());
    s.replace("alpha");
    s.trackChanges(true);
    commands.execute(s, "paragraph.spacing", Json.object("rule", "exact", "value", 18));
    assertNotNull(s.document().paragraph(0).formatRevision);
    s.resolveRevision(null, false);
    assertEquals("auto", s.document().paragraph(0).format.lineRule);
    assertEquals(1.2f, s.document().paragraph(0).lineSpacing, 0);
    s.undo();
    s.resolveRevision(null, true);
    assertEquals("exact", s.document().paragraph(0).format.lineRule);
    assertEquals(18, s.document().paragraph(0).lineSpacing, 0);
  }

  @Test
  public void cellSpacingAndSplitParagraphRetainRule() {
    var s = new Session(Document.empty());
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    commands.execute(s, "paragraph.spacing", Json.object("rule", "atLeast", "value", 22));
    s.replace("alpha\nbeta");
    for (Paragraph p : ((Table) s.document().blocks.get(1)).cell(0, 0)) {
      assertEquals("atLeast", p.format.lineRule);
      assertEquals(22, p.lineSpacing, 0);
    }
  }

  @Test
  public void styleUpdatePreservesDimensionedDirectOverride() {
    var s = new Session(Document.empty());
    s.replace("alpha");
    s.applyStyle("Normal");
    commands.execute(s, "paragraph.spacing", Json.object("rule", "exact", "value", 18));
    commands.execute(
        s, "styles.modify", Json.object("id", "Normal", "property", "spacing", "value", "2"));
    assertEquals("exact", s.document().paragraph(0).format.lineRule);
    assertEquals(18, s.document().paragraph(0).lineSpacing, 0);
  }
}
