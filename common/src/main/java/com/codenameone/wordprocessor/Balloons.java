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
    /** The run's own formatting for comment text (Wordcraft lays out each comment's part); null otherwise. */
    public final TextStyle style;

    Item(String text, float x, float y, float width, float size, boolean bold, int color) {
      this(text, x, y, width, size, bold, color, null);
    }

    Item(String text, float x, float y, float width, float size, boolean bold, int color, TextStyle style) {
      this.style = style;
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

  /**
   * Lays out a comment's paragraphs in {@code width} from (x, y) with each run's own formatting,
   * as Wordcraft lays out the comment's part in the card with its full paragraph engine
   * (line breaking, alignment, indents, spacing and tabs). Unformatted text takes the card's
   * 9pt. Returns the y below the text.
   */
  static float rich(
      List<Item> items, List<Paragraph> paras, float x, float y, float width, TextStyle base,
      LayoutEngine.Metrics m, Document d, LayoutEngine engine) {
    if (paras.isEmpty()) return y;
    var blocks = new Sequence<Block>();
    for (Paragraph p : paras) blocks = blocks.append(p.text(p.text.mapStyles(st -> styleOf(st, base))));
    float[] used = {0};
    for (LayoutEngine.Line line : engine.frameLines(d, blocks, width, used))
      for (LayoutEngine.Span span : line.spans) {
        String text = span.displayText;
        if (text.isEmpty() || InlineField.OBJECT.equals(text)) continue;
        var st = span.style;
        items.add(new Item(text, x + line.x + span.x, y + line.y, span.width, sizeOf(st), st.bold,
            st.color == 0 ? 0x242424 : st.color, st));
      }
    return y + used[0];
  }

  private static TextStyle styleOf(TextStyle s, TextStyle base) {
    // A run without its own size takes the card's 9pt rather than the document default.
    return s == null ? base : s.size == TextStyle.DEFAULT.size ? s.with("size", String.valueOf(SIZE)) : s;
  }

  private static float sizeOf(TextStyle s) {
    return s.size;
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
    var engine = new LayoutEngine(m);
    for (int i = 0; i < r.pages.size(); i++) pages.add(new ArrayList<Card>());
    boolean revisions = mode != null && !"inline".equals(mode);
    if (d.comments.isEmpty() && !revisions || r.pages.isEmpty()) return pages;
    float inner = MARKUP_AREA - 2 * SIDE - STRIPE - 2 * PAD;
    var head = TextStyle.DEFAULT.with("size", String.valueOf(SIZE)).with("bold", "true");
    var body = TextStyle.DEFAULT.with("size", String.valueOf(SIZE));
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
        List<Paragraph> text = k == 0 ? c.paragraphs() : c.replies.get(k - 1).paragraphs();
        if (k > 0) {
          // A reply starts: a hairline between it and what it answers.
          y += 3;
          items.add(new Item(null, x, y, inner, 0.25f, false, 0xC8C8C8));
          y += 3;
        }
        items.add(new Item(name(author), x, y, inner, SIZE, true, k == 0 ? color : authorColor(d, author)));
        y += m.height(head) + 2;
        y = rich(items, text, x, y, inner, body, m, d, engine);
      }
      pages.get((int) at[0]).add(new Card(c.id, (int) at[0], at[1], at[2], items, y + PAD, color));
    }
    if (revisions) {
      boolean deletions = "revisions".equals(mode);
      // Paragraph marks by id, so a deletion running across a deleted mark stays one balloon.
      var marks = new java.util.HashMap<String, Revision>();
      for (Block block : d.blocks)
        if (block instanceof Paragraph para) marks.put(para.id, para.breakRevision);
        else if (block instanceof Table t)
          for (int tr = 0; tr < t.rows(); tr++)
            for (int tc = 0; tc < t.columns(); tc++)
              if (!t.covered(tr, tc)) for (Paragraph para : t.cell(tr, tc)) marks.put(para.id, para.breakRevision);
      // Wordcraft's deletion_balloons (cmd/review.rs): deleted text running on across spans,
      // lines and deleted paragraph marks by the same author is one balloon, its text cut to
      // DELETION_TEXT_CHARS and an ellipsis; a move's source has a "Moved from" balloon instead.
      var cards = new ArrayList<Pending>();
      Pending open = null;
      String lastPara = null;
      for (int p = 0; p < r.pages.size(); p++)
        for (LayoutEngine.Line line : r.pages.get(p).lines) {
          if (line.block < 0) continue;
          if (open != null && lastPara != null && !lastPara.equals(line.paragraphId)) {
            Revision mark = marks.get(lastPara);
            if (mark != null && !mark.inserted() && mark.move == null && mark.author.equals(open.who.author))
              open.add("\u00b6");
            else open = null;
          }
          lastPara = line.paragraphId;
          for (LayoutEngine.Span span : line.spans) {
            var rev = span.style.revision;
            boolean deleted = deletions && rev != null && !rev.inserted();
            if (deleted && rev.move == null) {
              if (open != null && open.who.author.equals(rev.author)) {
                open.add(span.text);
                continue;
              }
              if (count >= MAX_BALLOONS) continue;
              float[] foot = line.toPage(line.x + span.x, line.y + line.height);
              open = new Pending(rev, p, foot);
              open.add(span.text);
              count++;
              cards.add(open);
              continue;
            }
            if (span.text != null && !span.text.isEmpty()) open = null;
            String label = null;
            Revision who = null;
            if (deleted) {
              label = "Moved from";
              who = rev;
            } else if (span.style.formatRevision != null) {
              label = "Formatted";
              who = span.style.formatRevision;
            }
            if (label == null || count >= MAX_BALLOONS) continue;
            count++;
            float[] foot = line.toPage(line.x + span.x, line.y + line.height);
            var c = new Pending(who, p, foot);
            c.label = label;
            cards.add(c);
            break;
          }
        }
      for (Pending c : cards) {
        int color = authorColor(d, c.who.author);
        var items = new ArrayList<Item>();
        float x = STRIPE + PAD, y = PAD;
        items.add(new Item(name(c.who.author), x, y, inner, SIZE, true, color));
        y += m.height(head) + 2;
        String label = c.label != null ? c.label : "Deleted: " + c.text;
        // Laid out with the full paragraph engine, as the comment cards are.
        y = rich(items, java.util.Collections.singletonList(new Paragraph("balloon", RichText.of(label, body))),
            x, y, inner, body, m, d, engine);
        pages.get(c.page).add(new Card(c.who.id, c.page, c.foot[0], c.foot[1], items, y + PAD, color));
      }
    }
    for (int p = 0; p < pages.size(); p++) place(pages.get(p), r.pages.get(p).spec.width, r.pages.get(p).spec.height);
    return pages;
  }

  /** Wordcraft's DELETION_TEXT_CHARS: at most this many deleted characters show in a balloon. */
  static final int DELETION_TEXT_CHARS = 200;

  /** A revision balloon being gathered: its anchor and, for a deletion, the text so far. */
  private static final class Pending {
    final Revision who;
    final int page;
    final float[] foot;
    final StringBuilder text = new StringBuilder();
    int chars;
    String label;

    Pending(Revision who, int page, float[] foot) {
      this.who = who;
      this.page = page;
      this.foot = foot;
    }

    void add(String s) {
      if (s == null) return;
      for (int i = 0; i < s.length() && chars <= DELETION_TEXT_CHARS; i++) {
        char c = s.charAt(i);
        if (c == '\t' || c == '\n' || c == '\u000b' || c == '\f') c = ' ';
        else if (c == '\ufffc' || c < 0x20 || c >= 0x7f && c < 0xa0) continue;
        chars++;
        text.append(chars > DELETION_TEXT_CHARS ? '\u2026' : c);
      }
    }
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
