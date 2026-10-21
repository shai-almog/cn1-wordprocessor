package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Comment ranges across cells and in note stories; Zotero fields in cells and notes. */
public class StoryRangesTest {
  private static final ZoteroBridge.Host HOST = (t, i, b) -> 1;
  private static final String CODE =
      "ITEM CSL_CITATION {\"citationID\":\"a1\",\"citationItems\":[{\"id\":7}],\"schema\":\"x\"}";

  private static int table(Document d) {
    for (int b = 0; b < d.blocks.size(); b++) if (d.blocks.get(b) instanceof Table) return b;
    return -1;
  }

  private static Document roundTrip(Document d) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    return Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
  }

  private static Session tableSession() {
    var s = new Session(Document.empty());
    s.replace("Body");
    s.insertTable(2, 2);
    int t = table(s.document());
    String[] text = {"one", "two", "three", "four"};
    for (int i = 0; i < 4; i++) {
      s.enterCell(t, i / 2, i % 2);
      s.replace(text[i]);
    }
    s.leaveCell();
    return s;
  }

  @Test
  public void commentSpansSelectedCellsAndRoundTripsDocx() throws Exception {
    var s = tableSession();
    int t = table(s.document());
    s.selectCells(t, 0, 1, 1, 0);
    s.addComment("Ann", "Across");
    Document d = s.document();
    assertEquals(1, d.comments.size());
    ReviewComment c = d.comments.get(0);
    var table = (Table) d.blocks.get(t);
    assertEquals(table.cell(0, 0).get(0).id, c.startId);
    assertEquals(table.cell(1, 1).get(0).id, c.endId);
    assertEquals(0, c.start);
    assertEquals(4, c.end);
    assertTrue(ReviewComment.validRange(d, c.startId, c.start, c.endId, c.end));
    // Editing a cell keeps the cross-cell range.
    s.enterCell(t, 1, 1);
    s.select(0, 4, 4);
    s.replace("!");
    s.leaveCell();
    assertEquals(1, s.document().comments.size());
    // Selecting the comment selects its cells.
    s.selectComment(c.id);
    assertNotNull(s.cellSelection());
    Document back = roundTrip(s.document());
    assertEquals(1, back.comments.size());
    var bt = (Table) back.blocks.get(table(back));
    assertEquals(bt.cell(0, 0).get(0).id, back.comments.get(0).startId);
    assertEquals(bt.cell(1, 1).get(0).id, back.comments.get(0).endId);
    assertEquals("Across", back.comments.get(0).text);
  }

  @Test
  public void bodyIntoTableRangeIsValidAndFollowsBodyEdits() {
    var s = tableSession();
    Document d = s.document();
    var cell = ((Table) d.blocks.get(table(d))).cell(0, 1).get(0);
    var c = new ReviewComment("c9", "Ann", "Mixed", d.paragraph(0).id, 2, cell.id, 3, false);
    assertTrue(ReviewComment.validRange(d, c.startId, c.start, c.endId, c.end));
    // A range cannot end before it starts.
    assertFalse(ReviewComment.validRange(d, cell.id, 0, d.paragraph(0).id, 1));
    s = new Session(d.comments(Arrays.asList(c)));
    s.select(0, 0, 0);
    s.replace("XX");
    ReviewComment moved = s.document().comments.get(0);
    assertEquals(4, moved.start);
    assertEquals(cell.id, moved.endId);
  }

  private static String noteId(Document d) {
    return Notes.references(d).get(0).note.id;
  }

  @Test
  public void commentInFootnoteStoryIsEditedAndRoundTrips() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Claim");
    s.insertNote("footnote", "Source text", "");
    String id = noteId(s.document());
    s.enterNote(id);
    assertEquals(id, s.noteStory());
    assertFalse(s.inCell());
    s.select(0, 0, 6);
    s.addComment("Ann", "Check");
    s.select(0, 11, 11);
    s.replace(" more");
    s.leaveCell();
    assertNull(s.noteStory());
    Document d = s.document();
    assertEquals("Source text more", Notes.references(d).get(0).note.plainText());
    assertEquals(1, d.comments.size());
    ReviewComment c = d.comments.get(0);
    ReviewComment.Anchor a = ReviewComment.locate(d, c.startId);
    assertNotNull(a.note);
    assertEquals(0, c.start);
    assertEquals(6, c.end);
    // Body edits keep the note's comment.
    s.select(0, 0, 0);
    s.replace("A ");
    assertEquals(1, s.document().comments.size());
    s.selectComment(c.id);
    assertEquals(id, s.noteStory());
    assertEquals(6, s.selection().end());
    s.leaveCell();
    Document back = roundTrip(s.document());
    assertEquals(1, back.comments.size());
    ReviewComment r = back.comments.get(0);
    assertNotNull(ReviewComment.locate(back, r.startId).note);
    assertEquals(0, r.start);
    assertEquals(6, r.end);
    assertEquals("Check", r.text);
  }

  private static ZoteroBridge bridge(Session s) {
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3));
    return b;
  }

  @Test
  public void fieldSelectOnNoteCitationSelectsInsideTheNote() {
    var s = new Session(Document.empty());
    s.replace("Claim");
    var b = bridge(s);
    var t = (List<?>) b.handle("Document_insertField", Arrays.<Object>asList(1, "ReferenceMark", 1));
    Object id = t.get(0);
    b.handle("Field_setCode", Arrays.<Object>asList(1, id, CODE));
    b.handle("Field_setText", Arrays.<Object>asList(1, id, "Doe 2020", false));
    b.handle("Field_select", Arrays.<Object>asList(1, id));
    assertEquals(noteId(s.document()), s.noteStory());
    Selection sel = s.selection();
    var p = s.editingDocument().paragraph(sel.endBlock());
    String picked = p.text.slice(sel.start(), sel.end()).plainText().replace(InlineField.OBJECT, "");
    assertEquals("Doe 2020", picked);
    assertNotNull(b.handle("Document_cursorInField", Arrays.<Object>asList(1)));
    // Body fields are reached again from the note.
    s.leaveCell();
    assertNull(b.handle("Document_cursorInField", Arrays.<Object>asList(1)));
  }

  @Test
  public void citationsInCellsAndNotesRoundTripDocx() throws Exception {
    var s = tableSession();
    int t = table(s.document());
    s.enterCell(t, 0, 0);
    s.select(0, 3, 3);
    var b = bridge(s);
    var c1 = (List<?>) b.handle("Document_insertField", Arrays.<Object>asList(1, "ReferenceMark", 0));
    b.handle("Field_setCode", Arrays.<Object>asList(1, c1.get(0), CODE));
    b.handle("Field_setText", Arrays.<Object>asList(1, c1.get(0), "(Cell 1)", false));
    s.leaveCell();
    s.select(0, 4, 4);
    var c2 = (List<?>) b.handle("Document_insertField", Arrays.<Object>asList(1, "ReferenceMark", 1));
    b.handle("Field_setCode", Arrays.<Object>asList(1, c2.get(0), CODE));
    b.handle("Field_setText", Arrays.<Object>asList(1, c2.get(0), "Note cite", false));
    b.handle("Document_complete", Arrays.<Object>asList(1));
    if (s.inCell() || s.noteStory() != null) s.leaveCell();
    Document back = roundTrip(s.document());
    // The editor opens the read fields as ranges in every story (ZoteroBridge.expand).
    var fields = ZoteroBridge.fields(ZoteroBridge.expand(back));
    assertEquals(2, fields.size());
    int inCell = -1, inNote = -1;
    for (int i = 0; i < 2; i++) {
      if (fields.get(i).table >= 0) inCell = i;
      if (fields.get(i).note != null) inNote = i;
    }
    assertTrue(inCell >= 0 && inNote >= 0);
    assertTrue(fields.get(inCell).field.rangeStart());
    assertTrue(fields.get(inNote).field.rangeStart());
    var reopened = new Session(back);
    var b2 = bridge(reopened);
    @SuppressWarnings("unchecked")
    var ids = (List<Object>) ((List<Object>) b2.handle("Document_getFields", Arrays.<Object>asList(1, "ReferenceMark"))).get(0);
    var texts = new java.util.HashSet<Object>();
    for (Object id : ids) texts.add(b2.handle("Field_getText", Arrays.<Object>asList(1, id)));
    assertEquals(new java.util.HashSet<Object>(Arrays.asList("(Cell 1)", "Note cite")), texts);
  }

  private static Paragraph atomic(String id, String code, String result) {
    var f = new InlineField(Zotero.stored(code), result, false, false);
    return new Paragraph(id, RichText.of("x", TextStyle.DEFAULT).concat(RichText.field(f, TextStyle.DEFAULT)));
  }

  @Test
  public void expandAndBalanceCoverCellsAndNotes() {
    var cell = new Sequence<Paragraph>().append(atomic("p1", CODE, "(Doe)"));
    var row = Arrays.asList(cell);
    var table = new Table("t1", Arrays.asList(row));
    var note = new Note("n1", "footnote", "", "1", new Sequence<Paragraph>().append(atomic("p2", CODE, "Doe, T.")));
    var body = new Paragraph("p3", RichText.of("Claim", TextStyle.DEFAULT).concat(RichText.note(note, TextStyle.DEFAULT)));
    var d = new Document(new Sequence<Block>().append(body).append(table), Document.empty().page, "", 10);
    Document e = ZoteroBridge.expand(d);
    var fields = ZoteroBridge.fields(e);
    assertEquals(2, fields.size());
    for (var f : fields) {
      assertTrue(f.field.rangeStart());
      assertTrue(f.hasEnd);
    }
    // Drop every end mark: balance re-adds one in each story.
    Document broken =
        e.blocks(
            e.blocks, e.nextId);
    broken = ZoteroBridge.everyStory(broken, x -> {
      Sequence<Block> blocks = x.blocks;
      for (int i = 0; i < blocks.size(); i++) {
        var p = (Paragraph) blocks.get(i);
        RichText r = p.text;
        for (int k = r.length() - 1; k >= 0; k--)
          if (r.objectAt(k) && r.fieldAt(k) != null && r.fieldAt(k).rangeEnd())
            r = r.slice(0, k).concat(r.slice(k + 1, r.length()));
        if (r != p.text) blocks = blocks.set(i, p.text(r));
      }
      return blocks == x.blocks ? x : x.blocks(blocks, x.nextId);
    });
    for (var f : ZoteroBridge.fields(broken)) assertFalse(f.hasEnd);
    Document fixed = ZoteroBridge.balance(broken, null);
    for (var f : ZoteroBridge.fields(fixed)) assertTrue(f.hasEnd);
  }

  @Test
  public void bibliographyInsideNoteTakesBibliographyStyle() {
    var s = new Session(Document.empty());
    s.replace("Refs");
    var b = bridge(s);
    var t = (List<?>) b.handle("Document_insertField", Arrays.<Object>asList(1, "Bookmark", 1));
    b.handle("Field_setCode", Arrays.<Object>asList(1, t.get(0), "BIBL {} CSL_BIBLIOGRAPHY"));
    b.handle("Field_setText", Arrays.<Object>asList(1, t.get(0), "Doe. A.\nRoe. B.", false));
    b.handle("Document_complete", Arrays.<Object>asList(1));
    Note n = Notes.references(s.document()).get(0).note;
    assertEquals(2, n.paragraphs.size());
    for (Paragraph p : n.paragraphs) assertEquals("Bibliography", p.style);
    assertTrue(s.document().styles.containsKey("Bibliography"));
  }
}
