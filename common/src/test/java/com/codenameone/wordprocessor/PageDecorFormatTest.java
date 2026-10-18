package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class PageDecorFormatTest {
  private static Document decorated() {
    Document d = Document.empty();
    var side = new ParagraphBorders.Side(1.5f, 0x1f4e79, 24, "double");
    var decor =
        PageDecor.NONE.pageColor(0xfff2cc).watermark("DRAFT", 0xc00000)
            .borders(ParagraphBorders.preset("all", side, ParagraphBorders.NONE));
    return d.sections(d.sections, d.lastSection.decor(decor));
  }

  @Test
  public void docxKeepsPageColorAndBordersButNotWatermark() throws Exception {
    Document d = decorated();
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    var back = opened.document;
    var xml = new String(out.toByteArray(), "ISO-8859-1");
    var got = back.lastSection.decor;
    assertEquals(0xfff2cc, got.pageColor);
    assertEquals(d.lastSection.decor.borders, got.borders);
    assertEquals("", got.watermark);
  }

  @Test
  public void nativeXmlKeepsEverything() throws Exception {
    Document d = decorated();
    assertEquals(d.lastSection.decor, DocumentXml.read(DocumentXml.write(d)).lastSection.decor);
  }
}
