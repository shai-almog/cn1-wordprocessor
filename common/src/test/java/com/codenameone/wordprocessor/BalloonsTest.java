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
}
