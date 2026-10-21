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
}
