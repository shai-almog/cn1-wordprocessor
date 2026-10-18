package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class MultilevelListTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static String labels(Document d) {
    var n = new ListNumbers();
    var b = new StringBuilder();
    for (Block block : d.blocks) b.append(n.next((Paragraph) block)).append(' ');
    return b.toString().trim();
  }

  private Session list(String kind, int... levels) {
    var s = new Session(Document.empty());
    for (int i = 0; i < levels.length; i++) {
      if (i > 0) s.enterParagraph();
      s.replace("item");
    }
    s.selectRange(0, 0, levels.length - 1, 4);
    run(s, "para.multilevel", "kind", kind);
    for (int i = 0; i < levels.length; i++) {
      s.select(i, 0, 0);
      s.paragraph("listLevel", String.valueOf(levels[i]));
    }
    return s;
  }

  @Test
  public void outlineAndLegalLabels() throws Exception {
    assertEquals("1. a. i. b. 2.", labels(list("outline", 0, 1, 2, 1, 0).document()));
    var legal = list("legal", 0, 1, 2, 1, 0);
    assertEquals("1. 1.1. 1.1.1. 1.2. 2.", labels(legal.document()));
    var out = new ByteArrayOutputStream();
    Docx.write(legal.document(), out);
    Docx.Opened back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(back.warnings.toString(), back.warnings.isEmpty());
    assertEquals("1. 1.1. 1.1.1. 1.2. 2.", labels(back.document));
  }

  @Test
  public void customNumberAndBulletRoundTrip() throws Exception {
    var s = new Session(Document.empty());
    s.replace("one");
    s.enterParagraph();
    s.replace("two");
    s.selectRange(0, 0, 1, 3);
    run(s, "para.defineNumber", "format", "upperLetter", "label", "(%1)");
    assertEquals("(A) (B)", labels(s.document()));
    s.select(1, 3, 3);
    s.enterParagraph();
    s.replace("tick");
    run(s, "para.defineBullet", "symbol", "✓");
    assertEquals("(A) (B) ✓", labels(s.document()));
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(back.warnings.toString(), back.warnings.isEmpty());
    assertEquals("(A) (B) ✓", labels(back.document));
    assertEquals(labels(s.document()), labels(DocumentXml.read(DocumentXml.write(s.document()))));
  }
}
