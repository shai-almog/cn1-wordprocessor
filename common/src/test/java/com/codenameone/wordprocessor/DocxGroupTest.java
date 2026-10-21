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
}
