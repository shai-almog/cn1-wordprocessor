package com.codenameone.wordprocessor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Outline view state and the display transform it applies: paragraphs indented by outline level,
 * headings below the shown level or under a collapsed heading hidden, optional plain text and
 * first-line-only body text. The transform never changes the stored document.
 */
public final class OutlineView {
  /** Body text has this level; showLevel 10 shows all levels including body text. */
  public static final int BODY = 10;

  public final int showLevel;
  public final boolean showFormatting, firstLineOnly;
  private final Set<String> collapsed;

  public static final OutlineView ALL = new OutlineView(BODY, true, false, Collections.<String>emptySet());

  private OutlineView(int showLevel, boolean showFormatting, boolean firstLineOnly, Set<String> collapsed) {
    if (showLevel < 1 || showLevel > BODY) throw new IllegalArgumentException("Show level must be 1-9 or all");
    this.showLevel = showLevel;
    this.showFormatting = showFormatting;
    this.firstLineOnly = firstLineOnly;
    this.collapsed = Collections.unmodifiableSet(collapsed);
  }

  public OutlineView showLevel(int level) {
    return new OutlineView(level, showFormatting, firstLineOnly, collapsed);
  }

  public OutlineView showFormatting(boolean value) {
    return new OutlineView(showLevel, value, firstLineOnly, collapsed);
  }

  public OutlineView firstLineOnly(boolean value) {
    return new OutlineView(showLevel, showFormatting, value, collapsed);
  }

  public boolean collapsed(String paragraphId) {
    return collapsed.contains(paragraphId);
  }

  /** Collapse (true) or expand (false) a heading's subordinate text. */
  public OutlineView collapse(String paragraphId, boolean value) {
    Set<String> next = new HashSet<String>(collapsed);
    if (value) next.add(paragraphId);
    else next.remove(paragraphId);
    return new OutlineView(showLevel, showFormatting, firstLineOnly, next);
  }

  /** True when the transform hides, truncates or unformats anything (the view is then read-only). */
  public boolean filters(Document d) {
    if (!showFormatting || firstLineOnly) return true;
    for (Block b : d.blocks)
      if (b instanceof Paragraph && (hidden(d, (Paragraph) b) || collapsed.contains(b.id()))) return true;
    return showLevel < BODY;
  }

  private boolean hidden(Document d, Paragraph p) {
    return level(d, p) > showLevel;
  }

  /** Outline level 1-9 of a heading paragraph, or {@link #BODY}. */
  public static int level(Document d, Paragraph p) {
    int own = p.format.outlineLevel;
    if (own >= 1) return own;
    if (own == 0) return BODY;
    ParagraphStyle style = d.styles.get(p.style);
    if (style != null && style.paragraph.format.outlineLevel >= 1) return style.paragraph.format.outlineLevel;
    if (p.style != null && p.style.startsWith("Heading") && p.style.length() == 8) {
      char c = p.style.charAt(7);
      if (c >= '1' && c <= '9') return c - '0';
    }
    return BODY;
  }

  /** True when a heading has subordinate blocks (shows the expand/collapse symbol). */
  public static boolean hasChildren(Document d, int block) {
    if (!(d.blocks.get(block) instanceof Paragraph)) return false;
    int level = level(d, (Paragraph) d.blocks.get(block));
    if (level == BODY || block + 1 >= d.blocks.size()) return false;
    Block next = d.blocks.get(block + 1);
    return !(next instanceof Paragraph) || level(d, (Paragraph) next) > level;
  }

  public Document apply(Document d) {
    Sequence<Block> out = new Sequence<Block>();
    int hideBelow = Integer.MAX_VALUE, heading = 0;
    for (int i = 0; i < d.blocks.size(); i++) {
      Block b = d.blocks.get(i);
      int level = b instanceof Paragraph ? level(d, (Paragraph) b) : BODY;
      if (level <= hideBelow) hideBelow = Integer.MAX_VALUE;
      if (hideBelow != Integer.MAX_VALUE || level > showLevel) continue;
      if (level < BODY && collapsed.contains(b.id())) hideBelow = level;
      if (!(b instanceof Paragraph)) {
        out = out.append(b);
        continue;
      }
      Paragraph p = (Paragraph) b;
      if (level < BODY) heading = level;
      RichText text = p.text;
      if (firstLineOnly && level == BODY) {
        String plain = text.plainText();
        int cut = Math.min(plain.length(), 90);
        if (cut < plain.length()) {
          int space = plain.lastIndexOf(' ', cut);
          if (space > 40) cut = space;
          text = text.slice(0, cut).concat(RichText.of("\u2026", text.styleAt(Math.max(0, cut - 1))));
        }
      }
      if (!showFormatting) {
        final float size = level < BODY ? 12 : 11;
        final boolean bold = level < BODY;
        text =
            text.mapStyles(
                s ->
                    TextStyle.DEFAULT
                        .with("size", String.valueOf(size))
                        .with("bold", String.valueOf(bold)));
      }
      if (collapsed.contains(p.id) && hasChildren(d, i))
        text = text.mapStyles(s -> s.with("underlineStyle", "wave"));
      float indent = 18 * ((level < BODY ? level : heading + 1) - 1);
      Paragraph shown =
          p.text(text)
              .with("indent", String.valueOf(Math.max(0, indent)))
              .with("align", "left")
              .with("firstIndent", "0")
              .with("pageBreak", "false")
              .with("before", String.valueOf(level < BODY ? 6 : 2))
              .with("after", "2");
      out = out.append(shown);
    }
    if (out.size() == 0) out = out.append(new Paragraph("outline-empty", new RichText()));
    // Outline view has no page layout: one section, so removed blocks never own a section break.
    return d.blocks(out, d.nextId)
        .sections(new java.util.LinkedHashMap<String, Section>(), d.lastSection);
  }
}
