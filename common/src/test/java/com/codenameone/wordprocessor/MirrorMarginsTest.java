package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

/** Mirrored margins, as Wordcraft's mirror_even_pages and its w:mirrorMargins setting. */
public class MirrorMarginsTest {
  private static Document twoPages(boolean mirror) {
    var s = new Session(Document.empty());
    var b = new StringBuilder();
    for (int i = 0; i < 80; i++) b.append("Line ").append(i).append('\n');
    s.replace(b.toString());
    var r = s.activeSection().raw;
    s.section(r.page(new PageSpec(612, 792, 72, 36, 72, 90)));
    if (mirror) s.mirrorMargins(true);
    return s.document();
  }

  @Test
  public void evenPagesStartAtTheOutsideMargin() {
    var plain = new LayoutEngine(new PdfMetrics()).layout(twoPages(false), 0);
    var mirrored = new LayoutEngine(new PdfMetrics()).layout(twoPages(true), 0);
    assertTrue(mirrored.pages.size() >= 2);
    assertEquals(plain.pages.get(0).lines.get(0).x, mirrored.pages.get(0).lines.get(0).x, .01f);
    assertEquals(plain.pages.get(1).lines.get(0).x - 54, mirrored.pages.get(1).lines.get(0).x, .01f);
    assertEquals(36, mirrored.pages.get(1).spec.left, .01f);
    assertEquals(90, mirrored.pages.get(1).spec.right, .01f);
  }

  @Test
  public void docxSettingRoundTrips() throws Exception {
    var bytes = new ByteArrayOutputStream();
    Docx.write(twoPages(true), bytes);
    var d = Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document;
    assertTrue(d.mirrorMargins());
    assertTrue(DocumentXml.read(DocumentXml.write(d)).mirrorMargins());
    bytes = new ByteArrayOutputStream();
    Docx.write(d.mirrorMargins(false), bytes);
    assertFalse(Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document.mirrorMargins());
  }
}
