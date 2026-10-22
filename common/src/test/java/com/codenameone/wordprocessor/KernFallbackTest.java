package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.InputStream;
import org.junit.Test;

/**
 * The kerning fallback is only the legacy 'kern' table (harfrust's apply_kern), never another
 * script's GPOS 'kern' lookups. Source Serif 4 has GPOS kerning and no 'kern' table.
 */
public class KernFallbackTest {
  private static PdfFonts.TrueType font() throws Exception {
    try (InputStream in = KernFallbackTest.class.getResourceAsStream("/SourceSerif4-Regular.ttf")) {
      return new PdfFonts.TrueType(in.readAllBytes());
    }
  }

  @Test
  public void fallbackIsTheLegacyKernTableOnly() throws Exception {
    var font = font();
    assertEquals(0, font.kern(font.glyph('A'), font.glyph('V')));
    assertEquals(0, font.kern(font.glyph('T'), font.glyph('o')));
  }

  /**
   * A morx face whose run takes a non-default shaper (harfrust's dumber shaper, fallback_position
   * off) gets no legacy 'kern' table: Kailasa (Tibetan, morx + kern + GPOS) with its GPOS 'kern'
   * features renamed away, so only the legacy table could kern U+0F3C U+0F44 (+113).
   */
  @Test
  public void aatComplexRunSkipsTheLegacyKernTable() throws Exception {
    var file = new java.io.File("/System/Library/Fonts/Supplemental/Kailasa.ttc");
    org.junit.Assume.assumeTrue(file.exists());
    byte[] data;
    try (var in = new java.io.FileInputStream(file)) {
      data = FontSubset.face(in.readAllBytes(), 0);
    }
    var font = new PdfFonts.TrueType(data);
    int[] gpos = font.tables.get("GPOS");
    if (gpos != null)
      for (int i = gpos[0]; i + 4 <= gpos[0] + gpos[1]; i++)
        if (data[i] == 'k' && data[i + 1] == 'e' && data[i + 2] == 'r' && data[i + 3] == 'n')
          data[i] = 'x';
    font = new PdfFonts.TrueType(data);
    assertTrue(font.kern(font.glyph(0xf3c), font.glyph(0xf44)) != 0);
    var g = PdfShaping.shapeRun(font, new OpenType(font), "\u0f3c\u0f44", 0, 2, false, true);
    assertEquals(font.advance(g.get(0).id), g.get(0).adv);
  }

  @Test
  public void latinRunStillKernsThroughItsGpos() throws Exception {
    var font = font();
    var g = PdfShaping.shapeRun(font, new OpenType(font), "AV", 0, 2, false, true);
    assertTrue(g.get(0).adv < font.advance(g.get(0).id));
  }
}
