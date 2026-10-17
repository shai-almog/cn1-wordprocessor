package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class RevisionsTest {
  private Session tracked(String text) {
    Session s = new Session(Document.empty());
    s.replace(text);
    s.trackChanges(true);
    s.reviewAuthor("Ada");
    return s;
  }

  @Test
  public void replaceRetainsOriginalAndCanAcceptRejectAndUndo() {
    Session s = tracked("red green blue");
    s.select(0, 4, 9);
    s.replace("gold");
    assertEquals("red goldgreen blue", s.document().plainText());
    assertEquals("red gold blue", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("red green blue", Revisions.resolve(s.document(), null, false).plainText());
    assertEquals(2, Revisions.list(s.document()).size());
    for (Revisions.Change c : Revisions.list(s.document())) assertEquals("Ada", c.revision.author);
    s.resolveRevision(null, true);
    assertEquals("red gold blue", s.document().plainText());
    assertTrue(Revisions.list(s.document()).isEmpty());
    s.undo();
    assertEquals(2, Revisions.list(s.document()).size());
  }

  @Test
  public void insertedParagraphBreaksAndTrackedJoinsResolveCorrectly() {
    Session s = tracked("First last");
    s.select(0, 6, 6);
    s.replace("new\nline\n");
    assertEquals("First new\nline\nlast", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("First last", Revisions.resolve(s.document(), null, false).plainText());
    s = tracked("First\nSecond\nThird");
    s.selectRange(0, 3, 2, 2);
    s.replace("!");
    assertEquals("Fir!ird", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("First\nSecond\nThird", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void deletingOwnInsertionCancelsTextAndParagraphBreak() {
    Session s = tracked("AB");
    s.select(0, 1, 1);
    s.replace("new\n");
    s.deleteBackward();
    assertEquals("AnewB", s.document().plainText());
    s.select(0, 1, 4);
    s.replace("");
    assertEquals("AB", s.document().plainText());
    assertTrue(Revisions.list(s.document()).isEmpty());
  }

  @Test
  public void typingCoalescesAndDisablingTrackingDoesNotExtendOldRevision() {
    Session s = tracked("a");
    s.replace("b");
    String id = Revisions.list(s.document()).get(0).revision.id;
    s.replace("c");
    assertEquals(id, Revisions.list(s.document()).get(0).revision.id);
    s.trackChanges(false);
    s.replace("d");
    assertEquals("ad", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void richPasteAndFieldsAreAtomicTrackedInsertions() {
    Session s = tracked("AB");
    s.select(0, 1, 1);
    Session fragment = new Session(Document.empty());
    fragment.replace("bold\nplain");
    fragment.select(0, 0, 4);
    fragment.format("bold", "true");
    s.paste(fragment.document());
    assertTrue(s.document().paragraph(0).text.styleAt(2).bold);
    assertEquals("Abold\nplainB", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("AB", Revisions.resolve(s.document(), null, false).plainText());
    s.insertField(new InlineField("PAGE", "1", false, false));
    assertNotNull(s.document().paragraph(1).text.styleAt(s.selection().caret - 1).revision);
    assertEquals("AB", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void cellEditingAndCompositionParticipateInRevisionHistory() {
    Session s = new Session(Document.empty());
    s.insertTable(1, 1);
    s.trackChanges(true);
    s.reviewAuthor("Ada");
    int block = 0;
    while (!(s.document().blocks.get(block) instanceof Table)) block++;
    s.enterCell(block, 0, 0);
    s.compose("café", 4);
    s.finishComposition();
    assertEquals("Ada", Revisions.list(s.document()).get(0).revision.author);
    assertTrue(
        ((Table) Revisions.resolve(s.document(), null, false).blocks.get(block))
                .cell(0, 0)
                .get(0)
                .text
                .length()
            == 0);
    s.undo();
    assertTrue(Revisions.list(s.document()).isEmpty());
  }

  @Test
  public void forwardDeleteSkipsRetainedDeletionsAndMarkedBreaks() {
    Session s = tracked("A😀B\nCD");
    s.select(0, 1, 1);
    s.deleteForward();
    s.deleteForward();
    s.deleteForward();
    s.deleteForward();
    assertEquals("AD", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("A😀B\nCD", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void untrackedSplitDoesNotDuplicateTrackedParagraphMark() {
    Session s = tracked("AB");
    s.select(0, 1, 1);
    s.replace("\n");
    s.trackChanges(false);
    s.select(0, 0, 0);
    s.replace("\n");
    assertNull(s.document().paragraph(0).breakRevision);
    assertNotNull(s.document().paragraph(1).breakRevision);
    assertEquals("\nAB", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void resolvingTextKeepsAnEmptyParagraphsFormatting() {
    Session s = tracked("");
    s.format("bold", "true");
    assertTrue(Revisions.resolve(s.document(), null, true).paragraph(0).text.styleAt(0).bold);
  }

  @Test
  public void characterFormattingRestoresOriginalStyleAndTracksOneBaseline() {
    Session s = tracked("plain bold");
    s.select(0, 6, 10);
    s.format("bold", "true");
    s.format("color", "ff0000");
    assertEquals(1, Revisions.list(s.document()).size());
    assertEquals("format", Revisions.list(s.document()).get(0).revision.kind);
    Document accepted = Revisions.resolve(s.document(), null, true);
    assertTrue(accepted.paragraph(0).text.styleAt(6).bold);
    assertEquals(0xff0000, accepted.paragraph(0).text.styleAt(6).color);
    Document rejected = Revisions.resolve(s.document(), null, false);
    assertEquals(TextStyle.DEFAULT, rejected.paragraph(0).text.styleAt(6));
    s.resolveRevision(null, false);
    s.undo();
    assertTrue(s.document().paragraph(0).text.styleAt(6).bold);
    s.select(0, 10, 10);
    s.replace("!");
    assertNull(s.document().paragraph(0).text.styleAt(10).formatRevision);
  }

  @Test
  public void deletionAndFormattingCanBeReviewedIndependently() {
    Session s = tracked("word");
    s.select(0, 0, 4);
    s.format("italic", "true");
    String formatId = Revisions.list(s.document()).get(0).revision.id;
    s.replace("");
    Document rejectedFormat = Revisions.resolve(s.document(), formatId, false);
    assertFalse(rejectedFormat.paragraph(0).text.styleAt(0).italic);
    assertEquals(1, Revisions.list(rejectedFormat).size());
    assertEquals("word", Revisions.resolve(rejectedFormat, null, false).plainText());
    assertEquals("", Revisions.resolve(rejectedFormat, null, true).plainText());
  }

  @Test
  public void selectedReviewResolvesOnlySelectedCharactersEvenWithinOneRevision() {
    Session s = tracked("before ");
    s.replace("abcdef");
    s.select(0, 9, 11);
    s.resolveSelectedRevisions(false);
    assertEquals("before abef", s.document().plainText());
    assertEquals("before ", Revisions.resolve(s.document(), null, false).plainText());
    s.undo();
    s.select(0, 9, 11);
    s.resolveSelectedRevisions(true);
    assertEquals("before abcdef", s.document().plainText());
    assertEquals("before cd", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void selectedReviewIncludesBreaksAndBothSidesOfReplacement() {
    Session s = tracked("one two\nthree four");
    s.selectRange(0, 4, 1, 5);
    s.replace("new");
    s.selectRange(0, 4, 1, 5);
    s.resolveSelectedRevisions(true);
    assertEquals("one new four", s.document().plainText());
    assertTrue(Revisions.list(s.document()).isEmpty());
    s.undo();
    s.selectRange(0, 4, 1, 5);
    s.resolveSelectedRevisions(false);
    assertEquals("one two\nthree four", s.document().plainText());
    assertTrue(Revisions.list(s.document()).isEmpty());
  }

  @Test
  public void cellSelectionReviewDoesNotAffectOtherCells() {
    Session s = new Session(Document.empty());
    s.insertTable(1, 2);
    int block = 0;
    while (!(s.document().blocks.get(block) instanceof Table)) block++;
    s.trackChanges(true);
    s.enterCell(block, 0, 0);
    s.replace("first");
    s.enterCell(block, 0, 1);
    s.replace("second");
    s.select(0, 0, 6);
    s.resolveSelectedRevisions(false);
    assertTrue(s.inCell());
    Table table = (Table) s.document().blocks.get(block);
    assertEquals("first", table.cell(0, 0).get(0).text.toString());
    assertEquals("", table.cell(0, 1).get(0).text.toString());
    assertEquals(1, Revisions.list(s.document()).size());
  }

  @Test
  public void trackedStyleApplicationRestoresParagraphAndCharacterDefaults() {
    Session s = tracked("Heading");
    s.applyStyle("Heading1");
    assertEquals("Heading1", s.document().paragraph(0).style);
    assertTrue(s.document().paragraph(0).text.styleAt(0).bold);
    assertEquals(2, Revisions.list(s.document()).size());
    s.resolveRevision(null, false);
    Paragraph p = s.document().paragraph(0);
    assertEquals("Normal", p.style);
    assertEquals(TextStyle.DEFAULT, p.text.styleAt(0));
    s.undo();
    s.resolveRevision(null, true);
    assertEquals("Heading1", s.document().paragraph(0).style);
    assertTrue(s.document().paragraph(0).text.styleAt(0).bold);
    assertTrue(Revisions.list(s.document()).isEmpty());
  }

  @Test
  public void UnchangedParagraphAndStyleDoNotCreateRevisions() {
    Session s = tracked("unchanged");
    s.paragraph("align", "left");
    s.applyStyle("Normal");
    assertTrue(Revisions.list(s.document()).isEmpty());
  }

  @Test
  public void caseConversionTracksRichTextWithoutRewritingFieldsOrExistingDeletions() {
    Session s = tracked("Straße");
    s.select(0, 0, 6);
    s.changeCase(true);
    assertEquals("STRASSEStraße", s.document().plainText());
    assertEquals("STRASSE", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("Straße", Revisions.resolve(s.document(), null, false).plainText());
    s.select(0, 0, 13);
    s.changeCase(false);
    assertEquals("strasse", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("Straße", Revisions.resolve(s.document(), null, false).plainText());
    s.trackChanges(false);
    s.select(0, 13, 13);
    s.insertField(new InlineField("TITLE", "Title", true, false));
    s.trackChanges(true);
    s.select(0, 0, s.editingDocument().paragraph(0).text.length());
    s.changeCase(true);
    assertEquals(
        "Title", s.editingDocument().paragraph(0).text.fieldAt(s.selection().end() - 1).result);
  }

  @Test
  public void backwardDeleteSkipsExistingDeletedTextAndBreaks() {
    Session s = tracked("AB\nC😀D");
    s.select(1, 0, 1);
    s.replace("");
    s.select(1, 3, 3);
    s.deleteBackward();
    s.deleteBackward();
    s.deleteBackward();
    assertEquals("AD", Revisions.resolve(s.document(), null, true).plainText());
    assertEquals("AB\nC😀D", Revisions.resolve(s.document(), null, false).plainText());
  }

  @Test
  public void formattingPreferenceDoesNotDisableTextTracking() {
    Session s = tracked("text");
    s.trackFormatting(false);
    s.select(0, 0, 4);
    s.format("italic", "true");
    s.paragraph("align", "right");
    s.applyStyle("Heading1");
    assertTrue(Revisions.list(s.document()).isEmpty());
    assertFalse(s.fork().trackFormatting());
    s.select(0, 4, 4);
    s.replace("!");
    Document original = Revisions.resolve(s.document(), null, false);
    assertEquals("text", original.plainText());
    assertEquals("Heading1", original.paragraph(0).style);
    assertTrue(original.paragraph(0).text.styleAt(0).italic);
  }
}
