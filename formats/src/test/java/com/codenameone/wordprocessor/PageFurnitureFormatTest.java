package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class PageFurnitureFormatTest {
  @Test
  public void preservedSettingsXmlEscapesAttributesAndKeepsText() throws Exception {
    String input =
        "<w:settings xmlns:w=\"test\"><w:item value=\"a&amp;b&apos;c\"> x &amp; y"
            + " </w:item></w:settings>";
    com.codename1.xml.Element result = Xml.parse(Xml.write(Xml.parse(input)));
    assertEquals("a&b'c", Xml.attr(Xml.child(result, "item"), "value", ""));
    assertEquals(" x & y ", Xml.text(Xml.child(result, "item")));
  }

  @Test
  public void nativeAndDocxPreserveVariantsFieldsFormattingAndScopedLinks() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Body link");
    s.select(0, 0, 4);
    s.format("link", "https://example.com/body");
    PageFurniture f = PageFurniture.EMPTY.options(true, true, 20, 22);
    for (String key : PageFurniture.KEYS) {
      RichText text =
          RichText.of(
                  key,
                  TextStyle.DEFAULT.with("bold", "true").with("link", "https://example.com/" + key))
              .concat(
                  RichText.field(new InlineField("PAGE", "3", false, false), TextStyle.DEFAULT));
      f =
          f.story(
              key,
              new Sequence<Paragraph>().append(new Paragraph(key, text).with("align", "center")));
    }
    s.furniture(f);
    Document d = s.document();
    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    PageFurniture read = opened.document.furniture;
    assertTrue(read.differentFirst);
    assertTrue(read.differentEven);
    assertEquals(20, read.headerDistance, .01);
    assertEquals(22, read.footerDistance, .01);
    for (String key : PageFurniture.KEYS) {
      Paragraph para = read.get(key).get(0);
      assertEquals(key + "3", para.text.plainText());
      assertEquals("PAGE", para.text.fieldAt(key.length()).instruction);
      assertTrue(para.text.styleAt(0).bold);
      assertEquals("https://example.com/" + key, para.text.styleAt(0).link);
      assertEquals("center", para.align);
    }
    assertEquals("https://example.com/body", opened.document.paragraph(0).text.styleAt(0).link);
    out.reset();
    Docx.write(opened.document.title("Changed"), opened, out, true);
    Docx.Opened again = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(again.warnings.toString(), again.warnings.isEmpty());
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("firstHeader"));
    assertTrue(pdf.contains("firstFooter"));
  }
}
