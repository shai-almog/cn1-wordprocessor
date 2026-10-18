package com.codenameone.wordprocessor;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class NoteTest {
  private Session sample() {
    var s = new Session(Document.empty());
    s.replace("Body "); s.insertNote("footnote", "Footnote text", "");
    s.replace(" more "); s.insertNote("endnote", "Endnote text", "");
    s.replace(" custom "); s.insertNote("footnote", "Custom text", "*");
    return s;
  }

  @Test public void nativeAndDocxPreserveAtomicStoriesAndCustomMarks() throws Exception {
    var s = sample();
    String nativeXml = DocumentXml.write(s.document());
    assertEquals(nativeXml, DocumentXml.write(DocumentXml.read(nativeXml)));
    var out = new ByteArrayOutputStream(); Docx.write(s.document(), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(Collections.emptyList(), opened.warnings);
    var notes = Notes.all(opened.document);
    assertEquals(3, notes.size());
    assertEquals("Footnote text", notes.get(0).plainText());
    assertEquals("endnote", notes.get(1).kind);
    assertEquals("i", notes.get(1).label);
    assertEquals("*", notes.get(2).mark);
    assertEquals("Custom text", notes.get(2).plainText());
    assertEquals(s.document().plainText(), opened.document.plainText());
  }

  @Test public void formattingDeletionCopyAndUndoRetainNoteIdentity() {
    var s = sample(); var original = s.document();
    s.select(0, 0, s.document().paragraph(0).text.length());
    s.format("bold", "true"); s.changeCase("upper");
    assertEquals(3, Notes.all(s.document()).size());
    assertEquals("Footnote text", Notes.all(s.document()).get(0).plainText());
    s.replace(""); assertTrue(Notes.all(s.document()).isEmpty());
    s.undo(); assertEquals(3, Notes.all(s.document()).size());
    s.undo(); s.undo(); assertSame(original, s.document());
    s.select(0, s.document().paragraph(0).text.length(), s.document().paragraph(0).text.length());
    s.paste(original);
    var copies = Notes.all(s.document());
    assertEquals(6, copies.size());
    var ids = new HashSet<String>();
    for (Note n : copies) assertTrue(ids.add(n.id));
    assertEquals("2", copies.get(3).label);
  }

  @Test public void textExportsIncludeNoteStoriesAndHtmlLinks() {
    var s = sample();
    String html = TextFormats.html(s.document());
    assertTrue(html.contains("href=\"#note-"));
    assertTrue(html.contains("Footnote text")); assertTrue(html.contains("Endnote text"));
    String plain = TextFormats.write(s.document(), com.codename1.ui.RichTextFormat.PLAIN_TEXT);
    assertTrue(plain.contains("1 Footnote text")); assertTrue(plain.contains("i Endnote text"));
    assertTrue(plain.contains("* Custom text"));
  }

  @Test public void fieldsInsideNotesResolveAndRepeatedLayoutsReuseMeasurements() {
    var s = sample();
    var note = Notes.all(s.document()).get(0); var p = note.paragraphs.get(0);
    s.updateNote(note.id, note.paragraphs(note.paragraphs.set(0,
        p.text(RichText.field(new InlineField("PAGE", "?", false, false), TextStyle.DEFAULT)))));
    var engine = new LayoutEngine(new PdfMetrics());
    var first = engine.layout(s.document(), 0);
    s.mapFields(DocumentFields.resolver(s.document(), first, Collections.<String,String>emptyMap()));
    assertEquals("1", Notes.all(s.document()).get(0).plainText());
    engine.layout(s.document(), 1);
    var again = engine.layout(s.document(), 2);
    assertEquals(0, again.measuredParagraphs);
  }

  @Test public void referenceSuperscriptDoesNotLeakIntoTypingOrNewParagraphs() {
    for (boolean tracked : new boolean[] {false, true}) {
      var s = new Session(Document.empty().trackChanges(tracked));
      s.replace("Before "); s.insertNote("footnote", "Note", "");
      s.replace(" after\nNext paragraph");
      assertEquals("baseline", s.document().paragraph(0).text.styleAt(8).script);
      assertEquals("baseline", s.document().paragraph(1).text.styleAt(0).script);
      s.select(0, 8, 8); s.replace("X");
      assertEquals("baseline", s.document().paragraph(0).text.styleAt(8).script);
    }
    var cell = new Session(Document.empty()); cell.insertTable(1, 1); cell.enterCell(1, 0, 0);
    cell.format("bold", "true"); cell.replace("Bold");
    assertTrue(cell.editingDocument().paragraph(0).text.styleAt(0).bold);
  }

  @Test public void cellNotesPersistAndDoNotOverlapTables() throws Exception {
    var s = new Session(Document.empty().page(new PageSpec(300, 220, 20, 20, 20, 20)));
    s.insertTable(4, 2);
    int table = -1;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) table = i;
    for (int row = 0; row < 4; row++) {
      s.enterCell(table, row, 0); s.replace("Cell " + row + " ");
      s.insertNote("footnote", "Note in cell " + row + "\nSecond line", "");
    }
    s.leaveCell();
    assertEquals(4, Notes.all(s.document()).size());
    var result = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0);
    int lines = 0;
    for (LayoutEngine.Page page : result.pages) {
      lines += page.notes.size();
      if (page.notes.isEmpty()) continue;
      float top = page.notes.get(0).y;
      for (LayoutEngine.Box box : page.boxes) assertTrue(box.y + box.height <= top);
      for (LayoutEngine.Line line : page.lines) assertTrue(line.y + line.height <= top);
    }
    assertEquals(8, lines);
    var out = new ByteArrayOutputStream(); Docx.write(s.document(), out);
    assertEquals(4, Notes.all(Docx.read(new ByteArrayInputStream(out.toByteArray())).document).size());
    var id = Notes.all(s.document()).get(2).id;
    s.selectNote(id); assertTrue(s.inCell());
    s.deleteNote(id); assertEquals(3, Notes.all(s.document()).size());
    s.undo(); assertEquals(4, Notes.all(s.document()).size());
  }

  @Test public void trackedDeletionAndNoteStoryEditsCanBeRejected() throws Exception {
    var s = sample(); var id = Notes.all(s.document()).get(0).id;
    s.trackChanges(true); s.deleteNote(id);
    assertEquals(3, Notes.all(s.document()).size());
    s.resolveRevision(null, false); assertEquals(3, Notes.all(s.document()).size());
    s.deleteNote(id); s.resolveRevision(null, true); assertEquals(2, Notes.all(s.document()).size());
    var note = Notes.all(s.document()).get(0);
    var p = note.paragraphs.get(0);
    var change = new Revision("9000", "insert", "Reviewer", "2026-10-15T00:00:00Z");
    s.updateNote(note.id, note.paragraphs(note.paragraphs.set(0,
        p.text(p.text.concat(RichText.of(" Added", TextStyle.DEFAULT.revision(change)))))));
    assertTrue(Revisions.list(s.document()).stream().anyMatch(c -> c.story.startsWith("note:")));
    assertTrue(Revisions.nextId(s.document()) > 9000);
    var out = new ByteArrayOutputStream(); Docx.write(s.document(), out);
    var reopened = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    assertTrue(Notes.all(reopened).get(0).plainText().endsWith(" Added"));
    s.resolveRevision("9000", false);
    assertEquals(note.plainText(), Notes.all(s.document()).get(0).plainText());
  }

  @Test public void longNoteStartsInAvailableSpaceInsteadOfBlankingThePage() {
    var s = new Session(Document.empty().page(new PageSpec(220, 200, 20, 20, 20, 20)));
    s.replace("First paragraph.\nAnchor ");
    var text = new StringBuilder();
    for (int i = 0; i < 40; i++) text.append("Continued ").append(i).append('\n');
    s.insertNote("footnote", text.toString(), "");
    var layout = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0);
    assertEquals(2, layout.pages.get(0).lines.size());
    assertTrue(layout.pages.get(0).notes.size() > 5);
  }

  @Test public void noteTextRendersAtPageBottomAndLongNotesContinue() throws Exception {
    var s = new Session(Document.empty().page(new PageSpec(220, 200, 20, 20, 20, 20)));
    s.replace("Anchor");
    var text = new StringBuilder();
    for (int i = 0; i < 25; i++) text.append("Line ").append(i).append('\n');
    s.insertNote("footnote", text.toString(), "");
    LayoutEngine engine = PdfExporter.layoutEngine(BundledFonts.provider(path -> getClass().getResourceAsStream(path)));
    var result = engine.layout(s.document(), 0);
    assertTrue(result.pages.size() > 1);
    assertFalse(result.pages.get(0).notes.isEmpty());
    var all = new StringBuilder();
    for (LayoutEngine.Page p : result.pages) {
      float bodyEnd = p.spec.top;
      for (LayoutEngine.Line line : p.lines) bodyEnd = Math.max(bodyEnd, line.y + line.height);
      if (!p.notes.isEmpty()) assertTrue(bodyEnd <= p.notes.get(0).y);
      for (LayoutEngine.Line line : p.notes) {
        assertTrue(line.y + line.height <= p.spec.height - p.spec.bottom + .1f);
        for (LayoutEngine.Span span : line.spans) all.append(span.displayText);
      }
    }
    assertTrue(all.toString().contains("Line 24"));
    assertTrue(PdfExporter.write(s.document(), BundledFonts.provider(path -> getClass().getResourceAsStream(path))).length > 1000);
  }
}
