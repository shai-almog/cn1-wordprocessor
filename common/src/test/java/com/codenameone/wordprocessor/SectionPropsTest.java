package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.zip.*;
import org.junit.Test;

/** w:gutter, lnNumType start/distance and per-section note properties as Wordcraft's SectionProps. */
public class SectionPropsTest {
  private static Docx.Opened read(String body) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var z = new ZipOutputStream(bytes);
    z.putNextEntry(new ZipEntry("word/document.xml"));
    z.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + body + "</w:body></w:document>").getBytes("UTF-8"));
    z.finish();
    return Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
  }

  private static Docx.Opened roundTrip(Document d) throws IOException {
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    return Docx.read(new ByteArrayInputStream(out.toByteArray()));
  }

  private static final String SECT =
      "<w:sectPr><w:footnotePr><w:numFmt w:val=\"lowerRoman\"/><w:numStart w:val=\"3\"/></w:footnotePr>"
          + "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>"
          + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"200\"/>"
          + "<w:lnNumType w:countBy=\"5\" w:start=\"9\" w:distance=\"400\" w:restart=\"continuous\"/></w:sectPr>";

  @Test
  public void gutterLineNumbersAndNotePropsRoundTrip() throws IOException {
    var body = new StringBuilder();
    for (int i = 0; i < 12; i++) body.append("<w:p><w:r><w:t>Line ").append(i).append("</w:t></w:r></w:p>");
    var opened = read(body + SECT);
    assertFalse(opened.warnings.toString(), opened.warnings.toString().contains("Line numbers"));
    var d = opened.document;
    var sec = d.lastSection;
    // The gutter widens the left margin (Wordcraft: body x = margin_left + gutter).
    assertEquals(72 + 10, sec.page.left, .01);
    assertEquals("10.0", sec.decor.layout("gutter", ""));
    assertEquals("10", sec.decor.layout("lineStart", ""));
    assertEquals("20.0", sec.decor.layout("lineDistance", ""));
    var engine = new LayoutEngine(new LayoutEngine.Metrics() {
      public float width(String t, TextStyle s) { return t.length() * 6; }
      public float height(TextStyle s) { return 12; }
    });
    var page = engine.layout(d, 0).pages.get(0);
    // Numbers start at 10; countBy 5 shows 10, 15, 20.
    assertEquals(3, page.lineNumbers.size());
    assertEquals(10, (int) page.lineNumbers.get(0)[1]);
    assertEquals(20, (int) page.lineNumbers.get(2)[1]);
    var again = roundTrip(d).document.lastSection;
    assertEquals(sec.page.left, again.page.left, .01);
    assertEquals(sec.decor.layout(), again.decor.layout());
    // Per-section footnote properties are kept (as Wordcraft's SectionProps.footnote_pr).
    assertEquals("lowerRoman", sec.notes.footnote.numFmt);
    assertEquals(3, sec.notes.footnote.numStart);
    assertEquals("lowerRoman", again.notes.footnote.numFmt);
    assertEquals(3, again.notes.footnote.numStart);
  }
}
