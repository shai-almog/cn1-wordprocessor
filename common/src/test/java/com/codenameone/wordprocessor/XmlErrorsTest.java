package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class XmlErrorsTest {
  private static String failure(String xml) {
    try {
      Xml.parse(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      return e.getMessage();
    }
    return null;
  }

  @Test
  public void malformedPartsReportTheFirstErrorAndItsOffset() {
    assertEquals("Mismatched closing tag at XML offset 7", failure("<a></b>"));
    assertTrue(failure("<a x=\"1\" x=\"2\"/>").startsWith("Duplicate attribute"));
    assertTrue(failure("<a>&nope;</a>").startsWith("Unknown entity"));
    assertTrue(failure("<a>&#xD800;</a>").startsWith("Invalid numeric entity"));
    assertTrue(failure("<a>&#1;</a>").startsWith("Invalid numeric entity"));
    assertTrue(failure("<a>&amp</a>").startsWith("Unterminated entity"));
    assertTrue(failure("<a x=1/>").startsWith("Unquoted attribute"));
    assertTrue(failure("<a x=\"<\"/>").startsWith("Invalid attribute value"));
    assertTrue(failure("<a>\u0001</a>").startsWith("Invalid XML control character"));
    assertTrue(failure("<a><!-- open</a>").startsWith("Unterminated XML declaration/comment"));
    assertTrue(failure("<a><![CDATA[x</a>").startsWith("Unterminated CDATA"));
    assertTrue(failure("<!DOCTYPE a><a/>").startsWith("DTD"));
    assertTrue(failure("<a/><b/>").startsWith("Trailing XML content"));
    assertTrue(failure("<a>text").startsWith("Unterminated element"));
    assertTrue(failure("<1a/>").startsWith("Expected XML name"));
  }

  @Test
  public void entitiesAndUnicodeNamesDecode() throws IOException {
    var e = Xml.parse("<?xml version=\"1.0\"?><!-- c --><él a=\"&lt;&#x1F600;&#65;&quot;\">x &amp; &apos;y&gt;<?pi?><!-- c --></él>"
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals("él", e.getTagName());
    assertEquals("<😀A\"", e.getAttribute("a"));
    assertEquals("x & 'y>", ((com.codename1.xml.Element) e.getChildAt(0)).getText());
  }
}
