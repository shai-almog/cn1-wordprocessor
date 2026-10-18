package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class DesignCommandsTest {
  private static Map<String, Object> args(Object... kv) {
    var m = new HashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  @Test
  public void pageColorWatermarkAndBordersRoundTripAndUndo() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Body");
    var before = s.document();
    var c = new Commands();
    c.execute(s, "design.pageColor", args("color", "fff2cc"));
    c.execute(s, "design.watermark", args("text", "DRAFT", "color", "ff0000"));
    c.execute(s, "design.pageBorders", args("kind", "all", "style", "double", "width", 1.5));
    var d = s.activeSection().raw.decor;
    assertEquals(0xfff2cc, d.pageColor);
    assertEquals("DRAFT", d.watermark);
    assertEquals(0xff0000, d.watermarkColor);
    assertEquals("double", d.borders.top.style);
    assertEquals(24f, d.borders.left.space, 0);

    Document back = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals(d, back.lastSection.decor);

    c.execute(s, "design.pageColor", args());
    c.execute(s, "design.watermark", args("text", ""));
    c.execute(s, "design.pageBorders", args("kind", "none"));
    assertTrue(s.activeSection().raw.decor.isEmpty());
    for (int i = 0; i < 6; i++) s.undo();
    assertEquals(DocumentXml.write(before), DocumentXml.write(s.document()));
  }
}
