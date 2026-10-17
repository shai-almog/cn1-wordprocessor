package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DrawingCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  private static int block(Object result) {
    return ((Number) ((Map<?, ?>) result).get("block")).intValue();
  }

  private static Drawing drawing(Session s, int block) {
    return ((Picture) s.document().blocks.get(block)).drawing;
  }

  private static List<Drawing> drawings(Document d) {
    List<Drawing> out = new ArrayList<Drawing>();
    for (Block b : d.blocks) if (b instanceof Picture && ((Picture) b).drawing != null) out.add(((Picture) b).drawing);
    return out;
  }

  @Test
  public void shapesTextBoxesAndWordArtFormatAndUndo() {
    Session s = new Session(Document.empty());
    int shape = block(run(s, "insert.shape", "shape", "star", "text", "Hi"));
    run(s, "shape.fill", "block", shape, "color", "#ff0000", "transparency", 25);
    run(s, "shape.outline", "block", shape, "color", "none");
    run(s, "shape.effects", "block", shape, "shadow", true, "glow", "00ff00");
    run(s, "shape.textDirection", "block", shape, "direction", "stacked");
    run(s, "shape.alignText", "block", shape, "align", "top");
    Drawing d = drawing(s, shape);
    assertEquals("star", d.get("shape", ""));
    assertEquals("ff0000", d.get("fill", ""));
    assertEquals("none", d.get("line", ""));
    assertTrue(d.flag("shadow"));
    assertEquals("stacked", d.get("textDirection", ""));
    DrawingOps.Ops ops = DrawingOps.build(d, 144, 96);
    assertEquals(2, ops.texts.size()); // stacked: one glyph per line
    assertTrue(ops.paths.size() >= 3); // glow, shadow, shape
    s.undo();
    assertEquals("middle", drawing(s, shape).get("valign", "middle"));
    try {
      run(s, "insert.shape", "shape", "blob");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    int art = block(run(s, "insert.wordArt", "text", "Wow", "style", 3));
    run(s, "wordArt.transform", "block", art, "transform", "arch");
    run(s, "wordArt.textOutline", "block", art, "color", "000000");
    assertEquals(3, DrawingOps.build(drawing(s, art), 120, 60).texts.size());
    int a = block(run(s, "insert.textBox", "text", "one two three four five six seven eight nine ten"));
    run(s, "picture.size", "block", a, "width", 60, "height", 30);
    int b = block(run(s, "insert.textBox", "text", ""));
    Map<?, ?> moved = (Map<?, ?>) run(s, "shape.link", "block", a, "target", b);
    assertTrue(((Number) moved.get("moved")).intValue() > 0);
    assertFalse(drawing(s, b).get("text", "").isEmpty());
  }

  @Test
  public void chartsSmartArtEquationsInkAndArrange() throws Exception {
    Session s = new Session(Document.empty());
    int chart = block(run(s, "insert.chart", "type", "bar", "data", "x,Sales\nQ1,3\nQ2,5"));
    run(s, "chart.title", "block", chart, "title", "Revenue");
    run(s, "chart.legend", "block", chart, "value", false);
    run(s, "chart.dataLabels", "block", chart);
    run(s, "chart.type", "block", chart, "type", "pie");
    assertEquals("x,Sales\nQ1,3\nQ2,5", run(s, "chart.editData", "block", chart));
    Drawing c = drawing(s, chart);
    assertEquals("pie", c.get("chartType", ""));
    assertTrue(c.flag("dataLabels"));
    boolean titled = false;
    for (DrawingOps.Text t : DrawingOps.build(c, 360, 216).texts) titled |= "Revenue".equals(t.text);
    assertTrue(titled);

    int art = block(run(s, "insert.smartArt", "layout", "process", "nodes", "Plan\nBuild"));
    run(s, "smartArt.addShape", "block", art, "text", "Ship");
    run(s, "smartArt.demote", "block", art, "node", 2);
    run(s, "smartArt.promote", "block", art, "node", 2);
    run(s, "smartArt.layout", "block", art, "layout", "cycle");
    run(s, "smartArt.colors", "block", art, "colors", "colorful");
    assertEquals("Plan\nBuild\nShip", run(s, "smartArt.textPane", "block", art));
    run(s, "smartArt.reset", "block", art);
    assertEquals("accent1", drawing(s, art).get("colors", "accent1"));

    int eq = block(run(s, "insert.equation", "text", "x^2+\\alpha"));
    run(s, "equation.structure", "block", eq, "kind", "fraction");
    run(s, "equation.insertSymbol", "block", eq, "name", "pi");
    run(s, "equation.number", "block", eq);
    run(s, "equation.justify", "block", eq, "justify", "left");
    assertEquals("x\u00b2+\u03b1(a)/(b)\u03c0", DrawingOps.professional(drawing(s, eq).get("text", "")));
    assertEquals("1", drawing(s, eq).get("number", ""));
    run(s, "equation.convert", "block", eq);
    assertEquals("linear", drawing(s, eq).get("format", ""));

    int canvas = block(run(s, "insert.canvas"));
    run(s, "draw.pen", "block", canvas, "points", "0,0 10,10 20,0", "color", "ff0000");
    run(s, "draw.highlighter", "block", canvas, "points", "100,100 200,100");
    run(s, "draw.pen", "block", canvas, "points", "300,10 340,10 340,50 300,50 300,12");
    assertEquals(3, ((List<?>) run(s, "draw.replay", "block", canvas)).size());
    assertEquals(1, ((List<?>) run(s, "draw.lasso", "block", canvas, "x", -1, "y", -1, "width", 30, "height", 30)).size());
    assertEquals(1, run(s, "draw.eraser", "block", canvas, "x", 150, "y", 100));
    assertEquals("rect", run(s, "draw.inkToShape", "block", canvas));

    run(s, "arrange.wrap", "block", chart, "wrap", "square");
    run(s, "arrange.bringForward", "block", chart, "all", true);
    run(s, "arrange.selectionPane", "block", chart, "name", "Revenue chart", "visible", false);
    assertTrue(DrawingOps.build(drawing(s, chart), 10, 10).paths.isEmpty());
    List<?> objects = (List<?>) run(s, "select.objects");
    assertTrue(objects.size() >= 5);

    // Native XML and DOCX keep every drawing exactly; PDF and HTML render them.
    List<Drawing> expected = drawings(s.document());
    assertEquals(expected, drawings(DocumentXml.read(DocumentXml.write(s.document()))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    List<Drawing> fromDocx = drawings(back.document);
    assertEquals(expected.size(), fromDocx.size());
    for (int i = 0; i < expected.size(); i++)
      if (!"equation".equals(expected.get(i).kind)) assertEquals(expected.get(i), fromDocx.get(i));
    String docx = new String(part(out.toByteArray(), "word/document.xml"), "UTF-8");
    assertTrue(docx.contains("<m:oMathPara"));
    assertTrue(docx.contains("wpg:wgp"));
    assertTrue(new String(PdfExporter.write(s.document()), "ISO-8859-1").contains("(Revenue) Tj") == false);
    assertTrue(new String(PdfExporter.write(s.document()), "ISO-8859-1").contains("(Plan) Tj"));
    assertTrue(TextFormats.html(s.document()).contains("<svg"));
  }

  private static byte[] part(byte[] zip, String name) throws Exception {
    java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip));
    for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; )
      if (e.getName().equals(name)) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
      }
    throw new AssertionError(name);
  }

  @Test
  public void groupAndUngroup() {
    Session s = new Session(Document.empty());
    int a = block(run(s, "insert.shape", "shape", "ellipse"));
    int b = block(run(s, "insert.icon", "icon", "check"));
    assertEquals(a + 1, b);
    run(s, "arrange.group", "from", a, "to", b);
    Drawing g = drawing(s, a);
    assertEquals("canvas", g.kind);
    assertTrue(DrawingOps.build(g, 144, 150).paths.size() >= 2);
    try {
      // Group encodings use C0 separators; recovery XML must still read back.
      assertEquals("canvas", drawing(new Session(DocumentXml.read(DocumentXml.write(s.document()))), a).kind);
    } catch (java.io.IOException ex) {
      throw new AssertionError(ex);
    }
    assertEquals(2, run(s, "arrange.group", "block", a, "ungroup", true));
    assertEquals("shape", drawing(s, a).kind);
    assertEquals("icon", drawing(s, a + 1).kind);
  }

  @Test
  public void squareWrapNarrowsTextBesideTheObjectAndFrontTakesNoSpace() throws Exception {
    Session s = new Session(Document.empty());
    int shape = block(run(s, "insert.shape", "shape", "rect", "width", 100, "height", 100));
    s.replace("Wrapped text beside the shape.");
    LayoutEngine engine = PdfExporter.layoutEngine(null);
    float inlineY = engine.layout(s.document(), 1).pages.get(0).lines.get(0).y;
    run(s, "arrange.wrap", "block", shape, "wrap", "square");
    LayoutEngine.Page page = engine.layout(s.document(), 2).pages.get(0);
    LayoutEngine.PlacedPicture pic = page.pictures.get(0);
    assertTrue(page.lines.get(0).x >= pic.x + pic.width);
    assertTrue(page.lines.get(0).y < inlineY);
    run(s, "arrange.wrap", "block", shape, "wrap", "front");
    page = engine.layout(s.document(), 3).pages.get(0);
    assertEquals(pic.y, page.lines.get(0).y, .01f);
    assertEquals(1, LayoutEngine.byDepth(page.pictures, true).size());
  }
}
