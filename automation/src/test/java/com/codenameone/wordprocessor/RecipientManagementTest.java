package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RecipientManagementTest {
  private MergeData data() {
    return MergeData.csv(
        "Name,City,Score\nZoe,Paris,2\nAda,London,12\nAlan,London,3\nAmy,,02\nada,Rome,1", true);
  }

  @Test
  public void sortingIsStableAndRetainsSourceExclusionsMappingsAndCurrentPerson() {
    MergeData d =
        data()
            .options("directory", true, true, 1)
            .mapping(Collections.singletonMap("firstName", "Name"));
    MergeData selected = d.manage("Name", false, "", "contains", "", Arrays.asList(2));
    assertEquals(Arrays.asList(1, 4, 3, 0), selected.sourceIndices);
    assertEquals("Ada", selected.value("Name", selected.record));
    assertEquals(d.mapping, selected.mapping);
    assertEquals("directory", selected.kind);
    assertTrue(selected.preview && selected.highlight);
    assertEquals(d.csv(), selected.csv());
    MergeData reverse = selected.manage("Name", true, "", "contains", "", selected.excluded);
    assertEquals(Arrays.asList(0, 3, 1, 4), reverse.sourceIndices);
    assertEquals(2, reverse.record);
    assertEquals(
        Arrays.asList(0, 1, 2, 3, 4),
        reverse.manage("", false, "", "contains", "", Collections.<Integer>emptyList())
            .sourceIndices);
    assertSame(d.sourceRows, selected.sourceRows);
  }

  @Test
  public void filtersHandleCaseNumericInequalityBlankAndTextualIds() {
    MergeData d = data();
    assertEquals(
        Arrays.asList(1, 2),
        d.manage("", false, "City", "contains", "LON", d.excluded).sourceIndices);
    assertEquals(
        Arrays.asList(1, 2), d.manage("", false, "Score", ">", "2", d.excluded).sourceIndices);
    assertEquals(
        Arrays.asList(0), d.manage("", false, "Score", "=", "2", d.excluded).sourceIndices);
    assertEquals(
        Arrays.asList(3),
        d.manage("", false, "City", "blank", "ignored", d.excluded).sourceIndices);
    assertEquals(
        Arrays.asList(0, 1, 2, 4),
        d.manage("", false, "City", "notBlank", "", d.excluded).sourceIndices);
  }

  @Test
  public void invalidManagementIsAtomicAndEmptySelectionDisablesPreview() {
    Session s = new Session(Document.empty());
    Commands c = new Commands();
    MailMerge.recipients(s, data());
    MailMerge.insert(s, "MERGEFIELD Name");
    MailMerge.preview(s, true, 1);
    MergeData before = s.mergeData();
    Document doc = s.document();
    long revision = s.revision();
    for (Map<String, Object> a :
        Arrays.asList(
            Json.object("excluded", Arrays.asList(-1)),
            Json.object("excluded", Arrays.asList(5)),
            Json.object("excluded", Arrays.asList(1.5)),
            Json.object("sortField", "Unknown"),
            Json.object("filterOperator", "eval"))) {
      try {
        c.execute(s, "mailings.manageRecipients", a);
        fail();
      } catch (IllegalArgumentException expected) {
      }
      assertSame(before, s.mergeData());
      assertSame(doc, s.document());
      assertEquals(revision, s.revision());
    }
    c.execute(
        s, "mailings.manageRecipients", Json.object("excluded", Arrays.asList(0, 1, 2, 3, 4)));
    assertFalse(s.mergeData().preview);
    assertTrue(s.mergeData().rows.isEmpty());
    assertEquals("«Name»", s.document().plainText());
    try {
      c.execute(s, "mailings.finish");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    s.undo();
    assertSame(before, s.mergeData());
    assertSame(doc, s.document());
  }

  @Test
  public void previewNavigationFindAndFinishUseTheSelectedOrderAndUndoAsOneChange() {
    Session s = new Session(Document.empty());
    Commands c = new Commands();
    MailMerge.recipients(s, data());
    MailMerge.insert(s, "MERGEFIELD Name");
    c.execute(s, "mailings.start", Json.object("kind", "directory"));
    c.execute(
        s,
        "mailings.manageRecipients",
        Json.object(
            "filterField",
            "City",
            "filterValue",
            "London",
            "sortField",
            "Name",
            "descending",
            true));
    Map<?, ?> readback = (Map<?, ?>) c.execute(s, "mailings.editRecipients");
    assertEquals(data().rows, readback.get("sourceRows"));
    assertEquals(Arrays.asList(2, 1), readback.get("sourceIndices"));
    c.execute(s, "mailings.preview", Json.object("value", true));
    assertEquals("Alan", s.document().plainText());
    c.execute(s, "mailings.next");
    assertEquals("Ada", s.document().plainText());
    try {
      c.execute(s, "mailings.findRecipient", Json.object("text", "Zoe"));
      fail();
    } catch (IllegalArgumentException expected) {
    }
    Document before = s.document();
    MergeData recipients = s.mergeData();
    c.execute(s, "mailings.finish");
    assertEquals("Alan\nAda", s.document().plainText());
    // Also validate the real DOCX export/import path for the resulting recipient order.
    try {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      Docx.write(s.document(), null, out, true);
      assertEquals(
          s.document().plainText(),
          Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document.plainText());
    } catch (Exception ex) {
      throw new AssertionError(ex);
    }
    s.undo();
    assertSame(before, s.document());
    assertSame(recipients, s.mergeData());
    s.redo();
    assertEquals("Alan\nAda", s.document().plainText());
  }

  @Test
  public void nextRecordRulesSkipExcludedSourceRows() {
    Session s = new Session(Document.empty());
    Commands c = new Commands();
    MailMerge.recipients(s, data());
    MailMerge.insert(s, "MERGEFIELD Name");
    s.replace(" / ");
    MailMerge.insert(s, "NEXT");
    MailMerge.insert(s, "MERGEFIELD Name");
    c.execute(s, "mailings.manageRecipients", Json.object("excluded", Arrays.asList(1, 3, 4)));
    c.execute(s, "mailings.finish");
    assertEquals("Zoe / Alan", s.document().plainText());
  }
}
