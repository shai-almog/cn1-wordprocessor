package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class CatalogCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static Paragraph para(Session s, int i) {
    return (Paragraph) s.document().blocks.get(i);
  }

  @Test
  public void characterCommandsToggleAndStep() {
    var s = new Session(Document.empty());
    s.replace("hello world");
    s.select(0, 0, 5);
    run(s, "format.bold");
    assertTrue(para(s, 0).text.styleAt(0).bold);
    run(s, "format.bold");
    assertFalse(para(s, 0).text.styleAt(0).bold);
    run(s, "format.size", "size", 11);
    run(s, "format.growFont");
    assertEquals(12, para(s, 0).text.styleAt(0).size, .01);
    run(s, "format.shrinkFont");
    run(s, "format.shrinkFont");
    assertEquals(10.5, para(s, 0).text.styleAt(0).size, .01);
    run(s, "format.smallCaps");
    run(s, "format.superscript");
    run(s, "format.color", "color", "#112233");
    assertTrue(para(s, 0).text.styleAt(0).characters.smallCaps);
    assertEquals(0x112233, para(s, 0).text.styleAt(0).color);
    run(s, "format.clear");
    assertEquals(TextStyle.DEFAULT, para(s, 0).text.styleAt(0).clean());
    assertFalse(para(s, 0).text.styleAt(6).bold);
  }

  @Test
  public void paragraphListAndStyleCommands() {
    var s = new Session(Document.empty());
    s.replace("one");
    run(s, "para.alignCenter");
    assertEquals("center", para(s, 0).align);
    run(s, "para.align", "value", "justify");
    assertEquals("both", para(s, 0).align);
    run(s, "para.indent");
    run(s, "para.indent");
    run(s, "para.outdent");
    assertEquals(36, para(s, 0).indent, .01);
    run(s, "para.lineSpacing", "exactly", 24);
    assertEquals("exact", para(s, 0).format.lineRule);
    run(s, "para.keepLines");
    assertTrue(para(s, 0).format.keepLines);
    run(s, "para.numbering", "kind", "lowerLetter");
    assertEquals("number", para(s, 0).list);
    assertEquals("lowerLetter", para(s, 0).listFormat);
    run(s, "para.setNumberingValue", "value", 5);
    assertEquals(5, para(s, 0).listStart);
    run(s, "para.numbering");
    assertEquals("", para(s, 0).list);
    run(s, "para.heading2");
    assertEquals("Heading2", para(s, 0).style);
    for (int i = 0; i < 11; i++) s.undo();
    assertEquals("left", para(s, 0).align);
  }

  @Test
  public void editingSelectionAndTableCommands() {
    var s = new Session(Document.empty());
    run(s, "edit.paste", "text", "alpha beta");
    assertEquals(Boolean.TRUE, run(s, "select.text", "text", "beta"));
    assertEquals("beta", run(s, "edit.cut"));
    assertEquals("alpha ", s.document().plainText().trim() + " ");
    run(s, "select.all");
    assertEquals("alpha ", s.selectedText());
    s.insertTable(2, 2);
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Table) block = i;
    s.enterCell(block, 0, 0);
    run(s, "table.insertRowBelow");
    run(s, "table.insertColumnLeft");
    var t = (Table) s.document().blocks.get(block);
    assertEquals(3, t.rows());
    assertEquals(3, t.columns());
    s.enterCell(block, 0, 0);
    run(s, "table.deleteTable");
    for (Block b : s.document().blocks) assertFalse(b instanceof Table);
    try {
      run(s, "table.deleteRow");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void bordersShadingAndHorizontalLine() {
    var s = new Session(Document.empty());
    s.replace("Title");
    run(s, "para.borders", "kind", "bottom", "width", 1.5, "color", "1f4e79");
    assertEquals(1.5f, para(s, 0).format.borders.bottom.width, .001);
    assertFalse(para(s, 0).format.borders.top.present());
    run(s, "para.borders", "kind", "outside");
    assertTrue(para(s, 0).format.borders.left.present());
    run(s, "para.shading", "color", "fff2cc");
    assertEquals(0xfff2cc, para(s, 0).format.shading);
    run(s, "para.shading", "color", null);
    assertEquals(-1, para(s, 0).format.shading);
    run(s, "para.borders", "kind", "none");
    assertTrue(para(s, 0).format.borders.isEmpty());
    run(s, "insert.horizontalLine");
    assertEquals(3, s.document().blocks.size());
    assertTrue(para(s, 1).format.borders.bottom.present());
    assertTrue(para(s, 2).format.borders.isEmpty());
    assertEquals(2, s.selection().block);
  }
}
