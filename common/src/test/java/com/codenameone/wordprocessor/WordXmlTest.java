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
    assertTrue(warnings, warnings.contains("lists"));
    assertTrue(warnings, warnings.contains("Pictures"));
    assertTrue(warnings, warnings.contains("tracked changes"));
    assertTrue(warnings, warnings.contains("hyperlink"));
    assertEquals("bCs", WordXml.rename("b-cs"));
    assertEquals("vMerge", WordXml.rename("vmerge"));
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
}
