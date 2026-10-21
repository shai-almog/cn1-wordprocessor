package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * Markup balloons, ported from Wordcraft's crates/layout/src/balloons.rs: each comment thread in
 * a card in a {@link #MARKUP_AREA} strip on the right of its page, joined to the text it comments
 * on by a leader. Cards sit as near their anchors as the cards above allow, are pushed up from the
 * page foot, and are clipped (or, with no room left at all, left out) when a page has more than
 * its strip can hold.
 *
 * <p>The balloon mode is Wordcraft's BalloonMode ({@link #MODES}): "commentsAndFormatting" (the
 * default) puts comments and formatting changes in balloons, "revisions" deletions too, and
 * "inline" shows everything in the text with no markup area. As in Wordcraft, printed and PDF
 * markup carries the comment balloons only (revisions print inline), and only body comments get
 * balloons (headers, footers and notes keep their marks inline).
 */
public final class Balloons {
  private Balloons() {}

  /** Width of the markup area beside each page (points), as Wordcraft. */
  public static final float MARKUP_AREA = 216;

  static final float SIDE = 9, GAP = 6, PAD = 5, STRIPE = 3, EDGE = 12, MIN_CARD = 18;
  static final int MAX_BALLOONS = 5000, MAX_REPLIES = 200;
  static final float SIZE = 9;

  /** Wordcraft's BalloonMode values. */
  public static final List<String> MODES =
      java.util.Arrays.asList("revisions", "inline", "commentsAndFormatting");

  /** Wordcraft's revision_color author palette. */
  static final int[] COLORS = {0xB01E8F, 0x1E6EB0, 0x2E8B3E, 0xC05A10, 0x703CB0, 0x0E7C86};

  /** The current balloon mode (Review > Markup > Balloons), a per-user preference. */
  public static String mode() {
    String m = AppOptions.get("balloons");
    return MODES.contains(m) ? m : "commentsAndFormatting";
  }

  /** One line of card text or a hairline (text null), relative to the card's top-left. */
  public static final class Item {
    public final String text;
    public final float x, y, width, size;
    public final boolean bold;
    public final int color;

    Item(String text, float x, float y, float width, float size, boolean bold, int color) {
      this.text = text;
      this.x = x;
      this.y = y;
      this.width = width;
      this.size = size;
      this.bold = bold;
      this.color = color;
    }
  }

  /** A placed balloon: its card rectangle in page points (x beyond the page) and its anchor. */
  public static final class Card {
    public final String commentId;
    public final int page, color;
    public final float anchorX, anchorY;
    public float x, top, width, height;
    public final List<Item> items;

    Card(String id, int page, float ax, float ay, List<Item> items, float h, int color) {
      commentId = id;
      this.page = page;
      anchorX = ax;
      anchorY = ay;
      this.items = items;
      height = h;
      this.color = color;
      width = MARKUP_AREA - 2 * SIDE;
    }
  }

  static int authorColor(Document d, String author) {
    var seen = new ArrayList<String>();
    for (ReviewComment c : d.comments) if (!seen.contains(c.author)) seen.add(c.author);
    int i = Math.max(0, seen.indexOf(author));
    return COLORS[i % COLORS.length];
  }

  private static String name(String author) {
    return author == null || author.trim().isEmpty() ? "Author" : author;
  }

  /** Greedy word wrap of {@code text} into lines no wider than {@code width}. */
  static List<String> wrap(String text, float width, TextStyle style, LayoutEngine.Metrics m) {
    var out = new ArrayList<String>();
    for (String para : TextUtil.split(text, '\n')) {
      var line = new StringBuilder();
      for (String word : TextUtil.split(para, ' ')) {
        String tryLine = line.length() == 0 ? word : line + " " + word;
        if (line.length() > 0 && m.width(tryLine, style) > width) {
          out.add(line.toString());
          line.setLength(0);
          line.append(word);
        } else {
          line.setLength(0);
          line.append(tryLine);
        }
      }
      out.add(line.toString());
    }
    return out;
  }

  /**
   * The balloons for the unresolved body comment threads of {@code d} laid out in {@code r},
   * per page. Card text is measured with {@code m}.
   */
  public static List<List<Card>> layout(Document d, LayoutEngine.Result r, LayoutEngine.Metrics m) {
    return layout(d, r, m, null);
  }

  /**
   * As {@link #layout(Document, LayoutEngine.Result, LayoutEngine.Metrics)}, adding the editor's
   * revision balloons for {@code mode}: "Formatted" balloons for formatting changes in either
   * balloon mode and, in "revisions" mode, "Deleted: ..." balloons for tracked deletions.
   */
  public static List<List<Card>> layout(
      Document d, LayoutEngine.Result r, LayoutEngine.Metrics m, String mode) {
    var pages = new ArrayList<List<Card>>();
    for (int i = 0; i < r.pages.size(); i++) pages.add(new ArrayList<Card>());
    boolean revisions = mode != null && !"inline".equals(mode);
    if (d.comments.isEmpty() && !revisions || r.pages.isEmpty()) return pages;
    float inner = MARKUP_AREA - 2 * SIDE - STRIPE - 2 * PAD;
    var head = TextStyle.DEFAULT.with("size", String.valueOf(SIZE)).with("bold", "true");
    var body = TextStyle.DEFAULT.with("size", String.valueOf(SIZE));
    float lh = m.height(body);
    int count = 0;
    for (ReviewComment c : d.comments) {
      if (c.resolved || count >= MAX_BALLOONS) continue;
      float[] at = anchor(r, c);
      if (at == null) continue;
      count++;
      int color = authorColor(d, c.author);
      var items = new ArrayList<Item>();
      float x = STRIPE + PAD, y = PAD;
      int n = Math.min(c.replies.size(), MAX_REPLIES);
      for (int k = 0; k <= n; k++) {
        String author = k == 0 ? c.author : c.replies.get(k - 1).author;
        String text = k == 0 ? c.text : c.replies.get(k - 1).text;
        if (k > 0) {
          // A reply starts: a hairline between it and what it answers.
          y += 3;
          items.add(new Item(null, x, y, inner, 0.25f, false, 0xC8C8C8));
          y += 3;
        }
        items.add(new Item(name(author), x, y, inner, SIZE, true, k == 0 ? color : authorColor(d, author)));
        y += m.height(head) + 2;
        for (String line : wrap(text, inner, body, m)) {
          items.add(new Item(line, x, y, inner, SIZE, false, 0x242424));
          y += lh;
        }
      }
      pages.get((int) at[0]).add(new Card(c.id, (int) at[0], at[1], at[2], items, y + PAD, color));
    }
    if (revisions) {
      boolean deletions = "revisions".equals(mode);
      for (int p = 0; p < r.pages.size() && count < MAX_BALLOONS; p++)
        for (LayoutEngine.Line line : r.pages.get(p).lines) {
          if (line.block < 0) continue;
          for (LayoutEngine.Span span : line.spans) {
            var rev = span.style.revision;
            String label = null;
            Revision who = null;
            if (deletions && rev != null && !rev.inserted()) {
              label = "Deleted: " + span.text;
              who = rev;
            } else if (span.style.formatRevision != null) {
              label = "Formatted";
              who = span.style.formatRevision;
            }
            if (label == null) continue;
            count++;
            int color = authorColor(d, who.author);
            var items = new ArrayList<Item>();
            float x = STRIPE + PAD, y = PAD;
            items.add(new Item(name(who.author), x, y, inner, SIZE, true, color));
            y += m.height(head) + 2;
            for (String text : wrap(label, inner, body, m)) {
              items.add(new Item(text, x, y, inner, SIZE, false, 0x242424));
              y += lh;
            }
            float[] foot = line.toPage(line.x + span.x, line.y + line.height);
            pages.get(p).add(new Card(who.id, p, foot[0], foot[1], items, y + PAD, color));
            break;
          }
        }
    }
    for (int p = 0; p < pages.size(); p++) place(pages.get(p), r.pages.get(p).spec.width, r.pages.get(p).spec.height);
    return pages;
  }

  /** {page, x, y} of a comment's start at the foot of its line, null when not in the body. */
  static float[] anchor(LayoutEngine.Result r, ReviewComment c) {
    for (int p = 0; p < r.pages.size(); p++)
      for (LayoutEngine.Line line : r.pages.get(p).lines) {
        if (line.block < 0 || !c.startId.equals(line.paragraphId)) continue;
        if (c.start < line.start || c.start > line.end) continue;
        float x = line.width;
        for (LayoutEngine.Span s : line.spans)
          if (c.start >= s.start && c.start <= s.end) {
            float f = s.end > s.start ? (c.start - s.start) / (float) (s.end - s.start) : 0;
            x = s.rtl ? s.x + s.width * (1 - f) : s.x + s.width * f;
            break;
          }
        float[] foot = line.toPage(line.x + x, line.y + line.height);
        return new float[] {p, foot[0], foot[1]};
      }
    return null;
  }

  /** Wordcraft's three passes: near the anchor, pushed up from the foot, clipped below the top. */
  static void place(List<Card> list, float pageWidth, float pageHeight) {
    list.sort((a, b) -> a.anchorY != b.anchorY ? Float.compare(a.anchorY, b.anchorY) : Float.compare(a.anchorX, b.anchorX));
    float topMin = EDGE, bottom = Math.max(pageHeight - EDGE, EDGE + MIN_CARD);
    float[] tops = new float[list.size()];
    float next = topMin;
    for (int i = 0; i < list.size(); i++) {
      tops[i] = Math.max(list.get(i).anchorY - 10, next);
      next = tops[i] + list.get(i).height + GAP;
    }
    float limit = bottom;
    for (int i = list.size() - 1; i >= 0; i--) {
      if (tops[i] + list.get(i).height > limit) tops[i] = limit - list.get(i).height;
      limit = tops[i] - GAP;
    }
    next = topMin;
    var kept = new ArrayList<Card>();
    for (int i = 0; i < list.size(); i++) {
      var c = list.get(i);
      float top = Math.max(tops[i], next);
      // No room left beside this page: the rest are left out rather than drawn off the paper.
      if (top + MIN_CARD > bottom + .5f) break;
      float avail = bottom - top;
      if (c.height > avail) {
        c.height = avail;
        c.items.removeIf(it -> it.y + it.size > avail - PAD);
      }
      c.top = top;
      c.x = pageWidth + SIDE;
      next = top + c.height + GAP;
      kept.add(c);
    }
    list.clear();
    list.addAll(kept);
  }
}
