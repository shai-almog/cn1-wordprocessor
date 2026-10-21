package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.junit.Test;

/** Bookmarks in table cells, header/footer and note stories, as Wordcraft's marks in any story. */
public class BookmarkStoriesTest {
  private static LayoutEngine engine() {
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

  private static Session cellSample() {
    var s = new Session(Document.empty());
    s.replace("See ");
    s.insertTable(2, 2);
    s.setCell(1, 1, 1, "first second");
    s.enterCell(1, 1, 1);
    s.select(0, 6, 12);
    s.addBookmark("InCell");
    s.leaveCell();
    return s;
  }

  @Test
  public void cellBookmarkFollowsEditsAndGoesTo() {
    var s = cellSample();
    var d = s.document();
    assertEquals("second", d.bookmarks.get("InCell").text(d));
    s.enterCell(1, 1, 1);
    s.select(0, 0, 0);
    s.replace("the ");
    s.leaveCell();
    s.goToBookmark("InCell");
    assertTrue(s.inCell());
    assertEquals("second", s.selectedText());
    s.leaveCell();
    s.undo();
    assertEquals(6, s.document().bookmarks.get("InCell").start);
  }

  @Test
  public void cellBookmarkRoundTripsNativeAndDocx() throws Exception {
    var d = cellSample().document();
    String xml = DocumentXml.write(d);
    assertEquals("second", DocumentXml.read(xml).bookmarks.get("InCell").text(d));
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    var mark = opened.document.bookmarks.get("InCell");
    assertNotNull(mark);
    assertEquals("second", mark.text(opened.document));
  }

  @Test
  public void crossReferenceToCellBookmark() {
    var s = cellSample();
    s.select(0, 4, 4);
    CrossReferences.insert(s, "bookmark", "InCell", "text", engine());
    assertTrue(s.document().plainText(), s.document().plainText().startsWith("See second"));
  }

  @Test
  public void noteBookmarkAddsGoesToAndRoundTrips() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Body");
    s.select(0, 4, 4);
    s.insertNote("footnote", "Deep note", "");
    var note = Notes.references(s.document()).get(0).note;
    s.enterNote(note.id);
    s.select(0, 5, 9);
    s.addBookmark("InNote");
    s.leaveCell();
    var d = s.document();
    assertEquals("note", d.bookmarks.get("InNote").text(d));
    s.goToBookmark("InNote");
    assertEquals("note", s.selectedText());
    s.leaveCell();
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals("note", opened.document.bookmarks.get("InNote").text(opened.document));
    assertEquals(
        "note", DocumentXml.read(DocumentXml.write(d)).bookmarks.get("InNote").text(d));
  }

  @Test
  public void headerBookmarkRoundTripsDocxAndNative() throws Exception {
    var d = Document.empty();
    d =
        d.furniture(
            PageFurniture.EMPTY.story(
                "header",
                new Sequence<Paragraph>().append(new Paragraph("h1", RichText.of("Header text")))));
    var marks = new LinkedHashMap<String, Bookmark>();
    marks.put("Head", new Bookmark("Head", "h1", 0, "h1", 6));
    d = d.bookmarks(marks);
    assertEquals("Header", d.bookmarks.get("Head").text(d));
    // A range cannot run from the header into the body.
    try {
      new Bookmark("Bad", "h1", 0, d.blocks.get(0).id(), 0).validate(d);
      fail("cross-story bookmark accepted");
    } catch (IllegalArgumentException expected) {
    }
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals("Header", opened.document.bookmarks.get("Head").text(opened.document));
    assertEquals("Header", DocumentXml.read(DocumentXml.write(d)).bookmarks.get("Head").text(d));
    var s = new Session(d);
    s.goToBookmark("Head");
    assertFalse(s.inCell());
    assertEquals(Collections.emptyMap().size(), 0);
  }

  @Test
  public void nestedCellBookmarkGoesTo() throws Exception {
    var s = NestedTableTest.sample();
    s.select(0, 1, 4);
    s.addBookmark("Deep");
    var path = s.cellPath();
    s.leaveCell();
    var d = s.document();
    assertEquals("nne", d.bookmarks.get("Deep").text(d));
    s.goToBookmark("Deep");
    assertArrayEquals(path, s.cellPath());
    assertEquals("nne", s.selectedText());
    s.leaveCell();
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertEquals("nne", opened.document.bookmarks.get("Deep").text(opened.document));
  }
}
