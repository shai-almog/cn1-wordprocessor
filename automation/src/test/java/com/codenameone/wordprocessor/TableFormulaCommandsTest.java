package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TableFormulaCommandsTest {
  final Commands commands = new Commands();

  @Test
  public void lowercaseKeywordsWorkUnderTurkishSystemLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(new Locale("tr", "TR"));
      var s = fixture();
      commands.execute(s, "table.formula", Json.object("formula", "=min(above)"));
      assertEquals("10", field(s, 3, 1).result);
      s.setCell(1, 0, 1, "5");
      s.mapFields(TableFormulas.resolver(s.document()));
      assertEquals("5", field(s, 3, 1).result);
    } finally {
      Locale.setDefault(previous);
    }
  }

  Session fixture() {
    var s = new Session(Document.empty());
    s.insertTable(4, 3);
    s.setCell(1, 0, 1, "10");
    s.setCell(1, 1, 1, "20");
    s.setCell(1, 2, 1, "30");
    s.enterCell(1, 3, 1);
    return s;
  }

  Table table(Session s) {
    return (Table) s.document().blocks.get(1);
  }

  InlineField field(Session s, int row, int col) {
    return table(s).cell(row, col).get(0).text.fieldAt(0);
  }

  @Test
  public void allSixAggregatesInsertRealFieldsWithOneStepUndoRedo() {
    String[] names = {"SUM", "AVERAGE", "COUNT", "MIN", "MAX", "PRODUCT"},
        expected = {"60", "20", "3", "10", "30", "6000"};
    for (int i = 0; i < names.length; i++) {
      var s = fixture();
      var before = s.document();
      commands.execute(s, "table.formula", Json.object("formula", "=" + names[i] + "(ABOVE)"));
      assertEquals(expected[i], field(s, 3, 1).result);
      assertEquals("=" + names[i] + "(ABOVE)", field(s, 3, 1).instruction);
      assertEquals(3, s.cellRow());
      assertEquals(1, s.selection().caret);
      var after = s.document();
      s.undo();
      assertSame(before, s.document());
      s.redo();
      assertSame(after, s.document());
    }
  }

  @Test
  public void directionsRangesConstantsAndMergedOwners() {
    var s = fixture();
    assertEquals("60", TableFormulas.calculate(table(s), 3, 1, "=SUM(B1:B3)"));
    assertEquals("10", TableFormulas.calculate(table(s), 0, 0, "=SUM(RIGHT,BELOW)"));
    assertEquals("65.50", TableFormulas.calculate(table(s), 3, 1, "=SUM(B1:B3,5.5)"));
    assertEquals("20", TableFormulas.calculate(table(s), 3, 1, "=SUM(B1,B1)"));
    assertEquals("80", TableFormulas.calculate(table(s), 3, 1, "=SUM(B1:B2,B2:B3)"));
    s.setCell(1, 0, 0, "7");
    s.mergeCells(1, 0, 0, 2, 1);
    assertEquals("7", TableFormulas.calculate(table(s), 3, 0, "=SUM(ABOVE)"));
  }

  @Test
  public void sourceEditAndUpdateFieldsRecalculateAndUndoPreservesCell() {
    var s = fixture();
    commands.execute(s, "table.formula");
    s.setCell(1, 1, 1, "70");
    s.enterCell(1, 3, 1);
    var before = s.document();
    commands.execute(s, "references.updateFields");
    assertEquals("110", field(s, 3, 1).result);
    assertEquals(3, s.cellRow());
    s.undo();
    assertSame(before, s.document());
    assertEquals("60", field(s, 3, 1).result);
  }

  @Test
  public void forwardDependenciesResolveInOnePass() {
    var s = fixture();
    s.enterCell(1, 0, 0);
    s.insertField(new InlineField("=SUM(A2)", "old", false, false));
    s.enterCell(1, 1, 0);
    s.insertField(new InlineField("=PRODUCT(B1,2)", "old", false, false));
    s.mapFields(TableFormulas.resolver(s.document()));
    assertEquals("20", field(s, 0, 0).result);
    assertEquals("20", field(s, 1, 0).result);
  }

  @Test
  public void cyclesHaveExplicitResultsAndNeverHang() {
    var s = fixture();
    s.enterCell(1, 0, 0);
    s.insertField(new InlineField("=SUM(A2)", "old", false, false));
    s.enterCell(1, 1, 0);
    s.insertField(new InlineField("=SUM(A1)", "old", false, false));
    s.mapFields(TableFormulas.resolver(s.document()));
    assertEquals("!Circular reference", field(s, 0, 0).result);
    assertEquals("!Circular reference", field(s, 1, 0).result);
  }

  @Test
  public void lockedFieldsAndUnsupportedInstructionsKeepCachedResults() {
    var s = fixture();
    s.insertField(new InlineField("=SUM(ABOVE)", "fixed", true, false));
    s.enterCell(1, 0, 0);
    s.insertField(new InlineField("=SUM(B1) \\# \"0.000\"", "12.345", false, false));
    s.mapFields(TableFormulas.resolver(s.document()));
    assertEquals("fixed", field(s, 3, 1).result);
    assertEquals("12.345", field(s, 0, 0).result);
  }

  @Test
  public void invalidInsertionIsAtomicAndDoesNotReplaceSelection() {
    var s = fixture();
    s.select(0, 0, 0);
    var before = s.document();
    for (String formula :
        new String[] {"=SUM(B4)", "=SUM(Z99)", "=SUM(ABOVE);exit()", "=UNKNOWN(ABOVE)", "=SUM()"}) {
      try {
        commands.execute(s, "table.formula", Json.object("formula", formula));
        fail(formula);
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.document());
      assertEquals(3, s.cellRow());
    }
  }

  @Test
  public void selectionUpdateIsPreservedAndRangeInsertionRejected() {
    var s = fixture();
    commands.execute(s, "table.formula");
    s.selectCells(1, 2, 0, 3, 2);
    var key = s.cellSelection().key();
    s.mapFields(TableFormulas.resolver(s.document()));
    assertEquals(key, s.cellSelection().key());
    var before = s.document();
    try {
      commands.execute(s, "table.formula");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
    assertEquals(key, s.cellSelection().key());
  }

  @Test
  public void groupedCurrencyPercentAndNonNumbersFollowEnglishRules() {
    var s = fixture();
    s.setCell(1, 0, 1, "$1,250.50");
    s.setCell(1, 1, 1, "−2.5");
    s.setCell(1, 2, 1, "12 %");
    assertEquals("1260", TableFormulas.calculate(table(s), 3, 1, "=SUM(ABOVE)"));
    s.setCell(1, 0, 1, "1,5");
    assertEquals("9.50", TableFormulas.calculate(table(s), 3, 1, "=SUM(ABOVE)"));
  }

  @Test
  public void updatesIgnoreDeletedNumbersAndKeepRevisionMetadata() {
    var s = fixture();
    s.trackChanges(true);
    s.enterCell(1, 1, 1);
    s.select(0, 0, 2);
    s.replace("");
    s.enterCell(1, 3, 1);
    commands.execute(s, "table.formula");
    assertEquals("40", field(s, 3, 1).result);
    assertNotNull(table(s).cell(3, 1).get(0).text.styleAt(0).revision);
  }
}
