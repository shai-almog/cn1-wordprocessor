package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import org.junit.Test;

public class RevisionFormatTest {
  private Document roundTrip(Document d) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(d, bytes);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(bytes.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    return opened.document;
  }

  @Test
  public void nativeAndDocxPreserveTextBreaksAuthorsAndTrackingState() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("old text\nsecond");
    s.trackChanges(true);
    s.reviewAuthor("Ada & Bob");
    s.selectRange(0, 4, 1, 3);
    s.replace("new\nwords");
    String xml = DocumentXml.write(s.document());
    assertEquals(xml, DocumentXml.write(DocumentXml.read(xml)));
    Document d = roundTrip(s.document());
    assertTrue(d.trackChanges);
    assertEquals(
        Revisions.resolve(s.document(), null, true).plainText(),
        Revisions.resolve(d, null, true).plainText());
    assertEquals("old text\nsecond", Revisions.resolve(d, null, false).plainText());
    for (Revisions.Change c : Revisions.list(d)) assertEquals("Ada & Bob", c.revision.author);
    Session imported = new Session(d);
    imported.reviewAuthor("Next author");
    imported.replace("next");
    String id = Revisions.list(imported.document()).get(0).revision.id;
    assertFalse(Revisions.list(d).stream().anyMatch(c -> c.revision.id.equals(id)));
  }

  @Test
  public void trackedFieldsRetainInstructionsAndAtomicRejection() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Page ");
    s.trackChanges(true);
    s.insertField(new InlineField("PAGE", "7", true, false));
    Document d = roundTrip(s.document());
    assertEquals("PAGE", d.paragraph(0).text.fieldAt(5).instruction);
    assertEquals("Page ", Revisions.resolve(d, null, false).plainText());
    s.resolveRevision(null, true);
    s.select(0, 5, 6);
    s.replace("");
    d = roundTrip(s.document());
    assertEquals("Page ", Revisions.resolve(d, null, true).plainText());
    assertEquals("Page 7", Revisions.resolve(d, null, false).plainText());
  }

  @Test
  public void acceptedAndRejectedExportsContainNoRemainingRevisions() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("one two");
    s.trackChanges(true);
    s.select(0, 4, 7);
    s.replace("three");
    for (boolean accept : new boolean[] {true, false}) {
      Document d = roundTrip(Revisions.resolve(s.document(), null, accept));
      assertTrue(Revisions.list(d).isEmpty());
      assertEquals(accept ? "one three" : "one two", d.plainText());
    }
  }

  @Test
  public void characterFormatChangesKeepBeforeAndAfterThroughBothFormats() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("old style");
    s.select(0, 0, 3);
    s.format("italic", "true");
    s.trackChanges(true);
    s.reviewAuthor("Editor");
    s.format("italic", "false");
    s.format("bold", "true");
    s.format("color", "00ff00");
    for (Document d :
        new Document[] {
          roundTrip(s.document()), DocumentXml.read(DocumentXml.write(s.document()))
        }) {
      assertEquals("format", Revisions.list(d).get(0).revision.kind);
      assertEquals("Editor", Revisions.list(d).get(0).revision.author);
      TextStyle before = Revisions.resolve(d, null, false).paragraph(0).text.styleAt(0);
      assertTrue(before.italic);
      assertFalse(before.bold);
      assertEquals(TextStyle.DEFAULT.color, before.color);
      TextStyle after = Revisions.resolve(d, null, true).paragraph(0).text.styleAt(0);
      assertFalse(after.italic);
      assertTrue(after.bold);
      assertEquals(0x00ff00, after.color);
    }
  }

  @Test
  public void commentsAfterDeletedTextRetainOffsets() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("one two three");
    s.trackChanges(true);
    s.select(0, 0, 3);
    s.replace("");
    s.select(0, 8, 13);
    s.addComment("Ada", "Keep this");
    Document d = roundTrip(s.document());
    assertEquals(8, d.comments.get(0).start);
    assertEquals(13, d.comments.get(0).end);
  }

  @Test
  public void rejectingAnotherAuthorsDeletionRestoresInsertionHistory() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("AB");
    s.trackChanges(true);
    s.reviewAuthor("Ada");
    s.select(0, 1, 1);
    s.replace("new\n");
    String insertion = Revisions.list(s.document()).get(0).revision.id;
    s.reviewAuthor("Bob");
    s.selectRange(0, 1, 1, 0);
    s.replace("");
    String deletion = Revisions.list(s.document()).get(0).revision.id;
    assertNotEquals(insertion, deletion);
    for (Document d :
        new Document[] {
          roundTrip(s.document()), DocumentXml.read(DocumentXml.write(s.document()))
        }) {
      Document restored = Revisions.resolve(d, deletion, false);
      assertEquals("Anew\nB", restored.plainText());
      for (Revisions.Change c : Revisions.list(restored)) assertEquals("Ada", c.revision.author);
      assertEquals("AB", Revisions.resolve(restored, null, false).plainText());
      assertEquals("AB", Revisions.resolve(d, null, true).plainText());
    }
  }

  @Test
  public void paragraphChangesPreserveAlignmentSpacingAndListHistory() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("First\nSecond");
    s.selectRange(0, 0, 1, 6);
    s.paragraph("list", "number");
    s.trackChanges(true);
    s.reviewAuthor("Ada");
    s.select(0, 0, 5);
    s.paragraph("align", "right");
    s.paragraph("after", "18");
    s.paragraph("list", "");
    for (Document d :
        new Document[] {
          roundTrip(s.document()), DocumentXml.read(DocumentXml.write(s.document()))
        }) {
      assertEquals(1, Revisions.list(d).size());
      Document rejected = Revisions.resolve(d, null, false),
          accepted = Revisions.resolve(d, null, true);
      assertEquals("left", rejected.paragraph(0).align);
      assertEquals(6, rejected.paragraph(0).after, .01);
      assertEquals("number", rejected.paragraph(0).list);
      assertEquals("right", accepted.paragraph(0).align);
      assertEquals(18, accepted.paragraph(0).after, .01);
      assertEquals("", accepted.paragraph(0).list);
      assertEquals("number", accepted.paragraph(1).list);
    }
  }

  @Test
  public void namedStyleRevisionRestoresOriginalThroughNativeAndDocx() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Heading");
    s.trackChanges(true);
    s.applyStyle("Heading1");
    for (Document d :
        new Document[] {
          roundTrip(s.document()), DocumentXml.read(DocumentXml.write(s.document()))
        }) {
      Paragraph accepted = Revisions.resolve(d, null, true).paragraph(0);
      Paragraph rejected = Revisions.resolve(d, null, false).paragraph(0);
      assertEquals("Heading1", accepted.style);
      assertTrue(accepted.text.styleAt(0).bold);
      assertEquals("Normal", rejected.style);
      assertFalse(rejected.text.styleAt(0).bold);
      assertEquals(12, rejected.text.styleAt(0).size, .01);
    }
  }

  @Test
  public void inheritedStyleRevisionRestoresEffectiveRunDefaults() throws Exception {
    Session s = new Session(Document.empty());
    s.replace("Heading");
    s.trackChanges(true);
    s.applyStyle("Heading1");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    // An external producer can store the style change in pPr alone, inheriting the run style.
    java.util.zip.ZipInputStream in =
        new java.util.zip.ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    ByteArrayOutputStream packageBytes = new ByteArrayOutputStream();
    java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(packageBytes);
    java.util.zip.ZipEntry entry;
    while ((entry = in.getNextEntry()) != null) {
      ByteArrayOutputStream part = new ByteArrayOutputStream();
      byte[] buffer = new byte[2048];
      int n;
      while ((n = in.read(buffer)) != -1) part.write(buffer, 0, n);
      byte[] bytes = part.toByteArray();
      if ("word/document.xml".equals(entry.getName()))
        bytes =
            new String(bytes, "UTF-8")
                .replaceAll("<w:rPr>.*?</w:rPr></w:rPrChange></w:rPr>", "")
                .getBytes("UTF-8");
      zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
      zip.write(bytes);
      zip.closeEntry();
    }
    zip.close();
    in.close();
    Document imported = Docx.read(new ByteArrayInputStream(packageBytes.toByteArray())).document;
    assertEquals(26, imported.paragraph(0).text.styleAt(0).size, .01);
    Document original = Revisions.resolve(imported, null, false);
    assertEquals("Normal", original.paragraph(0).style);
    assertEquals(12, original.paragraph(0).text.styleAt(0).size, .01);
  }
}
