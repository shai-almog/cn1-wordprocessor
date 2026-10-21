package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.Collections;
import org.junit.Test;

/** Text boxes holding a block story, as Wordcraft's text box parts. */
public class TextBoxStoryTest {
  static Document boxed() {
    var story = new Sequence<Block>()
        .append(new Paragraph("p10", RichText.of("Bold start").format(0, 4, "bold", "true")))
        .append(new Paragraph("p11", RichText.of("Second line")).with("align", "center"));
    var box = Picture.drawing("i5", Drawing.of("textBox").with("valign", "top").story(story), 200, 100, "", "left");
    var doc = new Document(
        new Sequence<Block>().append(new Paragraph("p1", RichText.of("Body"))).append(box)
            .append(new Paragraph("p2", RichText.of("After"))),
        PageSpec.A4, "T", 20);
    var c = new ReviewComment("c1", "Ann", "Note this", "p11", 0, "p11", 6, false);
    return doc.comments(Collections.singletonList(c));
  }

  static Sequence<Block> story(Document d) {
    return TextBoxes.all(d).get(0).drawing.story;
  }

  @Test
  public void storyMirrorsPlainText() {
    var d = boxed();
    assertEquals("Bold start\nSecond line", TextBoxes.all(d).get(0).drawing.get("text", ""));
    // Setting a different plain text through the single-string path drops the story.
    var flat = TextBoxes.all(d).get(0).drawing.with("text", "Other");
    assertNull(flat.story);
    assertNotNull(TextBoxes.all(d).get(0).drawing.with("fill", "ff0000").story);
  }

  @Test
  public void commentAnchorsInTextBoxStory() {
    var d = boxed();
    assertTrue(ReviewComment.validRange(d, "p11", 0, "p11", 6));
    assertEquals("i5", ReviewComment.textBox(ReviewComment.locate(d, "p11")));
    // A range may not run from the body into a text box.
    assertFalse(ReviewComment.validRange(d, "p1", 0, "p11", 2));
  }

  @Test
  public void docxRoundTripKeepsParagraphsRunsAndComments() throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(boxed(), out);
    String xml = new String(DrawingCommandsTest.part(out.toByteArray(), "word/document.xml"), "UTF-8");
    assertTrue(xml, xml.contains("<w:txbxContent>"));
    assertTrue(xml.contains("commentRangeStart"));
    var back = Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document;
    var s = story(back);
    assertEquals(2, s.size());
    var first = (Paragraph) s.get(0);
    assertEquals("Bold start", first.text.toString());
    assertTrue(first.text.styleAt(0).bold);
    assertFalse(first.text.styleAt(6).bold);
    assertEquals("center", ((Paragraph) s.get(1)).align);
    assertEquals(1, back.comments.size());
    var c = back.comments.get(0);
    assertEquals(s.get(1).id(), c.startId);
    assertEquals(6, c.end);
    // Body paragraphs are untouched by the box's text.
    assertEquals("Body", ((Paragraph) back.blocks.get(0)).text.toString());
  }

  @Test
  public void nativeRoundTripAndOldSingleStringFiles() throws Exception {
    var back = DocumentXml.read(DocumentXml.write(boxed()));
    var s = story(back);
    assertEquals("p11", s.get(1).id());
    assertTrue(((Paragraph) s.get(0)).text.styleAt(0).bold);
    assertEquals(1, back.comments.size());
    var pic = TextBoxes.all(back).get(0);
    assertEquals(200, pic.width, .01f);
    // An older file: a text box whose text is one string still reads (and shows its text).
    var old = new Document(new Sequence<Block>().append(new Paragraph("p1", new RichText()))
        .append(Picture.drawing("i2", Drawing.of("textBox").with("text", "One\nTwo"), 100, 50, "", "left")),
        PageSpec.A4, "T", 5);
    var read = DocumentXml.read(DocumentXml.write(old));
    var d = ((Picture) read.blocks.get(1)).drawing;
    assertNull(d.story);
    assertEquals(2, d.storyOrText("x").size());
  }

  @Test
  public void wordTextBoxWithTwoParagraphsReads() throws Exception {
    String wsp = "<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr/><wps:txbx><w:txbxContent>"
        + "<w:p><w:r><w:rPr><w:i/></w:rPr><w:t>One</w:t></w:r></w:p><w:p><w:r><w:t>Two</w:t></w:r></w:p>"
        + "</w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp>";
    String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    String xml = "<?xml version=\"1.0\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/2006/xmlPackage\">"
        + "<pkg:part pkg:name=\"/word/document.xml\"><pkg:xmlData><w:document xmlns:w=\"" + w + "\""
        + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
        + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"1828800\" cy=\"914400\"/><wp:docPr id=\"1\" name=\"b\"/>"
        + "<a:graphic><a:graphicData>" + wsp + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "</w:body></w:document></pkg:xmlData></pkg:part></pkg:package>";
    var back = Docx.read(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8"))).document;
    var s = story(back);
    assertEquals(2, s.size());
    assertTrue(((Paragraph) s.get(0)).text.styleAt(0).italic);
    assertEquals("Two", ((Paragraph) s.get(1)).text.toString());
  }

  @Test
  public void storyIsLaidOutInsideTheBox() throws Exception {
    var d = boxed();
    var r = new LayoutEngine(new PdfMetrics()).layout(d, 0);
    var page = r.pages.get(0);
    LayoutEngine.PlacedPicture box = null;
    for (LayoutEngine.PlacedPicture p : page.pictures) if (p.picture().id().equals("i5")) box = p;
    assertNotNull(box);
    var lines = page.textBoxLines("i5");
    assertEquals(2, lines.size());
    assertEquals("p10", lines.get(0).paragraphId);
    assertEquals("p11", lines.get(1).paragraphId);
    // Top-aligned inside the 7.2pt internal margin; the centered line sits mid-box.
    assertEquals(box.y() + 7.2f, lines.get(0).y, .5f);
    assertTrue(lines.get(0).x >= box.x() + 7.1f);
    float mid = lines.get(1).x + lines.get(1).width / 2;
    assertEquals(box.x() + box.width() / 2, mid, 2f);
    assertTrue(lines.get(1).y > lines.get(0).y);
    // The bold run is its own span, styled from the story.
    assertTrue(lines.get(0).spans.get(0).style.bold);
    // Body lines are unaffected.
    for (LayoutEngine.Line l : page.lines) assertNull(l.textBox);
    // PDF writes the story text over the box, not the drawing's flat text.
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("re W n"));
  }

  @Test
  public void editingInsideTextBoxWithUndoAndComments() {
    var s = new Session(boxed());
    s.enterTextBox("i5");
    assertEquals("i5", s.textBoxStory());
    assertFalse(s.inCell());
    assertEquals(2, s.editingDocument().blocks.size());
    s.select(1, 6, 6);
    s.typeText("X", 0);
    s.enterParagraph();
    s.typeText("New", 0);
    var story = story(s.document());
    assertEquals(3, story.size());
    assertEquals("SecondX", ((Paragraph) story.get(1)).text.toString());
    assertEquals("New line", ((Paragraph) story.get(2)).text.toString());
    // The body is untouched; the box text property mirrors the story.
    assertEquals("Body", s.document().paragraph(0).text.toString());
    assertEquals("Bold start\nSecondX\nNew line", TextBoxes.all(s.document()).get(0).drawing.get("text", ""));
    // The comment in the box still anchors its paragraph.
    assertTrue(ReviewComment.validRange(s.document(), "p11", 0, "p11", 6));
    s.select(0, 0, 4);
    s.addComment("Bo", "On bold");
    assertEquals(2, s.document().comments.size());
    s.leaveCell();
    assertNull(s.textBoxStory());
    var added = s.document().comments.get(1);
    assertEquals("p10", added.startId);
    // Selecting the comment enters the box with its range selected.
    s.selectComment(added.id);
    assertEquals("i5", s.textBoxStory());
    assertEquals(0, s.selection().start());
    assertEquals(4, s.selection().end());
    s.leaveCell();
    s.undo();
    assertEquals(1, s.document().comments.size());
    s.undo();
    s.undo();
    s.undo();
    assertEquals("Second line", ((Paragraph) story(s.document()).get(1)).text.toString());
  }

  @Test
  public void singleStringBoxBecomesStoryWhenEdited() {
    var doc = new Document(new Sequence<Block>().append(new Paragraph("p1", new RichText()))
        .append(Picture.drawing("i2", Drawing.of("textBox").with("text", "One\nTwo"), 100, 50, "", "left")),
        PageSpec.A4, "T", 5);
    var s = new Session(doc);
    s.enterTextBox("i2");
    s.leaveCell();
    assertNull(((Picture) s.document().blocks.get(1)).drawing.story);
    s.enterTextBox("i2");
    s.select(1, 3, 3);
    s.typeText("!", 0);
    s.leaveCell();
    var d = ((Picture) s.document().blocks.get(1)).drawing;
    assertNotNull(d.story);
    assertEquals("One\nTwo!", d.get("text", ""));
    // The layout shows the edited story inside the box.
    var r = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0);
    assertEquals(2, r.pages.get(0).textBoxLines("i2").size());
  }

  static Document boxedTable() {
    var row = java.util.Arrays.asList(
        Sequence.<Paragraph>of(Collections.singletonList(new Paragraph("p20", RichText.of("A1")))),
        Sequence.<Paragraph>of(Collections.singletonList(new Paragraph("p21", RichText.of("B1")))));
    var table = new Table("t1", Collections.singletonList(row));
    var story = new Sequence<Block>().append(new Paragraph("p10", RichText.of("Head"))).append(table)
        .append(new Paragraph("p12", RichText.of("Tail")));
    var box = Picture.drawing("i5", Drawing.of("textBox").with("valign", "top").story(story), 300, 150, "", "left");
    return new Document(new Sequence<Block>().append(new Paragraph("p1", RichText.of("Body"))).append(box),
        PageSpec.A4, "T", 30);
  }

  @Test
  public void cellsInsideTextBoxAreEntered() throws Exception {
    var d = boxedTable();
    var lines = new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.get(0).textBoxLines("i5");
    int[] cell = null;
    for (LayoutEngine.Line l : lines) {
      if (l.paragraphId.equals("p21")) cell = l.boxCell;
      if (l.paragraphId.equals("p10") || l.paragraphId.equals("p12")) assertNull(l.boxCell);
    }
    assertArrayEquals(new int[] {1, 0, 1}, cell);
    var s = new Session(d);
    s.enterTextBoxCell("i5", cell);
    assertEquals("i5", s.enclosingTextBox());
    assertNull(s.textBoxStory());
    assertArrayEquals(cell, s.cellPath());
    s.select(0, 2, 2);
    s.typeText("x", 0);
    s.leaveCell();
    var t = (Table) story(s.document()).get(1);
    assertEquals("B1x", t.paragraph(0, 1, LayoutEngine.NO_PATH, "p21").text.toString());
    s.undo();
    t = (Table) story(s.document()).get(1);
    assertEquals("B1", t.paragraph(0, 1, LayoutEngine.NO_PATH, "p21").text.toString());
  }

  static Document turnedBox(String direction, int rotation) {
    var story = new Sequence<Block>().append(new Paragraph("p10", RichText.of("Turn me")))
        .append(new Paragraph("p11", RichText.of("Two")));
    var drawing = Drawing.of("textBox").with("valign", "top").with("textDirection", direction)
        .with("rotation", String.valueOf(rotation)).story(story);
    return new Document(new Sequence<Block>().append(new Paragraph("p1", RichText.of("Body")))
        .append(Picture.drawing("i5", drawing, 80, 160, "", "left")), PageSpec.A4, "T", 30);
  }

  static LayoutEngine.PlacedPicture placed(LayoutEngine.Page page) {
    for (LayoutEngine.PlacedPicture p : page.pictures) if (p.picture().id().equals("i5")) return p;
    throw new AssertionError("no box");
  }

  @Test
  public void verticalBoxStoryRunsDownFromTheRightEdge() throws Exception {
    var d = turnedBox("rotate90", 0);
    var page = new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.get(0);
    var box = placed(page);
    var lines = page.textBoxLines("i5");
    assertEquals(2, lines.size());
    var first = lines.get(0);
    assertEquals(1, first.turn);
    // Lines run along the 160pt height (inside the 7.2pt margins), stacking from the right edge.
    assertEquals(box.x() + box.width() - 7.2f, first.turnX, .5f);
    assertEquals(box.y() + 7.2f, first.turnY, .5f);
    float[] b = first.pageBounds();
    assertTrue(b[3] > b[2]);
    assertTrue(b[0] + b[2] <= box.x() + box.width() - 7f);
    assertTrue(lines.get(1).pageBounds()[0] < b[0]);
    // A point in the first line's page bounds maps back into its frame.
    float[] f = first.toFrame(b[0] + b[2] / 2, b[1] + 2);
    assertTrue(f[0] >= first.x - .1f && f[1] >= first.y - .1f && f[1] <= first.y + first.height + .1f);
    // Upward text starts from the bottom-left.
    var up = new LayoutEngine(new PdfMetrics()).layout(turnedBox("rotate270", 0), 0).pages.get(0);
    var u = up.textBoxLines("i5").get(0);
    assertEquals(2, u.turn);
    assertEquals(placed(up).y() + 160 - 7.2f, u.turnY, .5f);
    // The PDF turns those lines rather than drawing the drawing's own text.
    String pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("q 0 -1 1 0 "));
  }

  @Test
  public void rotatedBoxStoryTurnsWithTheBoxAndStaysEditable() throws Exception {
    var d = turnedBox("horizontal", 30);
    var page = new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.get(0);
    var box = placed(page);
    var first = page.textBoxLines("i5").get(0);
    assertEquals(3, first.turn);
    assertEquals(30, first.angle(), .01f);
    // The frame's origin is the text area's top-left corner turned about the box centre.
    double r = Math.toRadians(30);
    float dx = 7.2f - box.width() / 2, dy = 7.2f - box.height() / 2;
    assertEquals(box.x() + box.width() / 2 + dx * Math.cos(r) - dy * Math.sin(r), first.turnX, .5f);
    assertEquals(box.y() + box.height() / 2 + dx * Math.sin(r) + dy * Math.cos(r), first.turnY, .5f);
    float[] p = first.toPage(first.x + 3, first.y + 4);
    float[] back = first.toFrame(p[0], p[1]);
    assertEquals(first.x + 3, back[0], .01f);
    assertEquals(first.y + 4, back[1], .01f);
    // Editing the story reaches the turned lines' paragraphs.
    var s = new Session(d);
    s.enterTextBox("i5");
    s.select(0, 0, 0);
    s.typeText("X", 0);
    s.leaveCell();
    assertEquals("XTurn me", ((Paragraph) story(s.document()).get(0)).text.toString());
    var again = new LayoutEngine(new PdfMetrics()).layout(s.document(), 0).pages.get(0);
    assertEquals(3, again.textBoxLines("i5").get(0).turn);
    assertTrue(new String(PdfExporter.write(d), "ISO-8859-1").contains(" cm"));
  }

  @Test
  public void stackedBoxStoryIsOneLetterToALine() throws Exception {
    var page = new LayoutEngine(new PdfMetrics()).layout(turnedBox("stacked", 0), 0).pages.get(0);
    var lines = page.textBoxLines("i5");
    int letters = 0;
    for (LayoutEngine.Line l : lines) {
      assertEquals(0, l.turn);
      assertTrue(l.end - l.start <= 2);
      if (l.paragraphId.equals("p11")) letters++;
    }
    assertEquals(3, letters);
  }
}
