package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class DocumentPropertiesFormatTest {
  private static Document withProperties() {
    return Document.empty()
        .properties(
            DocumentProperties.EMPTY
                .with("title", "Quarterly <report>")
                .with("author", "Ada")
                .with("keywords", "q3; sales")
                .with("company", "Acme & Co")
                .protection("comments"));
  }

  @Test
  public void docxCoreAppAndProtection() throws Exception {
    Document d = withProperties();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(d, out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    assertEquals(d.properties, opened.document.properties);
    // Rewriting from the opened package keeps one copy of each property.
    ByteArrayOutputStream again = new ByteArrayOutputStream();
    Docx.write(opened.document.properties(opened.document.properties.with("author", "Grace")), opened, again, true);
    Docx.Opened second = Docx.read(new ByteArrayInputStream(again.toByteArray()));
    assertEquals("Grace", second.document.properties.get("author"));
    assertEquals("Acme & Co", second.document.properties.get("company"));
  }

  @Test
  public void nativeXmlRoundTrip() throws Exception {
    Document d = withProperties();
    assertEquals(d.properties, DocumentXml.read(DocumentXml.write(d)).properties);
  }
}
