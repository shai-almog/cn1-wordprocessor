package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

public class FieldFormatTest {
  @Test
  public void nativeAndDocxRoundTripFieldInstructionResultStyleAndComments() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Page ");
    s.insertField(new InlineField("PAGE \\* ROMAN", "III", true, false));
    s.select(0, 5, 6);
    s.format("bold", "true");
    s.addComment("Reviewer", "Keep page number");
    String xml = DocumentXml.write(s.document());
    assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    var text = opened.document.paragraph(0).text;
    assertEquals(6, text.length());
    assertEquals("PAGE \\* ROMAN", text.fieldAt(5).instruction);
    assertEquals("III", text.fieldAt(5).result);
    assertTrue(text.fieldAt(5).locked);
    assertTrue(text.styleAt(5).bold);
    assertEquals(5, opened.document.comments.get(0).start);
    assertEquals(6, opened.document.comments.get(0).end);
    assertTrue(new String(PdfExporter.write(s.document()), "ISO-8859-1").contains("III"));
  }

  @Test
  public void complexFieldsBecomeAtomicWithCachedResultAndUnknownCodePreserved() throws Exception {
    var bytes = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(bytes);
    zip.putNextEntry(new ZipEntry("word/document.xml"));
    zip.write(
        ("""
             <w:document\
              xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>A</w:t></w:r><w:r><w:fldChar\
              w:fldCharType="begin" w:fldLock="true"/></w:r><w:r><w:instrText> CUSTOM\
              </w:instrText></w:r><w:r><w:instrText>Arg </w:instrText></w:r><w:r><w:fldChar\
              w:fldCharType="separate"/></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>Cached</w:t></w:r><w:r><w:fldChar\
              w:fldCharType="end"/></w:r><w:r><w:t>Z</w:t></w:r></w:p></w:body></w:document>""")
            .getBytes("UTF-8"));
    zip.closeEntry();
    zip.close();
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    var t = opened.document.paragraph(0).text;
    assertEquals("A\ufffcZ", t.toString());
    assertEquals("ACachedZ", t.plainText());
    assertEquals("CUSTOM Arg", t.fieldAt(1).instruction);
    assertTrue(t.fieldAt(1).locked);
    assertTrue(t.styleAt(1).bold);
  }

  @org.junit.Test
  public void captionsRetainNumberingAfterNativeAndDocxReopen() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Object");
    s.insertCaption("Figure", "First", false, "ROMAN", false, 0, "-");
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(s.document(), out);
    for (Document document :
        new Document[] {
          DocumentXml.read(DocumentXml.write(s.document())),
          Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document
        }) {
      var opened = new Session(document);
      opened.select(1, 0, 0);
      opened.insertCaption("Figure", "New", true, "ROMAN", false, 0, "-");
      org.junit.Assert.assertEquals(
          "Object\nFigure I: New\nFigure II: First", opened.document().plainText());
      org.junit.Assert.assertEquals(
          "SEQ Figure \\* ROMAN", opened.document().paragraph(2).text.fieldAt(7).instruction);
    }
  }
}
