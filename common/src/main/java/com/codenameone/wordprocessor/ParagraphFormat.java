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

  /**
   * Drop cap: empty for none, else "mode,lines,distance,font" where mode is drop or margin, lines
   * 1-10, distance from text in points and font empty for the paragraph's font.
   */
  public final String dropCap;

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
    this(right, first, tab, keep, widow, tabs, outlineLevel, lineRule, columnBreak, borders, shading, rtl, asianOff, "");
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
      String asianOff,
      String dropCap) {
    this(
        layout(right, first, tab),
        first,
        tab,
        keep,
        widow,
        TabStop.normalized(tabs),
        outline(outlineLevel),
        lineRule(lineRule),
        columnBreak,
        shading(borders, shading),
        rtl,
        asianOptions(asianOff),
        dropCap(dropCap),
        borders);
  }

  /** Already validated values: {@link #with} checks only the property it changes. */
  private ParagraphFormat(
      float right,
      float first,
      float tab,
      boolean keep,
      boolean widow,
      java.util.List<TabStop> tabs,
      int outlineLevel,
      String lineRule,
      boolean columnBreak,
      int shading,
      boolean rtl,
      String asianOff,
      String dropCap,
      ParagraphBorders borders) {
    rightIndent = right;
    firstIndent = first;
    tabInterval = tab;
    keepLines = keep;
    widowControl = widow;
    this.tabs = tabs;
    this.outlineLevel = outlineLevel;
    this.lineRule = lineRule;
    this.columnBreak = columnBreak;
    this.shading = shading;
    this.rtl = rtl;
    this.asianOff = asianOff;
    this.dropCap = dropCap;
    this.borders = borders;
  }

  private static float layout(float right, float first, float tab) {
    if (!finite(right) || !finite(first) || !finite(tab) || right < 0 || tab <= 0 || tab > 1000)
      throw new IllegalArgumentException("Invalid paragraph layout");
    return right;
  }

  private static int outline(int level) {
    if (level < -1 || level > 9) throw new IllegalArgumentException("Invalid outline level");
    return level;
  }

  private static String lineRule(String rule) {
    if (!"auto".equals(rule) && !"exact".equals(rule) && !"atLeast".equals(rule))
      throw new IllegalArgumentException("Invalid line spacing rule");
    return rule;
  }

  private static int shading(ParagraphBorders borders, int shading) {
    if (borders == null || shading < -1 || shading > 0xffffff)
      throw new IllegalArgumentException("Invalid paragraph borders or shading");
    return shading;
  }

  private static String asianOptions(String asianOff) {
    if (asianOff.isEmpty()) return "";
    var off = new StringBuilder();
    for (String a : ASIAN)
      if (("," + asianOff + ",").contains("," + a + ","))
        off.append(off.length() == 0 ? "" : ",").append(a);
    for (String a : TextUtil.split(asianOff, ','))
      if (!a.trim().isEmpty() && !ASIAN.contains(a.trim()))
        throw new IllegalArgumentException("Unknown Asian typography option: " + a);
    return off.toString();
  }

  private static boolean finite(float f) {
    return !Float.isNaN(f) && !Float.isInfinite(f);
  }

  /** The right and first-line indents set at once, without a round trip through text. */
  public ParagraphFormat withIndents(float right, float first) {
    layout(right, first, tabInterval);
    return new ParagraphFormat(
        right, first, tabInterval, keepLines, widowControl, tabs, outlineLevel, lineRule,
        columnBreak, shading, rtl, asianOff, dropCap, borders);
  }

  public ParagraphFormat with(String key, String value) {
    // Every other field is already valid, so only the changed one is parsed and checked.
    float right = rightIndent, first = firstIndent, tab = tabInterval;
    boolean keep = keepLines, widow = widowControl, column = columnBreak, bidi = rtl;
    var stops = tabs;
    int outline = outlineLevel, shade = shading;
    String rule = lineRule, asian = asianOff, drop = dropCap;
    var edges = borders;
    switch (key) {
      case "rightIndent": right = Float.parseFloat(value); break;
      case "firstIndent": first = Float.parseFloat(value); break;
      case "tabInterval": tab = Float.parseFloat(value); break;
      case "keepLines": keep = Boolean.parseBoolean(value); break;
      case "widowControl": widow = Boolean.parseBoolean(value); break;
      case "tabs": stops = TabStop.normalized(TabStop.decode(value)); break;
      case "outlineLevel": outline = outline(Integer.parseInt(value)); break;
      case "lineRule": rule = lineRule(value); break;
      case "columnBreak": column = Boolean.parseBoolean(value); break;
      case "borders": edges = ParagraphBorders.decode(value); shading(edges, shade); break;
      case "shading": shade = shading(edges, value.isEmpty() ? -1 : Integer.parseInt(value, 16)); break;
      case "rtl": bidi = Boolean.parseBoolean(value); break;
      case "asianOff": asian = asianOptions(value); break;
      case "dropCap": drop = dropCap(value); break;
      default: return this;
    }
    layout(right, first, tab);
    return new ParagraphFormat(
        right, first, tab, keep, widow, stops, outline, rule, column, shade, bidi, asian, drop, edges);
  }

  /** Validates and normalizes a drop cap specification. */
  public static String dropCap(String spec) {
    if (spec == null || spec.trim().isEmpty() || "none".equals(spec.trim())) return "";
    String[] f = TextUtil.split(spec, ',');
    var mode = f[0].trim();
    if (!"drop".equals(mode) && !"margin".equals(mode))
      throw new IllegalArgumentException("Drop cap position is none, drop or margin");
    int lines;
    float distance;
    try {
      lines = f.length > 1 && !f[1].trim().isEmpty() ? Integer.parseInt(f[1].trim()) : 3;
      distance = f.length > 2 && !f[2].trim().isEmpty() ? Float.parseFloat(f[2].trim()) : 0;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid drop cap lines or distance");
    }
    if (lines < 1 || lines > 10) throw new IllegalArgumentException("Lines to drop is 1-10");
    if (!(distance >= 0 && distance <= 144)) throw new IllegalArgumentException("Distance from text is 0-144 points");
    String font = f.length > 3 ? f[3].trim() : "";
    return mode + "," + lines + "," + (distance == (int) distance ? String.valueOf((int) distance) : String.valueOf(distance)) + "," + font;
  }

  public String dropMode() {
    return dropCap.isEmpty() ? "none" : TextUtil.split(dropCap, ',')[0];
  }

  public int dropLines() {
    return dropCap.isEmpty() ? 0 : Integer.parseInt(TextUtil.split(dropCap, ',')[1]);
  }

  public float dropDistance() {
    return dropCap.isEmpty() ? 0 : Float.parseFloat(TextUtil.split(dropCap, ',')[2]);
  }

  public String dropFont() {
    if (dropCap.isEmpty()) return "";
    String[] f = TextUtil.split(dropCap, ',');
    return f.length > 3 ? f[3] : "";
  }

  public boolean asian(String option) {
    return !("," + asianOff + ",").contains("," + option + ",");
  }
}
