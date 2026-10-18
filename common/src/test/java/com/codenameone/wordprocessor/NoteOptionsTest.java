package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import org.junit.Test;

public class NoteOptionsTest {
  private NoteOptions.Properties props(String format, int start, String restart, String position) {
    return new NoteOptions.Properties(format, start, restart, position);
  }
  private Session twoSections(String kind) {
    var s = new Session(Document.empty());
    s.replace("First "); s.insertNote(kind, "First note", "");
    s.sectionBreak("nextPage"); s.replace("Second "); s.insertNote(kind, "Second note", "");
    return s;
  }
  @Test public void sparseOverridesFollowDefaultsWithoutLosingSettingsInImmutableCopies() {
    var s = twoSections("footnote");
    s.noteOptions("footnote", props("upperRoman", 3, "continuous", "beneathText"), true);
    s.noteOptions("footnote", props(null, 9, "eachSect", null), false);
    assertEquals("III", Notes.all(s.document()).get(0).label);
    assertEquals("IX", Notes.all(s.document()).get(1).label);
    var original = s.document();
    var copied = original.title("Renamed").page(PageSpec.A4).styles(original.styles)
        .furniture(original.furniture).comments(original.comments).bookmarks(original.bookmarks)
        .trackChanges(true).blocks(original.blocks, original.nextId);
    assertEquals("upperRoman", copied.notes.footnote.numFmt);
    assertEquals(9, copied.lastSection.notes.footnote.numStart);
    assertNull(copied.lastSection.notes.footnote.numFmt);
    s.noteOptions("footnote", props("lowerLetter", 0, null, null), true);
    assertEquals("c", Notes.all(s.document()).get(0).label);
    assertEquals("i", Notes.all(s.document()).get(1).label);
    assertEquals(9, s.document().lastSection.notes.footnote.numStart);
    s.undo(); assertSame(original, s.document());
  }
  @Test public void selectedSectionsAndCellSelectionArePreserved() {
    var s = twoSections("footnote");
    s.selectRange(1, 1, 0, 2);
    var selected = s.selection();
    s.noteOptions("footnote", props("upperLetter", 4, "eachSect", null), false);
    assertEquals(selected.anchorBlock, s.selection().anchorBlock);
    assertEquals(selected.anchor, s.selection().anchor);
    assertEquals("D", Notes.all(s.document()).get(0).label);
    assertEquals("D", Notes.all(s.document()).get(1).label);
    s.insertTable(1, 1);
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) block = i;
    s.enterCell(block, 0, 0); s.replace("Cell"); s.select(0, 1, 3);
    s.noteOptions("footnote", props("decimalZero", 0, null, null), false);
    assertTrue(s.inCell()); assertEquals("el", s.selectedText());
    s.undo(); assertTrue(s.inCell()); assertEquals("el", s.selectedText());
  }
  @Test public void nativeAndStandardDocxRoundTripDefaultsAndSparseOverrides() throws Exception {
    var s = twoSections("endnote");
    s.noteOptions("endnote", props("upperLetter", 5, "continuous", "docEnd"), true);
    s.noteOptions("endnote", props(null, 11, "eachSect", "sectEnd"), false);
    String xml = DocumentXml.write(s.document());
    assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
    var bytes = new ByteArrayOutputStream(); Docx.write(s.document(), bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertEquals(Collections.emptyList(), opened.warnings);
    assertEquals("upperLetter", opened.document.notes.endnote.numFmt);
    assertNull(opened.document.lastSection.notes.endnote.numFmt);
    assertEquals(11, opened.document.lastSection.notes.endnote.numStart);
    assertEquals("sectEnd", opened.document.lastSection.notes.endnote.pos);
    assertEquals("E", Notes.all(opened.document).get(0).label);
    assertEquals("K", Notes.all(opened.document).get(1).label);
    bytes.reset(); Docx.write(opened.document, opened, bytes, false);
    assertEquals("K", Notes.all(Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document).get(1).label);
  }
  @Test public void physicalPagesRestartLabelsAndUnchangedPaginationReusesMeasurements() {
    var s = twoSections("footnote");
    s.noteOptions("footnote", props("upperRoman", 7, "eachPage", "pageBottom"), true);
    var engine = new LayoutEngine(new PdfMetrics());
    var layout = engine.layout(s.document(), 0);
    assertEquals(2, layout.pages.size());
    assertEquals("VII", Notes.all(layout.document).get(0).label);
    assertEquals("VII", Notes.all(layout.document).get(1).label);
    assertTrue(layout.warnings.isEmpty());
    assertEquals(0, engine.layout(s.document(), 1).measuredParagraphs);
    assertTrue(layout.pages.get(1).notes.get(0).spans.get(0).displayText.startsWith("VII"));
    s.section(s.activeSection().raw.start("continuous"));
    layout = engine.layout(s.document(), 2);
    assertEquals(1, layout.pages.size());
    assertEquals("VIII", Notes.all(layout.document).get(1).label);
  }
  @Test public void belowTextMovesNotesUpAndSectionEndPrecedesFollowingBody() {
    var s = twoSections("footnote");
    var engine = new LayoutEngine(new PdfMetrics());
    float bottom = engine.layout(s.document(), 0).pages.get(0).notes.get(0).y;
    s.noteOptions("footnote", props(null, 0, null, "beneathText"), true);
    var page = engine.layout(s.document(), 1).pages.get(0);
    assertTrue(page.notes.get(0).y < bottom - 100);
    var body = page.lines.get(page.lines.size() - 1);
    assertTrue(page.notes.get(0).y >= body.y + body.height);
    var e = twoSections("endnote");
    e.select(0, 0, 0); e.noteOptions("endnote", props(null, 0, null, "sectEnd"), false);
    var result = engine.layout(e.document(), 2);
    assertTrue(result.pages.get(0).lines.stream().anyMatch(l -> l.block == -2));
    assertTrue(result.pages.get(1).lines.stream().anyMatch(l -> l.block == -2));
    assertFalse(result.pages.get(0).lines.stream().anyMatch(l -> l.block == 1));
  }
  @Test public void customMarksDoNotConsumeNumbersAndInvalidOptionsDoNotMutate() {
    var s = new Session(Document.empty());
    s.insertNote("footnote", "Custom", "*"); s.insertNote("footnote", "Automatic", "");
    s.noteOptions("footnote", props("decimalZero", 5, null, null), true);
    assertEquals("*", Notes.all(s.document()).get(0).label);
    assertEquals("05", Notes.all(s.document()).get(1).label);
    var before = s.document();
    try { s.noteOptions("endnote", props(null, 0, "eachPage", null), true); fail(); }
    catch (IllegalArgumentException expected) { assertSame(before, s.document()); }
    assertEquals("BB", NoteOptions.label(28, "upperLetter"));
    assertEquals("††", NoteOptions.label(6, "chicago"));
    assertEquals("11th", NoteOptions.label(11, "ordinal"));
    assertEquals("21st", NoteOptions.label(21, "ordinal"));
  }
  @Test public void narrowScreenReflowKeepsThePrintedNoteNumbers() {
    var s = new Session(Document.empty());
    s.replace("First "); s.insertNote("footnote", "First note", "");
    var text = new StringBuilder("\n");
    for (int i = 0; i < 200; i++) text.append("word ");
    s.replace(text.toString()); s.insertNote("footnote", "Second note", "");
    s.noteOptions("footnote", props("decimal", 1, "eachPage", "pageBottom"), true);
    var engine = new LayoutEngine(new PdfMetrics());
    var print = engine.layout(s.document(), 0);
    assertEquals(1, print.pages.size());
    var narrow = engine.reflow(s.document(), 0, 120);
    assertTrue(narrow.pages.size() > print.pages.size());
    assertEquals("2", Notes.all(narrow.document).get(1).label);
    assertEquals("1", Notes.all(narrow.document).get(0).label);
  }
}
