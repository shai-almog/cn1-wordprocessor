package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import com.codename1.ui.RichTextFormat;
import java.util.*;
import org.junit.Test;

/**
 * Editing pictures in table cells through the cell path (Wordcraft's object paths run through the
 * cell), cell pictures in header tables, cell float placement and cell pictures in RTF/Markdown.
 */
public class CellPictureEditingTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static byte[] png() throws Exception {
    var image = new java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

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

  private static Document table(Block... cellBlocks) {
    var cell = new Sequence<Block>();
    for (Block b : cellBlocks) cell = cell.append(b);
    var rows = new ArrayList<List<Sequence<Block>>>();
    rows.add(new ArrayList<>(List.of(cell)));
    Table t = Table.of("t", rows, new ArrayList<Table.Merge>());
    return new Document(
        new Sequence<Block>().append(new Paragraph("p0", RichText.of("before"))).append(t).append(new Paragraph("z", new RichText())),
        new PageSpec(400, 600, 20, 20, 20, 20),
        "C",
        50);
  }

  private static Picture cellPicture(Session s) {
    for (Block b : ((Table) s.document().blocks.get(1)).blocks(0, 0)) if (b instanceof Picture p) return p;
    return null;
  }

  @Test
  public void insertPictureWithTheCaretInACellInsertsIntoTheCell() throws Exception {
    var s = new Session(table(new Paragraph("a", RichText.of("cell text"))));
    s.enterCell(1, 0, 0);
    s.select(0, 4, 4);
    s.insertPicture(png(), 80, 40, "logo");
    assertTrue(s.inCell());
    var blocks = ((Table) s.document().blocks.get(1)).blocks(0, 0);
    assertTrue(blocks.get(1) instanceof Picture);
    assertEquals("logo", ((Picture) blocks.get(1)).alt);
    assertEquals(3, s.document().blocks.size());
    s.undo();
    assertNull(cellPicture(s));
  }

  @Test
  public void pictureCommandsResizeMoveDeleteAndReplaceThroughTheCellPath() throws Exception {
    byte[] png = png();
    var pic = new Picture("i1", png, 80, 40, "x", "left");
    var s = new Session(table(new Paragraph("a", RichText.of("top")), pic, new Paragraph("b", RichText.of("after"))));
    // As the editor's click selection: the cell path, the caret after the picture.
    s.enterCellPath(new int[] {1, 0, 0});
    s.select(2, 0, 0);
    run(s, "picture.border", "color", "ff0000", "width", 2);
    assertEquals(0xff0000, cellPicture(s).look.borderColor);
    assertTrue(s.inCell());
    run(s, "picture.size", "block", 1, "width", 40);
    assertEquals(40, cellPicture(s).width, .01);
    assertEquals(20, cellPicture(s).height, .01);
    run(s, "arrange.rotation", "block", 1, "degrees", 90);
    assertEquals(90, cellPicture(s).look.rotation);
    run(s, "arrange.bounds", "block", 1, "x", 30, "y", 5);
    assertEquals("square", cellPicture(s).wrap());
    var f = FloatPosition.parse(cellPicture(s).position());
    assertEquals(30, f.x, .01);
    assertEquals(5, f.y, .01);
    run(s, "picture.change", "block", 1, "base64", com.codename1.util.Base64.encodeNoNewline(png));
    assertEquals("i1", cellPicture(s).id());
    // The body is untouched; undo walks back each step.
    assertEquals(3, s.document().blocks.size());
    for (int i = 0; i < 5; i++) s.undo();
    assertEquals(0, cellPicture(s).look.borderColor == 0xff0000 ? 1 : 0);
    assertEquals(80, cellPicture(s).width, .01);
    // Delete (the picture dialog's Delete) removes it from the cell; undo restores it.
    s.enterCellPath(new int[] {1, 0, 0});
    s.updatePicture(1, 80, 40, "x", "left", true);
    assertNull(cellPicture(s));
    s.undo();
    assertNotNull(cellPicture(s));
  }

  @Test
  public void inkStrokesGoToTheCellCanvas() {
    var canvas = Picture.drawing("k1", Drawing.of("ink"), 100, 60, "", "left");
    var s = new Session(table(new Paragraph("a", RichText.of("top")), canvas, new Paragraph("b", new RichText())));
    s.enterCellPath(new int[] {1, 0, 0});
    var a = new LinkedHashMap<String, Object>();
    a.put("block", 1);
    a.put("points", "1,1 10,10 20,5");
    commands.execute(s, "draw.pen", a);
    assertFalse(cellPicture(s).drawing.get("strokes", "").isEmpty());
    s.undo();
    assertTrue(cellPicture(s).drawing.get("strokes", "").isEmpty());
  }

  @Test
  public void inkToShapeStaysInTheCell() {
    var canvas = Picture.drawing("k1", Drawing.of("ink"), 100, 60, "", "left");
    var s = new Session(table(new Paragraph("a", RichText.of("top")), canvas, new Paragraph("b", new RichText())));
    s.enterCellPath(new int[] {1, 0, 0});
    run(s, "draw.pen", "block", 1, "points", "0,0 40,0 40,30 0,30 0,0");
    assertEquals("rect", run(s, "draw.inkToShape", "block", 1));
    var cell = ((Table) s.document().blocks.get(1)).blocks(0, 0);
    assertTrue(cell.get(2) instanceof Picture);
    assertEquals("shape", ((Picture) cell.get(2)).drawing.kind);
    assertEquals(3, s.document().blocks.size());
    s.undo();
    assertEquals(3, ((Table) s.document().blocks.get(1)).blocks(0, 0).size());
  }

  @Test
  public void cellEquationsNumberWithinTheCell() {
    var e1 = Picture.drawing("e1", Drawing.of("equation").with("text", "a").with("display", "true"), 100, 20, "", "left");
    var e2 = Picture.drawing("e2", Drawing.of("equation").with("text", "b").with("display", "true"), 100, 20, "", "left");
    var s = new Session(table(new Paragraph("a", RichText.of("top")), e1, e2, new Paragraph("b", new RichText())));
    s.enterCellPath(new int[] {1, 0, 0});
    assertEquals(1, run(s, "equation.number", "block", 1));
    assertEquals(2, run(s, "equation.number", "block", 2));
    assertTrue(s.inCell());
    var cell = ((Table) s.document().blocks.get(1)).blocks(0, 0);
    assertEquals("1", ((Picture) cell.get(1)).drawing.get("number", ""));
    assertEquals("2", ((Picture) cell.get(2)).drawing.get("number", ""));
  }

  @Test
  public void cellEquationsEditInPlace() {
    var e1 = Picture.drawing("e1", Drawing.of("equation").with("text", "E=mc").with("display", "true"), 100, 20, "", "left");
    var s = new Session(table(new Paragraph("a", RichText.of("top")), e1, new Paragraph("b", new RichText())));
    s.enterCellPath(new int[] {1, 0, 0});
    run(s, "equation.edit", "block", 1, "steps", "end\ntype:^2 ");
    var cell = ((Table) s.document().blocks.get(1)).blocks(0, 0);
    assertEquals("E=mc^2", ((Picture) cell.get(1)).drawing.get("text", ""));
    // An inline equation in a cell paragraph.
    s.enterCellPath(new int[] {1, 0, 0});
    s.select(0, 3, 3);
    run(s, "insert.equation", "text", "x");
    assertTrue(s.inCell());
    s.select(0, 4, 4);
    run(s, "equation.edit", "steps", "end\ntype:+y");
    var p = (Paragraph) ((Table) s.document().blocks.get(1)).blocks(0, 0).get(0);
    assertEquals("x+y", Equation.inlineText(p.text.fieldAt(3)));
  }

  @Test
  public void placedCellPicturesCarryTheirCellPathAndIndex() throws Exception {
    var pic = new Picture("i1", png(), 80, 40, "x", "left");
    var d = table(new Paragraph("a", RichText.of("top")), pic, new Paragraph("b", new RichText()));
    var layout = engine().layout(d, 0);
    LayoutEngine.PlacedPicture placed = layout.pages.get(0).pictures.get(0);
    assertTrue(placed.inCell());
    assertArrayEquals(new int[] {1, 0, 0}, placed.cell());
    assertEquals(1, placed.block());
    assertSame(pic, placed.picture());
  }

  private static float floatTop(String position, int rotation) {
    var d =
        Drawing.of("shape").with("wrap", "square").with("position", position).with("rotation", String.valueOf(rotation));
    var f = Picture.drawing("c2", d, 60, 40, "", "left");
    var doc = table(new Paragraph("a", RichText.of("top")), f, new Paragraph("b", RichText.of("word ".repeat(60))));
    var layout = engine().layout(doc, 0);
    var p = layout.pages.get(0).pictures.get(0);
    return p.y() - p.frame()[1];
  }

  @Test
  public void cellFloatsAlignAtTheirParagraphAsWordcraft() {
    // No page frame in a cell: bottom/middle alignment hangs the object above its anchor.
    assertEquals(0, floatTop("page,,0;page,top,0", 0), .01);
    assertEquals(-20, floatTop("page,,0;page,center,0", 0), .01);
    assertEquals(-40, floatTop("page,,0;page,bottom,0", 0), .01);
    assertEquals(7, floatTop("column,,0;paragraph,,7", 0), .01);
  }

  private static float firstLineLead(int rotation, String wrap, String polygon) {
    var d = Drawing.of("shape").with("wrap", wrap).with("position", "column,,0;paragraph,,0").with("rotation", String.valueOf(rotation));
    if (polygon != null) d = d.with("wrapPolygon", polygon);
    var f = Picture.drawing("c2", d, 100, 20, "", "left");
    var doc = table(f, new Paragraph("b", RichText.of("word ".repeat(60))));
    float min = Float.MAX_VALUE;
    var layout = engine().layout(doc, 0);
    for (var line : layout.pages.get(0).lines)
      if (line.row >= 0 && line.y < layout.pages.get(0).pictures.get(0).y() + 10) min = Math.min(min, line.x);
    return min;
  }

  @Test
  public void rotationAndTightOutlinesShapeCellWrap() {
    float plain = firstLineLead(0, "square", null);
    // Turned 90 degrees the 100x20 box's bounds are 20 wide: text comes closer.
    float turned = firstLineLead(90, "square", null);
    assertTrue(plain + " vs " + turned, turned < plain - 30);
    // Tight around a left-half outline: text follows it rather than the frame.
    float tight = firstLineLead(0, "tight", "0,0 6480,0 6480,21600 0,21600");
    assertTrue(plain + " vs " + tight, tight < plain - 30);
  }

  @Test
  public void headerTablePicturesRoundTripThroughDocx() throws Exception {
    var pic = new Picture("h1", png(), 50, 25, "hdr", "left");
    var cell = new Sequence<Block>().append(new Paragraph("a", RichText.of("logo"))).append(pic).append(new Paragraph("b", new RichText()));
    var rows = new ArrayList<List<Sequence<Block>>>();
    rows.add(new ArrayList<>(List.of(cell)));
    Table t = Table.of("t", rows, new ArrayList<Table.Merge>());
    var d = Document.empty();
    d = d.furniture(d.furniture.blocks("header", new Sequence<Block>().append(t).append(new Paragraph("e", new RichText()))));
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    Table bt = null;
    for (Block b : back.furniture.blocks("header")) if (b instanceof Table x) bt = x;
    assertNotNull(bt);
    Picture got = null;
    for (Block b : bt.blocks(0, 0)) if (b instanceof Picture p) got = p;
    assertNotNull(got);
    assertEquals("hdr", got.alt);
    assertEquals(50, got.width, .5);
  }

  @Test
  public void rtfAndMarkdownCellPictures() throws Exception {
    var pic = new Picture("i1", png(), 30, 15, "chart", "left");
    var d = table(new Paragraph("a", RichText.of("cell")), pic, new Paragraph("b", RichText.of("end")));
    String md = TextFormats.write(d, RichTextFormat.MARKDOWN);
    assertTrue(md, md.contains("| cell<br>![chart](data:image/png;base64,"));
    String rtf = TextFormats.write(d, RichTextFormat.RTF);
    int cellStart = rtf.indexOf("\\intbl"), cellEnd = rtf.indexOf("\\cell\n");
    int pict = rtf.indexOf("{\\pict");
    assertTrue(rtf, pict > cellStart && pict < cellEnd);
  }
}
