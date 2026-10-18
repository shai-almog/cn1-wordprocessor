package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class SectionsFormatTest {
  private Document sample() {
    var s = new Session(Document.empty());
    s.furniture(
        PageFurniture.EMPTY.story(
            "header",
            new Sequence<Paragraph>()
                .append(
                    new Paragraph(
                        "h",
                        RichText.of(
                            "Linked header",
                            TextStyle.DEFAULT.with("link", "https://example.com/section"))))));
    s.replace("First section");
    s.sectionBreak("nextPage");
    s.replace("Second section");
    s.section(s.activeSection().raw.page(new PageSpec(842, 595, 40, 40, 40, 40)).numbering(7));
    return s.document();
  }

  @Test
  public void nativeAndDocxPreserveLinkedSectionsAndRestart() throws Exception {
    var d = sample();
    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals(2, opened.document.sectionRanges().size());
    var second = opened.document.sectionAt(1);
    assertEquals(7, second.raw.pageStart);
    assertEquals(842, second.raw.page.width, .01);
    assertTrue(second.raw.furniture.stories.isEmpty());
    assertEquals("Linked header", second.resolved.furniture.get("header").get(0).text.plainText());
    assertEquals(
        "https://example.com/section",
        second.resolved.furniture.get("header").get(0).text.styleAt(0).link);
    out.reset();
    Docx.write(opened.document, opened, out, true);
    assertEquals(
        2, Docx.read(new ByteArrayInputStream(out.toByteArray())).document.sectionRanges().size());
  }

  @Test
  public void pdfHasDistinctMediaBoxesForEachSection() throws Exception {
    var pdf = new String(PdfExporter.write(sample()), "ISO-8859-1");
    assertTrue(pdf.contains("/MediaBox [0 0 595.280 841.890]"));
    assertTrue(pdf.contains("/MediaBox [0 0 842.000 595.000]"));
    assertTrue(pdf.contains("/Count 2"));
  }
}
