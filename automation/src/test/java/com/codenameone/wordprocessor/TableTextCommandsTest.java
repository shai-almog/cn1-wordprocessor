package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class TableTextCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @Test
  public void textToTableDeleteCellsAndWidth() {
    var s = new Session(Document.empty());
    s.replace("a,b,c");
    s.enterParagraph();
    s.replace("d,e");
    s.selectRange(0, 0, 1, 3);
    Map<?, ?> r = (Map<?, ?>) run(s, "table.fromText", "separator", "comma");
    assertEquals(2, r.get("rows"));
    assertEquals(3, r.get("columns"));
    var t = (Table) s.document().blocks.get(0);
    assertEquals("e", Table.cellText(t.cell(1, 1)));
    assertEquals("", Table.cellText(t.cell(1, 2)));
    assertTrue(s.document().blocks.get(1) instanceof Paragraph);

    s.enterCell(0, 0, 0);
    run(s, "table.deleteCells", "shift", "left");
    t = (Table) s.document().blocks.get(0);
    assertEquals("b", Table.cellText(t.cell(0, 0)));
    assertEquals("", Table.cellText(t.cell(0, 2)));
    run(s, "table.deleteCells", "shift", "up");
    t = (Table) s.document().blocks.get(0);
    assertEquals("d", Table.cellText(t.cell(0, 0)));

    var w = (Number) run(s, "table.columnWidth", "width", 200);
    assertEquals(200, w.intValue(), 1);
    for (int i = 0; i < 4; i++) s.undo();
    assertEquals("a,b,c\nd,e", s.document().plainText());
  }
}
