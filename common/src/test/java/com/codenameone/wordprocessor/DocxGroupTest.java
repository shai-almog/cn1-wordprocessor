package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.HashSet;
import org.junit.Test;

/** wpg:wgp groups and a:custGeom freeforms as Wordcraft's group_members and freeform readers. */
public class DocxGroupTest {
  private static final String NS = "xmlns:wp=\"w\" xmlns:wpg=\"g\" xmlns:wps=\"s\" xmlns:a=\"a\"";

  private static String wsp(long x, long y, long cx, long cy, String geom) {
    return "<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx
        + "\" cy=\"" + cy + "\"/></a:xfrm>" + geom + "</wps:spPr><wps:bodyPr/></wps:wsp>";
  }

  @Test
  public void groupMembersMapThroughChildSpaces() throws Exception {
    String free = "<a:custGeom><a:pathLst><a:path w=\"100\" h=\"100\"><a:moveTo><a:pt x=\"0\" y=\"0\"/></a:moveTo>"
        + "<a:lnTo><a:pt x=\"100\" y=\"0\"/></a:lnTo><a:cubicBezTo><a:pt x=\"100\" y=\"50\"/><a:pt x=\"50\" y=\"100\"/>"
        + "<a:pt x=\"0\" y=\"100\"/></a:cubicBezTo><a:close/></a:path>"
        + "<a:path w=\"100\" h=\"100\" fill=\"none\"><a:moveTo><a:pt x=\"0\" y=\"50\"/></a:moveTo><a:lnTo><a:pt x=\"100\" y=\"50\"/></a:lnTo></a:path>"
        + "</a:pathLst></a:custGeom>";
    // 144 x 72 pt frame over a 2000 x 1000 child space; a nested group scaling by half.
    String xml = "<wp:anchor " + NS + "><wp:extent cx=\"1828800\" cy=\"914400\"/><a:graphic><a:graphicData><wpg:wgp>"
        + "<wpg:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1828800\" cy=\"914400\"/><a:chOff x=\"0\" y=\"0\"/>"
        + "<a:chExt cx=\"2000\" cy=\"1000\"/></a:xfrm></wpg:grpSpPr>"
        + wsp(0, 0, 1000, 1000, "<a:prstGeom prst=\"ellipse\"/>")
        + "<wpg:grpSp><wpg:grpSpPr><a:xfrm><a:off x=\"1000\" y=\"0\"/><a:ext cx=\"1000\" cy=\"1000\"/>"
        + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"2000\" cy=\"2000\"/></a:xfrm></wpg:grpSpPr>"
        + wsp(1000, 1000, 1000, 1000, free) + "</wpg:grpSp></wpg:wgp></a:graphicData></a:graphic></wp:anchor>";
    Drawing d = DocxDrawings.shape(Xml.parse(xml), new HashSet<>());
    assertTrue(d.flag("group"));
    String[] items = d.get("items", "").split("\u001e");
    assertEquals(2, items.length);
    String[] second = items[1].split("\u001f");
    // Nested child (1000,1000) in a 2000 space at outer 1000 + 500 = 1500 of 2000 -> 108 pt.
    assertEquals(108, Float.parseFloat(second[0]), .1f);
    assertEquals(36, Float.parseFloat(second[1]), .1f);
    assertEquals(36, Float.parseFloat(second[2]), .1f);
    Drawing f = Drawing.decode(second[4]);
    assertEquals("custom", f.get("shape", ""));
    String path = f.get("path", "");
    assertTrue(path, path.startsWith("c:0.0,0.0 1.0,0.0") && path.contains(";o:0.0,0.5 1.0,0.5"));
    // Flattened Bezier ends at its end point; the group draws both members' paths.
    assertTrue(path, path.contains("0.0,1.0;"));
    var ops = DrawingOps.build(d, 144, 72);
    assertTrue(ops.paths.size() >= 3);
  }

  @Test
  public void groupPicturesAndRotationAreDrawnSavedAndExported() throws Exception {
    // 100 x 50 pt group (child space in EMUs) turned 90 degrees, holding a picture at the left
    // half turned 30 degrees and a rectangle at the right half.
    String pic = "<pic:pic xmlns:pic=\"p\" xmlns:r=\"r\"><pic:blipFill><a:blip r:embed=\"rId9\"/></pic:blipFill><pic:spPr>"
        + "<a:xfrm rot=\"1800000\"><a:off x=\"0\" y=\"0\"/><a:ext cx=\"635000\" cy=\"635000\"/></a:xfrm></pic:spPr></pic:pic>";
    String xml = "<wp:anchor " + NS + "><wp:extent cx=\"1270000\" cy=\"635000\"/><a:graphic><a:graphicData><wpg:wgp>"
        + "<wpg:grpSpPr><a:xfrm rot=\"5400000\"><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1270000\" cy=\"635000\"/>"
        + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"1270000\" cy=\"635000\"/></a:xfrm></wpg:grpSpPr>"
        + pic + wsp(635000, 0, 635000, 635000, "<a:prstGeom prst=\"rect\"/>") + "</wpg:wgp></a:graphicData></a:graphic></wp:anchor>";
    var warnings = new HashSet<String>();
    Drawing d = DocxDrawings.shape(Xml.parse(xml), warnings, id -> "rId9".equals(id) ? OdtTest.PNG : null);
    assertTrue(warnings.toString(), warnings.isEmpty());
    assertEquals(90, d.num("rotation", 0), .01f);
    var ops = DrawingOps.build(d, 100, 50);
    assertEquals(1, ops.images.size());
    var im = ops.images.get(0);
    // Member 30 degrees plus the group's 90; its centre (25, 25) turned about (50, 25) -> (50, 0).
    assertEquals(120, im.rotation(), .01f);
    assertEquals(50, im.x() + im.width() / 2, .01f);
    assertEquals(0, im.y() + im.height() / 2, .01f);
    // Without bytes the picture is dropped with a warning, as before.
    var dropped = new HashSet<String>();
    DocxDrawings.shape(Xml.parse(xml), dropped, null);
    assertTrue(dropped.contains("Picture inside a drawing group dropped"));
    // DOCX keeps it (with a pic:pic for Word) and PDF draws it as an image XObject.
    var doc = new Document(new Sequence<Block>().append(Picture.drawing("i1", d, 100, 50, "", "left")),
        PageSpec.A4, "G", 2);
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(doc, out);
    var back = Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document;
    assertEquals(1, DrawingOps.build(((Picture) back.blocks.get(0)).drawing, 100, 50).images.size());
    String xmlOut = new String(DrawingCommandsTest.part(out.toByteArray(), "word/document.xml"), "UTF-8");
    assertTrue(xmlOut.contains("<pic:pic") && xmlOut.contains("rot=\"7200000\""));
    String pdf = new String(PdfExporter.write(doc), "ISO-8859-1");
    assertTrue(pdf.contains("/Im0 Do"));
  }
}
