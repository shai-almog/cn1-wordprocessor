package com.codenameone.wordprocessor;

import java.util.*;

/** Named paragraph and character defaults; direct formatting survives a definition update. */
public final class ParagraphStyle {
  public final String id, name;
  public final Paragraph paragraph;
  public final TextStyle text;

  public ParagraphStyle(String id, String name, Paragraph paragraph, TextStyle text) {
    if (id == null
        || id.trim().isEmpty()
        || name == null
        || name.trim().isEmpty()
        || id.length() > 128
        || name.length() > 128
        || paragraph == null
        || text == null) throw new IllegalArgumentException("Invalid style definition");
    this.id = id;
    this.name = name;
    this.text = text.clean().with("link", "");
    this.paragraph =
        paragraph
            .with("style", id)
            .text(RichText.of("", this.text))
            .breakRevision(null)
            .formatRevision(null, null);
  }

  public Paragraph apply(Paragraph p, ParagraphStyle old, boolean update) {
    return apply(p, old, update, null);
  }

  public Paragraph apply(Paragraph p, ParagraphStyle old, boolean update, Revision change) {
    final TextStyle previous = old == null ? TextStyle.DEFAULT : old.text;
    final RichText content =
        p.text.mapStyles(style -> merge(style, previous, text).trackedProperties(style, change));
    Paragraph result =
        new Paragraph(
                p.id,
                content,
                id,
                paragraph.align,
                paragraph.list,
                paragraph.before,
                paragraph.after,
                paragraph.indent,
                paragraph.lineSpacing,
                p.pageBreak,
                paragraph.keepNext,
                paragraph.listLevel,
                p.listStart,
                paragraph.listFormat,
                paragraph.format.with("outlineLevel", "-1"))
            .breakRevision(p.breakRevision)
            .formatRevision(p.formatRevision, p.previousFormat);
    if (update && old != null) {
      String[] keys = {
        "align",
        "list",
        "before",
        "after",
        "indent",
        "spacing",
        "lineRule",
        "keepNext",
        "listLevel",
        "listFormat",
        "rightIndent",
        "firstIndent",
        "tabInterval",
        "tabs",
        "keepLines",
        "widowControl",
        "outlineLevel"
      };
      for (String key : keys)
        if (!value(p, key).equals(value(old.paragraph, key)))
          result = result.with(key, value(p, key));
      // Rule and value form one dimensioned value; do not combine points from a
      // direct override with a multiple from an updated style (or vice versa).
      if (p.lineSpacing != old.paragraph.lineSpacing
          || !p.format.lineRule.equals(old.paragraph.format.lineRule))
        result = result.with("spacing", "" + p.lineSpacing).with("lineRule", p.format.lineRule);
    }
    return result.trackedProperties(p, change);
  }

  private static String value(Paragraph p, String key) {
    if ("align".equals(key)) return p.align;
    if ("list".equals(key)) return p.list;
    if ("listFormat".equals(key)) return p.listFormat;
    if ("before".equals(key)) return "" + p.before;
    if ("after".equals(key)) return "" + p.after;
    if ("indent".equals(key)) return "" + p.indent;
    if ("spacing".equals(key)) return "" + p.lineSpacing;
    if ("lineRule".equals(key)) return p.format.lineRule;
    if ("keepNext".equals(key)) return "" + p.keepNext;
    if ("listLevel".equals(key)) return "" + p.listLevel;
    if ("rightIndent".equals(key)) return "" + p.format.rightIndent;
    if ("firstIndent".equals(key)) return "" + p.format.firstIndent;
    if ("tabs".equals(key)) return TabStop.encode(p.format.tabs);
    if ("tabInterval".equals(key)) return "" + p.format.tabInterval;
    if ("outlineLevel".equals(key)) return "" + p.format.outlineLevel;
    if ("keepLines".equals(key)) return "" + p.format.keepLines;
    return "" + p.format.widowControl;
  }

  private static TextStyle merge(TextStyle s, TextStyle a, TextStyle b) {
    boolean inheritedUnderline = s.characters.underlineStyle.equals(a.characters.underlineStyle);
    CharacterFormat characters = s.characters.merge(a.characters, b.characters);
    return new TextStyle(
            s.font.equals(a.font) ? b.font : s.font,
            s.size == a.size ? b.size : s.size,
            s.color == a.color ? b.color : s.color,
            s.bold == a.bold ? b.bold : s.bold,
            s.italic == a.italic ? b.italic : s.italic,
            inheritedUnderline ? b.underline : s.underline,
            s.strike == a.strike ? b.strike : s.strike,
            s.background == a.background ? b.background : s.background,
            s.link,
            s.script.equals(a.script) ? b.script : s.script)
        .characters(characters)
        .revision(s.revision)
        .formatRevision(s.formatRevision, s.previousStyle);
  }

  public static Map<String, ParagraphStyle> defaults() {
    return DEFAULTS;
  }

  private static final Map<String, ParagraphStyle> DEFAULTS = buildDefaults();

  private static Map<String, ParagraphStyle> buildDefaults() {
    Map<String, ParagraphStyle> result = new LinkedHashMap<String, ParagraphStyle>();
    String[] names = {
      "Normal",
      "No Spacing",
      "Heading 1",
      "Heading 2",
      "Heading 3",
      "Heading 4",
      "Heading 5",
      "Heading 6",
      "Title",
      "Subtitle",
      "Caption"
    };
    for (String name : names) {
      String id = name.replace(" ", "");
      Paragraph p = new Paragraph("style", new RichText());
      TextStyle text = TextStyle.DEFAULT;
      if (id.startsWith("Heading")) {
        int level = Integer.parseInt(id.substring(7));
        text =
            text.with("size", level == 1 ? "26" : level == 2 ? "18" : level == 3 ? "16" : "14")
                .with("bold", "true");
        p = p.with("keepNext", "true").with("before", "12");
      }
      if ("NoSpacing".equals(id)) p = p.with("before", "0").with("after", "0").with("spacing", "1");
      if ("Title".equals(id)) text = text.with("size", "32");
      if ("Caption".equals(id)) text = text.with("italic", "true");
      if ("Subtitle".equals(id)) text = text.with("size", "16").with("color", "666666");
      result.put(id, new ParagraphStyle(id, name, p, text));
    }
    return Collections.unmodifiableMap(result);
  }
}
