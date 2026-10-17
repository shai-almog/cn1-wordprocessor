package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class WorkflowCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @Test
  public void advancedFindWildcardsCaseAndWholeWords() {
    Session s = new Session(Document.empty());
    s.replace("Cat cat category bat");
    assertEquals(3, ((List<?>) run(s, "edit.advancedFind", "text", "cat")).size());
    assertEquals(2, ((List<?>) run(s, "edit.advancedFind", "text", "cat", "matchCase", true)).size());
    assertEquals(2, ((List<?>) run(s, "edit.advancedFind", "text", "cat", "wholeWord", true)).size());
    List<?> wild = (List<?>) run(s, "edit.advancedFind", "text", "<[cb]at>", "wildcards", true, "index", 1);
    assertEquals(2, wild.size());
    assertEquals("bat", s.selectedText());
  }

  @Test
  public void clipboardPaneRecordsCopiesAndPastes() {
    Session s = new Session(Document.empty());
    run(s, "edit.clipboardPane", "action", "clear");
    s.replace("one two");
    s.select(0, 0, 3);
    run(s, "edit.copy");
    s.select(0, 4, 7);
    run(s, "edit.copy");
    List<?> items = (List<?>) run(s, "edit.clipboardPane");
    assertEquals("two", items.get(0));
    assertEquals("one", items.get(1));
    s.select(0, 7, 7);
    run(s, "edit.clipboardPane", "action", "paste", "index", 1);
    assertEquals("one twoone", s.document().plainText().trim());
  }

  @Test
  public void spreadsheetEvaluatesFormulas() {
    Session s = new Session(Document.empty());
    run(s, "insert.spreadsheet", "data", "1\t2\n3\t4\n=SUM(ABOVE)\t=SUM(ABOVE)");
    String text = s.document().plainText();
    assertTrue(text, text.contains("4"));
    assertTrue(text, text.contains("6"));
  }

  @Test
  public void coverPageInsertsAndRemoves() {
    Session s = new Session(Document.empty());
    s.replace("Body");
    int before = s.document().blocks.size();
    run(s, "insert.coverPage", "title", "Report", "author", "Ana");
    assertTrue(s.document().blocks.size() > before);
    assertTrue(s.document().plainText().contains("Report"));
    run(s, "insert.coverPage", "remove", true);
    assertEquals(before, s.document().blocks.size());
    assertFalse(s.document().plainText().contains("Report"));
  }
}
