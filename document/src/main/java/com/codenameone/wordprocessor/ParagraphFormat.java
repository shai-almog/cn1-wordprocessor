package com.codenameone.wordprocessor;

/** Immutable paragraph layout options, in typographic points. */
public final class ParagraphFormat {
  public static final java.util.List<String> ASIAN =
      java.util.Arrays.asList("kinsoku", "wordWrap", "overflowPunct", "autoSpaceDE", "autoSpaceDN");
  public static final ParagraphFormat DEFAULT = new ParagraphFormat(0, 0, 36, false, true);
  public final float rightIndent, firstIndent, tabInterval;
  public final boolean keepLines, widowControl;

  /** auto uses a multiple; exact and atLeast use points in Paragraph.lineSpacing. */
  public final String lineRule;

  /** -1 inherits the named style; 0 excludes from outlines; 1..9 are heading levels. */
  public final int outlineLevel;

  public final java.util.List<TabStop> tabs;

  /** Start this paragraph at the top of the next newspaper column (a DOCX column break). */
  public final boolean columnBreak;

  public final ParagraphBorders borders;

  /** Paragraph shading RRGGBB, or -1 for none. */
  public final int shading;

  /** Right-to-left paragraph direction (w:bidi): mirrors alignment and indents. */
  public final boolean rtl;

  /** Asian typography options switched off, from {@link #ASIAN}, comma separated. */
  public final String asianOff;


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
    this(right, first, tab, keep, widow, tabs, outlineLevel, lineRule, false);
  }

  public ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs,
      int outlineLevel,
      String lineRule,
      boolean columnBreak) {
    this(right, first, tab, keep, widow, tabs, outlineLevel, lineRule, columnBreak, ParagraphBorders.NONE, -1);
  }

  public ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs,
      int outlineLevel,
      String lineRule,
      boolean columnBreak,
      ParagraphBorders borders,
      int shading) {
    this(right, first, tab, keep, widow, tabs, outlineLevel, lineRule, columnBreak, borders, shading, false, "");
  }

  public ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs,
      int outlineLevel,
      String lineRule,
      boolean columnBreak,
      ParagraphBorders borders,
      int shading,
      boolean rtl,
      String asianOff) {
    StringBuilder off = new StringBuilder();
    for (String a : ASIAN)
      if (("," + asianOff + ",").contains("," + a + ","))
        off.append(off.length() == 0 ? "" : ",").append(a);
    for (String a : asianOff.split(","))
      if (!a.trim().isEmpty() && !ASIAN.contains(a.trim()))
        throw new IllegalArgumentException("Unknown Asian typography option: " + a);
    this.rtl = rtl;
    this.asianOff = off.toString();
    if (borders == null || shading < -1 || shading > 0xffffff)
      throw new IllegalArgumentException("Invalid paragraph borders or shading");
    this.borders = borders;
    this.shading = shading;
    this.columnBreak = columnBreak;
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
        "lineRule".equals(key) ? value : lineRule,
        "columnBreak".equals(key) ? Boolean.parseBoolean(value) : columnBreak,
        "borders".equals(key) ? ParagraphBorders.decode(value) : borders,
        "shading".equals(key) ? (value.isEmpty() ? -1 : Integer.parseInt(value, 16)) : shading,
        "rtl".equals(key) ? Boolean.parseBoolean(value) : rtl,
        "asianOff".equals(key) ? value : asianOff);
  }

  public boolean asian(String option) {
    return !("," + asianOff + ",").contains("," + option + ",");
  }
}
