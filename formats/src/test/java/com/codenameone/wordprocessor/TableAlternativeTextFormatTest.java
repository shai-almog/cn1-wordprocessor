package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import java.io.*;
import org.junit.Test;

public class TableAlternativeTextFormatTest {
  @Test
  public void nativeAndDocxPreserveEscapedUnicodeMetadataWithoutWarnings() throws Exception {
    Session s = new Session(Document.empty());
    s.insertTable(2, 2);
    Table t = (Table) s.document().blocks.get(1);
    String title = "Sales & <growth> \"2026\" \ud83d\ude00", desc = "Région\nRevenue's share";
    s.formatTable(1, t.format.altText(title, desc));
    Document d = s.document();
    Document nativeCopy = DocumentXml.read(DocumentXml.write(d));
    TableFormat n = ((Table) nativeCopy.blocks.get(1)).format;
    assertEquals(title, n.title);
    assertEquals(desc, n.description);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    TableFormat f = ((Table) opened.document.blocks.get(1)).format;
    assertEquals(title, f.title);
    assertEquals(desc, f.description);
    String html = TextFormats.write(d, RichTextFormat.HTML);
    assertTrue(html.contains("Région&#10;Revenue"));
    assertTrue(DocumentXml.write(d).contains("Région&#10;Revenue"));
    assertTrue(html.contains("aria-label=\"Sales &amp; &lt;growth&gt; &quot;2026&quot;"));
    assertTrue(html.contains("aria-description=\"Région"));
  }

  @Test
  public void legacyDocumentHasEmptyMetadataAndEmptyAttributesAreOmitted() throws Exception {
    Session s = new Session(Document.empty());
    s.insertTable(1, 1);
    String xml = DocumentXml.write(s.document());
    assertFalse(xml.contains("<tableFormat headers=\"0\" title="));
    TableFormat f = ((Table) DocumentXml.read(xml).blocks.get(1)).format;
    assertEquals("", f.title);
    assertEquals("", f.description);
  }
}
