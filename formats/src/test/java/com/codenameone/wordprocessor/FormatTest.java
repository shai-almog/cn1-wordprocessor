package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

public class FormatTest {
  private Session sample() {
    Session s = new Session(Document.empty());
    s.replace(" Hello & <world> 😀 שלום \nSecond paragraph");
    s.select(0, 1, 6);
    s.format("bold", "true");
    s.insertTable(2, 2);
    s.setCell(1, 0, 0, "Cell & text");
    return s;
  }

  @Test
  public void nativeRoundTripIsLossless() throws Exception {
    Document d = sample().document();
    String xml = DocumentXml.write(d);
    Document next = DocumentXml.read(xml);
    assertEquals(xml, DocumentXml.write(next));
    assertEquals(d.plainText(), next.plainText());
    assertTrue(next.paragraph(0).text.styleAt(2).bold);
  }

  @Test
  public void docxRoundTripPreservesTextAndFormatting() throws Exception {
    Document d = sample().document();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened next = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(d.plainText(), next.document.plainText());
    assertTrue(next.document.paragraph(0).text.styleAt(2).bold);
    assertEquals(d.page.width, next.document.page.width, 0.1);
  }

  @Test
  public void unknownPartsPreservedAndEditingRequiresAcknowledgement() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ZipOutputStream z = new ZipOutputStream(bytes);
    entry(
        z,
        "word/document.xml",
        "<w:document"
            + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Hello</w:t><w:drawing/></w:r></w:p></w:body></w:document>");
    entry(z, "word/media/example.bin", "opaque data");
    z.finish();
    Docx.Opened source = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertFalse(source.warnings.isEmpty());
    ByteArrayOutputStream unchanged = new ByteArrayOutputStream();
    Docx.write(source.document, source, unchanged, false);
    assertTrue(
        new String(unchanged.toByteArray(), "ISO-8859-1").contains("word/media/example.bin"));
    Session s = new Session(source.document);
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
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ZipOutputStream z = new ZipOutputStream(bytes);
    entry(
        z,
        "word/document.xml",
        "<w:document"
            + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Hello</w:t><w:drawing/></w:r></w:p></w:body></w:document>");
    entry(z, "word/media/keep.bin", "retained bytes");
    z.finish();
    Docx.Opened original = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    Session s = new Session(original.document);
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
    ByteArrayOutputStream preserved = new ByteArrayOutputStream();
    Docx.write(restored.source.document, restored.source, preserved, false);
    ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(preserved.toByteArray()));
    boolean found = false;
    ZipEntry ze;
    while ((ze = zin.getNextEntry()) != null)
      if (ze.getName().equals("word/media/keep.bin")) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int c;
        while ((c = zin.read()) != -1) data.write(c);
        assertEquals("retained bytes", data.toString("UTF-8"));
        found = true;
      }
    assertTrue(found);
  }

  @Test
  public void newUnsupportedFeaturesRequireExplicitConversion() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("link");
    s.select(0, 0, 4);
    s.format("link", "https://example.com");
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
