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
}
