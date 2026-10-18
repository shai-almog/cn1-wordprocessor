package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DocumentTest {
  @Test
  public void persistentSequenceMatchesArrayList() {
    var r = new Random(72);
    var seq = new Sequence<Integer>();
    var list = new ArrayList<Integer>();
    Sequence<Integer> snapshot = null;
    List<Integer> expected = null;
    for (int k = 0; k < 10000; k++) {
      int i = r.nextInt(list.size() + 1);
      if (list.isEmpty() || r.nextBoolean()) {
        int v = r.nextInt();
        list.add(i, v);
        seq = seq.insert(i, v);
      } else {
        i = r.nextInt(list.size());
        if (r.nextBoolean()) {
          list.remove(i);
          seq = seq.remove(i);
        } else {
          list.set(i, k);
          seq = seq.set(i, k);
        }
      }
      assertEquals(list.size(), seq.size());
      for (int j = 0; j < list.size(); j++) assertEquals(list.get(j), seq.get(j));
      assertTrue(seq.depth() < 25);
      if (k == 300) {
        snapshot = seq;
        expected = new ArrayList<>(list);
      }
    }
    for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), snapshot.get(i));
  }

  @Test
  public void styledRopeMatchesReference() {
    var r = new Random(911);
    var ref = new StringBuilder();
    var text = new RichText();
    for (int k = 0; k < 6000; k++) {
      int a = r.nextInt(ref.length() + 1), b = a + r.nextInt(ref.length() - a + 1);
      var value = "word " + k + " ";
      if (k % 7 == 0) value = "";
      var old = text;
      var oldString = ref.toString();
      text = text.replace(a, b, RichText.of(value));
      ref.replace(a, b, value);
      assertEquals(ref.toString(), text.toString());
      assertEquals(oldString, old.toString());
      assertTrue(text.depth() < 30);
    }
  }

  @Test
  public void ropeDoesNotFlattenLargeDocument() {
    RichText text = RichText.of(repeat("abcdef ", 100000));
    var next = text.replace(300000, 300001, RichText.of("x"));
    assertEquals(text.length(), next.length());
    assertTrue(next.depth() < 25);
    assertEquals('x', next.charAt(300000));
    assertEquals('a', text.charAt(0));
  }

  @Test
  public void rangeFormattingPreservesOtherRuns() {
    var t = RichText.of("abcdef").format(2, 4, "bold", "true");
    assertFalse(t.styleAt(1).bold);
    assertTrue(t.styleAt(2).bold);
    assertTrue(t.styleAt(3).bold);
    assertFalse(t.styleAt(4).bold);
    assertEquals("abcdef", t.toString());
  }

  @Test
  public void cannotSplitSurrogate() {
    RichText t = RichText.of("A😀B");
    try {
      t.replace(2, 2, RichText.of("x"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals("😀", t.slice(1, 3).toString());
  }

  @Test
  public void paragraphSplitMergeAndUndo() {
    var s = new Session(Document.empty());
    s.replace("one\ntwo");
    assertEquals(2, s.document().blocks.size());
    s.select(1, 0, 0);
    s.deleteBackward();
    assertEquals("onetwo", s.document().plainText());
    s.undo();
    assertEquals("one\ntwo", s.document().plainText());
    s.redo();
    assertEquals("onetwo", s.document().plainText());
  }

  @Test
  public void transactionRollsBackOnFailure() {
    var s = new Session(Document.empty());
    var original = s.document();
    try {
      s.transact(
          x -> {
            x.replace("changed");
            throw new IllegalArgumentException("failure");
          });
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(original, s.document());
    assertEquals(0, s.revision());
    assertFalse(s.canUndo());
  }

  @Test
  public void batchIsOneUndoAndRevision() {
    var s = new Session(Document.empty());
    s.transact(
        x -> {
          x.replace("one");
          x.replace("two");
        });
    assertEquals(1, s.revision());
    s.undo();
    assertEquals("", s.document().plainText());
    assertFalse(s.canUndo());
  }

  @Test
  public void stalePreviewCannotApplyAfterUndo() {
    var s = new Session(Document.empty());
    var p = s.fork();
    long rev = s.revision();
    p.replace("preview");
    s.replace("edit");
    s.undo();
    try {
      s.applyPreview(p, rev);
      fail();
    } catch (IllegalStateException expected) {
    }
    assertEquals("", s.document().plainText());
  }

  @Test
  public void previewAppliesAsOneUndo() {
    var s = new Session(Document.empty());
    var p = s.fork();
    p.replace("preview");
    assertEquals("", s.document().plainText());
    s.applyPreview(p, 0);
    assertEquals("preview", s.document().plainText());
    s.undo();
    assertEquals("", s.document().plainText());
  }

  @Test
  public void unicodeBackspaceRemovesWholeCodePoint() {
    var s = new Session(Document.empty());
    s.replace("A😀");
    s.deleteBackward();
    assertEquals("A", s.document().plainText());
  }

  @Test
  public void compositionIsOneUndo() {
    var s = new Session(Document.empty());
    s.replace("a");
    long before = s.revision();
    s.compose("k", 1);
    s.compose("ka", 2);
    s.compose("漢", 1);
    assertEquals(before, s.revision());
    assertEquals("a漢", s.document().plainText());
    s.finishComposition();
    assertEquals(before + 1, s.revision());
    s.undo();
    assertEquals("a", s.document().plainText());
  }

  @Test
  public void cancelledCompositionRestoresSelection() {
    var s = new Session(Document.empty());
    s.replace("abc");
    s.select(0, 0, 2);
    s.compose("漢", 1);
    s.cancelComposition();
    assertEquals("abc", s.document().plainText());
    assertEquals(0, s.selection().anchor);
    assertEquals(2, s.selection().caret);
  }

  @Test
  public void replaceAcrossParagraphsPreservesSuffixAndUndo() {
    var s = new Session(Document.empty());
    s.replace("first\nsecond\nthird");
    s.selectRange(0, 2, 2, 2);
    assertEquals("rst\nsecond\nth", s.selectedText());
    s.replace("X\nY");
    assertEquals("fiX\nYird", s.document().plainText());
    s.undo();
    assertEquals("first\nsecond\nthird", s.document().plainText());
    assertEquals(2, s.selection().block);
    assertEquals(0, s.selection().anchorBlock);
  }

  @Test
  public void backwardsSelectionHasSameReplacement() {
    var s = new Session(Document.empty());
    s.replace("abc\ndef");
    s.selectRange(1, 2, 0, 1);
    s.replace("X");
    assertEquals("aXf", s.document().plainText());
  }

  @Test
  public void typingFormatAppliesWithoutSelectingText() {
    var s = new Session(Document.empty());
    s.format("bold", "true");
    s.replace("bold");
    assertTrue(s.document().paragraph(0).text.styleAt(0).bold);
    s.format("bold", "false");
    s.replace(" plain");
    assertFalse(s.document().paragraph(0).text.styleAt(5).bold);
  }

  @Test
  public void formattingSpansParagraphs() {
    var s = new Session(Document.empty());
    s.replace("abc\ndef");
    s.selectRange(0, 1, 1, 2);
    s.format("italic", "true");
    assertFalse(s.document().paragraph(0).text.styleAt(0).italic);
    assertTrue(s.document().paragraph(0).text.styleAt(1).italic);
    assertTrue(s.document().paragraph(1).text.styleAt(1).italic);
    assertFalse(s.document().paragraph(1).text.styleAt(2).italic);
  }

  @Test
  public void rollbackRestoresTypingStyle() {
    var s = new Session(Document.empty());
    try {
      s.transact(
          x -> {
            x.format("bold", "true");
            throw new IllegalArgumentException("failure");
          });
      fail();
    } catch (IllegalArgumentException expected) {
    }
    s.replace("normal");
    assertFalse(s.document().paragraph(0).text.styleAt(0).bold);
  }

  @Test
  public void leadingAndConsecutiveEmptyParagraphsArePreserved() {
    var s = new Session(Document.empty());
    s.replace("\n\nhello\n\n");
    assertEquals("\n\nhello\n\n", s.document().plainText());
  }

  @Test
  public void statisticsOnlyRecountChangedBlocks() {
    var s = new Session(Document.empty());
    s.replace("one two\nthree four");
    var stats = new DocumentStats();
    assertEquals(4, stats.words(s.document()));
    s.select(1, 0, 0);
    s.replace("new ");
    assertEquals(5, stats.words(s.document()));
    assertEquals(1, stats.measuredBlocks());
  }

  private static String repeat(String s, int n) {
    var b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  @Test
  public void loadClearsHistoryStyleAndInvalidatesPreviews() {
    var s = new Session(Document.empty());
    s.replace("old");
    s.format("bold", "true");
    long revision = s.revision();
    s.loadDocument(Document.empty());
    assertFalse(s.canUndo());
    assertFalse(s.canRedo());
    assertTrue(s.revision() > revision);
    s.replace("new");
    assertFalse(s.document().paragraph(0).text.styleAt(0).bold);
  }

  @Test
  public void paragraphPropertiesApplyAcrossSelectionInOneUndo() {
    var s = new Session(Document.empty());
    s.replace("first\nsecond\nthird");
    s.selectRange(0, 2, 2, 1);
    s.paragraph("align", "center");
    for (int i = 0; i < 3; i++) assertEquals("center", s.document().paragraph(i).align);
    s.undo();
    for (int i = 0; i < 3; i++) assertEquals("left", s.document().paragraph(i).align);
  }
}
