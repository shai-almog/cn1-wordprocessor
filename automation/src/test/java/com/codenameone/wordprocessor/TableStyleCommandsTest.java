package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TableStyleCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static Table table(Session s) {
    for (Block b : s.document().blocks) if (b instanceof Table table) return table;
    throw new AssertionError("no table");
  }

  @Test
  public void customStylesLookAndRoundTrip() throws Exception {
    var s = new Session(Document.empty());
    run(s, "table.insert", "rows", 4, "columns", 3);
    s.enterCell(s.document().blocks.size() - 1 > 0 ? indexOfTable(s) : 0, 1, 1);
    run(s, "table.newStyle", "name", "Ocean", "header", "003366", "band", "cce0ff", "border", "336699", "width", 1, "sides", "horizontal");
    run(s, "table.style", "style", "Ocean");
    Table t = table(s);
    assertEquals("Ocean", t.format.prop("style", ""));
    assertEquals(0x003366, t.format.cell(0, 1).background);
    assertEquals(0xcce0ff, t.format.cell(1, 1).background);
    assertEquals(0xffffff, t.format.cell(2, 1).background);
    run(s, "table.look", "headerRow", false, "firstColumn", false);
    t = table(s);
    assertEquals(0xcce0ff, t.format.cell(0, 1).background);
    run(s, "table.modifyStyle", "name", "Ocean", "band", "ffeecc");
    assertEquals(0xffeecc, table(s).format.cell(0, 1).background);
    assertTrue(((List<?>) run(s, "table.styles")).contains("Ocean"));
    run(s, "table.rtl", "value", true);
    run(s, "table.textDirection", "value", "btLr");
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    Table bt = null;
    for (Block b : back.blocks) if (b instanceof Table table) bt = table;
    assertEquals("true", bt.format.prop("rtl", ""));
    assertEquals("btLr", bt.format.direction(1, 1));
    assertEquals("bandedRows", bt.format.prop("look", ""));
    Table nt = null;
    for (Block b : DocumentXml.read(DocumentXml.write(s.document())).blocks) if (b instanceof Table table) nt = table;
    assertEquals(table(s).format.props(), nt.format.props());
    run(s, "table.deleteStyle", "name", "Ocean");
    assertEquals("", table(s).format.prop("style", ""));
    try {
      run(s, "table.deleteStyle", "name", "TableGrid");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private static int indexOfTable(Session s) {
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) return i;
    return -1;
  }

  @Test
  public void painterDrawAndEraser() {
    var s = new Session(Document.empty());
    run(s, "table.insert", "rows", 2, "columns", 2);
    int block = indexOfTable(s);
    s.enterCell(block, 0, 0);
    run(s, "table.borderPainter", "color", "ff0000", "width", 2);
    assertEquals(0xff0000, table(s).format.cell(0, 0).borderColor);
    assertEquals(2, table(s).format.cell(0, 0).borderWidth, 0);
    run(s, "table.draw", "orientation", "vertical");
    assertEquals(3, table(s).columns());
    s.enterCell(block, 0, 0);
    run(s, "table.draw", "orientation", "horizontal");
    assertEquals(3, table(s).rows());
    s.enterCell(block, 0, 0);
    run(s, "table.eraser", "direction", "right");
    assertEquals(2, table(s).span(0, 0).columns);
    s.undo();
    assertEquals(1, table(s).span(0, 0).columns);
  }
}
