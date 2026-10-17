package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

public class BookmarkFormatTest {
  @Test
  public void rangesCommentsAndAtomicFieldsRoundTripTogether() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("alpha beta\nnext");
    s.select(0, 6, 10);
    s.addBookmark("Spot");
    s.addComment("Ada", "Shared range");
    s.selectRange(0, 1, 1, 2);
    s.addBookmark("Across");
    s.select(1, 2, 2);
    s.addBookmark("Point");
    s.select(1, 4, 4);
    s.insertField(new InlineField("PAGE", "1", false, false));
    s.select(1, 4, 5);
    s.addBookmark("Field");
    String xml = DocumentXml.write(s.document());
    assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(s.document(), bytes);
    Docx.Opened reopened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(reopened.warnings.toString(), reopened.warnings.isEmpty());
    assertEquals(4, reopened.document.bookmarks.size());
    for (String name : s.document().bookmarks.keySet())
      assertEquals(
          s.document().bookmarks.get(name).text(s.document()),
          reopened.document.bookmarks.get(name).text(reopened.document));
    assertEquals("Shared range", reopened.document.comments.get(0).text);
    assertEquals("1", reopened.document.bookmarks.get("Field").text(reopened.document));
  }

  @Test
  public void externalMarkersUseAtomicFieldOffsetsAndPreservePointRanges() throws Exception {
    Docx.Opened opened =
        read(
            "<w:p><w:r><w:t>X</w:t></w:r><w:bookmarkStart w:id=\"7\""
                + " w:name=\"Target\"/><w:fldSimple"
                + " w:instr=\"PAGE\"><w:r><w:t>123</w:t></w:r></w:fldSimple><w:bookmarkEnd"
                + " w:id=\"7\"/><w:bookmarkStart w:id=\"8\" w:name=\"Point\"/><w:bookmarkEnd"
                + " w:id=\"8\"/></w:p>");
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    Bookmark m = opened.document.bookmarks.get("Target");
    assertEquals(1, m.start);
    assertEquals(2, m.end);
    assertEquals("123", m.text(opened.document));
    assertEquals(2, opened.document.bookmarks.get("Point").start);
  }

  @Test
  public void malformedAndFieldResultBookmarksReportConversionWarnings() throws Exception {
    Docx.Opened broken =
        read(
            "<w:p><w:bookmarkStart w:id=\"1\""
                + " w:name=\"MissingEnd\"/><w:r><w:t>A</w:t></w:r></w:p>");
    assertFalse(broken.warnings.isEmpty());
    assertTrue(broken.document.bookmarks.isEmpty());
    Docx.Opened inside =
        read(
            "<w:p><w:fldSimple w:instr=\"PAGE\"><w:bookmarkStart w:id=\"1\""
                + " w:name=\"Inside\"/><w:r><w:t>1</w:t></w:r><w:bookmarkEnd"
                + " w:id=\"1\"/></w:fldSimple></w:p>");
    assertFalse(inside.warnings.isEmpty());
    assertTrue(inside.document.bookmarks.isEmpty());
  }

  @Test
  public void nativeReaderRejectsInvalidBookmarkRange() throws Exception {
    String xml =
        DocumentXml.write(Document.empty())
            .replace(
                "</document>",
                "<bookmark name=\"Bad\" startId=\"p1\" endId=\"p1\" start=\"0\""
                    + " end=\"10\"/></document>");
    try {
      DocumentXml.read(xml);
      fail();
    } catch (IOException expected) {
    }
  }

  private Docx.Opened read(String body) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ZipOutputStream zip = new ZipOutputStream(out);
    zip.putNextEntry(new ZipEntry("word/document.xml"));
    zip.write(
        ("<w:document"
             + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body
                + "</w:body></w:document>")
            .getBytes("UTF-8"));
    zip.closeEntry();
    zip.close();
    return Docx.read(new ByteArrayInputStream(out.toByteArray()));
  }
}
