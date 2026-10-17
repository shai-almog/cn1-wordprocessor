package com.codenameone.wordprocessor;

public final class Paragraph implements Block {
  public final String id, style, align, list;
  public final RichText text;
  public final float before, after, indent, lineSpacing;
  public final boolean pageBreak, keepNext;
  public final int listLevel, listStart;
  public final String listFormat;
  public final ParagraphFormat format;
  public final Revision breakRevision, formatRevision;
  public final Paragraph previousFormat;

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
    this(
        id, text, style, align, list, before, after, indent, line, pageBreak, keepNext, 0, 0,
        "decimal");
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
      boolean keepNext,
      int level,
      int start,
      String format) {
    this(
        id,
        text,
        style,
        align,
        list,
        before,
        after,
        indent,
        line,
        pageBreak,
        keepNext,
        level,
        start,
        format,
        ParagraphFormat.DEFAULT);
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
      boolean keepNext,
      int level,
      int start,
      String format,
      ParagraphFormat layout) {
    this(
        id, text, style, align, list, before, after, indent, line, pageBreak, keepNext, level,
        start, format, layout, null, null, null);
  }

  private Paragraph(
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
      boolean keepNext,
      int level,
      int start,
      String format,
      ParagraphFormat layout,
      Revision breakRevision,
      Revision formatRevision,
      Paragraph previousFormat) {
    this.formatRevision = formatRevision;
    this.previousFormat = previousFormat;
    this.breakRevision = breakRevision;
    if (layout == null) throw new IllegalArgumentException("Missing paragraph format");
    this.format = layout;
    if (level < 0
        || level > 8
        || start < 0
        || start > 1000000
        || !ListNumbers.valid(format)) throw new IllegalArgumentException("Invalid list properties");
    listLevel = level;
    listStart = start;
    listFormat = format;
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
        || line < .06f) throw new IllegalArgumentException("Invalid paragraph");
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

  public Paragraph breakRevision(Revision value) {
    return new Paragraph(
        id,
        text,
        style,
        align,
        list,
        before,
        after,
        indent,
        lineSpacing,
        pageBreak,
        keepNext,
        listLevel,
        listStart,
        listFormat,
        format,
        value,
        formatRevision,
        previousFormat);
  }

  public Paragraph formatRevision(Revision revision, Paragraph previous) {
    if ((revision == null) != (previous == null))
      throw new IllegalArgumentException("Incomplete paragraph revision");
    return new Paragraph(
        id,
        text,
        style,
        align,
        list,
        before,
        after,
        indent,
        lineSpacing,
        pageBreak,
        keepNext,
        listLevel,
        listStart,
        listFormat,
        format,
        breakRevision,
        revision,
        previous);
  }

  public Paragraph trackedProperties(Paragraph previous, Revision change) {
    if (change == null || sameProperties(previous)) return this;
    return formatRevision(
        previous.formatRevision == null ? change : previous.formatRevision,
        previous.formatRevision == null
            ? previous.text(new RichText()).breakRevision(null)
            : previous.previousFormat);
  }

  private boolean sameProperties(Paragraph p) {
    return style.equals(p.style)
        && align.equals(p.align)
        && list.equals(p.list)
        && before == p.before
        && after == p.after
        && indent == p.indent
        && lineSpacing == p.lineSpacing
        && format.lineRule.equals(p.format.lineRule)
        && pageBreak == p.pageBreak
        && keepNext == p.keepNext
        && listLevel == p.listLevel
        && listStart == p.listStart
        && listFormat.equals(p.listFormat)
        && format.rightIndent == p.format.rightIndent
        && format.firstIndent == p.format.firstIndent
        && format.tabInterval == p.format.tabInterval
        && format.keepLines == p.format.keepLines
        && format.widowControl == p.format.widowControl
        && format.outlineLevel == p.format.outlineLevel
        && format.columnBreak == p.format.columnBreak
        && format.borders.equals(p.format.borders)
        && format.shading == p.format.shading
        && TabStop.encode(format.tabs).equals(TabStop.encode(p.format.tabs));
  }

  public Paragraph reviewFormat(String revisionId, boolean accept) {
    if (formatRevision == null || revisionId != null && !revisionId.equals(formatRevision.id))
      return this;
    if (accept) return formatRevision(null, null);
    Paragraph p = previousFormat;
    return new Paragraph(
        id,
        text,
        p.style,
        p.align,
        p.list,
        p.before,
        p.after,
        p.indent,
        p.lineSpacing,
        p.pageBreak,
        p.keepNext,
        p.listLevel,
        p.listStart,
        p.listFormat,
        p.format,
        breakRevision,
        null,
        null);
  }

  public Paragraph text(RichText t) {
    return new Paragraph(
        id,
        t,
        style,
        align,
        list,
        before,
        after,
        indent,
        lineSpacing,
        pageBreak,
        keepNext,
        listLevel,
        listStart,
        listFormat,
        format,
        breakRevision,
        formatRevision,
        previousFormat);
  }

  public Paragraph copy(String newId, RichText t) {
    return new Paragraph(
        newId,
        t,
        style,
        align,
        list,
        before,
        after,
        indent,
        lineSpacing,
        false,
        keepNext,
        listLevel,
        0,
        listFormat,
        format,
        breakRevision,
        formatRevision,
        previousFormat);
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
        "keepNext".equals(key) ? Boolean.parseBoolean(value) : keepNext,
        "listLevel".equals(key) ? Integer.parseInt(value) : listLevel,
        "listStart".equals(key) ? Integer.parseInt(value) : listStart,
        "listFormat".equals(key) ? value : listFormat,
        format.with(key, value),
        breakRevision,
        formatRevision,
        previousFormat);
  }
}
