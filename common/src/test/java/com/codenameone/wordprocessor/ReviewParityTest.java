package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

/** Comment dates, the hidden-text option and per-cell formula languages, as in Wordcraft. */
public class ReviewParityTest {
  final Commands commands = new Commands();

  private Session commented() {
    var s = new Session(Document.empty());
    s.replace("Body text");
    s.select(0, 0, 4);
    s.addComment("Ada", "Check");
    s.replyComment(s.document().comments.get(0).id, "Lin", "Done");
    return s;
  }

  @Test
  public void newCommentsAndRepliesAreDated() {
    var c = commented().document().comments.get(0);
    assertTrue(c.date, c.date.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"));
    assertTrue(c.replies.get(0).date.matches("\\d{4}-.*Z"));
    assertEquals(c.date.substring(0, 10) + " " + c.date.substring(11, 16), ReviewComment.displayDate(c.date));
    assertEquals("", ReviewComment.displayDate(""));
    // Editing or resolving keeps the dates.
    var e = c.edited(-1, "Changed").edited(0, "Again").resolved(true);
    assertEquals(c.date, e.date);
    assertEquals(c.replies.get(0).date, e.replies.get(0).date);
  }

  @Test
  public void commentDatesRoundTripNativeAndDocx() throws Exception {
    var d = commented().document();
    var c = d.comments.get(0).dated("2024-03-05T14:07:00Z");
    var dated = d.comments(Collections.singletonList(c.reply("Bo", "Two", "2024-03-06T09:00:00Z")));
    var n = DocumentXml.read(DocumentXml.write(dated)).comments.get(0);
    assertEquals("2024-03-05T14:07:00Z", n.date);
    assertEquals("2024-03-06T09:00:00Z", n.replies.get(1).date);
    var out = new ByteArrayOutputStream();
    Docx.write(dated, out);
    var x = Docx.read(new ByteArrayInputStream(out.toByteArray())).document.comments.get(0);
    assertEquals("2024-03-05T14:07:00Z", x.date);
    assertEquals("2024-03-06T09:00:00Z", x.replies.get(1).date);
    Map<?, ?> all = (Map<?, ?>) commands.execute(new Session(dated), "review.comments", Json.object());
    Map<?, ?> listed = (Map<?, ?>) ((List<?>) all.get("comments")).get(0);
    assertEquals("2024-03-05T14:07:00Z", listed.get("date"));
  }

  @Test
  public void hiddenTextOptionIsSeparateFromFormattingMarks() {
    AppOptions.reset();
    assertFalse(AppOptions.flag("showHiddenText"));
    commands.execute(new Session(Document.empty()), "file.options",
        Json.object("key", "showHiddenText", "value", "true"));
    assertTrue(AppOptions.flag("showHiddenText"));
    try {
      commands.execute(new Session(Document.empty()), "file.options",
          Json.object("key", "showHiddenText", "value", "maybe"));
      fail("non-boolean accepted");
    } catch (IllegalArgumentException expected) {
    }
    AppOptions.reset();
  }

  @Test
  public void decimalCommaNumbersFollowTheLanguage() {
    assertEquals(1.5, TableFormulas.number("1,5", "pl-PL"), 0);
    assertEquals(1250, TableFormulas.number("1 250,00", "pl-PL"), 0);
    assertEquals(1250.5, TableFormulas.number("1\u00a0250,50 z\u0142", "pl-PL"), 0);
    assertEquals(1250, TableFormulas.number("1.250,00", "de-DE"), 0);
    assertEquals(1.5, TableFormulas.number("1.5", "pl-PL"), 0);
    assertEquals(1, TableFormulas.number("1.", "pl-PL"), 0);
    assertEquals(-2.25, TableFormulas.number("-2,25", "pl-PL"), 0);
    assertNull(TableFormulas.number("1,2,3", "pl-PL"));
    assertEquals(1250, TableFormulas.number("1,250", "en-US"), 0);
    assertEquals('.', TableFormulas.decimalSeparator("de-CH"));
    assertEquals("5,75", TableFormulas.format(5.75, "pl-PL"));
    assertEquals("5.75", TableFormulas.format(5.75, ""));
  }

  @Test
  public void formulasReadEachCellInItsLanguage() {
    var s = new Session(Document.empty());
    s.insertTable(3, 1);
    s.setCell(1, 0, 0, "3,50");
    s.setCell(1, 1, 0, "2,25");
    var table = (Table) s.document().blocks.get(1);
    var polish = TextStyle.DEFAULT.with("lang", "pl-PL");
    for (int r = 0; r < 2; r++) {
      var p = table.cell(r, 0).get(0);
      table = table.setCell(r, 0, Sequence.of(Collections.singletonList(
          p.text(RichText.of(p.text.plainText(), polish)))));
    }
    assertEquals("5,75", TableFormulas.calculate(table, 2, 0, "=SUM(ABOVE)"));
    assertEquals("pl-PL", TableFormulas.cellLang(table, 0, 0));
  }
}
