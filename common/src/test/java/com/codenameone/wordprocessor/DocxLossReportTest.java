package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

/** DOCX constructs the model cannot keep must be reported, never dropped silently. */
public class DocxLossReportTest {
  private static Docx.Opened read(String body) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var z = new ZipOutputStream(bytes);
    z.putNextEntry(new ZipEntry("word/document.xml"));
    z.write(
        ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body
                + "</w:body></w:document>")
            .getBytes("UTF-8"));
    z.finish();
    return Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
  }

  private static void reported(String body, String warning, String text) throws IOException {
    var opened = read(body);
    assertTrue(opened.warnings.toString(), opened.warnings.toString().contains(warning));
    assertEquals(text, opened.document.plainText());
    // An unedited document saves its original bytes; an edited one must still report the loss.
    assertTrue(Docx.conversionWarnings(opened.document, opened).isEmpty());
    var s = new Session(opened.document);
    s.replace("!");
    assertTrue(Docx.conversionWarnings(s.document(), opened).toString().contains(warning));
  }

  @Test
  public void unmodeledConstructsAreReported() throws IOException {
    reported(
        "<w:p><w:r><w:ruby><w:rubyPr/><w:rt><w:r><w:t>kan</w:t></w:r></w:rt>"
            + "<w:rubyBase><w:r><w:t>K</w:t></w:r></w:rubyBase></w:ruby></w:r></w:p>",
        "Phonetic guide",
        "K");
    reported(
        "<w:p><w:fldSimple w:instr=\"EQ \\o\\ac(O,1)\"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>",
        "Equation field",
        "1");
    reported(
        "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> eq \\o(1,O)</w:instrText></w:r>"
            + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1</w:t></w:r>"
            + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>",
        "Equation field",
        "1");
    reported(
        "<w:p><w:r><w:rPr><w:eastAsianLayout w:combine=\"1\"/></w:rPr><w:t>AB</w:t></w:r></w:p>",
        "eastAsianLayout",
        "AB");
  }

  @Test
  public void modelFeaturesDocxCannotWriteRequireConversion() {
    var s = new Session(Document.empty());
    s.replace("Kanji circled");
    assertTrue(Docx.conversionWarnings(s.document(), null).isEmpty());
    s.select(0, 0, 5);
    s.format("ruby", "kan");
    s.select(0, 6, 13);
    s.format("enclose", "circle");
    var warnings = Docx.conversionWarnings(s.document(), null).toString();
    assertTrue(warnings, warnings.contains("Phonetic guides"));
    assertTrue(warnings, warnings.contains("Enclosed characters"));
  }
}
