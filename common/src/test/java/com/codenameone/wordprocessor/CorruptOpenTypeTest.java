package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

/**
 * Fuzz-style: GPOS / GSUB / GDEF tables with corrupted bytes and offsets must never throw an
 * unchecked exception out of kerning or shaping (which would abort a whole PDF export); a run
 * whose shaping fails degrades to unshaped glyphs.
 */
public class CorruptOpenTypeTest {
  private static byte[] resource(String name) throws IOException {
    try (InputStream in = CorruptOpenTypeTest.class.getResourceAsStream("/" + name + ".ttf")) {
      return in.readAllBytes();
    }
  }

  @Test
  public void corruptedLayoutTablesNeverThrow() throws Exception {
    String[][] cases = {
      {"SourceSerif4-Regular", "AVATAR Wolf office"},
      {"NotoNaskhArabic-Bold", "بِّسم الله"},
    };
    var random = new Random(7);
    int runs = 0;
    for (String[] c : cases) {
      byte[] clean = resource(c[0]);
      var ok = new PdfFonts.TrueType(clean);
      for (String tag : new String[] {"GPOS", "GSUB", "GDEF"}) {
        int[] t = ok.tables.get(tag);
        if (t == null) continue;
        for (int round = 0; round < 60; round++) {
          byte[] data = clean.clone();
          // Corrupt the header and list offsets most of all, plus a few random bytes.
          int hits = 1 + random.nextInt(8);
          for (int h = 0; h < hits; h++) {
            int at = t[0] + (h < 2 ? random.nextInt(Math.min(t[1], 64)) : random.nextInt(t[1]));
            data[at] = (byte) (random.nextBoolean() ? 0xff : random.nextInt(256));
          }
          PdfFonts.TrueType font;
          try {
            font = new PdfFonts.TrueType(data);
          } catch (IOException rejected) {
            continue;
          }
          String text = c[1];
          try {
            for (int i = 0; i + 1 < text.length(); i++)
              font.kern(font.glyph(text.charAt(i)), font.glyph(text.charAt(i + 1)));
            boolean rtl = text.charAt(0) >= 0x600;
            var glyphs = PdfShaping.shapeRun(font, new OpenType(font), text, 0, text.length(), rtl,
                true);
            assertFalse(glyphs.isEmpty());
          } catch (RuntimeException e) {
            throw new AssertionError(c[0] + " " + tag + " round " + round + ": " + e, e);
          }
          runs++;
        }
      }
    }
    assertTrue(runs > 100);
  }

  @Test
  public void corruptFeatureListTagDoesNotThrow() throws Exception {
    byte[] data = resource("SourceSerif4-Regular");
    var font = new PdfFonts.TrueType(data);
    int[] t = font.tables.get("GPOS");
    // FeatureList offset pointing at the last byte of the font: its tag runs off the end.
    int list = data.length - 3 - t[0];
    if (list > 0xffff) {
      // Too far for a 16-bit offset: point it at the GPOS end, where records overrun.
      list = t[1] - 3;
    }
    data[t[0] + 6] = (byte) (list >> 8);
    data[t[0] + 7] = (byte) list;
    var bad = new PdfFonts.TrueType(data);
    bad.kern(bad.glyph('A'), bad.glyph('V'));
    PdfShaping.shapeRun(bad, new OpenType(bad), "AVA", 0, 3, false, true);
  }
}
