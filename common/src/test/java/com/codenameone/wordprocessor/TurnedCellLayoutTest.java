package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Turned (btLr / tbRl) cell text, as Wordcraft's turned cells. */
public class TurnedCellLayoutTest {
  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String s, TextStyle t) {
            return s.codePointCount(0, s.length()) * t.size / 2;
          }

          public float height(TextStyle t) {
            return t.size;
          }
        });
  }

  private static Document turned(String dir, String exactHeight) {
    var s = new Session(Document.empty());
    s.insertTable(1, 2);
    s.setCell(1, 0, 0, "turned words");
    s.setCell(1, 0, 1, "flat");
    var d = s.document();
    int at = 0;
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i) instanceof Table) at = i;
    var t = (Table) d.blocks.get(at);
    var f = t.format.direction(0, 0, dir);
    if (exactHeight != null) f = f.row(0, new TableFormat.Row(Float.parseFloat(exactHeight), "exact", true));
    return d.blocks(d.blocks.set(at, t.format(f)), d.nextId);
  }

  private static LayoutEngine.Line line(LayoutEngine.Result r, String text) {
    for (LayoutEngine.Line l : r.pages.get(0).lines)
      if (l.column == 0 && l.row == 0 && l.end > l.start) return l;
    fail(text);
    return null;
  }

  @Test
  public void bottomToTopLinesRunUpTheCellAndTheRowGrowsToFit() {
    var r = engine().layout(turned("btLr", null), 0);
    var l = line(r, "turned line");
    assertEquals(2, l.turn);
    LayoutEngine.Box box = null;
    for (LayoutEngine.Box b : r.pages.get(0).boxes) if (b.column == 0) box = b;
    // "turned words" is 12 characters of 12pt text, 72pt unwrapped: the row grows to hold it.
    assertTrue(box.height >= 72);
    float[] b = l.pageBounds();
    // The turned line is as tall as it was long and stands inside its cell box.
    assertEquals(l.width, b[3], .01f);
    assertEquals(l.height, b[2], .01f);
    assertTrue(b[0] >= box.x - .01f && b[0] + b[2] <= box.x + box.width + .01f);
    assertTrue(b[1] >= box.y - .01f && b[1] + b[3] <= box.y + box.height + .01f);
    // Up: the line starts at the bottom of the cell; its frame maps back and forth.
    float[] start = l.toPage(l.x, l.y);
    assertTrue(start[1] > b[1] + b[3] - .01f - 0);
    float[] back = l.toFrame(start[0], start[1]);
    assertEquals(l.x, back[0], .01f);
    assertEquals(l.y, back[1], .01f);
  }

  @Test
  public void topToBottomStacksFromTheRightAndAnExactRowSetsTheLength() {
    var r = engine().layout(turned("tbRl", "40"), 0);
    var l = line(r, "turned");
    assertEquals(1, l.turn);
    LayoutEngine.Box box = null;
    for (LayoutEngine.Box b : r.pages.get(0).boxes) if (b.column == 0) box = b;
    assertEquals(40, box.height, .01f);
    // Lines wrap to the exact row height (minus padding) and stack from the right edge.
    for (LayoutEngine.Line x : r.pages.get(0).lines)
      if (x.column == 0 && x.row == 0) assertTrue(x.width <= 40);
    float[] b = l.pageBounds();
    assertTrue(b[0] + b[2] > box.x + box.width / 2);
    // Horizontal cells are untouched.
    for (LayoutEngine.Line x : r.pages.get(0).lines) if (x.column == 1) assertEquals(0, x.turn);
  }

  @Test
  public void pdfTurnsTheCellLines() throws Exception {
    var d = turned("btLr", null);
    var pdf = new String(PdfExporter.write(d.page, engine().layout(d, 0)), StandardCharsets.ISO_8859_1);
    assertTrue(pdf.contains("q 0 1 -1 0 "));
  }
}
