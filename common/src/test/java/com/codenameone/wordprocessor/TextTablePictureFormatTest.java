package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import com.codename1.ui.RichTextFormat;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TextTablePictureFormatTest {
  private static Sequence<Paragraph> cell(String id, String text) {
    return new Sequence<Paragraph>().append(new Paragraph(id, RichText.of(text)));
  }

  private static Document fixture() throws Exception {
    var rows = new ArrayList<List<Sequence<Paragraph>>>();
    String[][] text = {{"Name", "Qty", "Note"}, {"a|b", "2", "x"}, {"c", "", "y"}};
    for (int r = 0; r < 3; r++) {
      var row = new ArrayList<Sequence<Paragraph>>();
      for (int c = 0; c < 3; c++) row.add(cell("c" + r + c, text[r][c]));
      rows.add(row);
    }
    var merges = new ArrayList<Table.Merge>();
    merges.add(new Table.Merge(1, 1, 2, 1));
    var blocks = new Sequence<Block>()
        .append(new Paragraph("p1", RichText.of("Before")))
        .append(new Table("t1", rows, merges))
        .append(new Picture("i1", PictureTest.rgba(), 30, 15, "Logo [x]", "left"))
        .append(new Paragraph("p2", RichText.of("After")));
    return new Document(blocks, PageSpec.A4, "Fixture", 100);
  }

  private static Table table(Document d) {
    for (Block b : d.blocks) if (b instanceof Table t) return t;
    return null;
  }

  private static Picture picture(Document d) {
    for (Block b : d.blocks) if (b instanceof Picture p) return p;
    return null;
  }

  private void roundTrip(RichTextFormat format, boolean merges) throws Exception {
    Document source = fixture();
    String out = TextFormats.write(source, format);
    Document back = TextFormats.read(out, format);
    assertTrue(out, back.plainText().contains("Before"));
    assertTrue(out, back.plainText().contains("After"));
    Table t = table(back);
    assertNotNull(out, t);
    assertEquals(3, t.rows());
    assertEquals(3, t.columns());
    assertEquals("Name", Table.cellText(t.cell(0, 0)));
    assertEquals("a|b", Table.cellText(t.cell(1, 0)));
    assertEquals("y", Table.cellText(t.cell(2, 2)));
    if (merges) assertEquals(2, t.span(1, 1).rows);
    Picture p = picture(back);
    assertNotNull(out, p);
    assertArrayEquals(PictureTest.rgba(), p.bytes());
    assertEquals("image/png", p.mime);
    int before = -1, table = -1, pic = -1, after = -1;
    for (int i = 0; i < back.blocks.size(); i++) {
      Block b = back.blocks.get(i);
      if (b instanceof Table) table = i;
      else if (b instanceof Picture) pic = i;
      else if (b instanceof Paragraph para && "Before".equals(para.text.plainText())) before = i;
      else if (b instanceof Paragraph para && "After".equals(para.text.plainText())) after = i;
    }
    assertTrue(before < table && table < pic && pic < after);
  }

  @Test
  public void htmlRoundTripsSpansAndDataPictures() throws Exception {
    roundTrip(RichTextFormat.HTML, true);
    Picture p = picture(TextFormats.read(TextFormats.write(fixture(), RichTextFormat.HTML), RichTextFormat.HTML));
    assertEquals("Logo [x]", p.alt);
    assertEquals(30, p.width, .01);
  }

  @Test
  public void markdownRoundTripsPipeTablesAndImages() throws Exception {
    String md = TextFormats.write(fixture(), RichTextFormat.MARKDOWN);
    assertTrue(md, md.contains("| --- |"));
    assertTrue(md, md.contains("a\\|b"));
    assertTrue(md, md.contains("![Logo \\[x\\]](data:image/png;base64,"));
    roundTrip(RichTextFormat.MARKDOWN, false);
  }

  @Test
  public void rtfRoundTripsRowsMergesAndPict() throws Exception {
    String rtf = TextFormats.write(fixture(), RichTextFormat.RTF);
    assertTrue(rtf, rtf.contains("\\trowd") && rtf.contains("\\clvmgf") && rtf.contains("\\pngblip"));
    roundTrip(RichTextFormat.RTF, true);
  }

  @Test
  public void htmlColspanRowspanAndTh() throws Exception {
    Document d = TextFormats.read(
        "<p>x</p><TABLE><tr><th colspan=2>H</th></tr><tr><td rowspan=\"2\">a</td><td>b</td></tr>"
            + "<tr><td>c</td></tr></TABLE><p>y</p>",
        RichTextFormat.HTML);
    Table t = table(d);
    assertEquals(3, t.rows());
    assertEquals(2, t.columns());
    assertEquals(2, t.span(0, 0).columns);
    assertEquals(2, t.span(1, 0).rows);
    assertEquals("c", Table.cellText(t.cell(2, 1)));
  }

  @Test
  public void gfmAlignmentAndRaggedRows() throws Exception {
    Document d = TextFormats.read("| a | b |\n|:-:|--:|\n| 1 |\n| 2 | 3 | 4 |\n\ntail", RichTextFormat.MARKDOWN);
    Table t = table(d);
    assertEquals(3, t.rows());
    assertEquals(2, t.columns());
    assertEquals("center", t.cell(0, 0).get(0).align);
    assertEquals("right", t.cell(1, 1).get(0).align);
    assertEquals("3", Table.cellText(t.cell(2, 1)));
    assertTrue(d.plainText().contains("tail"));
  }

  @Test
  public void rtfHorizontalMergeAndJpegKind() throws Exception {
    Document d = TextFormats.read(
        "{\\rtf1\\ansi\\trowd\\clmgf\\cellx1000\\clmrg\\cellx2000\\pard\\intbl {\\b wide}\\cell \\cell\\row"
            + "\\trowd\\cellx1000\\cellx2000\\pard\\intbl a\\cell b\\cell\\row\\pard after\\par}",
        RichTextFormat.RTF);
    Table t = table(d);
    assertEquals(2, t.rows());
    assertEquals(2, t.span(0, 0).columns);
    assertEquals("wide", Table.cellText(t.cell(0, 0)));
    assertEquals("b", Table.cellText(t.cell(1, 1)));
    assertTrue(d.plainText().contains("after"));
  }

  @Test
  public void hostileInputNeverThrows() throws Exception {
    String[] html = {
      "<table", "<table><tr><td colspan=99999999 rowspan=-4>x", "<td>a</td></table></table>",
      "<img src=\"data:image/png;base64,!!!!\">", "<img src='data:image/png;base64,AAAA' alt=x>",
      "<table><tr><td><table><tr><td>in</td></tr></table></td></tr></table>",
      "QQWPBLK0KLBPWQQ<p>QQWPBLK99KLBPWQQ</p>", "<img src=data:image/jpeg;base64>"
    };
    for (String h : html) assertNotNull(h, TextFormats.read(h, RichTextFormat.HTML));
    String[] md = {
      "|", "|---|", "a|b\n|---|---|", "![x](data:image/png;base64,@@@)", "![x](data:", "![](", "```\n|a|\n|-|\n```",
      "| a |\n| - |\n" + "| q |\n".repeat(3000)
    };
    for (String m : md) assertNotNull(m, TextFormats.read(m, RichTextFormat.MARKDOWN));
    Document big = TextFormats.read(md[md.length - 1], RichTextFormat.MARKDOWN);
    assertTrue(table(big).rows() <= TextBlocks.MAX_ROWS);
    String[] rtf = {
      "{\\rtf1\\trowd", "{\\rtf1\\trowd\\cellx-5\\cell\\cell\\cell\\row}", "{\\rtf1{\\pict\\pngblip zz}}",
      "{\\rtf1{\\pict\\jpegblip\\picwgoal99999999 ffd8ffe0" + "00".repeat(40) + "}}", "{\\rtf1{\\pict\\bin5 abcde}}",
      "{\\rtf1\\trowd\\clvmrg\\cellx10\\pard\\intbl }}}a\\cell\\row}", "{\\*\\shppict{\\pict"
    };
    for (String r : rtf) assertNotNull(r, TextFormats.read(r, RichTextFormat.RTF));
  }
}
