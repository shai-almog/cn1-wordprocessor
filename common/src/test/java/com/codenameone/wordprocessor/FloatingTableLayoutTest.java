package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/** Floating tables (w:tblpPr) as Wordcraft's place_floating_table and clear_of_float_tables. */
public class FloatingTableLayoutTest {
  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String text, TextStyle style) {
            return text.length() * 6;
          }

          public float height(TextStyle style) {
            return 12;
          }
        });
  }

  private static int ids;

  private static Table table(String id) {
    var rows = new java.util.ArrayList<java.util.List<Sequence<Block>>>();
    for (int r = 0; r < 2; r++) {
      var row = new java.util.ArrayList<Sequence<Block>>();
      for (int c = 0; c < 2; c++) row.add(new Sequence<Block>().append(para(id + r + c, r == c ? "AB" : "")));
      rows.add(row);
    }
    return Table.of(id, rows, java.util.Collections.<Table.Merge>emptyList());
  }

  private static Table floating(String spec) {
    var t = table("t" + ids++);
    var f = t.format.sizing(new TableFormat.Sizing("points", 120, "left", 0, true)).withProp("float", spec);
    return t.format(f);
  }

  private static Paragraph para(String id, String text) {
    return new Paragraph(id, RichText.of(text, TextStyle.DEFAULT));
  }

  private static String words(int n) {
    var b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append("word ");
    return b.toString().trim();
  }

  private static Document doc(Block... blocks) {
    var seq = new Sequence<Block>();
    for (Block b : blocks) seq = seq.append(b);
    return new Document(seq, PageSpec.A4, "t", 100);
  }

  /** {minX, minY, maxY, page} of the lines of the table at {@code block}. */
  private static float[] tableBox(LayoutEngine.Result r, int block) {
    float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -1, -1};
    for (int p = 0; p < r.pages.size(); p++)
      for (LayoutEngine.Line l : r.pages.get(p).lines)
        if (l.block == block && l.row >= 0) {
          box[0] = Math.min(box[0], l.x);
          box[1] = Math.min(box[1], l.y);
          box[2] = Math.max(box[2], l.y + l.height);
          if (box[3] >= 0 && box[3] != p) fail("floating table split across pages");
          box[3] = p;
        }
    return box;
  }

  @Test public void placedByOffsetAndTextWrapsBesideIt() {
    var spec = PageSpec.A4;
    var d = doc(para("p0", "intro"), floating("margin,text,200,10,,,9,0,9,0"), para("p2", words(200)));
    var r = engine().layout(d, 1);
    float[] box = tableBox(r, 1);
    assertEquals(0, (int) box[3]);
    assertTrue("table at its x offset", box[0] >= spec.left + 200 - .01f && box[0] < spec.left + 215);
    float introBottom = 0;
    float firstText = Float.MAX_VALUE;
    boolean beside = false;
    for (LayoutEngine.Line l : r.pages.get(0).lines) {
      if (l.block == 0) introBottom = Math.max(introBottom, l.y + l.height);
      if (l.block != 2) continue;
      firstText = Math.min(firstText, l.y);
      if (l.y >= box[1] && l.y < box[2]) {
        beside = true;
        // Text beside the table (both sides, Wordcraft BothSides) stays clear of it and its 9pt distance.
        for (LayoutEngine.Span sp : l.spans) {
          if (sp.text.trim().isEmpty()) continue;
          float x0 = l.x + sp.x, x1 = x0 + sp.width;
          assertTrue("span " + sp.text + " at " + x0 + "-" + x1,
              x1 <= spec.left + 200 - 9 + .5f || x0 >= spec.left + 200 + 120 + 9 - .5f);
        }
      }
    }
    assertTrue("text continues beside the table", beside);
    assertTrue("table takes no room in the flow", firstText < box[2]);
    assertTrue("table below its anchor paragraph by its offset", box[1] >= introBottom + 10 - .5f);
  }

  @Test public void nonOverlappingTablesStackAndOverlappingOnesDoNot() {
    var d = doc(para("p0", "x"), floating("margin,text,0,0,,,0,0,0,0,never"), floating("margin,text,0,0,,,0,0,0,0,never"), para("p3", "y"));
    var r = engine().layout(d, 1);
    float[] a = tableBox(r, 1), b = tableBox(r, 2);
    assertTrue("second moved below the first", b[1] >= a[2] - .5f);
    d = doc(para("p0", "x"), floating("margin,text,0,0,,,0,0,0,0"), floating("margin,text,0,0,,,0,0,0,0"), para("p3", "y"));
    r = engine().layout(d, 1);
    assertEquals(tableBox(r, 1)[1], tableBox(r, 2)[1], .5f);
  }

  @Test public void tableThatDoesNotFitMovesWholeToNextPage() {
    var spec = PageSpec.A4;
    float bottom = spec.height - spec.bottom;
    // Fill page one until the paragraph before the table leaves 22-38pt at the body's bottom:
    // room for the first row but not the whole table, where an in-flow table would split.
    for (int n = 400; n < 1400; n += 5) {
      var d = doc(para("p0", words(n)), floating("margin,text,0,0,,,0,0,0,0"), para("p2", "after"));
      var r = engine().layout(d, 1);
      float end = 0;
      for (LayoutEngine.Line l : r.pages.get(0).lines) if (l.block == 0) end = Math.max(end, l.y + l.height);
      if (bottom - end < 22 || bottom - end > 38) continue;
      float[] box = tableBox(r, 1);
      assertEquals("moved whole to the next page", 1, (int) box[3]);
      assertTrue("at the top of the next page", box[1] < spec.top + 20);
      return;
    }
    fail("no page-filling paragraph found");
  }

  @Test public void overlapRoundTripsThroughDocx() throws Exception {
    var d = doc(para("p0", "x"), floating("margin,text,0,0,,,0,0,0,0,never"), para("p2", "y"));
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(d, out);
    var back = Docx.read(new java.io.ByteArrayInputStream(out.toByteArray()));
    assertTrue(back.warnings.toString(), back.warnings.isEmpty());
    for (Block b : back.document.blocks)
      if (b instanceof Table t) assertEquals("never", t.format.floating()[10]);
  }
}
