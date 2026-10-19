package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class LineSpacingFormatTest {
  private Document fixture(String rule, String value) {
    var s = new Session(Document.empty());
    s.replace("first\nsecond");
    s.selectRange(0, 0, 1, 6);
    s.paragraph("lineRule", rule);
    s.paragraph("spacing", value);
    return s.document();
  }

  private byte[] docx(Document d) throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    return out.toByteArray();
  }

  private Docx.Opened read(byte[] bytes) throws Exception {
    return Docx.read(new ByteArrayInputStream(bytes));
  }

  private byte[] replace(byte[] data, String path, String from, String to) throws Exception {
    var in = new ZipInputStream(new ByteArrayInputStream(data));
    var bytes = new ByteArrayOutputStream();
    var out = new ZipOutputStream(bytes);
    ZipEntry e;
    while ((e = in.getNextEntry()) != null) {
      var value = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) value.write(b, 0, n);
      var v = value.toByteArray();
      if (e.getName().equals(path)) {
        var text = new String(v, "UTF-8");
        assertTrue(text.contains(from));
        v = text.replace(from, to).getBytes("UTF-8");
      }
      out.putNextEntry(new ZipEntry(e.getName()));
      out.write(v);
      out.closeEntry();
    }
    in.close();
    out.close();
    return bytes.toByteArray();
  }

  @Test
  public void allRulesRoundTripWithoutConversionWarnings() throws Exception {
    for (String rule : new String[] {"auto", "atLeast", "exact"}) {
      var d = fixture(rule, rule.equals("auto") ? "1.5" : "18.5");
      String xml = DocumentXml.write(d);
      assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
      var opened = read(docx(d));
      assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
      for (int i = 0; i < 2; i++) {
        assertEquals(rule, opened.document.paragraph(i).format.lineRule);
        assertEquals(d.paragraph(i).lineSpacing, opened.document.paragraph(i).lineSpacing, 0);
      }
    }
  }

  @Test
  public void legacyNativeFilesRetainMultiples() throws Exception {
    var xml = DocumentXml.write(fixture("auto", "1.5")).replace(" lineRule=\"auto\"", "");
    var p = DocumentXml.read(xml).paragraph(0);
    assertEquals("auto", p.format.lineRule);
    assertEquals(1.5f, p.lineSpacing, 0);
  }

  @Test
  public void beforeAfterOverridesDoNotDiscardInheritedLineRuleOrAmount() throws Exception {
    var s = new Session(fixture("exact", "24"));
    s.defineStyle(
        new ParagraphStyle("Normal", "Normal", s.document().paragraph(0), TextStyle.DEFAULT));
    var data =
        // Spacing equal to the style's is inherited, not written; add a before/after-only override.
        replace(
            docx(s.document()),
            "word/document.xml",
            "<w:pStyle w:val=\"Normal\"/>",
            "<w:pStyle w:val=\"Normal\"/><w:spacing w:before=\"0\" w:after=\"0\"/>");
    var p = read(data).document.paragraph(0);
    assertEquals("exact", p.format.lineRule);
    assertEquals(24, p.lineSpacing, 0);
  }

  @Test
  public void explicitRuleAndLineOverrideStyleIndependently() throws Exception {
    var data =
        replace(
            docx(fixture("exact", "24")),
            "word/document.xml",
            "w:lineRule=\"exact\"",
            "w:lineRule=\"atLeast\"");
    var p = read(data).document.paragraph(0);
    assertEquals("atLeast", p.format.lineRule);
    assertEquals(24, p.lineSpacing, 0);
  }

  @Test
  public void trackedSpacingRetainsPreviousRuleInBothFormats() throws Exception {
    var s = new Session(fixture("auto", "1.5"));
    s.trackChanges(true);
    s.selectRange(0, 0, 1, 6);
    s.transact(
        t -> {
          t.paragraph("lineRule", "exact");
          t.paragraph("spacing", "18");
        });
    for (Document d :
        new Document[] {
          DocumentXml.read(DocumentXml.write(s.document())), read(docx(s.document())).document
        }) {
      var review = new Session(d);
      review.resolveRevision(null, false);
      for (int i = 0; i < 2; i++) {
        assertEquals("auto", review.document().paragraph(i).format.lineRule);
        assertEquals(1.5f, review.document().paragraph(i).lineSpacing, 0);
      }
    }
  }

  @Test
  public void pdfUsesExactClippingAndKeepsStoredText() throws Exception {
    var d = fixture("exact", "6");
    var pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains(" re W n\n"));
    assertTrue(pdf.contains("first"));
    assertTrue(pdf.contains("second"));
    assertEquals("first\nsecond", d.plainText().trim());
  }
}
