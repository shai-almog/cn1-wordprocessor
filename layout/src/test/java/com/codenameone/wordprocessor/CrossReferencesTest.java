package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class CrossReferencesTest {
  private LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String s, TextStyle style) {
            return s.length() * 6;
          }

          public float height(TextStyle style) {
            return 12;
          }
        });
  }

  private void update(Session s) {
    s.mapFields(
        DocumentFields.resolver(
            s.document(),
            engine().layout(s.document(), s.revision()),
            Collections.<String, String>emptyMap()));
  }

  @Test
  public void headingReferencesReuseAnchorsUpdateAndUndoTogether() {
    Session s = new Session(Document.empty());
    s.replace("Kilns\nSee ");
    s.select(0, 0, 5);
    s.applyStyle("Heading1");
    s.select(1, 4, 4);
    Document before = s.document();
    CrossReferences.insert(s, "heading", "Kilns", "text", engine());
    assertEquals("Kilns\nSee Kilns", s.document().plainText());
    assertEquals(1, s.document().bookmarks.size());
    s.undo();
    assertSame(before, s.document());
    s.redo();
    CrossReferences.insert(s, "heading", "Kilns", "page", engine());
    assertEquals(1, s.document().bookmarks.size());
    assertEquals("Kilns\nSee Kilns1", s.document().plainText());
    s.select(0, 1, 5);
    s.replace("ILNS");
    update(s);
    assertEquals("KILNS\nSee KILNS1", s.document().plainText());
  }

  @Test
  public void namedBookmarkReferencesFollowSelectionAndDeletedTargetsShowErrors() {
    Session s = new Session(Document.empty());
    s.replace("Kilns are hot.\nSee ");
    s.select(0, 0, 5);
    s.addBookmark("Spot");
    s.select(1, 4, 4);
    CrossReferences.insert(s, "bookmark", "Spot", "text", engine());
    assertEquals("Kilns are hot.\nSee Kilns", s.document().plainText());
    s.deleteBookmark("Spot");
    update(s);
    assertTrue(s.document().plainText().contains("Error! Reference source not found."));
  }

  @Test
  public void sameParagraphEndDoesNotIncludeTheInsertedReference() {
    Session s = new Session(Document.empty());
    s.replace("Kilns");
    s.select(0, 0, 5);
    s.applyStyle("Heading1");
    s.select(0, 5, 5);
    CrossReferences.insert(s, "heading", "Kilns", "text", engine());
    assertEquals("KilnsKilns", s.document().plainText());
    Bookmark mark = s.document().bookmarks.values().iterator().next();
    assertEquals("Kilns", mark.text(s.document()));
    update(s);
    assertEquals("KilnsKilns", s.document().plainText());
  }

  @Test
  public void captionsAndRelativeReferencesUseRealBookmarks() {
    Session s = new Session(Document.empty());
    s.replace("Object");
    s.insertCaption("Figure", "Details", false, "ROMAN", false, 0, "-");
    s.select(0, 6, 6);
    CrossReferences.insert(s, "figure", "Figure I: Details", "number", engine());
    assertEquals("ObjectFigure I", s.document().paragraph(0).text.plainText());
    CrossReferences.insert(s, "figure", "Figure I: Details", "aboveBelow", engine());
    assertEquals("ObjectFigure Ibelow", s.document().paragraph(0).text.plainText());
  }

  @Test
  public void pageReferenceUsesSectionRestart() {
    Session s = new Session(Document.empty());
    s.replace("Here\nTarget");
    s.select(1, 0, 6);
    s.addBookmark("Spot");
    s.replaceDocument(
        s.document()
            .section(1, new Section(s.document().page, PageFurniture.EMPTY, "nextPage", 7)));
    s.select(0, 4, 4);
    CrossReferences.insert(s, "bookmark", "Spot", "page", engine());
    assertEquals("Here7", s.document().paragraph(0).text.plainText());
  }

  @Test
  public void cyclicReferencesStayBoundedAcrossRepeatedUpdates() {
    Session s = new Session(Document.empty());
    s.replace("A\nB");
    s.select(0, 1, 1);
    s.insertField(new InlineField("REF B", "old", false, false));
    s.select(1, 1, 1);
    s.insertField(new InlineField("REF A", "old", false, false));
    s.select(0, 0, 2);
    s.addBookmark("A");
    s.select(1, 0, 2);
    s.addBookmark("B");
    for (int i = 0; i < 8; i++) update(s);
    assertEquals(
        "AError! Circular reference.\nBError! Circular reference.", s.document().plainText());
  }

  @Test
  public void nestedReferencesResolveWithoutDependingOnCachedResults() {
    Session s = new Session(Document.empty());
    s.replace("Target\nSee \nOuter ");
    s.select(0, 0, 6);
    s.addBookmark("A");
    s.select(1, 4, 4);
    s.insertField(new InlineField("REF A", "stale", false, false));
    s.select(1, 0, 5);
    s.addBookmark("B");
    s.select(2, 6, 6);
    s.insertField(new InlineField("REF B", "stale", false, false));
    update(s);
    assertEquals("Target\nSee Target\nOuter See Target", s.document().plainText());
    s.insertField(new InlineField("REF Missing", "locked", true, false));
    update(s);
    assertTrue(s.document().plainText().endsWith("locked"));
  }
}
