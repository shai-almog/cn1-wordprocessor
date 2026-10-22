package com.codenameone.wordprocessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import org.junit.Test;

/**
 * Glyph ids, advances and offsets of Indic, Khmer, Lao and Myanmar sample strings against
 * Wordcraft's shaper (harfrust 0.13.3), with the macOS Sangam MN faces (shaped by their 'morx'
 * and 'kerx' / GPOS, as harfrust prefers 'morx') and GSUB-only faces (Kohinoor, ITF Devanagari,
 * Shree714, Mukta Mahee, Noto Sans Kannada/Oriya, Tamil Sangam MN) through the Indic shaper.
 * Fonts that are not installed are skipped; when none is (Linux CI) the test reports SKIPPED
 * rather than passing with nothing checked.
 */
public class IndicShapingParityTest {
  private static final String SUPPLEMENTAL = "/System/Library/Fonts/Supplemental/";

  @Test
  public void matchesHarfrust() throws Exception {
    var failures = new StringBuilder();
    int checked = 0, fonts = 0;
    try (var in = new BufferedReader(new InputStreamReader(
        IndicShapingParityTest.class.getResourceAsStream("/harfrust-indic-shaping.txt"), "UTF-8"))) {
      String path = null;
      PdfFonts.TrueType font = null;
      for (String line; (line = in.readLine()) != null; ) {
        if (line.startsWith("#") || line.trim().isEmpty()) continue;
        String[] p = line.split("\t");
        if (!p[0].equals(path)) {
          path = p[0];
          var f = new File(path.startsWith("/") ? path : SUPPLEMENTAL + path);
          font = null;
          if (f.exists())
            try (var fin = new FileInputStream(f)) {
              font = new PdfFonts.TrueType(FontSubset.face(fin.readAllBytes(), 0));
              fonts++;
            }
        }
        if (font == null) continue;
        String text = unescape(p[1]);
        var got = new StringBuilder();
        for (var g : PdfShaping.shapeRun(font, new OpenType(font), text, 0, text.length(), false,
            true)) {
          got.append(got.length() > 0 ? "|" : "").append(g.id);
          if (g.dx != 0 || g.dy != 0) got.append('@').append(g.dx).append(',').append(g.dy);
          got.append('+').append(g.adv);
        }
        checked++;
        if (!got.toString().equals(p[2]))
          failures.append(path).append(' ').append(p[1]).append(": ").append(p[2]).append(" != ")
              .append(got).append('\n');
      }
    }
    assumeTrue("no reference font installed (macOS only)", fonts > 0);
    assertTrue("no string checked", checked > 0);
    assertEquals(checked + " checked", "", failures.toString());
  }

  private static String unescape(String e) {
    var t = new StringBuilder();
    for (int i = 0; i < e.length(); ) {
      if (e.startsWith("\\u", i)) {
        t.append((char) Integer.parseInt(e.substring(i + 2, i + 6), 16));
        i += 6;
      } else t.append(e.charAt(i++));
    }
    return t.toString();
  }
}
