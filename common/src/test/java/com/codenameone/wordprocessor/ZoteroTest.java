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
    assertEquals(7, s.selection().caret);

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
    assertEquals("Doe. Title.\nRoe. Other.", s.document().paragraph(0).text.fieldAt(4).result);
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
    assertEquals(6, o.errors.size());
    for (String r : replies(out.toByteArray()).subList(0, 6)) assertTrue(r, r.startsWith("ERR:"));
    assertTrue(ZoteroBridge.fields(s.document()).isEmpty());

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
}
