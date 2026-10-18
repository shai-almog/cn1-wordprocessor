package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

public class FormatTest {
  private Session sample() {
    var s = new Session(Document.empty());
    s.replace(" Hello & <world> 😀 שלום \nSecond paragraph");
    s.select(0, 1, 6);
    s.format("bold", "true");
    int end = s.document().paragraph(0).text.length();
    s.select(0, end, end);
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "Cell & text");
    return s;
  }

  @Test
  public void simpleListsRoundTripWithIndentAndNumberingRelationships() throws Exception {
    var s = new Session(Document.empty());
    s.replace("One\nTwo\nBody\nRestart\nBullet");
    s.selectRange(0, 0, 1, 3);
    s.paragraph("list", "number");
    s.paragraph("indent", "18");
    s.select(3, 0, 0);
    s.paragraph("list", "number");
    s.select(4, 0, 0);
    s.paragraph("list", "bullet");
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals(s.document().plainText(), opened.document.plainText());
    for (int i = 0; i < 5; i++) {
      assertEquals(s.document().paragraph(i).list, opened.document.paragraph(i).list);
      assertEquals(s.document().paragraph(i).indent, opened.document.paragraph(i).indent, 0.01);
    }
    var parts = new java.util.HashMap<String, String>();
    var zin = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    ZipEntry entry;
    while ((entry = zin.getNextEntry()) != null) {
      var bytes = new ByteArrayOutputStream();
      int c;
      while ((c = zin.read()) != -1) bytes.write(c);
      parts.put(entry.getName(), bytes.toString("UTF-8"));
    }
    assertTrue(parts.get("word/_rels/document.xml.rels").contains("Target=\"numbering.xml\""));
    assertTrue(parts.get("[Content_Types].xml").contains("/word/numbering.xml"));
    assertEquals(3, Xml.children(Xml.parse(parts.get("word/numbering.xml")), "num").size());
    var edited = new Session(opened.document);
    edited.replace("Edited ");
    out.reset();
    Docx.write(edited.document(), opened, out, false);
    assertEquals(
        edited.document().plainText(),
        Docx.read(new ByteArrayInputStream(out.toByteArray())).document.plainText());
  }

  @Test
  public void xmlFastPathsPreserveEntitiesWhitespaceAndValidation() throws Exception {
    assertEquals("text", Xml.text(Xml.parse("<étiquette_2 属性=\"ok\">text</étiquette_2>")));
    var text = "plain\ttext\nwith whitespace";
    assertEquals(text, Xml.text(Xml.parse("<a>" + text + "</a>")));
    assertEquals(
        "before & middle < after 😀",
        Xml.text(Xml.parse("<a>before &amp; middle &lt; after &#x1f600;</a>")));
    assertEquals("beforeinsideafter", Xml.text(Xml.parse("<a>before<b>inside</b>after</a>")));
    assertEquals("", Xml.attr(Xml.parse("<a value=\"\"/>"), "value", "missing"));
    for (String xml :
        new String[] {
          "<a value=\"\" value=\"second\"/>",
          "<a>before\u0001after</a>",
          "<a>&amp;after\u0001</a>",
          "<a>&unknown;</a>"
        }) {
      try {
        Xml.parse(xml);
        fail(xml);
      } catch (IOException expected) {
      }
    }
  }

  @Test
  public void nativeRoundTripIsLossless() throws Exception {
    var d = sample().document();
    String xml = DocumentXml.write(d);
    Document next = DocumentXml.read(xml);
    assertEquals(xml, DocumentXml.write(next));
    assertEquals(d.plainText(), next.plainText());
    assertTrue(next.paragraph(0).text.styleAt(2).bold);
  }

  @Test
  public void docxRoundTripPreservesTextAndFormatting() throws Exception {
    var session = sample();
    session.select(0, 1, 6);
    for (String[] property :
        new String[][] {
          {"font", "Arial"},
          {"size", "15.5"},
          {"color", "123456"},
          {"background", "ffeedd"},
          {"italic", "true"},
          {"underline", "true"},
          {"strike", "true"}
        }) session.format(property[0], property[1]);
    var d = session.document();
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened next = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(d.plainText(), next.document.plainText());
    assertTrue(next.document.paragraph(0).text.styleAt(2).bold);
    assertEquals(d.paragraph(0).text.styleAt(2), next.document.paragraph(0).text.styleAt(2));
    assertEquals(d.paragraph(0).text.styleAt(0), next.document.paragraph(0).text.styleAt(0));
    assertEquals(d.page.width, next.document.page.width, 0.1);
  }

  @Test
  public void unknownPartsPreservedAndEditingRequiresAcknowledgement() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var z = new ZipOutputStream(bytes);
    entry(
        z,
        "word/document.xml",
        """
        <w:document\
         xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Hello</w:t><w:drawing/></w:r></w:p></w:body></w:document>""");
    entry(z, "word/media/example.bin", "opaque data");
    z.finish();
    Docx.Opened source = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertFalse(source.warnings.isEmpty());
    var unchanged = new ByteArrayOutputStream();
    Docx.write(source.document, source, unchanged, false);
    assertTrue(
        new String(unchanged.toByteArray(), "ISO-8859-1").contains("word/media/example.bin"));
    var s = new Session(source.document);
    s.replace("Changed");
    try {
      Docx.write(s.document(), source, new ByteArrayOutputStream(), false);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("conversion"));
    }
  }

  @Test
  public void declarationsRejected() throws Exception {
    try {
      DocumentXml.read("<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><document/>");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("DTD"));
    }
  }

  @Test
  public void versionAndDuplicateIdsRejected() throws Exception {
    try {
      DocumentXml.read("<document version=\"7\"/>");
      fail();
    } catch (IOException expected) {
    }
    String xml = DocumentXml.write(sample().document());
    xml = xml.replace("id=\"p2\"", "id=\"p1\"");
    try {
      DocumentXml.read(xml);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void nativeProjectRetainsOriginalPackageAndWarnings() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var z = new ZipOutputStream(bytes);
    entry(
        z,
        "word/document.xml",
        """
        <w:document\
         xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Hello</w:t><w:drawing/></w:r></w:p></w:body></w:document>""");
    entry(z, "word/media/keep.bin", "retained bytes");
    z.finish();
    Docx.Opened original = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    var s = new Session(original.document);
    s.replace("Edited");
    ProjectFile.Opened restored = ProjectFile.read(ProjectFile.write(s.document(), original));
    assertEquals(s.document().plainText(), restored.document.plainText());
    assertNotNull(restored.source);
    assertFalse(restored.source.warnings.isEmpty());
    assertEquals("Hello", restored.source.document.plainText());
    try {
      Docx.write(restored.document, restored.source, new ByteArrayOutputStream(), false);
      fail();
    } catch (IOException expected) {
    }
    var preserved = new ByteArrayOutputStream();
    Docx.write(restored.source.document, restored.source, preserved, false);
    var zin = new ZipInputStream(new ByteArrayInputStream(preserved.toByteArray()));
    boolean found = false;
    ZipEntry ze;
    while ((ze = zin.getNextEntry()) != null)
      if (ze.getName().equals("word/media/keep.bin")) {
        var data = new ByteArrayOutputStream();
        int c;
        while ((c = zin.read()) != -1) data.write(c);
        assertEquals("retained bytes", data.toString("UTF-8"));
        found = true;
      }
    assertTrue(found);
  }

  @Test
  public void newUnsupportedFeaturesRequireExplicitConversion() throws Exception {
    var s = new Session(Document.empty());
    s.replace("link");
    s.select(0, 0, 4);
    s.paragraph("list", "unsupported-custom-list");
    assertFalse(Docx.conversionWarnings(s.document(), null).isEmpty());
    try {
      Docx.write(s.document(), new ByteArrayOutputStream());
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void strictXmlRejectsMalformedDocuments() throws Exception {
    for (String xml :
        new String[] {"<a><b></a>", "<a x=\"1\" x=\"2\"/>", "<a>&unknown;</a>", "<a/><b/>"}) {
      try {
        Xml.parse(xml);
        fail(xml);
      } catch (IOException expected) {
      }
    }
    assertEquals("  \n\t😀 ", Xml.text(Xml.parse("<a>  \n\t&#x1f600; </a>")));
  }

  private void entry(ZipOutputStream z, String path, String value) throws IOException {
    z.putNextEntry(new ZipEntry(path));
    z.write(value.getBytes("UTF-8"));
    z.closeEntry();
  }
}
