package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class DropCapTest {
  private static final LayoutEngine.Metrics METRICS =
      new LayoutEngine.Metrics() {
        public float width(String text, TextStyle style) {
          return text.length() * style.size * .5f;
        }

        public float height(TextStyle style) {
          return style.size * 1.2f;
        }
      };

  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static Session sample() {
    var s = new Session(Document.empty());
    var b = new StringBuilder("Once");
    for (int i = 0; i < 60; i++) b.append(" upon a time");
    s.replace(b.toString());
    s.select(0, 0, 0);
    return s;
  }

  @Test
  public void droppedLetterIndentsTheFirstLinesOnly() {
    Session s = sample();
    var plain = new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0);
    run(s, "insert.dropCap", "position", "drop", "lines", 3, "distance", 4);
    assertEquals("drop,3,4,", s.document().paragraph(0).format.dropCap);
    var page = new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0);
    LayoutEngine.Line first = page.lines.get(0), letter = page.lines.get(1);
    assertEquals("O", letter.spans.get(0).text);
    assertEquals(0, letter.start);
    assertTrue(letter.spans.get(0).style.size > 40);
    float indent = first.x - plain.lines.get(0).x;
    assertTrue("first lines move right of the letter", indent > letter.spans.get(0).width);
    assertEquals(first.x, page.lines.get(2).x, .01);
    assertEquals(first.x, page.lines.get(3).x, .01);
    assertEquals(plain.lines.get(0).x, page.lines.get(4).x, .01);
    assertEquals("nce", first.spans.get(0).text.substring(0, 3));
    // The letter's baseline sits on the third line's baseline.
    float letterBaseline = letter.y + letter.spans.get(0).top + METRICS.ascent(letter.spans.get(0).style);
    float thirdBaseline = page.lines.get(3).y + page.lines.get(3).spans.get(0).top + METRICS.ascent(page.lines.get(3).spans.get(0).style);
    assertEquals(thirdBaseline, letterBaseline, 2f);
    run(s, "insert.dropCap", "position", "margin", "lines", 2);
    page = new LayoutEngine(METRICS).layout(s.document(), 0).pages.get(0);
    assertEquals(plain.lines.get(0).x, page.lines.get(0).x, .01);
    assertTrue(page.lines.get(1).x < page.lines.get(0).x);
    run(s, "insert.dropCap", "position", "none");
    assertEquals("", s.document().paragraph(0).format.dropCap);
    s.undo();
    assertEquals("margin", s.document().paragraph(0).format.dropMode());
  }

  @Test
  public void persistsInNativeXmlAndDocx() throws Exception {
    Session s = sample();
    run(s, "insert.dropCap", "lines", 4, "font", "Georgia");
    Document xml = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals("drop,4,0,Georgia", xml.paragraph(0).format.dropCap);
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    var bytes = new String(out.toByteArray(), "ISO-8859-1");
    var docx = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    assertEquals(s.document().paragraph(0).text.plainText(), docx.paragraph(0).text.plainText());
    assertEquals("drop", docx.paragraph(0).format.dropMode());
    assertEquals(4, docx.paragraph(0).format.dropLines());
    assertEquals("Georgia", docx.paragraph(0).format.dropFont());
    assertEquals(s.document().paragraph(0).text.styleAt(0).size, docx.paragraph(0).text.styleAt(0).size, .01);
  }

  @Test
  public void cellsRejectDropCaps() {
    var s = new Session(Document.empty());
    run(s, "table.insert", "rows", 1, "columns", 1);
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Table) s.enterCell(i, 0, 0);
    try {
      run(s, "insert.dropCap");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
