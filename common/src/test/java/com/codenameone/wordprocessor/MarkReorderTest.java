package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.InputStream;
import org.junit.Test;

/**
 * Marks are reordered by (harfrust's modified) canonical combining class before shaping, as
 * hb_ot_shape_normalize. Indic virama+nukta cases are in IndicShapingParityTest's data.
 */
public class MarkReorderTest {
  private static String shape(PdfFonts.TrueType font, String text, boolean rtl) throws Exception {
    var got = new StringBuilder();
    for (var g : PdfShaping.shapeRun(font, new OpenType(font), text, 0, text.length(), rtl, true)) {
      got.append(got.length() > 0 ? "|" : "").append(g.id);
      if (g.dx != 0 || g.dy != 0) got.append('@').append(g.dx).append(',').append(g.dy);
      got.append('+').append(g.adv);
    }
    return got.toString();
  }

  /** Arabic kasra+shadda shapes as shadda+kasra (shadda's modified class 27 < kasra's 33). */
  @Test
  public void arabicKasraShaddaMatchesHbShape() throws Exception {
    byte[] data;
    try (InputStream in = MarkReorderTest.class.getResourceAsStream("/NotoNaskhArabic-Bold.ttf")) {
      data = in.readAllBytes();
    }
    var font = new PdfFonts.TrueType(data);
    // hb-shape 14.2.1 gives the same glyphs and offsets for both orders.
    String shaddaFirst = shape(font, "\u0628\u0651\u0650", true);
    assertEquals(shaddaFirst, shape(font, "\u0628\u0650\u0651", true));
  }

  private static String order(int... cps) {
    var buffer = new java.util.ArrayList<OpenType.Glyph>();
    for (int i = 0; i < cps.length; i++) buffer.add(new OpenType.Glyph(1, cps[i], i, i + 1, 0));
    CombiningClass.reorder(buffer);
    var out = new StringBuilder();
    for (var g : buffer) out.append(Integer.toHexString(g.cp)).append(' ');
    return out.toString().trim();
  }

  @Test
  public void reordersDevanagariKannadaAndArabicMarks() {
    assertEquals("915 93c 94d", order(0x915, 0x94d, 0x93c));
    assertEquals("c95 cbc ccd", order(0xc95, 0xccd, 0xcbc));
    assertEquals("628 651 650", order(0x628, 0x650, 0x651));
    // Starters bound each run; equal classes keep their order.
    assertEquals("628 651 628 650", order(0x628, 0x651, 0x628, 0x650));
    assertEquals("61 301 300", order(0x61, 0x301, 0x300));
  }

  @Test
  public void classes() {
    assertEquals(9, CombiningClass.of(0x094d));
    assertEquals(7, CombiningClass.of(0x093c));
    assertEquals(27, CombiningClass.modified(0x0651));
    assertEquals(33, CombiningClass.modified(0x0650));
    assertEquals(0, CombiningClass.of('a'));
  }
}
