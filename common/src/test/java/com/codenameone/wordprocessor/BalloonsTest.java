package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** Markup balloons, as Wordcraft's crates/layout/src/balloons.rs. */
public class BalloonsTest {
  private static final LayoutEngine.Metrics METRICS =
      new LayoutEngine.Metrics() {
        public float width(String s, TextStyle t) {
          return s.length() * t.size / 2;
        }

        public float height(TextStyle t) {
          return t.size;
        }
      };

  private static Document commented() {
    var s = new Session(Document.empty());
    s.replace("alpha beta gamma");
    s.select(0, 6, 10);
    s.addComment("Ada", "Root comment that is long enough to wrap across more than one balloon line");
    var id = s.document().comments.get(0).id;
    s.replyComment(id, "Grace", "Reply");
    return s.document();
  }

  @Test
  public void threadSitsInTheMarkupAreaBesideItsAnchor() {
    var d = commented();
    var r = new LayoutEngine(METRICS).layout(d, 0);
    List<List<Balloons.Card>> pages = Balloons.layout(d, r, METRICS);
    assertEquals(1, pages.get(0).size());
    var card = pages.get(0).get(0);
    var spec = r.pages.get(0).spec;
    assertEquals(spec.width + 9, card.x, .01f);
    assertEquals(216 - 18, card.width, .01f);
    // Near the anchor: Wordcraft puts the card 10pt above the anchor's line foot.
    assertEquals(Math.max(12, card.anchorY - 10), card.top, .01f);
    assertTrue(card.anchorX > spec.left);
    // Author names in the author colours, the reply under a hairline.
    int hairlines = 0, names = 0, text = 0;
    for (Balloons.Item it : card.items) {
      if (it.text == null) hairlines++;
      else if (it.bold) names++;
      else text++;
    }
    assertEquals(1, hairlines);
    assertEquals(2, names);
    assertTrue(text >= 3);
    assertEquals(Balloons.COLORS[0], card.color);
  }

  @Test
  public void crowdedPagesClipAndLeaveOutCards() {
    var s = new Session(Document.empty());
    s.replace("x");
    for (int i = 0; i < 60; i++) {
      s.select(0, 0, 1);
      s.addComment("A" + i, "Comment " + i + " with several words of text to make a tall card here");
    }
    var d = s.document();
    var r = new LayoutEngine(METRICS).layout(d, 0);
    var cards = Balloons.layout(d, r, METRICS).get(0);
    assertTrue(cards.size() < 60);
    float h = r.pages.get(0).spec.height;
    for (Balloons.Card c : cards) assertTrue(c.top + c.height <= h - 12 + .5f);
    for (int i = 1; i < cards.size(); i++)
      assertTrue(cards.get(i).top >= cards.get(i - 1).top + cards.get(i - 1).height + 6 - .01f);
  }

  @Test
  public void resolvedCommentsAndInlineModeHaveNoBalloons() {
    var d = commented();
    var r = new LayoutEngine(METRICS).layout(d, 0);
    var resolved = d.comments(java.util.Collections.singletonList(d.comments.get(0).resolved(true)));
    assertTrue(Balloons.layout(resolved, r, METRICS).get(0).isEmpty());
    assertTrue(Balloons.MODES.contains(Balloons.mode()));
  }

  @Test
  public void pdfPrintsBalloonsScaledToThePaper() throws Exception {
    String pdf = new String(PdfExporter.write(commented()), "ISO-8859-1");
    float w = Document.empty().page.width, s = w / (w + 216f);
    assertTrue(pdf.contains("q " + PdfExporter.n(s) + " 0 0 " + PdfExporter.n(s)));
    assertTrue(pdf.contains("(Ada) Tj"));
    assertTrue(pdf.contains("(Grace) Tj"));
    String plain = new String(PdfExporter.write(new Session(Document.empty()).document()), "ISO-8859-1");
    assertFalse(plain.contains(" 0 0 " + PdfExporter.n(s)));
  }

  @Test
  public void cardTextKeepsEachCommentsOwnFormatting() {
    var d = commented();
    var c = d.comments.get(0);
    var rich = RichText.of("plain ", TextStyle.DEFAULT).concat(RichText.of("bold", TextStyle.DEFAULT.with("bold", "true").with("size", "14")));
    d = d.comments(java.util.Collections.singletonList(c.body(java.util.Collections.singletonList(new Paragraph("cp", rich)))));
    var r = new LayoutEngine(METRICS).layout(d, 0);
    var card = Balloons.layout(d, r, METRICS).get(0).get(0);
    Balloons.Item bold = null, plain = null;
    for (Balloons.Item it : card.items) {
      if ("bold".equals(it.text)) bold = it;
      if ("plain ".equals(it.text)) plain = it;
    }
    assertNotNull(bold);
    assertNotNull(plain);
    assertTrue(bold.style.bold);
    assertEquals(14, bold.size, .01f);
    assertEquals(9, plain.size, .01f);
    assertEquals(plain.y, bold.y, .01f);
    assertTrue(bold.x > plain.x);
  }

  @Test
  public void cardTextIsLaidOutWithTheParagraphEngine() {
    // Wordcraft lays the comment part out with its paragraph engine: indents apply.
    var d = commented();
    var c = d.comments.get(0);
    var para = new Paragraph("cp", RichText.of("indented")).with("indent", "20");
    d = d.comments(java.util.Collections.singletonList(c.body(java.util.Collections.singletonList(para))));
    var r = new LayoutEngine(METRICS).layout(d, 0);
    var card = Balloons.layout(d, r, METRICS).get(0).get(0);
    Balloons.Item name = null, text = null;
    for (Balloons.Item it : card.items) {
      if ("Ada".equals(it.text)) name = it;
      if (it.text != null && it.text.startsWith("indented")) text = it;
    }
    assertNotNull(text);
    assertEquals(name.x + 20, text.x, .01f);
  }

  @Test
  public void revisionsModeTakesDeletionsOutOfTheLayout() {
    var s = new Session(Document.empty());
    s.replace("keep gone stay");
    s.trackChanges(true);
    s.select(0, 5, 10);
    s.deleteBackward();
    var d = s.document();
    var engine = new LayoutEngine(METRICS);
    float inline = engine.layout(d, 0).pages.get(0).lines.get(0).width;
    engine.setHideDeleted(true);
    var r = engine.layout(d, 1);
    float hidden = r.pages.get(0).lines.get(0).width;
    assertEquals(inline - METRICS.width("gone ", TextStyle.DEFAULT), hidden, .5f);
    boolean deleted = false;
    for (Balloons.Card c : Balloons.layout(d, r, METRICS, "revisions").get(0))
      for (Balloons.Item it : c.items) deleted |= it.text != null && it.text.startsWith("Deleted: gone");
    assertTrue(deleted);
  }

  @Test
  public void revisionsModeKeepsHeaderDeletionsInline() {
    // Wordcraft's hides_deleted: only StoryRef::Body loses its deletions in balloon mode.
    var s = new Session(Document.empty());
    s.replace("keep gone stay");
    s.trackChanges(true);
    s.select(0, 5, 10);
    s.deleteBackward();
    var body = (Paragraph) s.document().blocks.get(0);
    var d =
        s.document()
            .furniture(
                PageFurniture.EMPTY.story(
                    "header", new Sequence<Paragraph>().append(new Paragraph("hd", body.text))));
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var page = engine.layout(d, 0).pages.get(0);
    float full = METRICS.width("keep gone stay", TextStyle.DEFAULT);
    assertEquals(full, page.header.get(0).width, .5f);
    assertEquals(full - METRICS.width("gone ", TextStyle.DEFAULT), page.lines.get(0).width, .5f);
  }

  @Test
  public void headerDeletionsWithSpacingAndSmallCapsPaintAsLaidOut() {
    // The per-character painting path (character spacing, small caps) runs after layout with the
    // body's deletions-in-balloons switch on; a header deletion keeps its text and width there too.
    var s = new Session(Document.empty());
    s.replace("keep gone stay");
    s.select(0, 0, 14);
    s.format("smallCaps", "true");
    s.format("characterSpacing", "1.5");
    s.trackChanges(true);
    s.select(0, 5, 10);
    s.deleteBackward();
    var body = (Paragraph) s.document().blocks.get(0);
    var d =
        s.document()
            .furniture(
                PageFurniture.EMPTY.story(
                    "header", new Sequence<Paragraph>().append(new Paragraph("hd", body.text))));
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var page = engine.layout(d, 0).pages.get(0);
    LayoutEngine.Span deleted = null;
    for (LayoutEngine.Span span : page.header.get(0).spans)
      if (span.style.revision != null && !span.style.revision.inserted()) deleted = span;
    assertNotNull(deleted);
    assertTrue(deleted.width > 0);
    var painted = CharacterMetrics.painted(deleted);
    assertEquals(deleted.width, CharacterMetrics.width(METRICS, deleted.rawDisplay, painted), .5f);
    assertEquals(deleted.rawDisplay.toUpperCase(), painted.displayText(deleted.rawDisplay));
    // A body deletion the layout took out stays out when painted.
    for (LayoutEngine.Span span : page.lines.get(0).spans)
      if (span.style.revision != null && !span.style.revision.inserted())
        assertEquals(0, CharacterMetrics.painted(METRICS, span.rawDisplay, span), .01f);
  }

  @Test
  public void deletedAndFormattedCardsUseTheParagraphEngine() {
    var s = new Session(Document.empty());
    String gone = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau";
    s.replace("keep " + gone + " stay");
    s.trackChanges(true);
    s.select(0, 5, 5 + gone.length());
    s.deleteBackward();
    var d = s.document();
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var r = engine.layout(d, 0);
    Balloons.Card card = null;
    for (Balloons.Card c : Balloons.layout(d, r, METRICS, "revisions").get(0)) card = c;
    assertNotNull(card);
    // The label is laid out by LayoutEngine.frameLines at the card width: several lines, each
    // item carrying its run style, matching a direct frame layout of the same paragraph.
    var text = new StringBuilder();
    java.util.Set<Float> rows = new java.util.TreeSet<Float>();
    for (Balloons.Item it : card.items)
      if (it.style != null) {
        text.append(it.text);
        rows.add(it.y);
      }
    assertTrue(rows.size() > 1);
    assertTrue(text.toString(), text.toString().replace(" ", "").startsWith("Deleted:alphabeta"));
    float inner = Balloons.MARKUP_AREA - 2 * Balloons.SIDE - Balloons.STRIPE - 2 * Balloons.PAD;
    var body = TextStyle.DEFAULT.with("size", String.valueOf(Balloons.SIZE));
    var lines = new LayoutEngine(METRICS).frameLines(d,
        new Sequence<Block>().append(new Paragraph("x", RichText.of("Deleted: " + gone + " ", body))), inner, null);
    assertEquals(lines.size(), rows.size());
  }

  @Test
  public void pdfLinkRectanglesScaleWithThePage() throws Exception {
    var s = new Session(Document.empty());
    s.replace("alpha beta");
    s.select(0, 0, 5);
    s.format("link", "https://example.com");
    s.select(0, 6, 10);
    s.addComment("Ada", "Note");
    String pdf = new String(PdfExporter.write(s.document()), "ISO-8859-1");
    float w = Document.empty().page.width, k = w / (w + 216f);
    int at = pdf.indexOf("/Subtype /Link /Rect [");
    assertTrue(at > 0);
    String[] rect = pdf.substring(at + 22, pdf.indexOf(']', at)).trim().split(" ");
    float x0 = Float.parseFloat(rect[0]), x1 = Float.parseFloat(rect[2]);
    float left = Document.empty().page.left;
    assertEquals(left * k, x0, 1f);
    assertTrue(x1 < w * k);
  }

  private static List<String> revisionLabels(Document d, float width) {
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var r = engine.layout(d, 0);
    var out = new java.util.ArrayList<String>();
    for (List<Balloons.Card> page : Balloons.layout(d, r, METRICS, "revisions"))
      for (Balloons.Card c : page) {
        var t = new StringBuilder();
        for (int k = 1; k < c.items.size(); k++) if (c.items.get(k).text != null) t.append(c.items.get(k).text);
        out.add(t.toString().replace(" ", ""));
      }
    return out;
  }

  @Test
  public void aDeletionAcrossLinesAndMarksIsOneTruncatedBalloon() {
    // Wordcraft review.rs deletion_balloons: one balloon per deletion, cut to
    // DELETION_TEXT_CHARS characters and an ellipsis.
    var s = new Session(Document.empty());
    var long_ = new StringBuilder();
    for (int i = 0; i < 400; i++) long_.append("word ");
    s.replace(long_ + "\nsecond paragraph");
    s.trackChanges(true);
    s.selectRange(0, 0, 1, 6);
    s.deleteBackward();
    var labels = revisionLabels(s.document(), 0);
    assertEquals(labels.toString(), 1, labels.size());
    String l = labels.get(0);
    assertTrue(l, l.startsWith("Deleted:wordword") && l.endsWith("…"));
  }

  @Test
  public void aMovesSourceHasAMovedFromBalloonOnly() {
    var s = new Session(Document.empty());
    s.replace("First one.\nSecond one.\nThird one.");
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(1, 0, 2, 0);
    String text = s.cut();
    s.select(0, 0, 0);
    s.pasteText(text);
    var labels = revisionLabels(s.document(), 0);
    assertTrue(labels.toString(), labels.contains("Movedfrom"));
    for (String l : labels) assertFalse(labels.toString(), l.startsWith("Deleted:"));
  }

  @Test
  public void anotherEnginesLayoutLeavesTheEditorsMeasurementAlone() {
    // The hidden/deleted switches belong to each layout: a PDF export laying the document out
    // without balloons must not change the editor's laid-out deletion or its measurement.
    var s = new Session(Document.empty());
    s.replace("keep gone stay");
    s.trackChanges(true);
    s.select(0, 5, 10);
    s.deleteBackward();
    var d = s.document();
    var editor = new LayoutEngine(METRICS);
    editor.setHideDeleted(true);
    var line = editor.layout(d, 0).pages.get(0).lines.get(0);
    new LayoutEngine(METRICS).layout(d, 0);
    LayoutEngine.Span deleted = null;
    for (LayoutEngine.Span span : line.spans)
      if (span.style.revision != null && !span.style.revision.inserted()) deleted = span;
    assertNotNull(deleted);
    assertEquals(0, deleted.width, 0f);
    assertEquals("", deleted.displayText);
    assertEquals(0, CharacterMetrics.painted(METRICS, deleted.rawDisplay, deleted), 0f);
    var again = editor.layout(d, 1).pages.get(0).lines.get(0);
    assertEquals(line.width, again.width, 0f);
    assertEquals(line.spans.size(), again.spans.size());
    for (int i = 0; i < line.spans.size(); i++) assertEquals(line.spans.get(i).width, again.spans.get(i).width, 0f);
  }

  @Test
  public void movesHaveOneBalloonPerEndAndLaterDeletionsOnTheLineKeepTheirs() {
    // Wordcraft move_changes: one "Moved from" and one "Moved to" balloon per move end, even
    // across paragraphs; a deletion after a move's source on the same line keeps its balloon.
    var from = TextStyle.DEFAULT.revision(new Revision("r1", "delete", "Ana", "").move("m1"));
    var to = TextStyle.DEFAULT.revision(new Revision("r2", "insert", "Ana", "").move("m1"));
    var gone = TextStyle.DEFAULT.revision(new Revision("r3", "delete", "Bo", ""));
    var first = RichText.of("moved ", to).concat(RichText.of("away ", from)).concat(RichText.of("keep ", TextStyle.DEFAULT))
        .concat(RichText.of("gone", gone));
    var second = RichText.of("more ", from).concat(RichText.of("tail", TextStyle.DEFAULT));
    var d = Document.empty();
    d = d.blocks(new Sequence<Block>().append(new Paragraph("p1", first)).append(new Paragraph("p2", second)), d.nextId);
    var labels = revisionLabels(d, 0);
    assertEquals(labels.toString(), 1, java.util.Collections.frequency(labels, "Movedfrom"));
    assertEquals(labels.toString(), 1, java.util.Collections.frequency(labels, "Movedto"));
    assertTrue(labels.toString(), labels.contains("Deleted:gone"));
  }

  @Test
  public void aDeletedMarkAloneIsABalloonIncludingTheLastParagraphs() {
    var s = new Session(Document.empty());
    s.replace("one\ntwo");
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(0, 3, 1, 0);
    s.deleteBackward();
    var d = s.document();
    assertEquals(revisionLabels(d, 0).toString(), List.of("Deleted:\u00b6"), revisionLabels(d, 0));
    // A deleted mark on the last paragraph (as imported): no following line, still a balloon.
    var last = (Paragraph) d.blocks.get(d.blocks.size() - 1);
    var blocks = d.blocks.set(d.blocks.size() - 1, last.breakRevision(new Revision("r77", "delete", "Bo", "")));
    var labels = revisionLabels(d.blocks(blocks, d.nextId), 0);
    assertEquals(labels.toString(), 2, java.util.Collections.frequency(labels, "Deleted:\u00b6"));
  }

  @Test
  public void aDeletionAcrossAMarkInANestedCellIsOneBalloon() {
    var s = new Session(Document.empty());
    s.replace("body");
    s.select(0, 4, 4);
    s.insertTable(1, 1);
    s.enterCell(1, 0, 0);
    s.insertTable(1, 1);
    s.leaveCell();
    s.enterCell(1, 0, 0);
    int at = 0;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) at = i;
    s.enterNestedCell(at, 0, 0);
    s.replace("ab\ncd");
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(0, 1, 1, 1);
    s.deleteBackward();
    var labels = revisionLabels(s.document(), 0);
    assertEquals(labels.toString(), List.of("Deleted:b\u00b6c"), labels);
  }

  @Test
  public void deletionsInNeighbouringCellsAreSeparateBalloons() {
    var s = new Session(Document.empty());
    s.replace("before");
    s.select(0, 6, 6);
    s.insertTable(1, 2);
    int at = 0;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) at = i;
    s.enterCell(at, 0, 0);
    s.replace("ab");
    s.leaveCell();
    s.enterCell(at, 0, 1);
    s.replace("cd");
    s.leaveCell();
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.enterCell(at, 0, 0);
    s.select(0, 1, 2);
    s.deleteBackward();
    s.leaveCell();
    s.enterCell(at, 0, 1);
    s.select(0, 0, 1);
    s.deleteBackward();
    s.leaveCell();
    // Delete cell (0,0)'s own last mark too, so the deletion runs right up to the cell's end.
    var d = s.document();
    var t = (Table) d.blocks.get(at);
    var cell = t.cell(0, 0);
    var last = cell.get(cell.size() - 1);
    var del = new Revision("r9", "delete", "Ana", "");
    t = t.setCell(0, 0, cell.set(cell.size() - 1, last.breakRevision(del)));
    d = d.blocks(d.blocks.set(at, t), d.nextId);
    var labels = revisionLabels(d, 0);
    assertEquals(labels.toString(), List.of("Deleted:b\u00b6", "Deleted:c"), labels);
  }

  @Test
  public void frameLinesLeavesTheEnginesLayoutCachesAlone() throws Exception {
    var d = commented();
    var engine = new LayoutEngine(METRICS);
    engine.layout(d, 0);
    var next = LayoutEngine.class.getDeclaredField("nextCache");
    var content = LayoutEngine.class.getDeclaredField("nextByContent");
    next.setAccessible(true);
    content.setAccessible(true);
    Object a = next.get(engine), b = content.get(engine);
    engine.frameLines(d, new Sequence<Block>().append(new Paragraph("x", RichText.of("balloon", TextStyle.DEFAULT))), 100, null);
    assertSame(a, next.get(engine));
    assertSame(b, content.get(engine));
  }

  @Test
  public void aDeletedMarkInARowSplitAcrossPagesIsOneBalloon() {
    var s = new Session(Document.empty());
    s.insertTable(1, 2);
    int at = 0;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) at = i;
    s.enterCell(at, 0, 1);
    s.replace("b");
    s.leaveCell();
    s.enterCell(at, 0, 0);
    String longText = "word ".repeat(4000).trim();
    s.replace(longText + "\nx");
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(0, longText.length(), 1, 0);
    s.deleteBackward();
    var d = s.document();
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var r = engine.layout(d, 0);
    var pages = new java.util.HashSet<Integer>();
    for (int p = 0; p < r.pages.size(); p++)
      for (LayoutEngine.Line l : r.pages.get(p).lines) if (l.block == at) pages.add(p);
    assertTrue("row split: " + pages, pages.size() > 1);
    var labels = revisionLabels(d, 0);
    assertEquals(labels.toString(), 1, java.util.Collections.frequency(labels, "Deleted:\u00b6"));
  }

  @Test
  public void aDeletedMarkOfAnRtlParagraphAnchorsAtItsLeftEnd() {
    var s = new Session(Document.empty());
    s.replace("one\ntwo");
    s.selectRange(0, 0, 1, 3);
    s.mapParagraphs(p -> p.with("rtl", "true"));
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(0, 3, 1, 0);
    s.deleteBackward();
    var d = s.document();
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var r = engine.layout(d, 0);
    LayoutEngine.Line first = null;
    for (LayoutEngine.Line l : r.pages.get(0).lines) if (first == null && l.block >= 0) first = l;
    assertTrue(first.rtl);
    Balloons.Card card = null;
    for (List<Balloons.Card> page : Balloons.layout(d, r, METRICS, "revisions")) for (Balloons.Card c : page) card = c;
    assertNotNull(card);
    float left = first.toPage(first.x, 0)[0], right = first.toPage(first.x + first.width, 0)[0];
    assertEquals(left + " " + right, left, card.anchorX, 0.5f);
  }

  private static int tableAt(Session s) {
    int at = 0;
    for (int i = 0; i < s.document().blocks.size(); i++) if (s.document().blocks.get(i) instanceof Table) at = i;
    return at;
  }

  @Test
  public void aDeletionInARepeatedHeaderRowIsOneBalloonOnTheFirstPage() {
    // Wordcraft's deletion_balloons walks the document, not the laid-out lines: the header
    // row's repeats on later pages are copies, not more deletions.
    var s = new Session(Document.empty());
    s.insertTable(100, 1);
    int at = tableAt(s);
    var f = ((Table) s.document().blocks.get(at)).format.headerRows(1);
    for (int i = 1; i < 100; i++) f = f.minHeight(i, 40);
    s.formatTable(at, f);
    s.enterCell(at, 0, 0);
    s.replace("head keep");
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(0, 0, 0, 4);
    s.deleteBackward();
    s.leaveCell();
    var d = s.document();
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(true);
    var r = engine.layout(d, 0);
    int headerPages = 0;
    for (LayoutEngine.Page pg : r.pages) {
      boolean has = false;
      for (LayoutEngine.Line l : pg.lines) has |= l.block == at && l.row == 0;
      if (has) headerPages++;
    }
    assertTrue("header repeats on " + headerPages, headerPages >= 3);
    var cards = Balloons.layout(d, r, METRICS, "revisions");
    int found = 0, page = -1;
    for (int p = 0; p < cards.size(); p++)
      for (Balloons.Card c : cards.get(p)) {
        var t = new StringBuilder();
        for (int k = 1; k < c.items.size(); k++) if (c.items.get(k).text != null) t.append(c.items.get(k).text);
        if (t.toString().replace(" ", "").startsWith("Deleted:")) {
          found++;
          page = p;
        }
      }
    assertEquals(1, found);
    assertEquals(0, page);
  }

  @Test
  public void aDeletionContinuingIntoTheNextPageOfASplitRowIsOneBalloon() {
    var s = new Session(Document.empty());
    s.insertTable(1, 2);
    int at = tableAt(s);
    s.enterCell(at, 0, 1);
    s.replace("b");
    s.leaveCell();
    s.enterCell(at, 0, 0);
    String longText = "word ".repeat(4000).trim();
    s.replace(longText);
    s.reviewAuthor("Ana");
    s.trackChanges(true);
    s.selectRange(0, 500, 0, longText.length() - 500);
    s.deleteBackward();
    s.leaveCell();
    var d = s.document();
    var engine = new LayoutEngine(METRICS);
    engine.setHideDeleted(false);
    var r = engine.layout(d, 0);
    var pages = new java.util.HashSet<Integer>();
    for (int p = 0; p < r.pages.size(); p++)
      for (LayoutEngine.Line l : r.pages.get(p).lines) if (l.block == at) pages.add(p);
    assertTrue("row split: " + pages, pages.size() > 1);
    int found = 0;
    for (List<Balloons.Card> page : Balloons.layout(d, r, METRICS, "revisions"))
      for (Balloons.Card c : page) {
        var t = new StringBuilder();
        for (int k = 1; k < c.items.size(); k++) if (c.items.get(k).text != null) t.append(c.items.get(k).text);
        if (t.toString().startsWith("Deleted:")) found++;
      }
    assertEquals(1, found);
  }
}
