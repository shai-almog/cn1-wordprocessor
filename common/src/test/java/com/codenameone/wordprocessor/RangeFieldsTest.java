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

  private static String repeat(String s, int n) {
    var b = new StringBuilder();
    for (int i = 0; i < n; i++) b.append(s);
    return b.toString();
  }

  @Test
  public void largeEndNoteCitationAndOversizedFieldsDoNotAbortTheRead() throws Exception {
    String xml = "&lt;EndNote&gt;&lt;Cite&gt;" + repeat("&lt;Author&gt;Doe&lt;/Author&gt;", 1500) + "&lt;/Cite&gt;&lt;/EndNote&gt;";
    var opened = open("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
        + "<w:r><w:instrText xml:space=\"preserve\"> ADDIN EN.CITE " + xml + "</w:instrText></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>(Doe)</w:t></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
        + "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
        + "<w:r><w:instrText>QUOTE " + repeat("x", 9000) + "</w:instrText></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>big</w:t></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
        + "<w:p><w:fldSimple w:instr=\"QUOTE " + repeat("y", 9000) + "\"><w:r><w:t>simple</w:t></w:r></w:fldSimple></w:p>");
    var d = new Session(opened.document).document();
    assertEquals(1, marks(d, true));
    String text = d.plainText();
    assertTrue(text, text.contains("(Doe)") && text.contains("big") && text.contains("simple"));
    assertTrue(InlineField.valid("ADDIN EN.CITE " + repeat("z", 100000), ""));
    assertFalse(InlineField.valid("ADDIN EN.CITE " + repeat("z", InlineField.MAX_ADDIN_INSTRUCTION), ""));
  }

  private static java.util.List<InlineField> fields(Document d) {
    var out = new java.util.ArrayList<InlineField>();
    for (Block b : d.blocks)
      if (b instanceof Paragraph p)
        p.text.visit(
            new RichText.Visitor() {
              public void run(int offset, String text, TextStyle style) {}

              public void field(int offset, InlineField f, TextStyle style) {
                out.add(f);
              }
            });
    return out;
  }

  @Test
  public void overLimitInstructionsAreTruncatedAndTheFieldKept() throws Exception {
    var opened = open("<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
        + "<w:r><w:instrText>QUOTE " + repeat("x", 9000) + "</w:instrText></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>big</w:t></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
        + "<w:p><w:fldSimple w:instr=\"QUOTE " + repeat("y", 9000) + "\"><w:r><w:t>simple</w:t></w:r></w:fldSimple></w:p>"
        + "<w:p><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
        + "<w:r><w:instrText xml:space=\"preserve\"> ADDIN EN.REFLIST " + repeat("z", InlineField.MAX_ADDIN_INSTRUCTION)
        + "</w:instrText></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>1. Doe, A.</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>2. Roe, B.</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>");
    var d = new Session(opened.document).document();
    var f = fields(d);
    assertEquals(f.toString(), 4, f.size());
    assertEquals("QUOTE xxx", f.get(0).instruction.substring(0, 9));
    assertEquals(InlineField.MAX_INSTRUCTION, f.get(0).instruction.length());
    assertEquals("big", f.get(0).result);
    assertEquals(InlineField.MAX_INSTRUCTION, f.get(1).instruction.length());
    assertEquals("simple", f.get(1).result);
    assertTrue(f.get(2).rangeStart());
    assertTrue(f.get(2).instruction.startsWith("ADDIN EN.REFLIST zzz"));
    assertEquals(InlineField.MAX_ADDIN_INSTRUCTION, f.get(2).instruction.length());
    assertTrue(f.get(3).rangeEnd());
    assertTrue(d.plainText(), d.plainText().contains("1. Doe, A.\n2. Roe, B."));
  }
}
