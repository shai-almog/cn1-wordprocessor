package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

/** Citation managers' ADDIN fields are range fields spanning paragraphs, as in Wordcraft. */
public class RangeFieldsTest {
  private static Docx.Opened open(String body) throws Exception {
    var out = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(out);
    zip.putNextEntry(new ZipEntry("word/document.xml"));
    zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body + "</w:body></w:document>").getBytes("UTF-8"));
    zip.closeEntry();
    zip.close();
    return Docx.read(new ByteArrayInputStream(out.toByteArray()));
  }

  private static int marks(Document d, boolean start) {
    int[] n = {0};
    for (Block b : d.blocks)
      if (b instanceof Paragraph p)
        p.text.visit(
            new RichText.Visitor() {
              public void run(int offset, String text, TextStyle style) {}

              public void field(int offset, InlineField f, TextStyle style) {
                if (start ? f.rangeStart() : f.rangeEnd()) n[0]++;
              }
            });
    return n[0];
  }

  private static final String REFLIST =
      "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
          + "<w:r><w:instrText xml:space=\"preserve\"> ADDIN EN.REFLIST </w:instrText></w:r>"
          + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1. Doe, A.</w:t></w:r></w:p>"
          + "<w:p><w:r><w:t>2. Roe, B.</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>";

  @Test
  public void addinBibliographySpanningParagraphsIsOneRangeField() throws Exception {
    var opened = open(REFLIST);
    var d = opened.document;
    assertEquals(1, marks(d, true));
    assertEquals(1, marks(d, false));
    assertEquals("1. Doe, A.\n2. Roe, B.", d.plainText().trim());
    for (String w : opened.warnings) assertFalse(w, w.contains("complex field"));
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    assertEquals(1, marks(back, true));
    assertEquals(1, marks(back, false));
  }

  @Test
  public void singleParagraphAddinFieldOpensAsARange() throws Exception {
    var d = open("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
        + "<w:r><w:instrText> ADDIN EN.CITE x</w:instrText></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>(Doe)</w:t></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>").document;
    d = new Session(d).document();
    assertEquals(1, marks(d, true));
    assertTrue(d.plainText().contains("(Doe)"));
  }

  @Test
  public void strayEndMarkIsDropped() throws Exception {
    var d = open("<w:p><w:r><w:t>a</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>").document;
    assertEquals(0, marks(d, false));
    assertFalse(InlineField.rangeInstruction("PAGE"));
    assertTrue(InlineField.rangeInstruction(" addin x"));
  }
}
