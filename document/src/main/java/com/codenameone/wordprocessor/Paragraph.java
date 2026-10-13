package com.codenameone.wordprocessor;

public final class Paragraph implements Block {
  public final String id, style, align, list;
  public final RichText text;
  public final float before, after, indent, lineSpacing;
  public final boolean pageBreak, keepNext;

  public Paragraph(String id, RichText text) {
    this(id, text, "Normal", "left", "", 0, 6, 0, 1.2f, false, false);
  }

  public Paragraph(
      String id,
      RichText text,
      String style,
      String align,
      String list,
      float before,
      float after,
      float indent,
      float line,
      boolean pageBreak,
      boolean keepNext) {
    if (id == null
        || text == null
        || style == null
        || align == null
        || list == null
        || !finite(before)
        || !finite(after)
        || !finite(indent)
        || !finite(line)
        || before < 0
        || after < 0
        || indent < 0
        || line < 1) throw new IllegalArgumentException("Invalid paragraph");
    this.id = id;
    this.text = text;
    this.style = style;
    this.align = align;
    this.list = list;
    this.before = before;
    this.after = after;
    this.indent = indent;
    lineSpacing = line;
    this.pageBreak = pageBreak;
    this.keepNext = keepNext;
  }

  private static boolean finite(float f) {
    return !Float.isNaN(f) && !Float.isInfinite(f);
  }

  public String id() {
    return id;
  }

  public Paragraph text(RichText t) {
    return new Paragraph(
        id, t, style, align, list, before, after, indent, lineSpacing, pageBreak, keepNext);
  }

  public Paragraph copy(String newId, RichText t) {
    return new Paragraph(
        newId, t, style, align, list, before, after, indent, lineSpacing, false, keepNext);
  }

  public Paragraph with(String key, String value) {
    return new Paragraph(
        id,
        text,
        "style".equals(key) ? value : style,
        "align".equals(key) ? value : align,
        "list".equals(key) ? value : list,
        "before".equals(key) ? Float.parseFloat(value) : before,
        "after".equals(key) ? Float.parseFloat(value) : after,
        "indent".equals(key) ? Float.parseFloat(value) : indent,
        "spacing".equals(key) ? Float.parseFloat(value) : lineSpacing,
        "pageBreak".equals(key) ? Boolean.parseBoolean(value) : pageBreak,
        "keepNext".equals(key) ? Boolean.parseBoolean(value) : keepNext);
  }
}
