package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

/** Pictures and drawings inside table cells, as Wordcraft's cells hold them (inline and floating). */
public class CellPictureTest {
  private static Document doc() {
    return doc(Picture.drawing("c1", Drawing.of("shape").with("shape", "ellipse"), 60, 30, "", "left"));
  }

  private static Document doc(Picture inline) {
    Picture floating =
        Picture.drawing(
            "c2",
            Drawing.of("shape").with("wrap", "square").with("position", "column,,0;paragraph,,0"),
            60,
            60,
            "",
            "left");
    var cell =
        new Sequence<Block>()
            .append(new Paragraph("a", RichText.of("top")))
            .append(inline)
            .append(floating)
            .append(new Paragraph("b", RichText.of("word ".repeat(80))));
    var rows = new ArrayList<List<Sequence<Block>>>();
    rows.add(new ArrayList<>(List.of(cell)));
    Table t = Table.of("t", rows, new ArrayList<Table.Merge>());
    return new Document(
        new Sequence<Block>().append(t).append(new Paragraph("z", new RichText())),
        new PageSpec(400, 600, 20, 20, 20, 20),
        "C",
        9);
  }

  private static LayoutEngine engine() {
    return new LayoutEngine(
        new LayoutEngine.Metrics() {
          public float width(String text, TextStyle style) {
            return text.length() * 6;
          }

          public float height(TextStyle style) {
            return 12;
          }
        });
  }

  private static List<Picture> pictures(Document d) {
    var out = new ArrayList<Picture>();
    for (Block b : ((Table) d.blocks.get(0)).blocks(0, 0)) if (b instanceof Picture p) out.add(p);
    return out;
  }

  @Test
  public void cellPicturesFlowInlineAndFloatsWrapCellText() {
    var page = engine().layout(doc(), 0).pages.get(0);
    var placed = new ArrayList<LayoutEngine.PlacedPicture>();
    for (LayoutEngine.PlacedPicture p : page.pictures) if (p.block() < 0) placed.add(p);
    assertEquals(2, placed.size());
    var inline = placed.get(0);
    var floating = placed.get(1);
    assertEquals(inline.y() + inline.height(), floating.y(), .01f);
    LayoutEngine.Line beside = null, below = null;
    for (LayoutEngine.Line l : page.lines) {
      if (l.block != 0 || l.start == 0 && l.end <= 3) continue;
      if (beside == null) beside = l;
      if (l.y >= floating.y() + floating.height()) {
        below = l;
        break;
      }
    }
    assertNotNull(beside);
    assertNotNull(below);
    // Text beside the float starts right of it; below it, back at the cell's left.
    assertTrue(beside.x >= floating.x() + floating.width());
    assertEquals(inline.x(), below.x, .01f);
    assertTrue(beside.y >= inline.y() + inline.height() - .01f);
  }

  @Test
  public void cellPicturesRoundTripThroughDocxNativeOdtAndHtml() throws Exception {
    var out = new ByteArrayOutputStream();
    Docx.write(doc(), out);
    var back = Docx.read(new ByteArrayInputStream(out.toByteArray())).document;
    var pics = pictures(back);
    assertEquals(2, pics.size());
    assertEquals("inline", pics.get(0).wrap());
    assertEquals("ellipse", pics.get(0).drawing.get("shape", ""));
    assertEquals("square", pics.get(1).wrap());

    var nativeBack = DocumentXml.read(DocumentXml.write(doc()));
    assertEquals(2, pictures(nativeBack).size());

    // ODT carries raster pictures (draw:frame/draw:image) in cells, as in the body.
    var odt = new ByteArrayOutputStream();
    Odt.write(doc(new Picture("c1", OdtTest.PNG, 30, 30, "logo", "left")), odt);
    var odtPics = pictures(Odt.read(new ByteArrayInputStream(odt.toByteArray())).document);
    assertFalse(odtPics.isEmpty());
    assertNull(odtPics.get(0).drawing);

    String html = TextFormats.html(doc());
    int td = html.indexOf("<td");
    assertTrue(html, td >= 0 && html.indexOf("<svg", td) > td && html.indexOf("<svg", td) < html.indexOf("</td>", td));
  }

  @Test
  public void pdfPaintsCellPictures() throws Exception {
    Document with = doc();
    Table t = (Table) with.blocks.get(0);
    var text = new Sequence<Block>();
    for (Block b : t.blocks(0, 0)) if (b instanceof Paragraph) text = text.append(b);
    Document without = with.blocks(with.blocks.set(0, t.setBlocks(0, 0, text)), with.nextId);
    assertTrue(PdfExporter.write(with).length > PdfExporter.write(without).length);
  }
}
