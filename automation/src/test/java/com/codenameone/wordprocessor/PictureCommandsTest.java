package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class PictureCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static byte[] png() throws Exception {
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  public void cropBorderRotateSizeAltAndReset() throws Exception {
    Session s = new Session(Document.empty());
    s.insertPicture(png(), 200, 100, "");
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Picture) block = i;
    run(s, "picture.crop", "block", block, "left", .25, "bottom", .1);
    run(s, "picture.border", "block", block, "color", "ff0000", "width", 2);
    run(s, "arrange.rotate", "block", block, "kind", "left");
    run(s, "arrange.rotate", "block", block, "kind", "flipV");
    run(s, "picture.size", "block", block, "width", 100);
    run(s, "picture.altText", "block", block, "text", "Sales chart");
    Picture p = (Picture) s.document().blocks.get(block);
    assertEquals(.25, p.look.cropLeft, 1e-6);
    assertEquals(.1, p.look.cropBottom, 1e-6);
    assertEquals(0xff0000, p.look.borderColor);
    assertEquals(270, p.look.rotation);
    assertTrue(p.look.flipV);
    assertEquals(50, p.height, .01);
    assertEquals("Sales chart", p.alt);
    run(s, "picture.reset", "block", block);
    assertEquals(Picture.Look.NONE, ((Picture) s.document().blocks.get(block)).look);
    try {
      run(s, "picture.crop", "block", 0, "left", .1);
      if (!(s.document().blocks.get(0) instanceof Picture)) fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
