package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class MailMergeTest {
  private Session template() {
    Session s = new Session(Document.empty());
    MailMerge.recipients(
        s,
        MergeData.csv(
            "First Name,Last Name,City,Score\n"
                + "Ada,Lovelace,London,9\n"
                + "Alan,Turing,Wilmslow,10\n"
                + "Grace,Hopper,New York,11",
            true));
    MailMerge.insert(s, "GREETINGLINE");
    s.replace("\nSee you in ");
    MailMerge.insert(s, "MERGEFIELD City");
    return s;
  }

  @Test
  public void csvQuotesNewlinesBomHeadersAndStrictFailures() {
    MergeData d =
        MergeData.csv(
            "\ufeffName;;Name\r\n\"Smith; Ann\";\"line1\nline2\";\"say \"\"Hi\"\"\"\r\n\r\nBo;;",
            true);
    assertEquals(Arrays.asList("Name", "Column 2", "Name 2"), d.fields);
    assertEquals(2, d.rows.size());
    assertEquals("line1\nline2", d.rows.get(0).get(1));
    assertEquals("say \"Hi\"", d.rows.get(0).get(2));
    assertEquals(d.rows, MergeData.csv(d.csv(), true).rows);
    for (String csv :
        new String[] {"Name\n\"unfinished", "Name\n\"closed\"garbage", "Name\nAda,extra"}) {
      try {
        MergeData.csv(csv, true);
        fail(csv);
      } catch (IllegalArgumentException expected) {
      }
    }
    assertEquals(Arrays.asList("Column 1", "Column 2"), MergeData.csv("A\tB\nC\tD", false).fields);
  }

  @Test
  public void previewFinishUndoRedoAndTwoPageLayout() throws Exception {
    Session s = template();
    Document original = s.document();
    MergeData data = s.mergeData();
    MailMerge.preview(s, true, 1);
    assertEquals("Dear Alan Turing,\nSee you in Wilmslow", s.document().plainText());
    assertEquals("GREETINGLINE", s.document().paragraph(0).text.fieldAt(0).instruction);
    assertEquals(1, s.selection().block);
    Document preview = s.document();
    assertEquals(2, MailMerge.finish(s, 1, 2));
    assertEquals(
        "Dear Ada Lovelace,\nSee you in London\nDear Alan Turing,\nSee you in Wilmslow",
        s.document().plainText());
    assertEquals(2, new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.size());
    assertNull(s.document().paragraph(0).text.fieldAt(0));
    assertFalse(s.mergeData().preview);
    Document finished = s.document();
    s.undo();
    assertSame(preview, s.document());
    assertTrue(s.mergeData().preview);
    s.undo();
    assertSame(original, s.document());
    assertSame(data, s.mergeData());
    s.redo();
    s.redo();
    assertSame(finished, s.document());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Docx.write(finished, bytes);
    assertEquals(
        finished.plainText(),
        Docx.read(new ByteArrayInputStream(bytes.toByteArray())).document.plainText());
  }

  @Test
  public void recipientChangesRollbackForkAndLoadAreIsolated() {
    Session s = template();
    Document doc = s.document();
    MergeData data = s.mergeData();
    long revision = s.revision();
    Session fork = s.fork();
    try {
      s.transact(
          x -> {
            MailMerge.recipients(x, MergeData.csv("Name\nOther", true));
            throw new IllegalArgumentException("cancel");
          });
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(doc, s.document());
    assertSame(data, s.mergeData());
    assertEquals(revision, s.revision());
    MailMerge.recipients(fork, MergeData.csv("Name\nOther", true));
    assertSame(data, s.mergeData());
    assertNotSame(data, fork.mergeData());
    s.loadDocument(Document.empty());
    assertSame(MergeData.EMPTY, s.mergeData());
  }

  @Test
  public void conditionsNextAndSkipConsumeRecordsAndNumericComparison() {
    Session s = new Session(Document.empty());
    MailMerge.recipients(s, MergeData.csv("Name,Score\nAda,9\nAlan,10\nGrace,11", true));
    MailMerge.insert(s, MailMerge.rule("SKIPIF", "Name", "=", "Ada", "", ""));
    MailMerge.insert(s, MailMerge.rule("IF", "Score", ">=", "10", "Senior", "Junior"));
    s.replace(" ");
    MailMerge.insert(s, "MERGEFIELD Name");
    s.replace(" / ");
    MailMerge.insert(s, "NEXT");
    MailMerge.insert(s, "MERGEFIELD Name");
    assertEquals(1, MailMerge.finish(s, 1, 3));
    assertEquals("Senior Alan / Grace", s.document().plainText());
  }

  @Test
  public void tableCellPreviewKeepsCaretAndFormattingAndFinishClonesTable() {
    Session s = template();
    s.insertTable(1, 2);
    int table = 0;
    while (!(s.document().blocks.get(table) instanceof Table)) table++;
    s.enterCell(table, 0, 1);
    s.format("bold", "true");
    MailMerge.insert(s, "MERGEFIELD City");
    int caret = s.selection().caret;
    MailMerge.preview(s, true, 1);
    assertTrue(s.inCell());
    assertEquals(caret, s.selection().caret);
    assertTrue(s.editingDocument().plainText().contains("Wilmslow"));
    assertEquals(2, MailMerge.finish(s, 1, 2));
    int count = 0;
    Set<String> ids = new HashSet<String>();
    for (Block b : s.document().blocks) {
      assertTrue(ids.add(b.id()));
      if (b instanceof Table) {
        count++;
        Table t = (Table) b;
        Paragraph p = t.cell(0, 1).get(0);
        assertTrue(p.text.styleAt(0).bold);
        assertTrue(ids.add(p.id));
      }
    }
    assertEquals(2, count);
  }

  @Test
  public void headersSectionsNotesCommentsBookmarksAndReferencesAreIndependent() throws Exception {
    Session s = new Session(Document.empty());
    MailMerge.recipients(s, MergeData.csv("Name\nAda\nAlan", true));
    MailMerge.insert(s, "MERGEFIELD Name");
    s.select(0, 0, 1);
    s.addBookmark("Person");
    s.addComment("Review", "Check name");
    Document d = s.document();
    PageFurniture f =
        d.furniture.story(
            "header",
            new Sequence<Paragraph>()
                .append(
                    new Paragraph(
                        "h100",
                        RichText.field(
                            new InlineField("MERGEFIELD Name", "«Name»", false, false),
                            TextStyle.DEFAULT))));
    s.replaceDocument(d.furniture(f));
    s.select(0, 1, 1);
    s.insertField(new InlineField("REF Person \\h", "Name", false, false));
    MailMerge.preview(s, true, 0);
    assertEquals("Ada", s.document().furniture.get("header").get(0).text.plainText());
    MailMerge.finish(s, 1, 2);
    d = s.document();
    assertEquals(2, d.comments.size());
    assertEquals(2, d.bookmarks.size());
    assertEquals(
        "Ada", d.sectionRanges().get(0).resolved.furniture.get("header").get(0).text.plainText());
    assertEquals(
        "Alan", d.sectionRanges().get(1).resolved.furniture.get("header").get(0).text.plainText());
    for (Bookmark b : d.bookmarks.values())
      assertTrue(b.text(d).equals("Ada") || b.text(d).equals("Alan"));
    assertEquals(3, d.comments.get(0).end);
    assertEquals(4, d.comments.get(1).end);
    for (Block b : d.blocks)
      if (b instanceof Paragraph) {
        Paragraph p = (Paragraph) b;
        InlineField f2 = p.text.fieldAt(p.text.length() - 1);
        assertNotNull(f2);
        assertTrue(d.bookmarks.containsKey(new FieldCode(f2.instruction).arguments.get(0)));
      }
    assertEquals(d.plainText(), DocumentXml.read(DocumentXml.write(d)).plainText());
  }

  @Test
  public void missingFieldsAndMalformedRulesCannotPartiallyMerge() {
    Session s = template();
    MailMerge.insert(s, "MERGEFIELD Missing");
    Document before = s.document();
    assertEquals(Arrays.asList("Missing"), MailMerge.errors(before, s.mergeData()));
    try {
      MailMerge.finish(s, 1, 2);
      fail();
    } catch (IllegalArgumentException expected) {
      assertSame(before, s.document());
    }
    s.undo();
    MailMerge.insert(s, "IF { MERGEFIELD City } INVALID \"x\" \"yes\" \"no\"");
    before = s.document();
    MergeData data = s.mergeData();
    try {
      MailMerge.preview(s, true, 0);
      fail();
    } catch (IllegalArgumentException expected) {
      assertSame(before, s.document());
      assertSame(data, s.mergeData());
    }
  }

  @Test
  public void lockedFieldsUnrelatedFieldsAndAddressMappingArePreserved() {
    Session s = new Session(Document.empty());
    MailMerge.recipients(s, MergeData.csv("Who,Street,City\nAda,1 Main Road,London", true));
    Map<String, String> mapping = new HashMap<String, String>();
    mapping.put("firstName", "Who");
    s.mergeData(s.mergeData().mapping(mapping));
    MailMerge.insert(s, "ADDRESSBLOCK");
    s.insertField(new InlineField("MERGEFIELD Who", "LOCKED", true, false));
    s.insertField(new InlineField("PAGE", "7", false, false));
    MailMerge.finish(s, 1, 1);
    Paragraph p = s.document().paragraph(0);
    assertTrue(p.text.plainText().startsWith("Ada\n1 Main Road\nLondonLOCKED7"));
    assertTrue(p.text.fieldAt(p.text.length() - 2).locked);
    assertEquals("PAGE", p.text.fieldAt(p.text.length() - 1).instruction);
  }
}
