package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

/** wp:anchor wrapping as Wordcraft's anchor_float reads it: wrap kinds, behindDoc, polygons. */
public class DocxWrapTest {
  private static final String WP = "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"";

  private static String[] wrap(String anchorBody, String attrs) throws Exception {
    return DocxPictures.anchorWrap(Xml.parse("<wp:anchor " + WP + " " + attrs + ">" + anchorBody + "</wp:anchor>"));
  }

  @Test
  public void anchorKindsAndPolygons() throws Exception {
    assertEquals("behind", wrap("<wp:wrapNone/>", "behindDoc=\"1\"")[0]);
    assertEquals("front", wrap("<wp:wrapNone/>", "behindDoc=\"0\"")[0]);
    assertEquals("topBottom", wrap("<wp:wrapTopAndBottom/>", "")[0]);
    String[] t = wrap("<wp:positionH relativeFrom=\"column\"><wp:align>right</wp:align></wp:positionH>"
        + "<wp:wrapThrough wrapText=\"bothSides\"><wp:wrapPolygon edited=\"1\"><wp:start x=\"0\" y=\"0\"/>"
        + "<wp:lineTo x=\"10800\" y=\"21600\"/><wp:lineTo x=\"21600\" y=\"0\"/><wp:lineTo x=\"0\" y=\"0\"/>"
        + "</wp:wrapPolygon></wp:wrapThrough>", "");
    assertEquals("through", t[0]);
    assertEquals("1;0,0 10800,21600 21600,0", t[1]);
    assertEquals("right", t[2]);
    // An unedited frame polygon says nothing: dropped as in Wordcraft.
    assertEquals("", wrap("<wp:wrapTight><wp:wrapPolygon edited=\"0\"><wp:start x=\"0\" y=\"0\"/>"
        + "<wp:lineTo x=\"0\" y=\"21600\"/><wp:lineTo x=\"21600\" y=\"21600\"/><wp:lineTo x=\"21600\" y=\"0\"/>"
        + "<wp:lineTo x=\"0\" y=\"0\"/></wp:wrapPolygon></wp:wrapTight>", "")[1]);
  }

  private static Document doc(Picture p, String text) {
    return new Document(new Sequence<Block>().append(p).append(new Paragraph("t", RichText.of(text))),
        new PageSpec(400, 600, 20, 20, 20, 20), "W", 3);
  }

  @Test
  public void wrapRoundTripsThroughDocx() throws Exception {
    // A triangle pointing down: wide at the top, a point at the bottom.
    Drawing d = Drawing.of("shape").with("wrap", "tight").with("wrapPolygon", "1;0,0 21600,0 10800,21600");
    Document doc = doc(Picture.drawing("i1", d, 200, 200, "", "left"), "x");
    var out = new ByteArrayOutputStream();
    Docx.write(doc, out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    Picture p = (Picture) back.blocks.get(0);
    assertEquals("tight", p.wrap());
    assertEquals("1;0,0 21600,0 10800,21600", p.wrapPolygon());
    Drawing b = Drawing.of("shape").with("wrap", "behind");
    var out2 = new ByteArrayOutputStream();
    Docx.write(doc(Picture.drawing("i1", b, 100, 100, "", "left"), "x"), out2);
    assertEquals("behind", ((Picture) Docx.read(new ByteArrayInputStream(out2.toByteArray())).document.blocks.get(0)).wrap());
  }

  @Test
  public void polygonNarrowsTextLessWhereTheOutlineIsNarrow() {
    var engine = new LayoutEngine(new LayoutEngine.Metrics() {
      public float width(String text, TextStyle style) {
        return text.length() * 6;
      }

      public float height(TextStyle style) {
        return 12;
      }
    });
    float[] o = WrapOutline.parse("1;0,0 21600,0 10800,21600");
    assertArrayEquals(new float[] {0, 1}, WrapOutline.extent(o, 0, .01f), .02f);
    assertEquals(.5f, WrapOutline.extent(o, .99f, 1)[0], .02f);
    Drawing square = Drawing.of("shape").with("wrap", "square");
    Drawing tight = Drawing.of("shape").with("wrap", "tight").with("wrapPolygon", "1;0,0 21600,0 10800,21600");
    String text = "word ".repeat(60);
    var a = engine.layout(doc(Picture.drawing("i1", square, 200, 200, "", "left"), text), 0).pages.get(0).lines.get(0);
    var c = engine.layout(doc(Picture.drawing("i1", tight, 200, 200, "", "left"), text), 0).pages.get(0).lines.get(0);
    // At the top the triangle is full width either way; behind text does not narrow at all.
    assertEquals(a.x, c.x, 8);
    Drawing behind = Drawing.of("shape").with("wrap", "behind");
    var e = engine.layout(doc(Picture.drawing("i1", behind, 200, 200, "", "left"), text), 0).pages.get(0).lines.get(0);
    assertTrue(e.x < a.x);
  }

  private static LayoutEngine engine() {
    return new LayoutEngine(new LayoutEngine.Metrics() {
      public float width(String text, TextStyle style) {
        return text.length() * 6;
      }

      public float height(TextStyle style) {
        return 12;
      }
    });
  }

  @Test
  public void positionsAreReadAndRoundTrip() throws Exception {
    String[] t = wrap("<wp:positionH relativeFrom=\"page\"><wp:posOffset>3810000</wp:posOffset></wp:positionH>"
        + "<wp:positionV relativeFrom=\"margin\"><wp:align>bottom</wp:align></wp:positionV><wp:wrapSquare/>", "");
    assertEquals("page,,300;margin,end,0", t[3]);
    assertEquals("character,center,0;line,,-6",
        wrap("<wp:positionH relativeFrom=\"character\"><wp:align>center</wp:align></wp:positionH>"
            + "<wp:positionV relativeFrom=\"line\"><wp:posOffset>-76200</wp:posOffset></wp:positionV>", "")[3]);
    // Aligned in the column at the paragraph is the picture's own alignment.
    assertEquals("", wrap("<wp:positionH relativeFrom=\"column\"><wp:align>right</wp:align></wp:positionH>"
        + "<wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0</wp:posOffset></wp:positionV>", "")[3]);
    Drawing d = Drawing.of("shape").with("wrap", "square").with("position", "page,,300;margin,end,0");
    var out = new ByteArrayOutputStream();
    Docx.write(doc(Picture.drawing("i1", d, 50, 50, "", "left"), "x"), out);
    Picture back = (Picture) Docx.read(new ByteArrayInputStream(out.toByteArray())).document.blocks.get(0);
    assertEquals("page,,300;margin,end,0", back.position());
    // A raster picture keeps its position in its look, through native XML too.
    Picture raster = new Picture("i2", png(), 40, 40, "", "left",
        Picture.Look.NONE.wrap("square", "").position("margin,center,0;page,,72"));
    Document nd = doc(raster, "x");
    Picture again = (Picture) DocumentXml.read(DocumentXml.write(nd)).blocks.get(0);
    assertEquals("margin,center,0;page,,72", again.position());
  }

  private static byte[] png() throws Exception {
    return OdtTest.PNG;
  }

  @Test
  public void positionedSquareFloatNarrowsEachLineBesideIt() {
    Drawing d = Drawing.of("shape").with("wrap", "square").with("position", "page,,250;page,,100");
    var page = engine().layout(doc(Picture.drawing("i1", d, 100, 100, "", "left"), "word ".repeat(200)), 0).pages.get(0);
    var pic = page.pictures.get(0);
    assertEquals(250, pic.x(), .01f);
    assertEquals(100, pic.y(), .01f);
    int beside = 0, full = 0;
    for (var l : page.lines) {
      if (l.y + l.height > 100 && l.y < 200) {
        beside++;
        assertTrue(l.x + l.width <= 241.5f);
      } else if (l.width > 300) full++;
    }
    assertTrue(beside >= 7);
    assertTrue(full > 3);
  }

  @Test
  public void topAndBottomFloatClearsItsBandAndTightFollowsTheOutlinePerLine() {
    Drawing tb = Drawing.of("shape").with("wrap", "topBottom").with("position", "margin,,0;page,,100");
    var page = engine().layout(doc(Picture.drawing("i1", tb, 100, 100, "", "left"), "word ".repeat(200)), 0).pages.get(0);
    for (var l : page.lines) assertFalse(l.y + l.height > 100 && l.y < 200);
    // A triangle pointing down at the left margin: lines beside its top start right of it, lower
    // lines of the same paragraph start further left as the outline narrows.
    Drawing tight = Drawing.of("shape").with("wrap", "tight").with("wrapPolygon", "1;0,0 21600,0 0,21600")
        .with("position", "margin,,0;page,,20");
    var p2 = engine().layout(doc(Picture.drawing("i1", tight, 200, 200, "", "left"), "word ".repeat(200)), 0).pages.get(0);
    var first = p2.lines.get(0);
    var lower = p2.lines.get(12);
    assertTrue(first.x >= 220);
    assertTrue(lower.x < first.x - 50);
    assertTrue(lower.x > 20);
  }

  /** Text flows on both sides of a mid-column float (Wordcraft's row_spans), or on one side. */
  @Test
  public void bothSidesLeftRightAndLargestWrapText() throws Exception {
    Drawing d = Drawing.of("shape").with("wrap", "square").with("position", "page,,160;page,,100");
    var page = engine().layout(doc(Picture.drawing("i1", d, 80, 100, "", "left"), "word ".repeat(300)), 0).pages.get(0);
    int split = 0;
    for (var l : page.lines) {
      if (l.y + l.height <= 100 || l.y >= 200) continue;
      boolean left = false, right = false;
      for (var sp : l.spans) {
        if (sp.text.trim().isEmpty()) continue;
        float sx = l.x + sp.x;
        assertFalse("text under the float", sx + sp.width > 152 && sx < 248);
        if (sx < 152) left = true;
        else right = true;
      }
      if (left && right) split++;
    }
    assertTrue(split >= 6);
    // Left only: nothing right of the float; right only: nothing left of it.
    for (String side : new String[] {"left", "right", "largest"}) {
      Drawing one = d.with("position", "page,,160;page,,100;" + side);
      var pg = engine().layout(doc(Picture.drawing("i1", one, 80, 100, "", "left"), "word ".repeat(300)), 0).pages.get(0);
      for (var l : pg.lines) {
        if (l.y + l.height <= 100 || l.y >= 200) continue;
        for (var sp : l.spans) {
          if (sp.text.trim().isEmpty()) continue;
          float sx = l.x + sp.x;
          // Largest: the sides tie (131pt each), and Wordcraft then takes the left.
          if (!"right".equals(side)) assertTrue(sx + sp.width <= 152.5f);
          else assertTrue(side + " " + sx + " y" + l.y + " h" + l.height + " " + sp.text, sx >= 247.5f);
        }
      }
    }
    // wrapText reads into the position and is written back.
    assertEquals("column,start,0;paragraph,,0;left", wrap("<wp:wrapSquare wrapText=\"left\"/>", "")[3]);
    Drawing w = d.with("position", "page,,160;page,,100;largest");
    var out = new ByteArrayOutputStream();
    Docx.write(doc(Picture.drawing("i1", w, 50, 50, "", "left"), "x"), out);
    Picture back = (Picture) Docx.read(new ByteArrayInputStream(out.toByteArray())).document.blocks.get(0);
    assertEquals("page,,160;page,,100;largest", back.position());
    assertEquals("right", FloatPosition.sideOf(back.wrapSide("right").position()));
  }

  /** A column-aligned paragraph float wraps per line: lines below it return to the full width. */
  @Test
  public void columnAlignedFloatWrapsPerLine() {
    Drawing d = Drawing.of("shape").with("wrap", "square");
    var page = engine().layout(doc(Picture.drawing("i1", d, 100, 60, "", "right"), "word ".repeat(200)), 0).pages.get(0);
    var pic = page.pictures.get(0);
    assertEquals(280, pic.x(), .01f);
    boolean beside = false, full = false;
    for (var l : page.lines) {
      if (l.y < pic.y() + pic.height()) {
        assertTrue(l.x + l.width <= pic.x());
        beside = true;
      } else if (l.width > 300) full = true;
    }
    assertTrue(beside && full);
  }
}
