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

  @Test
  public void linkedBoxesFlowOneStoryAndEditItFromAnyBox() throws Exception {
    var story = new Sequence<Block>();
    for (int i = 0; i < 8; i++) story = story.append(new Paragraph("p" + (40 + i), RichText.of("Line " + i)));
    var head = Picture.drawing("i5", Drawing.of("textBox").with("valign", "top").with("linkNext", "i6").story(story), 120, 60, "", "left");
    var tail = Picture.drawing("i6", Drawing.of("textBox").with("valign", "top"), 120, 400, "", "left");
    var d = new Document(new Sequence<Block>().append(head).append(new Paragraph("p1", RichText.of("Body")))
        .append(tail), PageSpec.A4, "T", 60);
    var page = new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.get(0);
    var first = page.textBoxLines("i5");
    var rest = page.textBoxLines("i6");
    assertTrue(first.size() >= 1 && first.size() < 8);
    assertEquals(8, first.size() + rest.size());
    assertEquals("p40", first.get(0).paragraphId);
    assertEquals("p" + (40 + first.size()), rest.get(0).paragraphId);
    // The continued text starts at the top of the second box.
    LayoutEngine.PlacedPicture second = null;
    for (LayoutEngine.PlacedPicture p : page.pictures) if (p.picture().id().equals("i6")) second = p;
    assertEquals(second.y() + 7.2f, rest.get(0).y, .5f);
    for (LayoutEngine.Line l : rest) assertEquals("i5", l.boxStory);
    // Entering the second box edits the first box's story.
    var s = new Session(d);
    s.enterTextBox("i6");
    assertEquals("i5", s.textBoxStory());
    s.select(7, 6, 6);
    s.typeText("!", 0);
    s.leaveCell();
    assertEquals("Line 7!", ((Paragraph) TextBoxes.find(s.document(), "i5").drawing.story.get(7)).text.toString());
    assertNull(TextBoxes.find(s.document(), "i6").drawing.story);
    assertEquals(java.util.Arrays.asList("i5", "i6"), TextBoxes.chains(s.document()).get(0));
  }

  @Test
  public void overflowingStoryIsCutAtALineAsWordcraftFitsABox() throws Exception {
    var story = new Sequence<Block>();
    for (int i = 0; i < 8; i++) story = story.append(new Paragraph("p" + (40 + i), RichText.of("Line " + i)));
    for (String valign : new String[] {"top", "middle", "bottom"}) {
      var box = Picture.drawing("i5", Drawing.of("textBox").with("valign", valign).story(story), 120, 50, "", "left");
      var d = new Document(new Sequence<Block>().append(box).append(new Paragraph("p1", RichText.of("Body"))), PageSpec.A4, "T", 60);
      var page = new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.get(0);
      LayoutEngine.PlacedPicture pic = null;
      for (LayoutEngine.PlacedPicture p : page.pictures) if (p.picture().id().equals("i5")) pic = p;
      var lines = page.textBoxLines("i5");
      assertTrue(lines.size() >= 1 && lines.size() < 8);
      // Whole lines only: every kept line ends inside the text area (inset 3.6pt top and bottom).
      for (LayoutEngine.Line l : lines) assertTrue(valign, l.y + l.height <= pic.y() + 50 - 3.6f + .5f);
      assertTrue(lines.get(0).y >= pic.y() + 3.6f - .5f);
      assertEquals("p40", lines.get(0).paragraphId);
    }
  }

  @Test
  public void linkedBoxesRoundTripAsWordLinksTheirTextBoxes() throws Exception {
    var story = new Sequence<Block>().append(new Paragraph("p40", RichText.of("Flows on")));
    var a = Picture.drawing("i5", Drawing.of("textBox").with("linkNext", "i6").story(story), 120, 60, "", "left");
    var b = Picture.drawing("i6", Drawing.of("textBox").with("linkNext", "i7"), 120, 60, "", "left");
    var c = Picture.drawing("i7", Drawing.of("textBox"), 120, 60, "", "left");
    var d = new Document(new Sequence<Block>().append(a).append(new Paragraph("p1", RichText.of("Body"))).append(b).append(c),
        PageSpec.A4, "T", 60);
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(d, out);
    String xml = entry(out.toByteArray(), "word/document.xml");
    assertTrue(xml.contains("<wps:txbx id=\"1\">"));
    assertTrue(xml.contains("<wps:linkedTxbx id=\"1\" seq=\"1\"/>") && xml.contains("<wps:linkedTxbx id=\"1\" seq=\"2\"/>"));
    var back = Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document;
    var chains = TextBoxes.chains(back);
    assertEquals(1, chains.size());
    assertEquals(3, chains.get(0).size());
    assertEquals("Flows on", Drawing.plainText(TextBoxes.story(back, chains.get(0).get(0))));
    assertTrue(TextBoxes.find(back, chains.get(0).get(0)).drawing.get("txbxId", "").isEmpty());
  }

  @Test
  public void hostileWordLinksReadWithoutChainsRunningWild() throws Exception {
    String head = wsp("<wps:txbx id=\"7\"><w:txbxContent><w:p><w:r><w:t>Text</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr/>");
    String body = head + wsp("<wps:linkedTxbx id=\"7\" seq=\"1\"/><wps:bodyPr/>") + wsp("<wps:linkedTxbx id=\"7\" seq=\"1\"/><wps:bodyPr/>")
        + wsp("<wps:linkedTxbx id=\"9\" seq=\"1\"/><wps:bodyPr/>") + wsp("<wps:linkedTxbx id=\"7\" seq=\"-3\"/><wps:bodyPr/>");
    String xml = "<?xml version=\"1.0\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/2006/xmlPackage\">"
        + "<pkg:part pkg:name=\"/word/document.xml\"><pkg:xmlData><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
        + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><w:body>" + body
        + "</w:body></w:document></pkg:xmlData></pkg:part></pkg:package>";
    var d = Docx.read(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8"))).document;
    var boxes = new java.util.ArrayList<String>();
    for (Block blk : d.blocks) if (blk instanceof Picture p && p.drawing != null) boxes.add(p.id());
    assertEquals(5, boxes.size());
    assertEquals(java.util.Collections.singletonList(java.util.Arrays.asList(boxes.get(0), boxes.get(1))), TextBoxes.chains(d));
  }

  static String wsp(String inner) {
    return "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"1828800\" cy=\"914400\"/><wp:docPr id=\"1\" name=\"Box\"/><a:graphic>"
        + "<a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:cNvSpPr txBox=\"1\"/>"
        + "<wps:spPr><a:prstGeom prst=\"rect\"/></wps:spPr>" + inner + "</wps:wsp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
  }

  static String entry(byte[] zip, String name) throws Exception {
    try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (var e = in.getNextEntry(); e != null; e = in.getNextEntry())
        if (e.getName().equals(name)) return new String(in.readAllBytes(), "UTF-8");
    }
    throw new AssertionError(name);
  }

  static Document groupWithMemberText() throws Exception {
    String member = "<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:xfrm><a:off x=\"914400\" y=\"0\"/>"
        + "<a:ext cx=\"914400\" cy=\"914400\"/></a:xfrm><a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent>"
        + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Member</w:t></w:r><w:r><w:t> text</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>More</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr anchor=\"t\"/></wps:wsp>";
    String plain = "<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"914400\" cy=\"914400\"/>"
        + "</a:xfrm><a:prstGeom prst=\"ellipse\"/></wps:spPr><wps:bodyPr/></wps:wsp>";
    String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    String xml = "<?xml version=\"1.0\"?><pkg:package xmlns:pkg=\"http://schemas.microsoft.com/office/2006/xmlPackage\">"
        + "<pkg:part pkg:name=\"/word/document.xml\"><pkg:xmlData><w:document xmlns:w=\"" + w + "\""
        + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
        + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        + " xmlns:wpg=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\""
        + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"1828800\" cy=\"914400\"/><wp:docPr id=\"1\" name=\"g\"/>"
        + "<a:graphic><a:graphicData><wpg:wgp><wpg:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1828800\" cy=\"914400\"/>"
        + "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"1828800\" cy=\"914400\"/></a:xfrm></wpg:grpSpPr>" + plain + member
        + "</wpg:wgp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "</w:body></w:document></pkg:xmlData></pkg:part></pkg:package>";
    return Docx.read(new java.io.ByteArrayInputStream(xml.getBytes("UTF-8"))).document;
  }

  static Picture group(Document d) {
    for (Block b : d.blocks) if (b instanceof Picture p && p.drawing != null && p.drawing.flag("group")) return p;
    throw new AssertionError("no group");
  }

  @Test
  public void groupMemberTextIsAStoryLaidOutEditedAndSaved() throws Exception {
    var d = groupWithMemberText();
    var g = group(d);
    assertEquals(java.util.Collections.singleton(1), g.drawing.storyMembers());
    var story = g.drawing.memberStory(1);
    assertEquals(2, story.size());
    assertTrue(((Paragraph) story.get(0)).text.styleAt(0).bold);
    assertFalse(((Paragraph) story.get(0)).text.styleAt(7).bold);
    // The member's text is not drawn as a string; the layout places its story in the member.
    assertTrue(DrawingOps.build(g.drawing, 144, 72).texts.isEmpty());
    var page = new LayoutEngine(new PdfMetrics()).layout(d, 0).pages.get(0);
    LayoutEngine.PlacedPicture placed = null;
    for (LayoutEngine.PlacedPicture p : page.pictures) if (p.picture() == g) placed = p;
    var lines = page.textBoxLines(g.id());
    assertTrue(lines.size() >= 2);
    String id = TextBoxes.memberId(g.id(), 1);
    assertEquals(id, lines.get(0).textBox);
    assertEquals(placed.x() + 72 + 7.2f, lines.get(0).x, .5f);
    assertEquals(placed.y() + 7.2f, lines.get(0).y, .5f);
    // Edited in place as a story of its own.
    var s = new Session(d);
    s.enterTextBox(id);
    assertEquals(id, s.textBoxStory());
    s.select(1, 4, 4);
    s.typeText("!", 0);
    s.leaveCell();
    var edited = group(s.document()).drawing;
    assertEquals("More!", ((Paragraph) edited.memberStory(1).get(1)).text.toString());
    // Saved and read back in DOCX and the native format.
    var out = new java.io.ByteArrayOutputStream();
    Docx.write(s.document(), out);
    var back = group(Docx.read(new java.io.ByteArrayInputStream(out.toByteArray())).document).drawing;
    assertEquals("More!", ((Paragraph) back.memberStory(1).get(1)).text.toString());
    assertTrue(((Paragraph) back.memberStory(1).get(0)).text.styleAt(0).bold);
    var nat = group(DocumentXml.read(DocumentXml.write(s.document()))).drawing;
    assertEquals("More!", ((Paragraph) nat.memberStory(1).get(1)).text.toString());
  }
}
