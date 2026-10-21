package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import org.junit.Test;

/** ODT paragraph borders: Wordcraft reads fo:border-bottom as a rule and writes its rule so. */
public class OdtBordersTest {
  @Test
  public void bottomBorderRoundTripsThroughOdt() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Title");
    var a = new LinkedHashMap<String, Object>();
    a.put("kind", "bottom");
    a.put("width", 1.5);
    a.put("color", "1f4e79");
    new Commands().execute(s, "para.borders", a);
    var out = new ByteArrayOutputStream();
    Odt.write(s.document(), out);
    var back = Odt.read(new ByteArrayInputStream(out.toByteArray())).document.paragraph(0);
    assertTrue(back.format.borders.bottom.present());
    assertEquals(1.5f, back.format.borders.bottom.width, .2f);
    assertFalse(back.format.borders.top.present());
  }

  @Test
  public void odfBorderValues() {
    assertNull(Odt.odfBorder("none"));
    assertEquals(
        " w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"A0A0A0\"",
        Odt.odfBorder("0.75pt solid #a0a0a0"));
  }
}
