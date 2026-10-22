package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Default ignorables as harfrust: its is_default_ignorable table, the HIDDEN property (CGJ,
 * Mongolian FVS 1-3, TAG) that GSUB does not skip, and may_skip's SKIP_MAYBE, under which a
 * lookup that explicitly matches an ignorable still matches it.
 */
public class IgnorableSkipTest {
  private static OpenType openType() throws Exception {
    try (InputStream in = IgnorableSkipTest.class.getResourceAsStream("/SourceSans3-Regular.ttf")) {
      return new OpenType(new PdfFonts.TrueType(in.readAllBytes()));
    }
  }

  private static List<OpenType.Glyph> buffer(int... cps) {
    var buffer = new ArrayList<OpenType.Glyph>();
    for (int i = 0; i < cps.length; i++) buffer.add(new OpenType.Glyph(i + 1, cps[i], i, i + 1, 0));
    return buffer;
  }

  @Test
  public void harfrustIgnorableTable() {
    for (int cp : new int[] {0x115f, 0x1160, 0x3164, 0xffa0, 0x1bca0, 0x1bca3, 0x180f, 0x41})
      assertFalse(Integer.toHexString(cp), OpenType.ignorable(cp));
    for (int cp : new int[] {0xad, 0x34f, 0x180b, 0x180e, 0x200c, 0xfe00, 0x1d173, 0xe0020, 0xe0100})
      assertTrue(Integer.toHexString(cp), OpenType.ignorable(cp));
    assertTrue(OpenType.hidden(0x34f));
    assertTrue(OpenType.hidden(0x180d));
    assertTrue(OpenType.hidden(0xe007f));
    assertFalse(OpenType.hidden(0x180e));
    assertFalse(OpenType.hidden(0xfe00));
  }

  @Test
  public void skipMaybeMatchesAnExplicitIgnorable() throws Exception {
    var ot = openType();
    ot.positioning = true;
    var b = buffer(0x61, 0xfe00, 0x62);
    assertEquals(2, ot.nextIndex(0, 0, b, 0, 1, null));
    assertEquals(2, ot.nextIndex(0, 0, b, 0, 1, id -> id == 3));
    assertEquals(1, ot.nextIndex(0, 0, b, 0, 1, id -> id == 2));
    // GPOS ignores hidden glyphs too.
    assertEquals(2, ot.nextIndex(0, 0, buffer(0x61, 0x34f, 0x62), 0, 1, id -> id == 3));
  }

  @Test
  public void gsubSkipsIgnorablesButNotHiddenOnes() throws Exception {
    var ot = openType();
    ot.positioning = false;
    assertEquals(2, ot.nextIndex(0, 0, buffer(0x61, 0xad, 0x62), 0, 1, id -> id == 3));
    assertEquals(1, ot.nextIndex(0, 0, buffer(0x61, 0xad, 0x62), 0, 1, id -> id == 2));
    for (int hidden : new int[] {0x34f, 0x180b, 0xe0041})
      assertEquals(1, ot.nextIndex(0, 0, buffer(0x61, hidden, 0x62), 0, 1, id -> id == 3));
  }
}
