package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.junit.Test;

/** Header/footer stories hold any block, as in Word and Wordcraft. */
public class HeaderBlocksTest {
  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String t, TextStyle s) {
            return t.length() * 6;
          }

          public float height(TextStyle s) {
            return 12;
          }
        });
  }

  private static Sequence<Block> cell(String id, RichText text) {
    return new Sequence<Block>().append(new Paragraph(id, text).with("after", "0"));
  }

  private static RichText page() {
    return RichText.of("Page ")
        .concat(RichText.field(new InlineField("PAGE", "?", false, false), TextStyle.DEFAULT));
  }

  /** A 1x2 table whose second cell holds a nested 1x1 table with a PAGE field. */
  private static Table headerTable() {
    var nested = Table.of("t2", List.of(List.of(cell("n1", page()))), List.of());
    var right = new Sequence<Block>().append(nested).append(new Paragraph("c2", new RichText()));
    return Table.of("t1", List.of(List.of(cell("c1", RichText.of("Logo")), right)), List.of());
  }

  private static Sequence<Block> story() {
    return new Sequence<Block>()
        .append(headerTable())
        .append(new Picture("i1", OdtTest.PNG, 40, 20, "Mark", "center"))
        .append(new Paragraph("h1", RichText.of("Tail")).with("after", "0"));
  }

  private static Document twoPages(PageFurniture f) {
    var blocks = new Sequence<Block>();
    for (int i = 0; i < 2; i++)
      blocks = blocks.append(new Paragraph("p" + i, RichText.of("Body")).with("pageBreak", "true"));
    return new Document(blocks, PageSpec.A4, "Title", 100).furniture(f);
  }

  private static String text(List<LayoutEngine.Line> lines) {
    var b = new StringBuilder();
    for (LayoutEngine.Line l : lines) for (LayoutEngine.Span s : l.spans) b.append(s.displayText);
    return b.toString();
  }

  @Test
  public void storiesKeepTablesAndPicturesWhileParagraphViewsAndMappingReachThem() {
    var f = PageFurniture.EMPTY.blocks("header", story());
    assertEquals(3, f.blocks("header").size());
    assertEquals(1, f.get("header").size());
    // Every paragraph, nested table cells included, in reading order.
    assertEquals(4, f.allParagraphs("header").size());
    var mapped = f.map(p -> p.with("align", "right"));
    var nested = (Table) ((Table) mapped.blocks("header").get(0)).blocks(0, 1).get(0);
    assertEquals("right", ((Paragraph) nested.blocks(0, 0).get(0)).align);
    // story() replaces the whole story.
    assertEquals(0, f.story("header", new Sequence<Paragraph>()).blocks("header").size());
    // Wordcraft: an even variant no section defines shows the default story.
    assertSame(f.blocks("header"), f.options(false, true, 18, 18).shown("evenHeader"));
  }

  @Test
  public void headerTablesWithPageFieldsAndPicturesLayOutAndPushTheBody() throws Exception {
    var d = twoPages(PageFurniture.EMPTY.blocks("header", story()));
    var result = engine().layout(d, 0);
    assertEquals(2, result.pages.size());
    for (int i = 0; i < 2; i++) {
      var p = result.pages.get(i);
      String shown = text(p.header);
      assertTrue(shown, shown.contains("Logo") && shown.contains("Page " + (i + 1)));
      assertTrue(shown.endsWith("Tail"));
      // Outer cells and the nested cell, painted but never hit-tested as body cells.
      assertEquals(3, p.furnitureBoxes.size());
      assertTrue(p.boxes.isEmpty());
      assertEquals(3, p.paintedBoxes().size());
      assertEquals(1, p.furniturePictures.size());
      var pic = p.furniturePictures.get(0);
      assertEquals((PageSpec.A4.width - 40) / 2, pic.x(), .5);
      float headerBottom = 0;
      for (LayoutEngine.Line l : p.header) headerBottom = Math.max(headerBottom, l.y + l.height);
      assertTrue(pic.y() > p.furnitureBoxes.get(0).y);
      assertTrue(p.lines.get(0).y >= headerBottom);
    }
  }

  @Test
  public void pageAnchoredHeaderFloatsSitAtTheirPageOffsetAndTakeNoHeaderSpace() {
    var look = Picture.Look.NONE.wrap("front", "").position("page,,100;page,,300");
    var logo = new Picture("i9", OdtTest.PNG, 40, 20, "", "left", look);
    var story =
        new Sequence<Block>()
            .append(new Paragraph("h1", RichText.of("Head")).with("after", "0"))
            .append(logo);
    var p = engine().layout(twoPages(PageFurniture.EMPTY.blocks("header", story)), 0).pages.get(1);
    assertEquals(1, p.furniturePictures.size());
    assertEquals(100, p.furniturePictures.get(0).x(), .01);
    assertEquals(300, p.furniturePictures.get(0).y(), .01);
    assertEquals(1, p.header.size());
  }

  /** As Wordcraft's layout_box: header text wraps per line beside a header float. */
  @Test
  public void headerTextWrapsAroundHeaderFloats() {
    // Paragraph-anchored at a column offset, and a column-aligned Square float.
    for (String position : new String[] {"column,,0;paragraph,,0", ""}) {
      var look = Picture.Look.NONE.wrap("square", "").position(position);
      var logo = new Picture("i9", OdtTest.PNG, 60, 40, "", "left", look);
      var story =
          new Sequence<Block>()
              .append(logo)
              .append(new Paragraph("h1", RichText.of("head ".repeat(60))).with("after", "0"));
      var p = engine().layout(twoPages(PageFurniture.EMPTY.blocks("header", story)), 0).pages.get(1);
      var pic = p.furniturePictures.get(0);
      boolean beside = false, below = false;
      for (LayoutEngine.Line l : p.header) {
        if (l.y < pic.y() + pic.height()) {
          assertTrue(position, l.x >= pic.x() + pic.width());
          beside = true;
        } else if (l.x < pic.x() + pic.width()) below = true;
      }
      assertTrue(position, beside);
      assertTrue(position, below);
    }
  }

  @Test
  public void docxRoundTripsHeaderTablesNestedTablesAndPictures() throws Exception {
    var d =
        twoPages(
            PageFurniture.EMPTY
                .blocks("header", story())
                .story(
                    "footer",
                    new Sequence<Paragraph>().append(new Paragraph("f1", RichText.of("Foot")))));
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var parts = ZipParts.read(new ByteArrayInputStream(out.toByteArray()));
    String header = null, rels = null;
    for (var e : parts.entrySet()) {
      if (e.getKey().startsWith("word/cn1-header"))
        header = new String(e.getValue(), "UTF-8");
      if (e.getKey().startsWith("word/_rels/cn1-header"))
        rels = new String(e.getValue(), "UTF-8");
    }
    assertNotNull(header);
    assertTrue(header, header.contains("<w:tbl>") && header.contains("<w:drawing>"));
    assertTrue(rels, rels != null && rels.contains("/image\""));
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    var blocks = back.furniture.blocks("header");
    assertTrue(blocks.get(0) instanceof Table);
    assertTrue(((Table) blocks.get(0)).nested());
    boolean picture = false;
    for (Block b : blocks) picture |= b instanceof Picture;
    assertTrue(picture);
    assertEquals("Foot", back.furniture.get("footer").get(0).text.plainText());
    var p = engine().layout(back, 0).pages.get(1);
    assertTrue(text(p.header).contains("Page 2"));
  }

  @Test
  public void missingReferencesInheritFromThePreviousSectionInDocx() throws Exception {
    var blocks =
        new Sequence<Block>()
            .append(new Paragraph("p1", RichText.of("One")))
            .append(new Paragraph("p2", RichText.of("Two")));
    var base = new Document(blocks, PageSpec.A4, "T", 10);
    var first =
        base.lastSection.furniture(
            PageFurniture.EMPTY.blocks("header", new Sequence<Block>().append(headerTable())));
    // The last section defines no header: it links to the first.
    var sections = new LinkedHashMap<String, Section>();
    sections.put("p1", first);
    var d = base.sections(sections, base.lastSection);
    var out = new ByteArrayOutputStream();
    Docx.write(d, out);
    var parts = ZipParts.read(new ByteArrayInputStream(out.toByteArray()));
    String xml = new String(parts.get("word/document.xml"), "UTF-8");
    assertEquals(xml.indexOf("headerReference"), xml.lastIndexOf("headerReference"));
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    var ranges = back.sectionRanges();
    assertEquals(2, ranges.size());
    assertFalse(ranges.get(1).raw.furniture.stories.containsKey("header"));
    assertTrue(ranges.get(1).resolved.furniture.blocks("header").get(0) instanceof Table);
    var pages = engine().layout(back, 0).pages;
    assertTrue(text(pages.get(pages.size() - 1).header).contains("Logo"));
  }

  @Test
  public void nativeFormatStoresBlocksAndReadsOlderOrNewerStories() throws Exception {
    var d = twoPages(PageFurniture.EMPTY.blocks("header", story()));
    var back = DocumentXml.read(DocumentXml.write(d));
    var blocks = back.furniture.blocks("header");
    assertEquals(3, blocks.size());
    assertTrue(blocks.get(0) instanceof Table && blocks.get(1) instanceof Picture);
    assertEquals(OdtTest.PNG.length, ((Picture) blocks.get(1)).originalBytes().length);
    // A version 1 file (paragraphs only) and an unknown future element both load.
    String xml =
        DocumentXml.write(d)
            .replace("<story key=\"header\">", "<story key=\"header\"><future x=\"1\"/>");
    assertEquals(3, DocumentXml.read(xml).furniture.blocks("header").size());
  }

  @Test
  public void pdfPaintsHeaderTablesAndPictures() throws Exception {
    var with = PdfExporter.write(twoPages(PageFurniture.EMPTY.blocks("header", story())));
    String pdf = new String(with, "ISO-8859-1");
    assertTrue(pdf.contains("/Subtype /Image") || pdf.contains("/Subtype/Image"));
  }
}
