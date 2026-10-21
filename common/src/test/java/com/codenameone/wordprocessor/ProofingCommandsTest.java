package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ProofingCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> issues(Object report) {
    return (List<Map<String, Object>>) ((Map<String, Object>) report).get("issues");
  }

  private static Session session(String text) {
    var s = new Session(Document.empty());
    s.replace(text);
    s.select(0, 0, 0);
    return s;
  }

  @Test
  public void tableCellsAreProofedAndChangedInPlace() {
    var s = new Session(Document.empty());
    s.replace("Body wrold");
    s.insertTable(2, 2);
    int table = -1;
    for (int b = 0; b < s.document().blocks.size(); b++) if (s.document().blocks.get(b) instanceof Table) table = b;
    s.setCell(table, 1, 1, "Now recieve here");
    s.select(0, 0, 0);
    var found = issues(run(s, "review.spelling"));
    assertEquals(2, found.size());
    assertEquals("recieve", found.get(1).get("word"));
    assertEquals(table, found.get(1).get("block"));
    assertEquals(1, found.get(1).get("row"));
    assertEquals(1, found.get(1).get("column"));
    run(s, "review.spelling", "action", "next");
    assertEquals("wrold", s.selectedText());
    run(s, "review.spelling", "action", "next");
    assertTrue(s.inCell());
    assertEquals("recieve", s.selectedText());
    run(s, "review.spelling", "action", "change", "replacement", "receive");
    if (s.inCell()) s.leaveCell();
    assertEquals("Now receive here", ((Table) s.document().blocks.get(table)).cell(1, 1).get(0).text.plainText());
    assertEquals(1, issues(run(s, "review.spelling")).size());
  }

  @Test
  public void checkReportsMisspellingsWithSuggestionsAndNextSelects() {
    Session s = session("Hello wrold and recieve");
    var found = issues(run(s, "review.spelling"));
    assertEquals(2, found.size());
    assertEquals("wrold", found.get(0).get("word"));
    assertEquals(6, found.get(0).get("start"));
    assertTrue(found.get(0).get("suggestions").toString(), ((List<?>) found.get(0).get("suggestions")).contains("world"));
    run(s, "review.spelling", "action", "next");
    assertEquals("wrold", s.selectedText());
    run(s, "review.spelling", "action", "next");
    assertEquals("recieve", s.selectedText());
  }

  @Test
  public void changeIgnoreAndAddToDictionary() {
    Session s = session("Hello wrold and recieve and qqzzx");
    run(s, "review.spelling", "action", "change", "word", "wrold", "replacement", "world");
    assertTrue(s.document().paragraph(0).text.plainText().startsWith("Hello world and"));
    run(s, "review.spelling", "action", "changeAll", "word", "recieve", "replacement", "receive");
    run(s, "review.spelling", "action", "add", "word", "qqzzx");
    assertEquals(0, issues(run(s, "review.spelling")).size());
    assertEquals("qqzzx", s.document().properties.setting("proof.dictionary", ""));
    Session t = session("Zzqx here");
    run(t, "review.spelling", "action", "ignoreAll", "word", "Zzqx");
    assertEquals(0, issues(run(t, "review.spelling")).size());
    Session u = session("Zzqx here");
    run(u, "review.spelling", "action", "ignore", "word", "Zzqx");
    assertEquals(0, issues(run(u, "review.spelling")).size());
  }

  @Test
  public void grammarIssuesAreReported() {
    Session s = session("This is a apple and the the end.");
    var all = issues(run(s, "review.spelling")).toString();
    assertTrue(all, all.contains("kind=grammar"));
    assertTrue(all, all.contains("Repeated word"));
    assertTrue(all, all.contains("Use \"an\""));
  }

  @Test
  public void nonEnglishRunsAreSkipped() {
    Session s = session("Hello wrold");
    s.select(0, 6, 11);
    s.format("lang", "fr-FR");
    assertEquals(0, issues(run(s, "review.spelling")).size());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void thesaurusLooksUpAndReplaces() {
    Session s = session("a good day");
    s.select(0, 2, 6);
    Map<String, Object> r = (Map<String, Object>) run(s, "review.thesaurus");
    assertEquals("good", r.get("word"));
    assertTrue(((List<?>) r.get("synonyms")).contains("excellent"));
    run(s, "review.thesaurus", "word", "good", "replacement", "excellent");
    assertEquals("a excellent day", s.document().paragraph(0).text.plainText());
  }
}
