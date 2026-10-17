package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class DocumentComparisonTest {
  private Document doc(String text) {
    Session s = new Session(Document.empty());
    s.replace(text);
    return s.document();
  }

  private void roundTrip(String a, String b) {
    Document original = doc(a), revised = doc(b);
    DocumentComparison.Options opts = new DocumentComparison.Options();
    opts.author = "Reviewer";
    DocumentComparison.Result result = DocumentComparison.compare(original, revised, opts);
    assertEquals(b, Revisions.resolve(result.document, null, true).plainText());
    assertEquals(a, Revisions.resolve(result.document, null, false).plainText());
    for (Revisions.Change change : Revisions.list(result.document))
      assertEquals("Reviewer", change.revision.author);
    assertEquals(a, original.plainText());
    assertEquals(b, revised.plainText());
  }

  @Test
  public void changesAndParagraphBoundariesResolveBothWays() {
    String[] values = {
      "",
      "one",
      "one\ntwo",
      "\n",
      "\n\n",
      "The cat sat.\nKeep this.",
      "The dog sat.\nKeep this.\nNew line here.",
      "A\nB\nC",
      "😀 café!"
    };
    for (String a : values) for (String b : values) roundTrip(a, b);
  }

  @Test
  public void randomEditsPreserveEveryCharacter() {
    Random rand = new Random(76);
    for (int i = 0; i < 200; i++) {
      StringBuilder a = new StringBuilder(), b = new StringBuilder();
      for (int j = 0; j < rand.nextInt(40); j++) a.append("ab \n".charAt(rand.nextInt(4)));
      for (int j = 0; j < rand.nextInt(40); j++) b.append("ab \n".charAt(rand.nextInt(4)));
      roundTrip(a.toString(), b.toString());
    }
  }

  @Test
  public void ignoredCaseAndWhitespaceUseRevisedSpelling() {
    DocumentComparison.Options o = new DocumentComparison.Options();
    o.ignoreCase = true;
    o.ignoreWhiteSpace = true;
    DocumentComparison.Result r =
        DocumentComparison.compare(doc("HELLO   World"), doc("hello world"), o);
    assertEquals(0, r.changes);
    assertEquals("hello world", r.document.plainText());
    assertTrue(Revisions.list(r.document).isEmpty());
  }

  @Test
  public void characterDiffKeepsSurrogatePairsAtomic() {
    TextComparison.Result r = TextComparison.compare("a😀b", "a😁b", true, true, true);
    TextComparison.Edit changed = null;
    for (TextComparison.Edit e : r.edits) if (!e.same) changed = e;
    assertNotNull(changed);
    assertEquals(1, changed.from);
    assertEquals(3, changed.to);
    assertEquals(1, changed.revisedFrom);
    assertEquals(3, changed.revisedTo);
  }

  @Test
  public void boundedFallbackRetainsLargeUnmatchedMiddle() {
    StringBuilder a = new StringBuilder(), b = new StringBuilder();
    for (int i = 0; i < 2100; i++) {
      a.append("a ");
      b.append("b ");
    }
    TextComparison.Result r = TextComparison.compare(a.toString(), b.toString(), true, true, true);
    assertTrue(r.coarse);
    roundTrip(a.toString(), b.toString());
  }

  @Test
  public void unchangedParagraphFormattingIsTrackedAndReversible() {
    Document a = doc("Keep this.\nSecond");
    Session edit = new Session(a);
    edit.select(0, 0, 4);
    edit.format("bold", "true");
    edit.paragraph("align", "center");
    Document b = edit.document();
    DocumentComparison.Result result =
        DocumentComparison.compare(a, b, new DocumentComparison.Options());
    assertEquals(1, result.changes);
    Document accepted = Revisions.resolve(result.document, null, true),
        rejected = Revisions.resolve(result.document, null, false);
    assertTrue(accepted.paragraph(0).text.styleAt(0).bold);
    assertEquals("center", accepted.paragraph(0).align);
    assertFalse(rejected.paragraph(0).text.styleAt(0).bold);
    assertEquals("left", rejected.paragraph(0).align);
    DocumentComparison.Options o = new DocumentComparison.Options();
    o.formatting = false;
    result = DocumentComparison.compare(a, b, o);
    assertEquals(0, result.changes);
    assertTrue(Revisions.list(result.document).isEmpty());
    assertTrue(result.document.paragraph(0).text.styleAt(0).bold);
  }

  @Test
  public void emptyParagraphFormattingStillCounts() {
    Document a = doc("");
    Document b = a.blocks(a.blocks.set(0, a.paragraph(0).with("align", "right")), a.nextId);
    DocumentComparison.Result result =
        DocumentComparison.compare(a, b, new DocumentComparison.Options());
    assertEquals(1, result.changes);
    assertEquals("right", Revisions.resolve(result.document, null, true).paragraph(0).align);
    assertEquals("left", Revisions.resolve(result.document, null, false).paragraph(0).align);
  }

  @Test
  public void existingRevisionsAreNotSilentlyAccepted() {
    Session s = new Session(doc("Old"));
    s.trackChanges(true);
    s.replace("New");
    try {
      DocumentComparison.compare(s.document(), doc("Revised"), new DocumentComparison.Options());
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("existing changes"));
    }
  }
}
