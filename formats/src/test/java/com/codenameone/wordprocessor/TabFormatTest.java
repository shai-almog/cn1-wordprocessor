package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class TabFormatTest {
  @Test
  public void explicitTabsRoundTripAndLeadersReachPdf() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Item\t12.50\tEnd");
    s.paragraph("tabs", "120,decimal,dot;240,right,hyphen");
    Document d = s.document();
    assertEquals(DocumentXml.write(d), DocumentXml.write(DocumentXml.read(DocumentXml.write(d))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened imported = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(imported.warnings.toString(), imported.warnings.isEmpty());
    assertEquals(
        TabStop.encode(d.paragraph(0).format.tabs),
        TabStop.encode(imported.document.paragraph(0).format.tabs));
    assertEquals(d.plainText(), imported.document.plainText());
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("....."));
    assertTrue(pdf.contains("-----"));
  }
}
