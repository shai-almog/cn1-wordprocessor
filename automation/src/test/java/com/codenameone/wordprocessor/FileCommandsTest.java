package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FileCommandsTest {
  private final Commands commands = new Commands();

  private Object run(Session s, String id, Object... kv) {
    Map<String, Object> a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @Test
  public void propertiesAndProtection() {
    Session s = new Session(Document.empty());
    s.replace("Body");
    Map<?, ?> p = (Map<?, ?>) run(s, "file.properties", "title", "Plan", "author", "Ada");
    assertEquals("Plan", p.get("title"));
    assertEquals("Ada", s.document().properties.get("author"));

    run(s, "file.protect", "mode", "readOnly");
    try {
      s.replace("x");
      fail("read-only document accepted an edit");
    } catch (IllegalStateException expected) {
    }
    assertEquals("Body", s.document().plainText());

    run(s, "file.protect", "mode", "comments");
    s.select(0, 0, 4);
    s.addComment("Ada", "Looks good");
    assertEquals(1, s.document().comments.size());
    try {
      s.replace("y");
      fail("comments-only document accepted a text edit");
    } catch (IllegalStateException expected) {
    }

    run(s, "file.protect", "mode", "trackedChanges");
    assertTrue(s.document().trackChanges);
    try {
      s.trackChanges(false);
      fail("tracked-changes protection allowed turning tracking off");
    } catch (IllegalStateException expected) {
    }
    run(s, "file.protect", "mode", "none");
    s.trackChanges(false);
    assertFalse(s.document().trackChanges);
  }

  @Test
  public void inspectorRemovesCommentsHiddenTextAndProperties() {
    Session s = new Session(Document.empty());
    s.replace("Visible secret");
    s.select(0, 8, 14);
    s.format("hidden", "true");
    s.select(0, 0, 7);
    s.addComment("Ada", "note");
    run(s, "file.properties", "author", "Ada");
    Map<?, ?> r =
        (Map<?, ?>) run(s, "file.inspect", "remove", Arrays.<Object>asList("comments", "hidden", "properties"));
    Map<?, ?> found = (Map<?, ?>) r.get("found"), after = (Map<?, ?>) r.get("after");
    assertEquals(1, found.get("comments"));
    assertEquals(6, found.get("hiddenCharacters"));
    assertEquals(0, after.get("comments"));
    assertEquals(0, after.get("hiddenCharacters"));
    assertEquals(0, after.get("properties"));
    assertEquals("Visible ", s.document().plainText());
  }

  @Test
  public void accessibilityFindsAltTextHeadingOrderAndContrast() {
    Session s = new Session(Document.empty());
    s.replace("Intro");
    s.paragraph("style", "Heading1");
    s.enterParagraph();
    s.replace("Deep");
    s.paragraph("style", "Heading3");
    s.enterParagraph();
    s.replace("pale");
    s.select(2, 0, 4);
    s.format("color", "dddddd");
    Map<?, ?> r = (Map<?, ?>) run(s, "file.accessibility");
    StringBuilder codes = new StringBuilder();
    for (Object o : (List<?>) r.get("issues")) codes.append(((Map<?, ?>) o).get("code")).append(' ');
    assertTrue(codes.toString(), codes.toString().contains("heading-order"));
    assertTrue(codes.toString(), codes.toString().contains("contrast"));
    assertTrue(codes.toString(), codes.toString().contains("missing-title"));
    assertTrue(FileCommands.contrast(0, 0xffffff) > 20);
  }

  @Test
  public void exportPdfAndCompatibility() {
    Session s = new Session(Document.empty());
    s.replace("Hello");
    Map<?, ?> pdf = (Map<?, ?>) run(s, "file.exportPdf");
    assertTrue(((String) pdf.get("base64")).startsWith("JVBERi"));
    run(s, "design.watermark", "text", "DRAFT");
    Map<?, ?> compat = (Map<?, ?>) run(s, "file.compatibility");
    assertTrue(compat.get("issues").toString().contains("Watermark"));
  }
}
