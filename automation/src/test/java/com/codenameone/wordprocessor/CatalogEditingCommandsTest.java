package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class CatalogEditingCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static Session three() {
    Session s = new Session(Document.empty());
    s.replace("one");
    s.enterParagraph();
    s.replace("two");
    s.enterParagraph();
    s.replace("three");
    return s;
  }

  private static String text(Session s) {
    StringBuilder b = new StringBuilder();
    for (Block block : s.document().blocks)
      b.append(((Paragraph) block).text.plainText()).append('|');
    return b.toString();
  }

  @Test
  public void moveParagraphsUpAndDownWithUndo() {
    Session s = three();
    s.select(2, 2, 2);
    run(s, "para.moveUp");
    assertEquals("one|three|two|", text(s));
    assertEquals(1, s.selection().block);
    assertEquals(2, s.selection().caret);
    run(s, "para.moveUp");
    assertEquals("three|one|two|", text(s));
    assertEquals(false, run(s, "para.moveUp"));
    run(s, "outline.moveDown");
    assertEquals("one|three|two|", text(s));
    s.undo();
    assertEquals("three|one|two|", text(s));
  }

  @Test
  public void formatPainterCopiesCharacterAndParagraphFormat() {
    Session s = three();
    s.select(0, 0, 3);
    s.format("bold", "true");
    s.format("color", "c00000");
    s.paragraph("align", "center");
    s.select(0, 1, 1);
    run(s, "edit.copyFormat");
    s.select(2, 0, 5);
    run(s, "edit.pasteFormat");
    Paragraph p = s.document().paragraph(2);
    assertEquals("center", p.align);
    assertTrue(p.text.styleAt(0).bold);
    assertEquals(0xc00000, p.text.styleAt(0).color);
  }

  @Test
  public void repeatGoBackAndUnicodeToggle() {
    Session s = three();
    s.select(0, 3, 3);
    run(s, "text.newParagraph");
    run(s, "edit.repeat");
    assertEquals("one|||two|three|", text(s));
    s.select(4, 0, 0);
    run(s, "edit.goBack");
    assertEquals(2, s.selection().block);

    s.select(4, 5, 5);
    s.replace("U+00e9");
    assertEquals("E9", run(s, "text.toggleUnicode"));
    assertEquals("threeé", s.document().paragraph(4).text.plainText());
    assertEquals("00E9", run(s, "text.toggleUnicode"));
    assertEquals("three00E9", s.document().paragraph(4).text.plainText());
  }
}
