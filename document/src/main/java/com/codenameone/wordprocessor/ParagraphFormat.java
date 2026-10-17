package com.codenameone.wordprocessor;

/** Immutable paragraph layout options, in typographic points. */
public final class ParagraphFormat {
  public static final ParagraphFormat DEFAULT = new ParagraphFormat(0, 0, 36, false, true);
  public final float rightIndent, firstIndent, tabInterval;
  public final boolean keepLines, widowControl;

  /** auto uses a multiple; exact and atLeast use points in Paragraph.lineSpacing. */
  public final String lineRule;

  /** -1 inherits the named style; 0 excludes from outlines; 1..9 are heading levels. */
  public final int outlineLevel;

  public final java.util.List<TabStop> tabs;

  public ParagraphFormat(float right, float first, float tab, boolean keep, boolean widow) {
    this(right, first, tab, keep, widow, java.util.Collections.<TabStop>emptyList());
  }

  public ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs) {
    this(right, first, tab, keep, widow, tabs, -1);
  }

  public ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs,
      int outlineLevel) {
    this(right, first, tab, keep, widow, tabs, outlineLevel, "auto");
  }

  public ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs,
      int outlineLevel,
      String lineRule) {
    if (!java.util.Arrays.asList("auto", "exact", "atLeast").contains(lineRule))
      throw new IllegalArgumentException("Invalid line spacing rule");
    this.lineRule = lineRule;
    if (outlineLevel < -1 || outlineLevel > 9)
      throw new IllegalArgumentException("Invalid outline level");
    this.outlineLevel = outlineLevel;
    this.tabs = TabStop.normalized(tabs);
    if (!finite(right) || !finite(first) || !finite(tab) || right < 0 || tab <= 0 || tab > 1000)
      throw new IllegalArgumentException("Invalid paragraph layout");
    rightIndent = right;
    firstIndent = first;
    tabInterval = tab;
    keepLines = keep;
    widowControl = widow;
  }

  private static boolean finite(float f) {
    return !Float.isNaN(f) && !Float.isInfinite(f);
  }

  public ParagraphFormat with(String key, String value) {
    return new ParagraphFormat(
        "rightIndent".equals(key) ? Float.parseFloat(value) : rightIndent,
        "firstIndent".equals(key) ? Float.parseFloat(value) : firstIndent,
        "tabInterval".equals(key) ? Float.parseFloat(value) : tabInterval,
        "keepLines".equals(key) ? Boolean.parseBoolean(value) : keepLines,
        "widowControl".equals(key) ? Boolean.parseBoolean(value) : widowControl,
        "tabs".equals(key) ? TabStop.decode(value) : tabs,
        "outlineLevel".equals(key) ? Integer.parseInt(value) : outlineLevel,
        "lineRule".equals(key) ? value : lineRule);
  }
}
