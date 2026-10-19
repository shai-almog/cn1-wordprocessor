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
    var a = new LinkedHashMap<String, Object>();
    for (int i = 0; i < kv.length; i += 2) a.put((String) kv[i], kv[i + 1]);
    return commands.execute(s, id, a);
  }

  @Test
  public void propertiesAndProtection() {
    var s = new Session(Document.empty());
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
    s.select(0, 4, 4);
    s.replace(" tracked");
    int revisions = Revisions.list(s.document()).size();
    assertTrue(revisions > 0);
    for (String review : new String[] {"review.acceptAll", "review.rejectAll", "review.accept", "review.reject"})
      try {
        run(s, review);
        fail(review + " resolved revisions under tracked-changes protection");
      } catch (IllegalStateException expected) {
      }
    assertEquals(revisions, Revisions.list(s.document()).size());
    run(s, "file.protect", "mode", "none");
    s.trackChanges(false);
    assertFalse(s.document().trackChanges);
  }

  @Test
  public void inspectorRemovesCommentsHiddenTextAndProperties() {
    var s = new Session(Document.empty());
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
  public void inspectorReachesTableCellsAndHeaders() {
    var s = new Session(Document.empty());
    s.replace("SECRET");
    s.select(0, 0, 6);
    s.format("hidden", "true");
    var hidden = (Paragraph) s.document().blocks.get(0);
    var cell = new Sequence<Paragraph>().append(hidden.copy("c1", hidden.text));
    var plain = new Sequence<Paragraph>().append(new Paragraph("c2", RichText.of("ok")));
    var table = new Table("t1", Arrays.asList(Arrays.asList(cell, plain)));
    var d = s.document();
    d = d.blocks(d.blocks.append(table), d.nextId);
    d = d.furniture(d.furniture.story("header", new Sequence<Paragraph>().append(hidden.copy("h1", hidden.text))));
    s.replaceDocument(d);
    Map<?, ?> r = (Map<?, ?>) run(s, "file.inspect", "remove", Arrays.<Object>asList("hidden"));
    assertEquals(18, ((Map<?, ?>) r.get("found")).get("hiddenCharacters"));
    assertEquals(0, ((Map<?, ?>) r.get("after")).get("hiddenCharacters"));
    assertEquals("ok", Table.cellText(((Table) s.document().blocks.get(1)).cell(0, 1)));
    s.undo();
    assertEquals(18, ((Map<?, ?>) ((Map<?, ?>) run(s, "file.inspect")).get("found")).get("hiddenCharacters"));
  }

  @Test
  public void accessibilityFindsAltTextHeadingOrderAndContrast() {
    var s = new Session(Document.empty());
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
    var codes = new StringBuilder();
    for (Object o : (List<?>) r.get("issues")) codes.append(((Map<?, ?>) o).get("code")).append(' ');
    assertTrue(codes.toString(), codes.toString().contains("heading-order"));
    assertTrue(codes.toString(), codes.toString().contains("contrast"));
    assertTrue(codes.toString(), codes.toString().contains("missing-title"));
    assertTrue(FileCommands.contrast(0, 0xffffff) > 20);
  }

  @Test
  public void exportPdfAndCompatibility() {
    var s = new Session(Document.empty());
    s.replace("Hello");
    Map<?, ?> pdf = (Map<?, ?>) run(s, "file.exportPdf");
    assertTrue(((String) pdf.get("base64")).startsWith("JVBERi"));
    run(s, "design.watermark", "text", "DRAFT");
    Map<?, ?> compat = (Map<?, ?>) run(s, "file.compatibility");
    assertTrue(compat.get("issues").toString().contains("Watermark"));
  }
}
