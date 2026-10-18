package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class OfficeCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @After
  public void reset() {
    AppOptions.reset();
    OfficeCommands.research(null);
  }

  @Test
  public void optionsValidateAndPersist() {
    final var saved = new ArrayList<Map<String, String>>();
    AppOptions.install(v -> saved.add(v), null);
    var s = new Session(Document.empty());
    run(s, "file.options", "key", "userName", "value", "Ada");
    assertEquals("Ada", AppOptions.get("userName"));
    assertEquals("Ada", saved.get(saved.size() - 1).get("userName"));
    try {
      run(s, "file.options", "key", "units", "value", "furlongs");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(false, ((Map<?, ?>) run(s, "file.autosave")).get("value"));
    assertEquals(5, ((Map<?, ?>) run(s, "file.autosave", "value", true, "minutes", 5)).get("minutes"));
    run(s, "view.numeral", "value", "context");
    assertEquals("12", AppOptions.numerals("12", false));
    assertEquals("\u0661\u0662", AppOptions.numerals("12", true));
    AppOptions.install(null, null);
  }

  @Test
  public void autocorrectEntriesAndRules() {
    var s = new Session(Document.empty());
    run(s, "tools.autocorrect", "action", "add", "from", "wrod", "to", "word");
    assertEquals("A word", AppOptions.correct("A wrod"));
    assertEquals("Hello. The", AppOptions.correct("Hello. the"));
    assertEquals("on Monday", AppOptions.correct("on monday"));
    assertEquals("x \u00a9", AppOptions.correct("x (c)"));
    assertNull(AppOptions.correct("fine words"));
    run(s, "tools.autocorrect", "action", "option", "option", "replaceText", "value", false);
    assertNull(AppOptions.correct("A wrod"));
  }

  @Test
  public void printMarkupResolvesRevisionsWhenOff() {
    var s = new Session(Document.empty());
    s.replace("Hello");
    s.trackChanges(true);
    s.select(0, 5, 5);
    s.replace(" world");
    assertEquals(1, Revisions.list(OfficeCommands.printable(s.document())).size());
    run(s, "file.printMarkup", "value", false);
    Document printed = OfficeCommands.printable(s.document());
    assertEquals(0, Revisions.list(printed).size());
    assertEquals("Hello world", printed.paragraph(0).text.plainText());
  }

  @Test
  public void versionsSaveRestoreAndCompare() {
    var s = new Session(Document.empty());
    s.replace("first");
    run(s, "file.versions", "save", "v1");
    s.select(0, 0, 5);
    s.replace("second");
    List<?> list = (List<?>) run(s, "file.versions");
    assertEquals(1, list.size());
    assertFalse(((List<?>) ((Map<?, ?>) run(s, "file.versions", "compare", 0)).get("changes")).isEmpty());
    run(s, "file.versions", "restore", 0);
    assertEquals("first", s.document().plainText().trim());
    s.undo();
    assertEquals("second", s.document().plainText().trim());
    assertEquals(2, ((List<?>) run(s, "file.recover")).size());
  }

  @Test
  public void templatesSaveCreateAttachAndUpdate() {
    final var store = new LinkedHashMap<String, String>();
    OfficeCommands.templates(new OfficeCommands.TemplateStore() {
      public void save(String name, String xml) {
        store.put(name, xml);
      }

      public void delete(String name) {
        store.remove(name);
      }
    }, null);
    var s = new Session(Document.empty());
    run(s, "file.newFromTemplate", "name", "Report");
    assertTrue(s.document().plainText().contains("Introduction"));
    assertEquals("Heading1", s.document().paragraph(2).style);
    run(s, "file.saveTemplate", "name", "Quarterly");
    assertTrue(store.containsKey("Quarterly"));
    var t = new Session(Document.empty());
    run(t, "file.newFromTemplate", "name", "Quarterly");
    assertEquals(s.document().plainText(), t.document().plainText());
    Map<?, ?> info = (Map<?, ?>) run(t, "tools.templates", "action", "attach", "name", "Letter");
    assertEquals("Letter", info.get("attached"));
    run(t, "tools.templates", "action", "delete", "name", "Quarterly");
    assertFalse(store.containsKey("Quarterly"));
    OfficeCommands.templates(null, null);
  }

  @Test
  public void tellMeFindsAndRunsCommands() {
    var s = new Session(Document.empty());
    s.replace("abc");
    s.select(0, 0, 3);
    List<?> results = (List<?>) ((Map<?, ?>) run(s, "tools.search", "query", "bold")).get("results");
    assertEquals("format.bold", ((Map<?, ?>) results.get(0)).get("id"));
    run(s, "tools.search", "query", "bold", "run", true);
    assertTrue(s.document().paragraph(0).text.styleAt(1).bold);
  }

  @Test
  public void recordMacroAndReplay() {
    var s = new Session(Document.empty());
    run(s, "tools.recordMacro", "action", "start");
    run(s, "text.insert", "text", "Hi ");
    run(s, "tools.recordMacro", "action", "stop", "name", "Greet");
    assertEquals("Hi", s.document().plainText().trim());
    run(s, "tools.recordMacro", "action", "run", "name", "Greet");
    assertEquals("Hi Hi", s.document().plainText().trim());
    assertEquals("Greet", ((List<?>) ((Map<?, ?>) run(s, "tools.recordMacro", "action", "list")).get("macros")).get(0));
  }

  @Test
  public void researcherCitesAResult() {
    OfficeCommands.research(q -> {
      var out = new ArrayList<Map<String, String>>();
      var r = new LinkedHashMap<String, String>();
      r.put("title", "Ada Lovelace");
      r.put("author", "Wikipedia");
      r.put("year", "2026");
      r.put("url", "https://en.wikipedia.org/wiki/Ada_Lovelace");
      r.put("summary", "English mathematician");
      out.add(r);
      return out;
    });
    var s = new Session(Document.empty());
    Map<?, ?> r = (Map<?, ?>) run(s, "references.researcher", "query", "Ada", "cite", 0);
    assertNotNull(r.get("cited"));
    assertEquals(1, s.document().bibliography.sources.size());
  }

  @Test
  public void readAloudWalksSentences() {
    var s = new Session(Document.empty());
    s.replace("One two. Three four! Five");
    s.select(0, 0, 0);
    assertEquals("One two.", ((Map<?, ?>) run(s, "review.readAloud", "action", "start")).get("text"));
    assertEquals("Three four!", ((Map<?, ?>) run(s, "review.readAloud", "action", "next")).get("text"));
    assertEquals("Five", ((Map<?, ?>) run(s, "review.readAloud", "action", "next")).get("text"));
    assertEquals("Three four!", ((Map<?, ?>) run(s, "review.readAloud", "action", "previous")).get("text"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) run(s, "review.readAloud", "action", "next", "rate", 1.5)).get("reading"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) run(s, "review.readAloud", "action", "next")).get("done"));
  }

  @Test
  public void combineAddsReviewerChanges() throws IOException {
    var s = new Session(Document.empty());
    s.replace("The quick fox");
    var r = new Session(s.document());
    r.select(0, 4, 9);
    r.replace("slow");
    Map<?, ?> out = (Map<?, ?>) run(s, "review.combine", "xml", DocumentXml.write(r.document()), "author", "Bo");
    assertEquals("Bo", out.get("author"));
    assertFalse(Revisions.list(s.document()).isEmpty());
    for (Revisions.Change c : Revisions.list(s.document())) assertEquals("Bo", c.revision.author);
  }

  @Test
  public void hideInkAltPaneShareAndComments() {
    var s = new Session(Document.empty());
    s.replace("x");
    run(s, "draw.pen", "points", "0,0 10,10");
    int pictures = 0;
    for (Block b : s.document().blocks) if (b instanceof Picture) pictures++;
    assertEquals(1, pictures);
    run(s, "review.hideInk", "value", true);
    for (Block b : OfficeCommands.shown(s.document()).blocks) assertFalse(b instanceof Picture);
    Map<?, ?> pane = (Map<?, ?>) run(s, "view.altTextPane");
    Map<?, ?> ink = (Map<?, ?>) ((List<?>) pane.get("objects")).get(0);
    assertEquals("ink", ink.get("kind"));
    run(s, "view.altTextPane", "block", ink.get("block"), "text", "");
    assertEquals(1, ((Map<?, ?>) run(s, "view.altTextPane")).get("missing"));
    run(s, "view.altTextPane", "block", ink.get("block"), "text", "Signature");
    assertEquals(0, ((Map<?, ?>) run(s, "view.altTextPane")).get("missing"));
    Map<?, ?> share = (Map<?, ?>) run(s, "file.share");
    assertEquals("Document.docx", share.get("name"));
    assertTrue((Integer) share.get("bytes") > 100);
    assertEquals(Boolean.FALSE, ((Map<?, ?>) run(s, "review.comments", "value", false)).get("shown"));
  }

  @Test
  public void encryptedShareDecryptsWithThePassword() throws Exception {
    var s = new Session(Document.empty());
    s.replace("secret text");
    assertEquals(Boolean.TRUE, ((Map<?, ?>) run(s, "file.encrypt", "password", "pa55")).get("encrypted"));
    byte[] data = com.codename1.util.Base64.decode(((String) ((Map<?, ?>) run(s, "file.share")).get("base64")).getBytes("UTF-8"));
    assertTrue(OfficeCrypto.isEncrypted(data));
    var d = Docx.read(new java.io.ByteArrayInputStream(OfficeCrypto.decrypt(data, "pa55"))).document;
    assertEquals("secret text", d.plainText().trim());
    run(s, "file.encrypt", "password", "");
    assertFalse(OfficeCrypto.isEncrypted(com.codename1.util.Base64.decode(((String) ((Map<?, ?>) run(s, "file.share")).get("base64")).getBytes("UTF-8"))));
  }
}
