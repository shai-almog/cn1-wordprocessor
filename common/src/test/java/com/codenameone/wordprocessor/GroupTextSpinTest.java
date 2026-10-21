package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import org.junit.Test;

/** As Wordcraft's Draw::Rotated: the letters of a turned group member turn with it. */
public class GroupTextSpinTest {
  @Test
  public void rotatingOpsTurnsTextLettersAndPdfWritesTheTurn() throws Exception {
    var ops = new DrawingOps.Ops();
    ops.texts.add(new DrawingOps.Text("Hi", 0, 0, 40, 10, 0, false, false, "left", -1, 0));
    DrawingOps.rotate(ops, 0, 0, 0, 20, 5, 30);
    assertEquals(30, ops.texts.get(0).spin(), .001f);
    // Centre stays put when turning about the box's own centre.
    assertEquals(0, ops.texts.get(0).x(), .01f);
    DrawingOps.rotate(ops, 0, 0, 0, 20, 5, 45);
    assertEquals(75, ops.texts.get(0).spin(), .001f);

    // A group turned 90 degrees holding a text box: its letters are drawn turned in the PDF.
    String ns = "xmlns:wp=\"w\" xmlns:wpg=\"g\" xmlns:wps=\"s\" xmlns:a=\"a\" xmlns:w=\"m\"";
    String xml = "<wp:anchor " + ns + "><wp:extent cx=\"1270000\" cy=\"635000\"/><a:graphic><a:graphicData><wpg:wgp>"
        + "<wpg:grpSpPr><a:xfrm rot=\"5400000\"><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1270000\" cy=\"635000\"/>"
        + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"1270000\" cy=\"635000\"/></a:xfrm></wpg:grpSpPr>"
        + "<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1270000\" cy=\"635000\"/></a:xfrm>"
        + "<a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent><w:p><w:r><w:t>Turned</w:t></w:r></w:p>"
        + "</w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp></wpg:wgp></a:graphicData></a:graphic></wp:anchor>";
    Drawing d = DocxDrawings.shape(Xml.parse(xml), new java.util.HashSet<>(), id -> null);
    var built = DrawingOps.build(d, 100, 50);
    assertFalse(built.texts.isEmpty());
    for (DrawingOps.Text t : built.texts) assertEquals(90, t.spin(), .01f);
    var doc = new Document(new Sequence<Block>().append(Picture.drawing("i1", d, 100, 50, "", "left")), PageSpec.A4, "G", 2);
    String pdf = new String(PdfExporter.write(doc), "ISO-8859-1");
    assertTrue(pdf.contains(" cm BT"));
  }
}
