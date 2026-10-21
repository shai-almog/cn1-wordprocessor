package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class ZoteroTest {
  private static final String CODE =
      "ITEM CSL_CITATION {\"citationID\":\"a1\",\"citationItems\":[{\"id\":7}],\"schema\":\"x\"}";

  /** Builds the bytes a fake Zotero sends: one call frame per entry, txid 1..n. */
  private static byte[] script(String... calls) throws IOException {
    var out = new ByteArrayOutputStream();
    for (int i = 0; i < calls.length; i++) Zotero.writeFrame(out, i + 1, calls[i].getBytes("UTF-8"));
    return out.toByteArray();
  }

  private static List<String> replies(byte[] bytes) throws IOException {
    var in = new ByteArrayInputStream(bytes);
    var out = new ArrayList<String>();
    Object[] f;
    while ((f = Zotero.readFrame(in)) != null) out.add(new String((byte[]) f[1], "UTF-8"));
    return out;
  }

  /** A fake Zotero connection replaying a transcript and recording what the editor sent. */
  private static final class Fake implements Zotero.Connector, Zotero.Connection {
    final ByteArrayInputStream in;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    boolean closed;

    Fake(byte[] script) {
      in = new ByteArrayInputStream(script);
    }

    public Zotero.Connection open() {
      return this;
    }

    public InputStream input() {
      return in;
    }

    public OutputStream output() {
      return out;
    }

    public void close() {
      closed = true;
    }
  }

  private static final ZoteroBridge.Host HOST = (t, i, b) -> 1;

  @Test
  public void addCitationTranscriptInsertsFieldStoresPrefsAndRoundTripsDocx() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Claim ");
    var bridge = new ZoteroBridge(s, HOST);
    var fake =
        new Fake(
            script(
                "[\"Application_getActiveDocument\",[3]]",
                "[\"Document_getDocumentData\",[1]]",
                "[\"Document_setDocumentData\",[1,\"<data><pref name=\\\"fieldType\\\" value=\\\"ReferenceMark\\\"/></data>\"]]",
                "[\"Document_cursorInField\",[1]]",
                "[\"Document_canInsertField\",[1]]",
                "[\"Document_insertField\",[1,\"ReferenceMark\",0]]",
                "[\"Field_setCode\",[1,1,\"" + CODE.replace("\"", "\\\"") + "\"]]",
                "[\"Field_setText\",[1,1,\"{\\\\rtf (Doe, 2020)}\",true]]",
                "[\"Document_getFields\",[1,\"ReferenceMark\"]]",
                "[\"Field_getText\",[1,1]]",
                "[\"Document_complete\",[1]]",
                "[\"Never_reached\",[]]"));
    var outcome = Zotero.run(fake, Zotero.Command.ADD_EDIT_CITATION, bridge);
    assertTrue(outcome.completed);
    assertTrue(fake.closed);
    assertEquals(11, outcome.calls);
    assertTrue(outcome.errors.toString(), outcome.errors.isEmpty());
    var r = replies(fake.out.toByteArray());
    assertEquals("{\"command\":\"addEditCitation\",\"templateVersion\":1}", r.get(0));
    assertEquals("[3,1]", r.get(1));
    assertEquals("\"\"", r.get(2));
    assertEquals("null", r.get(4));
    assertEquals("true", r.get(5));
    assertEquals("[1,\"TEMP\",0]", r.get(6));
    assertTrue(r.get(9), r.get(9).startsWith("[[1],[\"ITEM CSL_CITATION {"));
    assertEquals("\"(Doe, 2020)\"", r.get(10));
    assertEquals(12, r.size());

    var d = s.document();
    assertEquals("Claim (Doe, 2020)", d.paragraph(0).text.plainText());
    assertEquals(Zotero.PREFIX + CODE, d.paragraph(0).text.fieldAt(6).instruction);
    assertTrue(d.properties.setting(Zotero.PREFS_SETTING, "").contains("value=\"Field\""));
    // The caret ends after the citation's end mark: "Claim " + start + text + end.
    assertEquals(19, s.selection().caret);
    assertTrue(d.paragraph(0).text.fieldAt(6).rangeStart());
    assertTrue(d.paragraph(0).text.fieldAt(18).rangeEnd());

    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    var opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    var f = opened.paragraph(0).text.fieldAt(6);
    assertEquals(Zotero.PREFIX + CODE, f.instruction);
    assertEquals("(Doe, 2020)", f.result);
    assertEquals(d.properties.setting(Zotero.PREFS_SETTING, ""), opened.properties.setting(Zotero.PREFS_SETTING, ""));
    var reopened = new ZoteroBridge(new Session(opened), HOST);
    reopened.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    assertTrue(Zotero.prefs(opened).contains("ReferenceMark"));
    assertEquals(1, ((List<?>) ((List<?>) reopened.handle("Document_getFields", Arrays.<Object>asList(1L))).get(0)).size());
  }

  @Test
  public void longPreferencesSplitIntoChunkedCustomProperties() throws Exception {
    var sb = new StringBuilder("<data>");
    for (int i = 0; i < 100; i++) sb.append("<pref name=\"p").append(i).append("\" value=\"v\"/>");
    String prefs = sb.append("</data>").toString();
    var d = Document.empty().properties(Zotero.prefs(Document.empty().properties, prefs));
    var bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    String custom = docPart(bytes.toByteArray(), "docProps/custom.xml");
    assertTrue(custom.contains("ZOTERO_PREF_1\""));
    assertTrue(custom.contains("ZOTERO_PREF_" + ((prefs.length() + 254) / 255) + "\""));
    var back = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertEquals(prefs, back.properties.setting(Zotero.PREFS_SETTING, ""));
  }

  private static String docPart(byte[] zip, String name) throws IOException {
    var z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip));
    java.util.zip.ZipEntry e;
    while ((e = z.getNextEntry()) != null)
      if (e.getName().equals(name)) return new String(z.readAllBytes(), "UTF-8");
    return "";
  }

  @Test
  public void bibliographyStyleAppliedAsWordcraft() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Refs ");
    s.select(0, 5, 5);
    s.insertField(new InlineField(Zotero.PREFIX + "BIBL {} CSL_BIBLIOGRAPHY", "old", false, false));
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    b.handle("Document_getFields", Arrays.<Object>asList(1L));
    // Hanging indent 0.5in, double spacing, 12pt entry spacing, one tab stop at 1in (twips).
    b.handle("Document_setBibliographyStyle",
        Arrays.<Object>asList(1L, -720L, 720L, 480L, 240L, Arrays.<Object>asList(1440L), 1L));
    b.handle("Field_setText", Arrays.<Object>asList(1L, 1L, "{\\rtf Doe. Title.\\par}", true));
    Paragraph p = s.document().paragraph(0);
    assertEquals("Bibliography", p.style);
    ParagraphStyle st = s.document().styles.get("Bibliography");
    assertEquals(36f, st.paragraph.indent, 0.01f);
    assertEquals(-36f, st.paragraph.format.firstIndent, 0.01f);
    assertEquals(2f, st.paragraph.lineSpacing, 0.01f);
    assertEquals(0f, st.paragraph.before, 0.01f);
    assertEquals(12f, st.paragraph.after, 0.01f);
    assertEquals(1, st.paragraph.format.tabs.size());
    assertEquals(72f, st.paragraph.format.tabs.get(0).position, 0.01f);
    assertEquals(36f, p.indent, 0.01f);
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(s.document(), out);
    String styles = docPart(out.toByteArray(), "word/styles.xml");
    int at = styles.indexOf("w:styleId=\"Bibliography\"");
    assertTrue(styles, at >= 0);
    String def = styles.substring(at, styles.indexOf("</w:style>", at));
    assertTrue(def, def.contains("w:hanging=\"720\"") && def.contains("w:after=\"240\"") && def.contains("w:pos=\"1440\""));
  }

  @Test
  public void editBibliographyDeleteAndUnlink() throws Exception {
    var s = new Session(Document.empty());
    s.replace("A B");
    s.select(0, 1, 1);
    s.insertField(new InlineField(Zotero.PREFIX + CODE, "(X)", false, false));
    s.select(0, 4, 4);
    s.insertField(new InlineField(Zotero.PREFIX + "BIBL {} CSL_BIBLIOGRAPHY", "old", false, false));
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    var fields = (List<?>) b.handle("Document_getFields", Arrays.<Object>asList(1L));
    assertEquals(Arrays.asList(1L, 2L), fields.get(0));
    b.handle("Field_setText", Arrays.<Object>asList(1L, 2L, "{\\rtf Doe. Title.\\par Roe. Other.\\par}", true));
    // As Wordcraft: the bibliography is a range field whose entries are real paragraphs.
    assertTrue(s.document().paragraph(0).text.fieldAt(4).rangeStart());
    assertEquals("Roe. Other.", s.document().paragraph(1).text.plainText());
    assertEquals("Bibliography", s.document().paragraph(1).style);
    assertEquals("Doe. Title.\nRoe. Other.", b.handle("Field_getText", Arrays.<Object>asList(1L, 2L)));
    s.select(0, 1, 2);
    assertEquals(Arrays.asList(1L, CODE, 0), b.handle("Document_cursorInField", Arrays.<Object>asList(1L)));
    b.handle("Field_removeCode", Arrays.<Object>asList(1L, 1L));
    assertEquals("A(X) B", s.document().paragraph(0).text.plainText().substring(0, 6));
    b.handle("Field_delete", Arrays.<Object>asList(1L, "2"));
    assertTrue(ZoteroBridge.fields(s.document()).isEmpty());
    try {
      b.handle("Field_getText", Arrays.<Object>asList(1L, 2L));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private static List<Object> args(Object... v) {
    return Arrays.asList(v);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> ids(Object fields) {
    return (List<Object>) ((List<Object>) fields).get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> notes(Object fields) {
    return (List<Object>) ((List<Object>) fields).get(2);
  }

  @Test
  public void citationsInTableCellsAreListedAndEdited() {
    var s = new Session(Document.empty());
    s.replace("Body");
    s.insertTable(1, 2);
    int table = -1;
    for (int b = 0; b < s.document().blocks.size(); b++) if (s.document().blocks.get(b) instanceof Table) table = b;
    s.enterCell(table, 0, 1);
    s.replace("Cell ");
    var bridge = new ZoteroBridge(s, HOST);
    bridge.handle("Application_getActiveDocument", args(3));
    assertEquals(Boolean.TRUE, bridge.handle("Document_canInsertField", args(1)));
    var t = (List<?>) bridge.handle("Document_insertField", args(1, "ReferenceMark", 0));
    Object id = t.get(0);
    bridge.handle("Field_setCode", args(1, id, CODE));
    bridge.handle("Field_setText", args(1, id, "(Doe, 2020)", false));
    bridge.handle("Document_complete", args(1));
    if (s.inCell()) s.leaveCell();
    var cell = ((Table) s.document().blocks.get(table)).cell(0, 1).get(0);
    assertEquals("Cell (Doe, 2020)", cell.text.plainText().replace(InlineField.OBJECT, ""));
    var b2 = new ZoteroBridge(s, HOST);
    b2.handle("Application_getActiveDocument", args(3));
    var f = b2.handle("Document_getFields", args(1, "ReferenceMark"));
    assertEquals(1, ids(f).size());
    assertEquals("(Doe, 2020)", b2.handle("Field_getText", args(1, ids(f).get(0))));
    s.enterCell(table, 0, 1);
    s.select(0, 7, 7);
    assertNotNull(b2.handle("Document_cursorInField", args(1)));
  }

  @Test
  public void noteStylesInsertFootnotesAndConvertBetweenTextAndNotes() {
    var s = new Session(Document.empty());
    s.replace("Claim");
    var bridge = new ZoteroBridge(s, HOST);
    bridge.handle("Application_getActiveDocument", args(3));
    var t = (List<?>) bridge.handle("Document_insertField", args(1, "ReferenceMark", 1));
    Object id = t.get(0);
    assertEquals(1, t.get(2));
    bridge.handle("Field_setCode", args(1, id, CODE));
    bridge.handle("Field_setText", args(1, id, "Doe, Title, 2020.", false));
    var f = bridge.handle("Document_getFields", args(1, "ReferenceMark"));
    assertEquals(List.of(1), notes(f));
    assertEquals("Doe, Title, 2020.", bridge.handle("Field_getText", args(1, id)));
    assertEquals(1, Notes.references(s.document()).size());
    assertEquals("footnote", Notes.references(s.document()).get(0).note.kind);
    // To an endnote, then into the text: the empty notes are removed, the id is kept.
    bridge.handle("Field_convert", args(1, id, "ReferenceMark", 2));
    assertEquals("endnote", Notes.references(s.document()).get(0).note.kind);
    assertEquals(1, Notes.references(s.document()).size());
    bridge.handle("Document_convert", args(1, List.of(id), "ReferenceMark", List.of(0)));
    assertEquals(0, Notes.references(s.document()).size());
    f = bridge.handle("Document_getFields", args(1, "ReferenceMark"));
    assertEquals(List.of(id), ids(f));
    assertEquals(List.of(0), notes(f));
    assertEquals("Doe, Title, 2020.", bridge.handle("Field_getText", args(1, id)));
    assertTrue(s.document().paragraph(0).text.plainText().startsWith("Claim"));
  }

  @Test
  public void placeholdersBecomeFieldsAndTransferIsRefused() {
    var s = new Session(Document.empty());
    s.replace("See Doe here");
    s.select(0, 4, 7);
    s.format("link", "https://www.zotero.org/?k1&id=7");
    var bridge = new ZoteroBridge(s, HOST);
    bridge.handle("Application_getActiveDocument", args(3));
    var r = (List<?>) bridge.handle("Document_convertPlaceholdersToFields", args(1, List.of("k1"), 0));
    assertEquals(1, ((List<?>) r.get(0)).size());
    Object id = ((List<?>) r.get(0)).get(0);
    assertEquals("Doe", bridge.handle("Field_getText", args(1, id)));
    assertEquals(1, ZoteroBridge.fields(s.document()).size());
    try {
      bridge.handle("Document_exportDocument", args(1));
      fail();
    } catch (UnsupportedOperationException expected) {
      assertTrue(expected.getMessage().indexOf("document transfer") >= 0);
    }
  }

  @Test
  public void malformedFramesAndCallsAreReportedNotFatal() throws Exception {
    var s = new Session(Document.empty());
    var b = new ZoteroBridge(s, HOST);
    var out = new ByteArrayOutputStream();
    var o =
        Zotero.serve(
            new ByteArrayInputStream(
                script(
                    "not json",
                    "{\"a\":1}",
                    "[]",
                    "[\"Document_unknown\",[]]",
                    "[\"Field_getText\",[1,\"x\"]]",
                    "[\"Document_insertField\",[1,\"Field\",1]]",
                    "[\"Document_complete\",[1]]")),
            out,
            b);
    assertTrue(o.completed);
    assertEquals(5, o.errors.size());
    for (String r : replies(out.toByteArray()).subList(0, 5)) assertTrue(r, r.startsWith("ERR:"));
    // A note style's insertField makes a footnote holding the field (Wordcraft's insert_field).
    var list = ZoteroBridge.fields(s.document());
    assertEquals(1, list.size());
    assertEquals(1, list.get(0).noteIndex);

    var huge = new ByteArrayOutputStream();
    huge.write(new byte[] {0, 0, 0, 1, 0x7f, 0, 0, 0});
    try {
      Zotero.serve(new ByteArrayInputStream(huge.toByteArray()), new ByteArrayOutputStream(), b);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("limit"));
    }
    try {
      Zotero.serve(new ByteArrayInputStream(new byte[] {0, 0, 0, 1, 0, 0}), new ByteArrayOutputStream(), b);
      fail();
    } catch (IOException expected) {
    }
    try {
      Zotero.serve(new ByteArrayInputStream(new byte[] {0, 0, 0, 1, 0, 0, 0, 9, '['}), new ByteArrayOutputStream(), b);
      fail();
    } catch (IOException expected) {
    }
    var refused = (Zotero.Connector) () -> { throw new IOException("refused"); };
    try {
      Zotero.run(refused, Zotero.Command.REFRESH, b);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().startsWith("Zotero isn't running"));
    }
  }

  @Test
  public void jsonAndPrefNames() {
    assertEquals("[\"a\\\"\\u00e9\",1,null,true]", Zotero.json(Arrays.asList("a\"\u00e9", 1.0, null, true)));
    assertEquals(12, Zotero.prefIndex("zotero_pref_12"));
    assertEquals(0, Zotero.prefIndex("ZOTERO_PREF_x"));
    assertNull(Zotero.code("PAGE"));
    assertEquals("Caf\u00e9", ZoteroBridge.plain("{\\rtf Caf\\u233?}", true));
    assertEquals("a & b\nc", ZoteroBridge.plain("<div>a &amp; b</div><div>c</div>", true));
    assertNotNull(Zotero.Command.named("removeCodes"));
  }

  @Test
  public void richResultsKeepFormatting() throws Exception {
    var runs = ZoteroBridge.runs("{\\rtf Doe, {\\i Title}, {\\scaps x}{\\super 2}\\b B\\b0 .}", true);
    var text = new StringBuilder();
    for (var r : runs) text.append(r.text);
    assertEquals("Doe, Title, x2B.", text.toString());
    assertTrue(runs.get(1).italic && "Title".equals(runs.get(1).text.toString()));
    assertTrue(runs.get(3).smallCaps && runs.get(4).script.equals("superscript") && runs.get(5).bold);
    var html = ZoteroBridge.runs("<div class=\"csl-entry\">A <i>B</i> <span style=\"font-variant: small-caps\">c</span><sub>1</sub></div>", true);
    assertTrue(html.get(1).italic && html.get(3).smallCaps && "subscript".equals(html.get(4).script));

    var s = new Session(Document.empty());
    s.replace("X");
    s.select(0, 1, 1);
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    b.handle("Document_insertText", Arrays.<Object>asList(1L, "<div>Doe, <i>Title</i>.</div><div>Roe<sup>2</sup></div>"));
    assertEquals("XDoe, Title.", s.document().paragraph(0).text.plainText());
    assertEquals("Roe2", s.document().paragraph(1).text.plainText());
    assertTrue(s.document().paragraph(0).text.styleAt(7).italic);
    assertFalse(s.document().paragraph(0).text.styleAt(2).italic);
    assertEquals("superscript", s.document().paragraph(1).text.styleAt(3).script);

    s.select(1, 4, 4);
    s.insertField(new InlineField(Zotero.PREFIX + CODE, "(X)", false, false));
    var fields = (List<?>) b.handle("Document_getFields", Arrays.<Object>asList(1L));
    b.handle("Field_setText", Arrays.<Object>asList(1L, fields.get(0) instanceof List<?> l ? l.get(0) : 1L, "{\\rtf {\\i Ibid.}}", true));
    assertTrue(s.document().paragraph(1).text.fieldAt(4).rangeStart());
    assertTrue(s.document().paragraph(1).text.styleAt(5).italic);
    assertEquals("Ibid.", b.handle("Field_getText", Arrays.<Object>asList(1L, fields.get(0) instanceof List<?> l ? l.get(0) : 1L)));
  }

  @Test
  public void fieldResultKeepsMixedFormattingThroughLayoutDocxAndUndo() throws Exception {
    var s = new Session(Document.empty());
    s.replace("See ");
    s.select(0, 4, 4);
    s.insertField(new InlineField(Zotero.PREFIX + CODE, "(X)", false, false));
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    b.handle("Document_getFields", Arrays.<Object>asList(1L));
    b.handle("Field_setText", Arrays.<Object>asList(1L, 1L, "{\\rtf Doe, {\\i Title}{\\super 2}}", true));
    // The result is ordinary formatted text between the start mark (4) and end mark (16).
    var t = s.document().paragraph(0).text;
    assertTrue(t.fieldAt(4).rangeStart() && t.fieldAt(16).rangeEnd());
    assertEquals("Doe, Title2", b.handle("Field_getText", Arrays.<Object>asList(1L, 1L)));
    assertFalse(t.styleAt(5).italic);
    assertTrue(t.styleAt(10).italic);
    assertEquals("superscript", t.styleAt(15).script);

    // Layout draws each run in its style; the marks take no width.
    var metrics = new PdfMetrics();
    var lines = new LayoutEngine(metrics).layout(s.document(), 0).pages.get(0).allLines();
    boolean title = false;
    for (LayoutEngine.Span sp : lines.get(0).spans) {
      if (InlineField.OBJECT.equals(sp.text)) assertEquals(0f, sp.width, .01f);
      title |= sp.style.italic && sp.text.contains("Title");
    }
    assertTrue(title);

    // DOCX: formatted runs between separate and end, read back as a field with that formatting.
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    String xml = docPart(out.toByteArray(), "word/document.xml");
    int sep = xml.indexOf("fldCharType=\"separate\""), end = xml.indexOf("fldCharType=\"end\"");
    String between = xml.substring(sep, end);
    assertTrue(between, between.contains("<w:i/>") && between.contains("superscript") && between.contains(">Title<"));
    Document read = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    InlineField back = read.paragraph(0).text.fieldAt(4);
    assertEquals("Doe, Title2", back.result);
    assertEquals("5:0;5:2;1:16", back.formats);
    // Opened in the editor, it is the same range field again.
    var reopened = new Session(read);
    assertEquals(t.toString(), reopened.document().paragraph(0).text.toString());
    assertTrue(reopened.document().paragraph(0).text.styleAt(10).italic);
    // The native format (and so copy/paste) keeps the marks and runs.
    var nat = DocumentXml.read(DocumentXml.write(s.document())).paragraph(0).text;
    assertTrue(nat.fieldAt(4).rangeStart() && nat.fieldAt(16).rangeEnd() && nat.styleAt(10).italic);

    // One undo step restores the old atomic result.
    s.undo();
    assertEquals("(X)", s.document().paragraph(0).text.fieldAt(4).result);
    assertEquals("", s.document().paragraph(0).text.fieldAt(4).formats);
  }

  @Test
  public void caretMovesAndTypesInsideACitation() throws Exception {
    var s = new Session(Document.empty());
    s.replace("See ");
    s.select(0, 4, 4);
    s.insertField(new InlineField(Zotero.PREFIX + CODE, "(Doe)", false, false));
    // A document opened with an atomic citation edits it as a range field.
    s.loadDocument(s.document());
    var t = s.document().paragraph(0).text;
    assertTrue(t.fieldAt(4).rangeStart() && t.fieldAt(10).rangeEnd());
    // The caret steps through the citation's characters, as in Wordcraft.
    assertEquals(6, Graphemes.next(t, 5));
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    s.select(0, 9, 9);
    assertEquals(Arrays.asList(1L, CODE, 0), b.handle("Document_cursorInField", Arrays.<Object>asList(1L)));
    s.replace(" 2020");
    assertEquals("(Doe 2020)", b.handle("Field_getText", Arrays.<Object>asList(1L, 1L)));
    // Distinct caret positions inside the citation lay out at increasing x.
    var engine = new LayoutEngine(new PdfMetrics());
    var line = engine.layout(s.document(), 0).pages.get(0).allLines().get(0);
    float last = -1;
    for (LayoutEngine.Span sp : line.spans)
      if (sp.start > 4 && !InlineField.OBJECT.equals(sp.text)) {
        assertTrue(sp.x > last);
        last = sp.x;
      }
    // After the end mark the caret is outside the field.
    s.select(0, 16, 16);
    assertEquals(null, b.handle("Document_cursorInField", Arrays.<Object>asList(1L)));
  }

  private static Session citation() {
    var s = new Session(Document.empty());
    s.replace("See ");
    s.select(0, 4, 4);
    s.insertField(new InlineField(Zotero.PREFIX + CODE, "(Doe)", false, false));
    s.loadDocument(s.document());
    return s; // "See [start](Doe)[end]": marks at 4 and 10
  }

  @Test
  public void caretAndDeletionCrossMarksInOneStep() {
    var s = citation();
    var t = s.document().paragraph(0).text;
    assertTrue(TextNavigation.isMark(t, 4) && TextNavigation.isMark(t, 10));
    assertEquals(6, TextNavigation.step(t, 4, true));
    assertEquals(3, TextNavigation.step(t, 5, false));
    assertEquals(9, TextNavigation.step(t, 11, false));
    assertEquals(10, TextNavigation.step(t, 10, true)); // only a mark is left
    assertEquals(9, TextNavigation.word(s.document(), 0, 11, false)[1]); // ")" past the end mark
    s.select(0, 5, 5);
    s.deleteBackward(); // the space before the start mark, not the mark
    t = s.document().paragraph(0).text;
    assertEquals("See(Doe)", t.plainText().replace(InlineField.OBJECT, ""));
    assertTrue(t.fieldAt(3).rangeStart());
    s.select(0, 3, 3);
    s.deleteForward(); // the "(" after the start mark
    t = s.document().paragraph(0).text;
    assertTrue(t.fieldAt(3).rangeStart() && t.fieldAt(8).rangeEnd());
    assertEquals(9, t.length());
  }

  @Test
  public void deletingOneMarkKeepsFieldsBalanced() throws Exception {
    var s = citation();
    s.select(0, 10, 11);
    s.replace(""); // the end mark alone: the citation gets an end at its paragraph's end
    var t = s.document().paragraph(0).text;
    assertTrue(t.fieldAt(4).rangeStart() && t.fieldAt(t.length() - 1).rangeEnd());
    s.select(0, 3, 5);
    s.replace(""); // the start mark: its end goes too, the text stays as plain text
    t = s.document().paragraph(0).text;
    assertFalse(t.hasFields());
    assertEquals("See(Doe)", t.plainText());
    // DOCX export repairs an orphan made directly in the model.
    var style = s.document().paragraph(0).text.styleAt(0);
    var orphan = RichText.of("A ", style)
        .concat(RichText.field(new InlineField(Zotero.PREFIX + CODE, "", false, false).start(), style))
        .concat(RichText.of("(X)", style));
    var d = s.document();
    d = d.blocks(d.blocks.set(0, d.paragraph(0).text(orphan)), d.nextId);
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    String xml = documentXml(out.toByteArray());
    assertEquals(count(xml, "fldCharType=\"begin\""), count(xml, "fldCharType=\"end\""));
    assertEquals(1, count(xml, "fldCharType=\"separate\""));
  }

  private static String documentXml(byte[] zip) throws IOException {
    try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
      for (var e = in.getNextEntry(); e != null; e = in.getNextEntry())
        if (e.getName().equals("word/document.xml")) return new String(in.readAllBytes(), "UTF-8");
    }
    throw new IOException("no document.xml");
  }

  private static int count(String s, String what) {
    int n = 0;
    for (int at = s.indexOf(what); at >= 0; at = s.indexOf(what, at + 1)) n++;
    return n;
  }

  @Test
  public void justifiedLineWithACitationIsJustified() {
    var s = new Session(Document.empty());
    var words = new StringBuilder();
    for (int i = 0; i < 40; i++) words.append("word").append(i).append(' ');
    s.replace(words.toString());
    s.select(0, 10, 10);
    s.insertField(new InlineField(Zotero.PREFIX + CODE, "(Doe)", false, false));
    s.loadDocument(s.document());
    var engine = new LayoutEngine(new PdfMetrics());
    var left = engine.layout(s.document(), 0).pages.get(0).allLines().get(0);
    s.select(0, 0, 0);
    s.paragraph("align", "both");
    var both = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.get(0).allLines().get(0);
    assertTrue(right(both) > right(left) + 1);
  }

  private static float right(LayoutEngine.Line line) {
    var last = line.spans.get(line.spans.size() - 1);
    return last.x + last.width;
  }

  @Test
  public void bibliographyIsOneDocxParagraphPerEntry() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Refs");
    s.select(0, 4, 4);
    s.insertField(new InlineField(Zotero.PREFIX + "BIBL {} CSL_BIBLIOGRAPHY", "old", false, false));
    var b = new ZoteroBridge(s, HOST);
    b.handle("Application_getActiveDocument", Arrays.<Object>asList(3L));
    b.handle("Document_getFields", Arrays.<Object>asList(1L));
    b.handle("Field_setText", Arrays.<Object>asList(1L, 1L, "{\\rtf Doe. {\\i Title}.\\par Roe. Other.\\par}", true));
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    String xml = docPart(out.toByteArray(), "word/document.xml");
    assertFalse(xml.indexOf('\u0000') >= 0);
    String between = xml.substring(xml.indexOf("fldCharType=\"separate\""), xml.indexOf("fldCharType=\"end\""));
    // The second entry starts its own Bibliography paragraph inside the field.
    assertTrue(between, between.contains("</w:p><w:p><w:pPr><w:pStyle w:val=\"Bibliography\""));
    assertTrue(between, between.contains(">Roe. Other.<") && between.contains("<w:i/>"));
    Document back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    InlineField f = back.paragraph(0).text.fieldAt(4);
    assertEquals("Doe. Title.\nRoe. Other.", f.result);
    assertEquals(1, ZoteroBridge.fields(back).size());
    // In the editor the imported bibliography is again one paragraph per entry.
    var opened = new Session(back).document();
    assertEquals(2, opened.blocks.size());
    assertEquals("Roe. Other.", opened.paragraph(1).text.plainText());
    assertTrue(opened.paragraph(0).text.styleAt(10).italic);
  }
}
