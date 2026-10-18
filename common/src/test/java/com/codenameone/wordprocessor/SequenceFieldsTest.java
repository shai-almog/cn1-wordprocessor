package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class SequenceFieldsTest {
  private void caption(Session s, String label, String text, boolean above) {
    s.insertCaption(label, text, above, "ARABIC", false, 0, "-");
  }

  @Test
  public void quotedArgumentsRemainDataEvenWhenTheyLookLikeSwitches() {
    var code = new FieldCode("SEQ \"\\\\h\" \\* \"ROMAN\"");
    assertEquals("\\h", code.arguments.get(0));
    assertFalse(code.switches.containsKey("h"));
    assertEquals("ROMAN", code.switches.get("*"));
    assertEquals("", FieldCode.tokens("TITLE \"\"").get(1));
    var s = new Session(Document.empty());
    s.insertField(new InlineField("SEQ \"\\\\h\" \\* ROMAN", "?", false, false));
    s.mapFields(SequenceFields.resolver(s.document()));
    assertEquals("I", s.document().plainText());
  }

  @Test
  public void captionsRenumberInDocumentOrderAndUndoAsOneOperation() {
    var s = new Session(Document.empty());
    s.replace("Object");
    caption(s, "Figure", "First", false);
    caption(s, "Figure", "Second", false);
    assertEquals("Object\nFigure 1: First\nFigure 2: Second", s.document().plainText());
    s.select(1, 0, 0);
    caption(s, "Figure", "New", true);
    assertEquals(
        "Object\nFigure 1: New\nFigure 2: First\nFigure 3: Second", s.document().plainText());
    s.undo();
    assertEquals("Object\nFigure 1: First\nFigure 2: Second", s.document().plainText());
    s.redo();
    assertEquals(4, s.document().blocks.size());
  }

  @Test
  public void trackedCaptionsCanBeAcceptedOrRejectedOnEitherSide() {
    for (boolean above : new boolean[] {true, false}) {
      var s = new Session(Document.empty());
      s.replace("Object");
      s.trackChanges(true);
      caption(s, "Figure", "Description", above);
      assertEquals("Object", Revisions.resolve(s.document(), null, false).plainText());
      assertEquals(
          above ? "Figure 1: Description\nObject" : "Object\nFigure 1: Description",
          Revisions.resolve(s.document(), null, true).plainText());
      s.undo();
      assertEquals("Object", s.document().plainText());
    }
  }

  @Test
  public void cellCaptionIsPlacedOutsideTheWholeTable() {
    var s = new Session(Document.empty());
    s.insertTable(1, 1);
    int block = 0;
    while (!(s.document().blocks.get(block) instanceof Table)) block++;
    s.enterCell(block, 0, 0);
    s.replace("Cell");
    caption(s, "Table", "Grid", false);
    assertFalse(s.inCell());
    assertTrue(s.document().blocks.get(block) instanceof Table);
    assertEquals("Table 1: Grid", s.document().paragraph(block + 1).text.plainText());
  }

  @Test
  public void sequencesSupportResetRepeatHideFormatsAndLockedFields() {
    var s = new Session(Document.empty());
    for (String code :
        new String[] {
          "SEQ Figure \\* ROMAN",
          "SEQ Figure \\c \\* roman",
          "SEQ Table \\r 26 \\* ALPHABETIC",
          "SEQ Table \\* ALPHABETIC",
          "SEQ Figure \\h",
          "SEQ Figure"
        }) {
      s.insertField(new InlineField(code, "?", false, false));
      s.replace(" ");
    }
    s.insertField(new InlineField("SEQ Figure", "Locked", true, false));
    s.mapFields(SequenceFields.resolver(s.document()));
    assertEquals("I i Z AA  3 Locked", s.document().plainText());
  }

  @Test
  public void chapterCaptionsRestartAndHeadingReferencesUseTextOrNumber() {
    var s = new Session(Document.empty());
    s.replace("First");
    s.applyStyle("Heading1");
    s.insertCaption("Figure", "One", false, "ARABIC", false, 1, "-");
    s.insertCaption("Figure", "Two", false, "ARABIC", false, 1, "-");
    s.replace("\nSecond");
    s.applyStyle("Heading1");
    s.insertCaption("Figure", "Three", false, "ARABIC", false, 1, "-");
    s.insertField(new InlineField("STYLEREF \"Heading 1\"", "?", false, false));
    s.mapFields(SequenceFields.resolver(s.document()));
    assertEquals(
        "First\nFigure 1-1: One\nFigure 1-2: Two\nSecond\nFigure 2-1: ThreeSecond",
        s.document().plainText());
  }
}
