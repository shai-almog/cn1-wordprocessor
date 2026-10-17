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

  @Test
  public void adjustmentsRoundTripThroughDocxAndNativeXml() throws Exception {
    Session s = new Session(Document.empty());
    s.insertPicture(png(), 200, 100, "");
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Picture) block = i;
    run(s, "picture.corrections", "block", block, "brightness", 20, "contrast", -40);
    run(s, "picture.transparency", "block", block, "percent", 35);
    run(s, "picture.color", "block", block, "mode", "grayscale");
    run(s, "picture.style", "block", block, "name", "simpleFrameBlack");
    Picture.Look k = ((Picture) s.document().blocks.get(block)).look;
    assertEquals(20, k.brightness);
    assertEquals(-40, k.contrast);
    assertEquals(35, k.transparency);
    assertEquals("grayscale", k.colorMode);
    assertEquals(6, k.borderWidth, 0);

    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened back = Docx.read(new java.io.ByteArrayInputStream(out.toByteArray()));
    assertTrue(back.warnings.toString(), back.warnings.isEmpty());
    Picture.Look docx = null;
    for (Block b : back.document.blocks) if (b instanceof Picture) docx = ((Picture) b).look;
    assertEquals(k, docx);
    Picture.Look xml = null;
    for (Block b : DocumentXml.read(DocumentXml.write(s.document())).blocks)
      if (b instanceof Picture) xml = ((Picture) b).look;
    assertEquals(k, xml);

    run(s, "picture.change", "block", block, "base64",
        new String(com.codename1.util.Base64.encodeNoNewline(png())));
    assertEquals(k, ((Picture) s.document().blocks.get(block)).look);
  }

  private int insertPicture(Session s) throws Exception {
    s.insertPicture(png(), 144, 72, "");
    int block = -1;
    for (int i = 0; i < s.document().blocks.size(); i++)
      if (s.document().blocks.get(i) instanceof Picture) block = i;
    return block;
  }

  @Test
  public void effectsAndBackgroundRoundTrip() throws Exception {
    Session s = new Session(Document.empty());
    int block = insertPicture(s);
    run(s, "picture.effects", "block", block, "effect", "pencilSketch");
    run(s, "picture.removeBackground", "block", block, "tolerance", 30);
    Picture.Look k = ((Picture) s.document().blocks.get(block)).look;
    assertEquals("pencilSketch", k.effect);
    assertEquals(30, k.backgroundTolerance);
    assertTrue(k.adjusted());
    Document back = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals(k, ((Picture) back.blocks.get(block)).look);
    try {
      run(s, "picture.effects", "block", block, "effect", "bogus");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void pixelMath() {
    int w = 4, h = 4;
    int[] px = new int[16];
    for (int i = 0; i < 16; i++) {
      int x = i % 4, y = i / 4;
      px[i] = (x == 0 || y == 0 || x == 3 || y == 3) ? 0xffffffff : 0xffff0000;
    }
    int[] out = PictureEffects.apply(px, w, h, Picture.Look.NONE.removeBackground(10));
    assertEquals(0, out[0] >>> 24);
    assertEquals(0xffff0000, out[5]);
    assertEquals(0xffffffff, px[0]);
    int[] neg = PictureEffects.apply(px, w, h, Picture.Look.NONE.effect("negative"));
    assertEquals(0xff000000, neg[0]);
    assertEquals(0xff00ffff, neg[5]);
  }

  @Test
  public void compressUsesCodecAndResetsCrop() throws Exception {
    Session s = new Session(Document.empty());
    int block = insertPicture(s);
    run(s, "picture.crop", "block", block, "left", .25);
    try {
      run(s, "picture.compress", "block", block);
      fail();
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("codec"));
    }
    final byte[] small = png();
    final int[] seen = new int[2];
    PictureCommands.codec(
        (data, mime, l, t, r, b, mw, mh) -> {
          seen[0] = mw;
          seen[1] = mh;
          assertEquals(.25f, l, 1e-6);
          return small;
        });
    try {
      run(s, "picture.compress", "block", block, "ppi", 72);
    } finally {
      PictureCommands.codec(null);
    }
    Picture p = (Picture) s.document().blocks.get(block);
    assertArrayEquals(small, p.bytes());
    assertFalse(p.look.cropped());
    assertEquals(144, seen[0]);
    assertEquals(72, seen[1]);
  }
}
