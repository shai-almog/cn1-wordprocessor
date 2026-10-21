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

  /** As Wordcraft's TurnedCell: a picture in a turned cell turns with the cell's text. */
  @Test
  public void picturesTurnWithTheirCell() {
    var d = turned("tbRl", "120");
    int at = 0;
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i) instanceof Table) at = i;
    var t = (Table) d.blocks.get(at);
    var pic = new Picture("tp1", OdtTest.PNG, 40, 20, "", "left");
    var cell = new Sequence<Block>().append(pic).append(t.blocks(0, 0).get(0));
    d = d.blocks(d.blocks.set(at, t.setBlocks(0, 0, cell)), d.nextId);
    var r = engine().layout(d, 0);
    LayoutEngine.PlacedPicture placed = null;
    for (LayoutEngine.PlacedPicture p : r.pages.get(0).pictures) if ("tp1".equals(p.picture().id())) placed = p;
    assertNotNull(placed);
    assertEquals(90, placed.picture().look.rotation);
    LayoutEngine.Box box = null;
    for (LayoutEngine.Box b : r.pages.get(0).boxes) if (b.column == 0 && b.row == 0) box = b;
    // Turned a quarter, the 40x20 picture is 20 wide and 40 tall on the page, inside its cell.
    float cx = placed.x() + placed.width() / 2, cy = placed.y() + placed.height() / 2;
    assertTrue(cx - 10 >= box.x - .01f && cx + 10 <= box.x + box.width + .01f);
    assertTrue(cy - 20 >= box.y - .01f && cy + 20 <= box.y + box.height + .01f);
    // Going down, content stacks leftward from the right: the picture, first, is right of the text.
    var l = line(r, "turned line");
    assertTrue(cx > l.pageBounds()[0] + l.pageBounds()[2]);
  }

  /** A 1x1 table whose cell (direction {@code outer}) holds a 1x1 table (direction {@code inner}). */
  private static Document nested(String outer, String inner) {
    return nested(outer, inner, false);
  }

  private static Document nested(String outer, String inner, boolean picture) {
    var s = new Session(Document.empty());
    s.insertTable(1, 1);
    var d = s.document();
    int at = 0;
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i) instanceof Table) at = i;
    var t = (Table) d.blocks.get(at);
    s.enterCell(at, 0, 0);
    s.insertTable(1, 1);
    s.leaveCell();
    d = s.document();
    t = (Table) d.blocks.get(at);
    int k = 0;
    for (int i = 0; i < t.blocks(0, 0).size(); i++) if (t.blocks(0, 0).get(i) instanceof Table) k = i;
    var n = (Table) t.blocks(0, 0).get(k);
    var content = new Sequence<Block>();
    if (picture) content = content.append(new Picture("np1", OdtTest.PNG, 40, 20, "", "left"));
    n = n.setBlocks(0, 0, content.append(new Paragraph("np", RichText.of("inner words"))));
    n = n.format(n.format.direction(0, 0, inner));
    t = t.setBlocks(0, 0, t.blocks(0, 0).set(k, n));
    t = t.format(t.format.direction(0, 0, outer));
    return d.blocks(d.blocks.set(at, t), d.nextId);
  }

  private static LayoutEngine.Line innerLine(LayoutEngine.Result r) {
    for (LayoutEngine.Line l : r.pages.get(0).lines)
      if (l.path != null && l.path.length >= 3 && l.end > l.start) return l;
    fail("no nested line");
    return null;
  }

  @Test
  public void nestedCellsKeepTheirOwnDirection() {
    // Inside a horizontal cell: the nested cell turns on its own.
    var flat = engine().layout(nested("lrTb", "btLr"), 0);
    var l = innerLine(flat);
    assertEquals(2, l.turn);
    LayoutEngine.Box outer = null;
    for (LayoutEngine.Box b : flat.pages.get(0).boxes) if (b.path.length == 0) outer = b;
    float[] b = l.pageBounds();
    assertEquals(l.width, b[3], .01f);
    if (outer != null) {
      assertTrue(b[0] >= outer.x - .01f && b[0] + b[2] <= outer.x + outer.width + .01f);
      assertTrue(b[1] >= outer.y - .01f && b[1] + b[3] <= outer.y + outer.height + .01f);
    }
    // Inside a turned cell: the nested cell's own (bottom-to-top) direction, not the outer one.
    var turned = engine().layout(nested("tbRl", "btLr"), 0);
    assertEquals(2, innerLine(turned).turn);
    // A horizontal nested cell in a turned cell turns with it.
    assertEquals(1, innerLine(engine().layout(nested("tbRl", "lrTb"), 0)).turn);
  }

  /** As Wordcraft's TurnedCell: a picture in a nested turned cell turns with that cell. */
  @Test
  public void picturesTurnWithTheirNestedTurnedCell() {
    var r = engine().layout(nested("lrTb", "btLr", true), 0);
    LayoutEngine.PlacedPicture placed = null;
    for (LayoutEngine.PlacedPicture p : r.pages.get(0).pictures) if ("np1".equals(p.picture().id())) placed = p;
    assertNotNull(placed);
    assertEquals(270, placed.picture().look.rotation);
    LayoutEngine.Box outer = null;
    for (LayoutEngine.Box b : r.pages.get(0).boxes) if (b.path.length == 0) outer = b;
    assertNotNull(outer);
    float cx = placed.x() + placed.width() / 2, cy = placed.y() + placed.height() / 2;
    // Turned a quarter, the 40x20 picture is 20 wide and 40 tall, inside the outer cell.
    assertTrue(cx - 10 >= outer.x - .01f && cx + 10 <= outer.x + outer.width + .01f);
    assertTrue(cy - 20 >= outer.y - .01f && cy + 20 <= outer.y + outer.height + .01f);
    // Bottom-to-top content stacks rightward from the left: the picture is left of the text.
    float[] b = innerLine(r).pageBounds();
    assertTrue(cx < b[0]);
  }

  /** As Wordcraft: a turned cell nested in a row's cell keeps that outer row on one page. */
  @Test
  public void nestedTurnedCellKeepsItsOuterRowWhole() {
    var d = nested("lrTb", "btLr");
    int at = 0;
    for (int i = 0; i < d.blocks.size(); i++) if (d.blocks.get(i) instanceof Table) at = i;
    var t = (Table) d.blocks.get(at);
    var cell = t.blocks(0, 0);
    for (int i = 0; i < 12; i++) cell = cell.append(new Paragraph("cp" + i, RichText.of("cell line " + i)));
    t = t.setBlocks(0, 0, cell);
    var blocks = d.blocks.set(at, t);
    float pageText = d.lastSection.page.height - d.lastSection.page.top - d.lastSection.page.bottom;
    // Fill the first page so only a few of the row's lines would still fit on it.
    int filler = (int) ((pageText - 60) / 12);
    for (int i = 0; i < filler; i++) blocks = blocks.insert(at, new Paragraph("f" + i, RichText.of("filler")));
    d = d.blocks(blocks, d.nextId);
    var r = engine().layout(d, 0);
    int tableBlock = at + filler, page = -1;
    for (int p = 0; p < r.pages.size(); p++)
      for (LayoutEngine.Line l : r.pages.get(p).lines)
        if (l.block == tableBlock && l.end > l.start) {
          if (page < 0) page = p;
          assertEquals("row split across pages", page, p);
        }
    assertTrue(page > 0);
  }
}
