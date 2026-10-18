package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class CharacterDecorationTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @Test
  public void decorationsApplyUndoAndRoundTrip() throws Exception {
    var s = new Session(Document.empty());
    s.replace("hello world");
    s.select(0, 0, 5);
    run(s, "format.shading", "color", "#ffcc00");
    run(s, "format.border", "color", "112233");
    run(s, "format.phonetic", "text", "ha");
    run(s, "format.enclose", "shape", "circle");
    run(s, "review.language", "lang", "fr-FR");
    run(s, "format.textEffects", "shadow", true, "outline", true);
    var c = s.document().paragraph(0).text.styleAt(0).characters;
    assertEquals(0xffcc00, c.shading);
    assertEquals(0x112233, c.border);
    assertEquals("ha", c.ruby);
    assertEquals("circle", c.enclose);
    assertEquals("fr-FR", c.lang);
    assertTrue(c.shadow && c.outline && !c.emboss);
    assertEquals(CharacterFormat.DEFAULT, s.document().paragraph(0).text.styleAt(8).characters);
    assertEquals(c, DocumentXml.read(DocumentXml.write(s.document())).paragraph(0).text.styleAt(0).characters);
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    var back =
        Docx.read(new ByteArrayInputStream(out.toByteArray())).document.paragraph(0).text.styleAt(0).characters;
    assertEquals(0x112233, back.border);
    assertEquals("fr-FR", back.lang);
    try {
      run(s, "format.enclose", "shape", "star");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    run(s, "format.shading", "color", "none");
    assertEquals(-1, s.document().paragraph(0).text.styleAt(0).characters.shading);
  }
}
