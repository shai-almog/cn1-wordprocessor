package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.InputStream;
import org.junit.Test;

/**
 * Default ignorables left by GSUB are hidden as harfrust's hide_default_ignorables: the space
 * glyph with no advance (or removed when the face has no space), never .notdef with an advance,
 * even when the face lacks the character.
 */
public class HiddenIgnorableTest {
  private static String shape(String resource, String text, boolean rtl) throws Exception {
    byte[] data;
    try (InputStream in = HiddenIgnorableTest.class.getResourceAsStream("/" + resource + ".ttf")) {
      data = in.readAllBytes();
    }
    var font = new PdfFonts.TrueType(data);
    var got = new StringBuilder();
    for (var g : PdfShaping.shapeRun(font, new OpenType(font), text, 0, text.length(), rtl, true)) {
      got.append(got.length() > 0 ? "|" : "").append(g.id);
      if (g.dx != 0 || g.dy != 0) got.append('@').append(g.dx).append(',').append(g.dy);
      got.append('+').append(g.adv);
    }
    return got.toString();
  }

  /**
   * hb-shape 14.2.1 on NotoNaskhArabic-Bold gives 323@332,-38+0|15+817|3+0|323@65,-38+0|19+275:
   * the same glyphs and offsets as without the ignorable, plus the space glyph (3) at no advance.
   * (Marks are compared in this shaper's own visual order.)
   */
  @Test
  public void missingCgjAndVariationSelectorAreHidden() throws Exception {
    String plain = shape("NotoNaskhArabic-Bold", "\u0628\u0628", true);
    for (String ignorable : new String[] {"\u034f", "\ufe00"}) {
      String got = shape("NotoNaskhArabic-Bold", "\u0628" + ignorable + "\u0628", true);
      assertFalse(got, ("|" + got).contains("|0+"));
      // The hidden glyph stays in its base's cluster, after it (this shaper keeps RTL clusters
      // base-first); it has no advance, so every drawn position matches hb-shape.
      assertTrue(got, (got + "|").contains("|3+0|"));
      assertEquals(plain, (got + "|").replace("|3+0|", "|").replaceAll("\\|$", ""));
    }
  }
}
