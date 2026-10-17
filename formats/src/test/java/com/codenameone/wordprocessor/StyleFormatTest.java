package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class StyleFormatTest {
  @Test
  public void customDefinitionAndDirectOverridesRoundTripAndRemainEditable() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Styled words");
    ParagraphStyle custom =
        new ParagraphStyle(
            "Custom",
            "Custom & readable",
            s.document().paragraph(0).with("before", "15").with("firstIndent", "12"),
            TextStyle.DEFAULT.with("size", "19").with("color", "123456"));
    s.defineStyle(custom);
    s.applyStyle("Custom");
    s.select(0, 7, 12);
    s.format("italic", "true");
    Document nativeCopy = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals(DocumentXml.write(s.document()), DocumentXml.write(nativeCopy));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    ParagraphStyle read = opened.document.styles.get("Custom");
    assertNotNull(read);
    assertEquals(custom.name, read.name);
    assertEquals(custom.text, read.text);
    assertEquals(15, read.paragraph.before, .001);
    assertEquals(12, read.paragraph.format.firstIndent, .001);
    assertTrue(opened.document.paragraph(0).text.styleAt(8).italic);
    Session edited = new Session(opened.document);
    edited.defineStyle(
        new ParagraphStyle(read.id, read.name, read.paragraph, read.text.with("size", "28")));
    assertEquals(28, edited.document().paragraph(0).text.styleAt(0).size, 0);
    assertTrue(edited.document().paragraph(0).text.styleAt(8).italic);
    out.reset();
    Docx.write(edited.document(), opened, out, false);
    assertEquals(
        28,
        Docx.read(new ByteArrayInputStream(out.toByteArray()))
            .document
            .styles
            .get("Custom")
            .text
            .size,
        0);
  }
}
