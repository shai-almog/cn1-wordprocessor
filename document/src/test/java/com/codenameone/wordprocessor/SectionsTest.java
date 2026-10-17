package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

public class SectionsTest {
  @Test
  public void breakPreservesSelectedTextAndUndoAndSplittingRetainsBoundary() {
    Session s = new Session(Document.empty());
    s.replace("First second third");
    s.select(0, 6, 12);
    s.sectionBreak("oddPage");
    assertEquals("First \nsecond third", s.document().plainText());
    assertEquals("second", s.selectedText());
    assertEquals(2, s.document().sectionRanges().size());
    assertEquals("oddPage", s.document().lastSection.start);
    s.undo();
    assertEquals("First second third", s.document().plainText());
    s.redo();
    s.select(0, 3, 3);
    s.replace("\n");
    assertFalse(s.document().sections.containsKey(s.document().blocks.get(0).id()));
    assertTrue(s.document().sections.containsKey(s.document().blocks.get(1).id()));
    s.selectRange(1, s.document().paragraph(1).text.length(), 2, 0);
    s.replace("");
    assertEquals(1, s.document().sectionRanges().size());
    assertEquals("oddPage", s.document().lastSection.start);
  }

  @Test
  public void linkedStoriesFollowPreviousSectionUntilExplicitlyUnlinked() {
    Session s = new Session(Document.empty());
    PageFurniture header =
        PageFurniture.EMPTY.story(
            "header", new Sequence<Paragraph>().append(new Paragraph("h", RichText.of("Shared"))));
    s.furniture(header);
    s.sectionBreak("nextPage");
    assertTrue(s.activeSection().raw.furniture.stories.isEmpty());
    assertEquals(
        "Shared", s.activeSection().resolved.furniture.get("header").get(0).text.toString());
    s.furniture(PageFurniture.EMPTY.story("header", new Sequence<Paragraph>()));
    assertEquals(0, s.activeSection().resolved.furniture.get("header").size());
    s.undo();
    assertEquals(
        "Shared", s.activeSection().resolved.furniture.get("header").get(0).text.toString());
  }

  @Test
  public void pageChangesAffectOnlyActiveSectionAndSurviveTitleChanges() {
    Session s = new Session(Document.empty());
    s.sectionBreak("evenPage");
    PageSpec landscape = new PageSpec(842, 595, 54, 54, 54, 54);
    s.section(s.activeSection().raw.page(landscape).numbering(7));
    assertSame(PageSpec.A4, s.document().sectionAt(0).raw.page);
    assertSame(landscape, s.document().title("Changed").sectionAt(1).raw.page);
    assertEquals(7, s.document().lastSection.pageStart);
    s.selectRange(1, 0, 0, 0);
    s.sectionBreak("continuous");
    assertEquals(3, s.document().sectionRanges().size());
  }

  @Test
  public void richPasteRetainsDestinationBoundaryAndCopiesInternalSections() {
    Session s = new Session(Document.empty());
    s.replace("First");
    s.sectionBreak("nextPage");
    s.replace("Second");
    s.select(0, 2, 2);
    s.paste(
        new Document(
            new Sequence<Block>().append(new Paragraph("copy", RichText.of("X"))),
            PageSpec.A4,
            "Clip",
            1));
    assertEquals(2, s.document().sectionRanges().size());
    assertEquals("FiXrst", s.document().paragraph(0).text.toString());
    s.selectRange(0, 0, 1, 6);
    Document fragment = s.copySelection();
    Session target = new Session(Document.empty());
    target.paste(fragment);
    assertEquals(2, target.document().sectionRanges().size());
    assertEquals(s.document().plainText(), target.document().plainText());
    target.undo();
    assertEquals(1, target.document().sectionRanges().size());
  }
}
