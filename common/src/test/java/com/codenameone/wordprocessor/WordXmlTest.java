package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class WordXmlTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String W2003 = "http://schemas.microsoft.com/office/word/2003/wordml";

  private static Docx.Opened open(String xml) throws IOException {
    return Docx.read(new ByteArrayInputStream(xml.getBytes("UTF-8")));
  }

  @Test
  public void flatOpcOpensWithStylesAndBinaryParts() throws Exception {
    String xml = "<?xml version=\"1.0\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/2006/xmlPackage\">"
        + "<pkg:part pkg:name=\"/word/document.xml\"><pkg:xmlData><w:document xmlns:w=\"" + W + "\"><w:body>"
        + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello flat</w:t></w:r></w:p></w:body></w:document></pkg:xmlData></pkg:part>"
        + "<pkg:part pkg:name=\"/word/media/x.bin\"><pkg:binaryData>AAEC\nAw==</pkg:binaryData></pkg:part>"
        + "</pkg:package>";
    var opened = open(xml);
    assertEquals("Hello flat", opened.document.paragraph(0).text.toString());
    assertTrue(opened.document.paragraph(0).text.styleAt(0).bold);
  }

  @Test
  public void word2003OpensBodyStylesAndHyphenatedNames() throws Exception {
    String xml = "<?xml version=\"1.0\"?><?mso-application progid=\"Word.Document\"?>"
        + "<w:wordDocument xmlns:w=\"" + W2003 + "\" xmlns:wx=\"http://schemas.microsoft.com/office/word/2003/auxHint\""
        + " xmlns:aml=\"http://schemas.microsoft.com/aml/2001/core\">"
        + "<w:styles><w:style w:type=\"paragraph\" w:styleId=\"H\"><w:name w:val=\"Big\"/><w:rPr><w:sz w:val=\"40\"/></w:rPr></w:style></w:styles>"
        + "<w:lists/><w:body><wx:sect><w:p><w:pPr><w:pStyle w:val=\"H\"/></w:pPr><w:r><w:rPr><w:i/><w:b-cs/></w:rPr><w:t>Old</w:t></w:r>"
        + "<aml:annotation aml:id=\"1\" w:type=\"Word.Deletion\"><aml:content><w:r><w:delText>gone</w:delText></w:r></aml:content></aml:annotation>"
        + "<w:hlink w:dest=\"http://x\"><w:r><w:t> link</w:t></w:r></w:hlink>"
        + "<w:r><w:pict><v:shape xmlns:v=\"urn:schemas-microsoft-com:vml\"/></w:pict></w:r></w:p>"
        + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></wx:sect></w:body></w:wordDocument>";
    var opened = open(xml);
    var p = opened.document.paragraph(0);
    assertEquals("Old link", p.text.toString());
    assertTrue(p.text.styleAt(0).italic);
    assertEquals(20, p.text.styleAt(0).size, .001);
    String warnings = opened.warnings.toString();
    assertTrue(warnings, warnings.contains("drawing shapes"));
    assertTrue(warnings, warnings.contains("tracked changes"));
    assertEquals("http://x", p.text.styleAt(4).link);
    assertEquals("bCs", WordXml.rename("b-cs"));
    assertEquals("vMerge", WordXml.rename("vmerge"));
  }

  private static final String PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

  @Test
  public void word2003ListsPicturesBoxesFurnitureNotesAndComments() throws Exception {
    String xml = "<?xml version=\"1.0\"?><w:wordDocument xmlns:w=\"" + W2003 + "\""
        + " xmlns:wx=\"http://schemas.microsoft.com/office/word/2003/auxHint\""
        + " xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:aml=\"http://schemas.microsoft.com/aml/2001/core\">"
        + "<w:lists><w:listDef w:listDefId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"3\"/><w:nfc w:val=\"4\"/>"
        + "<w:lvlText w:val=\"%1)\"/></w:lvl></w:listDef><w:list w:ilfo=\"1\"><w:ilst w:val=\"0\"/></w:list></w:lists>"
        + "<w:body><wx:sect>"
        + "<w:p><w:pPr><w:listPr><w:ilvl w:val=\"0\"/><w:ilfo w:val=\"1\"/><wx:t wx:val=\"c)\"/></w:listPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>"
        + "<w:p><w:hlink w:dest=\"https://example.com/\"><w:r><w:t>Web</w:t></w:r></w:hlink>"
        + "<w:r><w:t>Note</w:t></w:r><w:r><w:footnote><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t> Foot body</w:t></w:r></w:p></w:footnote></w:r>"
        + "<aml:annotation aml:id=\"0\" w:type=\"Word.Comment.Start\"/><w:r><w:t>Said</w:t></w:r>"
        + "<aml:annotation aml:id=\"0\" w:type=\"Word.Comment.End\"/>"
        + "<w:r><aml:annotation aml:id=\"0\" aml:author=\"Ann\" w:type=\"Word.Comment\"><aml:content>"
        + "<w:p><w:r><w:t>Remark</w:t></w:r></w:p></aml:content></aml:annotation></w:r></w:p>"
        + "<w:p><w:r><w:pict><w:binData w:name=\"wordml://1.png\">" + PNG + "</w:binData>"
        + "<v:shape style=\"width:36pt;height:1in\"><v:imagedata src=\"wordml://1.png\"/></v:shape></w:pict></w:r></w:p>"
        + "<w:p><w:r><w:pict><v:shape style=\"width:144pt;height:72pt\"><v:textbox><w:txbxContent>"
        + "<w:p><w:r><w:t>Boxed</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
        + "<w:sectPr><w:hdr w:type=\"odd\"><w:p><w:r><w:t>Head</w:t></w:r></w:p></w:hdr>"
        + "<w:ftr w:type=\"first\"><w:p><w:r><w:t>Foot</w:t></w:r></w:p></w:ftr><w:titlePg/></w:sectPr>"
        + "</wx:sect></w:body></w:wordDocument>";
    var opened = open(xml);
    var d = opened.document;
    String warnings = opened.warnings.toString();
    var item = d.paragraph(0);
    assertEquals("Item", item.text.toString());
    assertNotNull(warnings, item.list);
    assertEquals(3, item.listStart);
    var p = d.paragraph(1);
    assertTrue(p.text.toString(), p.text.toString().startsWith("WebNote\ufffcSaid"));
    assertEquals("https://example.com/", p.text.styleAt(0).link);
    assertEquals(1, Notes.all(d).size());
    assertEquals(1, d.comments.size());
    assertEquals("Ann", d.comments.get(0).author);
    int pictures = 0;
    boolean box = false;
    for (int i = 0; i < d.blocks.size(); i++)
      if (d.blocks.get(i) instanceof Picture pic) {
        pictures++;
        if (pic.drawing == null) {
          assertEquals(36, pic.width, .5);
          assertEquals(72, pic.height, .5);
        } else box |= pic.drawing.toString().contains("Boxed") || "textBox".equals(pic.drawing.kind);
      }
    assertEquals(warnings, 2, pictures);
    assertTrue(box);
    assertTrue(d.furniture.stories.toString(), d.furniture.stories.containsKey("header"));
    assertTrue(d.furniture.stories.toString(), d.furniture.stories.containsKey("firstFooter"));
    assertFalse(warnings, warnings.contains("not imported"));
  }

  @Test
  public void hostileWord2003SidePartsAreBounded() throws Exception {
    String xml = "<w:wordDocument xmlns:w=\"" + W2003 + "\" xmlns:v=\"urn:schemas-microsoft-com:vml\""
        + " xmlns:aml=\"http://schemas.microsoft.com/aml/2001/core\"><w:lists><w:list w:ilfo=\"&quot;x\"/></w:lists><w:body>"
        + "<w:p><w:r><w:pict><w:binData w:name=\"wordml://../../evil.\">!!notbase64</w:binData>"
        + "<v:shape style=\"width:-5pt;height:1e30in\"><v:imagedata src=\"file:///etc/passwd\"/></v:shape>"
        + "<v:oval/></w:pict></w:r>"
        + "<w:hlink w:dest=\"&quot;&gt;\" w:bookmark=\"b\"><w:r><w:t>x</w:t></w:r></w:hlink>"
        + "<aml:annotation aml:id=\"&quot;\" w:type=\"Word.Comment\"/>"
        + "<w:footnote><w:footnote><w:p/></w:footnote></w:footnote></w:p>"
        + "<w:sectPr><w:hdr w:type=\"weird\"/><w:hdr/><w:hdr/></w:sectPr></w:body></w:wordDocument>";
    try {
      var o = open(xml);
      assertNotNull(o.document);
    } catch (IOException | RuntimeException expected) {
      // rejected cleanly is acceptable too
    }
    var parts = WordXml.parts(xml.getBytes("UTF-8"), new java.util.HashSet<String>());
    for (String name : parts.keySet()) assertFalse(name, name.contains(".."));
    long[] s = WordXml.size("width:-5pt;height:1e30in");
    assertEquals(100 * 12700L, s[0]);
    assertEquals(20000 * 12700L, s[1]);
  }

  @Test
  public void rubyRoundTripsThroughDocx() throws Exception {
        var style = TextStyle.DEFAULT.with("ruby", "kan");
    var text = RichText.of("A", TextStyle.DEFAULT).concat(RichText.of("\u6f22", style)).concat(RichText.of("B", TextStyle.DEFAULT));
    var d = new Document(new Sequence<Block>().append(new Paragraph("p1", text)), new PageSpec(240, 300, 20, 20, 20, 20), "R", 2);
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    var p = back.document.paragraph(0);
    assertEquals("A\u6f22B", p.text.toString());
    assertEquals("kan", p.text.styleAt(1).characters.ruby);
    assertEquals("", p.text.styleAt(0).characters.ruby);
    assertEquals("", p.text.styleAt(2).characters.ruby);
    assertFalse(back.warnings.toString(), back.warnings.toString().contains("ruby"));
  }

  @Test
  public void hostileWordXmlIsRejectedCleanly() {
    String[] bad = {
      "<pkg:package xmlns:pkg=\"p\"><pkg:part pkg:name=\"/../evil\"><pkg:binaryData>AA==</pkg:binaryData></pkg:part></pkg:package>",
      "<pkg:package xmlns:pkg=\"p\"></pkg:package>",
      "<pkg:package xmlns:pkg=\"p\"><pkg:part pkg:name=\"/a\"><pkg:binaryData>AA==</pkg:binaryData></pkg:part>"
          + "<pkg:part pkg:name=\"/a\"><pkg:binaryData>AA==</pkg:binaryData></pkg:part></pkg:package>",
      "<w:wordDocument xmlns:w=\"" + W2003 + "\"></w:wordDocument>",
      "<html><body/></html>",
      "<w:wordDocument xmlns:w=\"" + W2003 + "\"><w:body><w:p>",
      "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><w:wordDocument xmlns:w=\"" + W2003
          + "\"><w:body><w:p><w:r><w:t>&e;</w:t></w:r></w:p></w:body></w:wordDocument>",
    };
    for (String s : bad) {
      try {
        var o = open(s);
        assertNotNull(o.document);
      } catch (IOException | RuntimeException expected) {
        // rejected
      }
    }
    var deep = new StringBuilder("<w:wordDocument xmlns:w=\"" + W2003 + "\"><w:body>");
    for (int i = 0; i < 5000; i++) deep.append("<wx:sub-section>");
    try {
      open(deep.toString());
      fail("deep nesting accepted");
    } catch (IOException | RuntimeException expected) {
      // bounded
    }
  }

  /** As Wordcraft's vml_spin: VML rotation (degrees or fd) and flips, and a line's direction. */
  @Test
  public void word2003VmlRotationAndFlips() throws Exception {
    String xml = "<?xml version=\"1.0\"?><w:wordDocument xmlns:w=\"" + W2003 + "\""
        + " xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>"
        + "<w:p><w:r><w:pict><w:binData w:name=\"wordml://1.png\">" + PNG + "</w:binData>"
        + "<v:shape style=\"width:36pt;height:36pt;rotation:30;flip:x\"><v:imagedata src=\"wordml://1.png\"/></v:shape>"
        + "</w:pict></w:r></w:p>"
        + "<w:p><w:r><w:pict><v:rect style=\"width:50pt;height:30pt;rotation:5898240fd;flip:y\" fillcolor=\"red\"/>"
        + "</w:pict></w:r></w:p>"
        + "<w:p><w:r><w:pict><v:line from=\"100pt,0\" to=\"0,50pt\"/></w:pict></w:r></w:p>"
        + "</w:body></w:wordDocument>";
    var opened = open(xml);
    var pics = new java.util.ArrayList<Picture>();
    for (Block b : opened.document.blocks) if (b instanceof Picture pic) pics.add(pic);
    assertEquals(opened.warnings.toString(), 3, pics.size());
    assertEquals(30, pics.get(0).look.rotation);
    assertTrue(pics.get(0).look.flipH);
    Drawing rect = pics.get(1).drawing;
    assertEquals(90, rect.num("rotation", 0), .01f);
    assertTrue(rect.flag("flipV"));
    // The 50 x 30 rectangle turned a quarter: its outline spans 30 wide and 50 tall about the centre.
    var ops = DrawingOps.build(rect, 50, 30);
    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
    for (var p : ops.paths) for (int k = 0; k < p.points().length; k += 2) {
      minX = Math.min(minX, p.points()[k]);
      maxX = Math.max(maxX, p.points()[k]);
    }
    assertEquals(30, maxX - minX, 1f);
    // Drawn from top right to bottom left: flipped horizontally.
    Drawing line = pics.get(2).drawing;
    assertTrue(line.flag("flipH"));
    assertFalse(line.flag("flipV"));
    var lp = DrawingOps.build(line, 100, 50).paths.get(0).points();
    assertEquals(100, lp[0], .5f);
  }

  @Test
  public void word2003VmlFloatsAndShapes() throws Exception {
    String xml = "<?xml version=\"1.0\"?><w:wordDocument xmlns:w=\"" + W2003 + "\""
        + " xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w10=\"urn:schemas-microsoft-com:office:word\"><w:body>"
        + "<w:p><w:r><w:pict><w:binData w:name=\"wordml://1.png\">" + PNG + "</w:binData>"
        + "<v:shape style=\"position:absolute;margin-left:100pt;margin-top:20pt;width:36pt;height:36pt;"
        + "mso-position-horizontal-relative:page;mso-position-vertical-relative:margin\">"
        + "<v:imagedata src=\"wordml://1.png\"/><w10:wrap type=\"square\"/></v:shape></w:pict></w:r></w:p>"
        + "<w:p><w:r><w:pict><v:rect style=\"position:absolute;width:50pt;height:30pt;z-index:-1;"
        + "mso-position-horizontal:center;mso-position-horizontal-relative:margin\" fillcolor=\"#f00\" stroked=\"f\"/>"
        + "</w:pict></w:r></w:p>"
        + "<w:p><w:r><w:pict><v:oval style=\"width:40pt;height:20pt\" fillcolor=\"blue\" strokecolor=\"#00ff00 [3]\"/>"
        + "</w:pict></w:r></w:p>"
        + "<w:p><w:r><w:pict><v:line style=\"position:absolute;mso-position-horizontal-relative:page;"
        + "mso-position-vertical-relative:page\" from=\"200pt,300pt\" to=\"100pt,350pt\" strokeweight=\"2pt\">"
        + "<w10:wrap type=\"topAndBottom\"/></v:line></w:pict></w:r></w:p>"
        + "</w:body></w:wordDocument>";
    var opened = open(xml);
    var pics = new java.util.ArrayList<Picture>();
    for (Block b : opened.document.blocks) if (b instanceof Picture pic) pics.add(pic);
    assertEquals(opened.warnings.toString(), 4, pics.size());
    assertEquals("square", pics.get(0).wrap());
    assertEquals("page,,100;margin,,20", pics.get(0).position());
    assertEquals("behind", pics.get(1).wrap());
    assertEquals("margin,center,0;paragraph,,0", pics.get(1).position());
    assertEquals("rect", pics.get(1).drawing.get("shape", ""));
    assertEquals("FF0000", pics.get(1).drawing.get("fill", ""));
    assertEquals("none", pics.get(1).drawing.get("line", ""));
    assertEquals("inline", pics.get(2).wrap());
    assertEquals("ellipse", pics.get(2).drawing.get("shape", ""));
    assertEquals("0000FF", pics.get(2).drawing.get("fill", ""));
    assertEquals("00FF00", pics.get(2).drawing.get("line", ""));
    // A line spans from..to: its box starts at the smaller corner.
    assertEquals("topBottom", pics.get(3).wrap());
    assertEquals("page,,100;page,,300", pics.get(3).position());
    assertEquals(100, pics.get(3).width, .01f);
    assertEquals(50, pics.get(3).height, .01f);
    assertEquals("line", pics.get(3).drawing.get("shape", ""));
    assertEquals(2, pics.get(3).drawing.num("lineWidth", 0), .01f);
  }
}
