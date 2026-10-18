package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class CharacterCommandsTest {
  private final Commands commands = new Commands();

  private void apply(Session s, String key, String value) {
    commands.execute(s, "format.set", Json.object("property", key, "value", value));
  }

  @Test
  public void allCharacterCommandsAreUndoableWithoutReplacingSourceText() {
    var s = new Session(Document.empty());
    s.replace("Original Text");
    s.selectRange(0, 0, 0, 8);
    for (String key : CharacterFormat.KEYS) {
      var before = s.document();
      String value =
          key.equals("underlineStyle")
              ? "wave"
              : key.equals("underlineColor") || key.equals("shading") || key.equals("border")
                  ? "cc3300"
                  : key.equals("characterScale")
                      ? "120"
                      : key.startsWith("character")
                          ? "2"
                          : key.equals("lang")
                              ? "en-GB"
                              : key.equals("ruby") ? "x" : key.equals("enclose") ? "circle" : "true";
      apply(s, key, value);
      assertEquals(before.plainText(), s.document().plainText());
      assertNotEquals(
          before.paragraph(0).text.styleAt(0), s.document().paragraph(0).text.styleAt(0));
      assertEquals(TextStyle.DEFAULT, s.document().paragraph(0).text.styleAt(10));
      s.undo();
      assertSame(before, s.document());
      s.redo();
      s.undo();
    }
  }

  @Test
  public void groupedFontChangesRollbackAllOnInvalidGeometry() {
    var s = new Session(Document.empty());
    s.replace("abc");
    s.selectRange(0, 0, 0, 3);
    var before = s.document();
    try {
      s.transact(
          t -> {
            t.format("allCaps", "true");
            t.format("characterScale", "0");
          });
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(before, s.document());
  }

  @Test
  public void trackingRejectAndAcceptCoverTheWholeFormatGroup() {
    var s = new Session(Document.empty());
    s.replace("abc");
    s.selectRange(0, 0, 0, 3);
    s.trackChanges(true);
    s.transact(
        t -> {
          t.format("smallCaps", "true");
          t.format("characterPosition", "4");
        });
    assertNotNull(s.document().paragraph(0).text.styleAt(0).formatRevision);
    s.resolveRevision(null, false);
    assertEquals(CharacterFormat.DEFAULT, s.document().paragraph(0).text.styleAt(0).characters);
    s.undo();
    s.resolveRevision(null, true);
    assertTrue(s.document().paragraph(0).text.styleAt(0).characters.smallCaps);
  }

  @Test
  public void styleUpdateKeepsDirectCharacterOverrides() {
    var s = new Session(Document.empty());
    s.replace("abc");
    s.applyStyle("Normal");
    s.selectRange(0, 0, 0, 3);
    apply(s, "characterSpacing", "3");
    commands.execute(
        s, "styles.modify", Json.object("id", "Normal", "property", "smallCaps", "value", "true"));
    assertTrue(s.document().paragraph(0).text.styleAt(0).characters.smallCaps);
    commands.execute(
        s,
        "styles.modify",
        Json.object("id", "Normal", "property", "characterSpacing", "value", "1"));
    assertEquals(3, s.document().paragraph(0).text.styleAt(0).characters.spacing, 0);
  }

  @Test
  public void caretInsideWordFormatsWholeWordWithoutMovingCaret() {
    var s = new Session(Document.empty());
    s.replace("one alpha_beta two");
    s.select(0, 6, 6);
    var before = s.document();
    var caret = s.selection();
    apply(s, "smallCaps", "true");
    assertSame(caret, s.selection());
    for (int i = 0; i < 18; i++)
      assertEquals(
          i >= 4 && i < 14, s.document().paragraph(0).text.styleAt(i).characters.smallCaps);
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertEquals(6, s.selection().caret);
  }

  @Test
  public void wordBoundaryChangesTypingStyleWithoutChangingExistingWord() {
    for (int offset : new int[] {0, 3, 4, 7}) {
      var s = new Session(Document.empty());
      s.replace("one two");
      s.select(0, offset, offset);
      var before = s.document();
      apply(s, "allCaps", "true");
      assertSame(before, s.document());
      s.replace("x");
      assertTrue(s.document().paragraph(0).text.styleAt(offset).characters.allCaps);
    }
  }

  @Test
  public void caretWordFormattingTracksAndRejectsAcrossCombiningMarks() {
    var s = new Session(Document.empty());
    s.replace("a\u0301bc rest");
    s.select(0, 2, 2);
    s.trackChanges(true);
    apply(s, "characterPosition", "3");
    for (int i = 0; i < 4; i++)
      assertEquals(3, s.document().paragraph(0).text.styleAt(i).characters.position, 0);
    assertEquals(2, s.selection().caret);
    s.resolveRevision(null, false);
    assertEquals(0, s.document().paragraph(0).text.styleAt(0).characters.position, 0);
  }

  @Test
  public void repeatedFontEditsAtCaretFormOneUndoTransaction() {
    var s = new Session(Document.empty());
    s.replace("hello world");
    s.select(0, 2, 2);
    var before = s.document();
    s.transact(
        t -> {
          t.format("characterScale", "130");
          t.format("doubleUnderline", "true");
          t.format("allCaps", "true");
        });
    assertEquals(130, s.document().paragraph(0).text.styleAt(4).characters.scale, 0);
    assertFalse(s.document().paragraph(0).text.styleAt(6).characters.allCaps);
    s.undo();
    assertSame(before, s.document());
    assertEquals(2, s.selection().caret);
  }

  @Test
  public void styleUnderlineModeDoesNotOverwriteDirectSingleUnderline() {
    var s = new Session(Document.empty());
    s.replace("alpha");
    s.applyStyle("Normal");
    s.select(0, 0, 5);
    s.format("underline", "true");
    commands.execute(
        s,
        "styles.modify",
        Json.object("id", "Normal", "property", "doubleUnderline", "value", "true"));
    var actual = s.document().paragraph(0).text.styleAt(0);
    assertTrue(actual.underline);
    assertFalse(actual.characters.doubleUnderline);
  }
}
