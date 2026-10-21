package com.codenameone.wordprocessor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Wordcraft catalog command ids (and their argument names) bound to the session operations the
 * ribbon already uses, so every catalog row can be driven and checked through one scriptable
 * surface. Only ids with real behavior are registered; nothing here is a stub.
 */
final class CatalogCommands implements Commands.Extension {
  /** Word's Grow/Shrink Font ladder. */
  private static final float[] SIZES = {
    8, 9, 10, 10.5f, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72
  };

  private static final List<String> ALIGN = Arrays.asList("left", "center", "right", "both", "distribute");

  public void register(final Commands c) {
    // Character formatting.
    toggle(c, "format.bold", "Toggle bold", "bold");
    toggle(c, "format.italic", "Toggle italic", "italic");
    toggle(c, "format.strikethrough", "Toggle strikethrough", "strike");
    toggle(c, "format.doubleUnderline", "Toggle double underline", "doubleUnderline");
    toggle(c, "format.doubleStrikethrough", "Toggle double strikethrough", "doubleStrike");
    toggle(c, "format.smallCaps", "Toggle small caps", "smallCaps");
    toggle(c, "format.allCaps", "Toggle all caps", "allCaps");
    toggle(c, "format.hidden", "Toggle hidden text", "hidden");
    toggle(c, "format.outline", "Toggle outline effect", "outline");
    toggle(c, "format.shadow", "Toggle shadow effect", "shadow");
    toggle(c, "format.emboss", "Toggle emboss effect", "emboss");
    toggle(c, "format.engrave", "Toggle engrave effect", "engrave");
    script(c, "format.superscript", "superscript");
    script(c, "format.subscript", "subscript");
    c.edit("format.underline", "style?:string",
        "Toggle underline, or set an underline style", (s, a) -> {
          if (!a.containsKey("style")) s.format("underline", "" + !s.activeStyle().underline);
          else if ("none".equals(a.get("style"))) s.format("underline", "false");
          else {
            s.format("underline", "true");
            s.format("underlineStyle", Commands.str(a, "style"));
          }
          return null;
        });
    c.edit("format.font", "name:string",
        "Set the font family", (s, a) -> format(s, "font", Commands.str(a, "name")));
    c.edit("format.size", "size:number",
        "Set the font size in points (1-1638)", (s, a) -> {
          float size = ((Number) a.get("size")).floatValue();
          if (size < 1 || size > 1638) throw new IllegalArgumentException("Size must be 1-1638");
          return format(s, "size", trim(size));
        });
    c.edit("format.growFont", "",
        "Increase to the next font size",
        (s, a) -> format(s, "size", trim(step(s.activeStyle().size, true))));
    c.edit("format.shrinkFont", "",
        "Decrease to the previous font size",
        (s, a) -> format(s, "size", trim(step(s.activeStyle().size, false))));
    c.edit("format.color", "color:string",
        "Set the font color (RRGGBB or auto)",
        (s, a) -> format(s, "color", color(Commands.str(a, "color"), hex(TextStyle.DEFAULT.color))));
    c.edit("format.highlight", "color:string",
        "Set the highlight color (RRGGBB or none)",
        (s, a) -> format(s, "background", color(Commands.str(a, "color"), hex(TextStyle.DEFAULT.background))));
    c.edit("format.shading", "color:string",
        "Set character shading (RRGGBB or none)",
        (s, a) -> format(s, "shading", color(Commands.str(a, "color"), "none")));
    c.edit("format.border", "color:string",
        "Set a character border (RRGGBB or none)",
        (s, a) -> format(s, "border", color(Commands.str(a, "color"), "none")));
    c.edit("format.phonetic", "text:string",
        "Set the phonetic guide (ruby) text; empty removes it",
        (s, a) -> format(s, "ruby", Commands.str(a, "text")));
    c.edit("format.enclose", "shape:string",
        "Enclose characters: none, circle, square, triangle or diamond", (s, a) -> {
          String shape = Commands.str(a, "shape");
          if (!java.util.Arrays.asList(CharacterFormat.ENCLOSURES).contains(shape))
            throw new IllegalArgumentException("Unknown enclosure: " + shape);
          return format(s, "enclose", shape);
        });
    c.edit("review.language", "lang:string,default?:boolean",
        "Set the proofing language tag of the selection (e.g. en-US; empty inherits); default also"
            + " makes it the document default language (w:docDefaults) used by untagged text and table formulas",
        (s, a) -> {
          String lang = Commands.str(a, "lang").replace('_', '-');
          for (int i = 0; i < lang.length(); i++) {
            char ch = lang.charAt(i);
            if (!(ch == '-' || (ch < 128 && Unicode.letterOrDigit(ch))))
              throw new IllegalArgumentException("Invalid language tag");
          }
          if (Commands.flag(a, "default", false)) {
            if (lang.isEmpty()) throw new IllegalArgumentException("Invalid language tag");
            int dash = lang.indexOf('-');
            if (dash > 0) lang = lang.substring(0, dash).toLowerCase() + lang.substring(dash).toUpperCase();
            s.documentProperties(s.document().properties.withSetting("lang", lang));
          }
          return format(s, "lang", lang);
        });
    c.edit(
        "format.textEffects", "outline?:boolean,shadow?:boolean,emboss?:boolean,engrave?:boolean",
        "Apply outline, shadow, emboss and engrave together; omitted effects are unchanged",
        (s, a) -> {
          for (String k : new String[] {"outline", "shadow", "emboss", "engrave"})
            if (a.containsKey(k)) s.format(k, String.valueOf(Boolean.TRUE.equals(a.get(k))));
          return null;
        });
    c.edit("format.spacing", "value:number",
        "Character spacing in points", (s, a) -> format(s, "characterSpacing", number(a, "value")));
    c.edit("format.scale", "value:number",
        "Character scale in percent", (s, a) -> format(s, "characterScale", number(a, "value")));
    c.edit("format.position", "value:number",
        "Raise (positive) or lower character position in points",
        (s, a) -> format(s, "characterPosition", number(a, "value")));
    c.edit("format.clear", "",
        "Clear direct character formatting", (s, a) -> {
          var d = TextStyle.DEFAULT;
          s.format("font", d.font);
          s.format("size", trim(d.size));
          s.format("color", Integer.toHexString(d.color));
          s.format("background", Integer.toHexString(d.background));
          for (String k : new String[] {"bold", "italic", "underline", "strike"})
            s.format(k, "false");
          s.format("script", "baseline");
          for (String k :
              new String[] {
                "doubleUnderline",
                "doubleStrike",
                "smallCaps",
                "allCaps",
                "hidden",
                "outline",
                "shadow",
                "emboss",
                "engrave"
              }) s.format(k, "false");
          for (String k :
              new String[] {"characterSpacing", "characterPosition"}) s.format(k, "0");
          s.format("characterScale", "100");
          return null;
        });

    // Paragraph formatting.
    align(c, "para.alignLeft", "left");
    align(c, "para.alignCenter", "center");
    align(c, "para.alignRight", "right");
    align(c, "para.justify", "both");
    c.edit("para.align", "value:string",
        "Set paragraph alignment (left, center, right, both)", (s, a) -> {
          String v = "justify".equals(a.get("value")) ? "both" : Commands.str(a, "value");
          if (!ALIGN.contains(v)) throw new IllegalArgumentException("Unknown alignment " + v);
          s.paragraph("align", v);
          return null;
        });
    c.edit("para.indent", "",
        "Increase the left indent by one 36pt step", (s, a) -> indent(s, 36));
    c.edit("para.outdent", "",
        "Decrease the left indent by one 36pt step", (s, a) -> indent(s, -36));
    c.edit("para.indents", "left?:number,right?:number,firstLine?:number",
        "Set left/right/first-line indents in points", (s, a) -> {
          if (a.containsKey("left")) s.paragraph("indent", number(a, "left"));
          if (a.containsKey("right")) s.paragraph("rightIndent", number(a, "right"));
          if (a.containsKey("firstLine")) s.paragraph("firstIndent", number(a, "firstLine"));
          return null;
        });
    c.edit("para.hangingIndent", "by?:number",
        "Hang the first line by the given amount (default 36pt)", (s, a) -> {
          float by = Commands.number(a, "by", 36);
          Paragraph p = current(s);
          if (p.indent < by) s.paragraph("indent", trim(by));
          s.paragraph("firstIndent", trim(-by));
          return null;
        });
    c.edit("para.spacing", "before?:number,after?:number",
        "Space before/after in points", (s, a) -> {
          if (a.containsKey("before")) s.paragraph("before", number(a, "before"));
          if (a.containsKey("after")) s.paragraph("after", number(a, "after"));
          return null;
        });
    c.edit("para.addSpaceBefore", "",
        "Toggle 12pt space before the paragraph", (s, a) -> {
          s.paragraph("before", current(s).before > 0 ? "0" : "12");
          return null;
        });
    c.edit("para.removeSpaceAfter", "",
        "Toggle space after the paragraph", (s, a) -> {
          s.paragraph("after", current(s).after > 0 ? "0" : "8");
          return null;
        });
    c.edit("para.lineSpacing", "value?:number,exactly?:number,atLeast?:number",
        "Line spacing as a multiple (value) or exact points (exactly) or minimum (atLeast)",
        (s, a) -> {
          if (a.containsKey("exactly")) {
            s.paragraph("lineRule", "exact");
            s.paragraph("spacing", number(a, "exactly"));
          } else if (a.containsKey("atLeast")) {
            s.paragraph("lineRule", "atLeast");
            s.paragraph("spacing", number(a, "atLeast"));
          } else if (a.containsKey("value")) {
            s.paragraph("lineRule", "auto");
            s.paragraph("spacing", number(a, "value"));
          } else throw new IllegalArgumentException("Missing argument: value");
          return null;
        });
    flag(c, "para.keepNext", "Toggle keep with next", "keepNext");
    flag(c, "para.keepLines", "Toggle keep lines together", "keepLines");
    flag(c, "para.pageBreakBefore", "Toggle page break before", "pageBreak");
    flag(c, "para.widowControl", "Toggle widow/orphan control", "widowControl");
    c.edit("para.outlineLevel", "level:integer",
        "Outline level 1-9, or 0 for body text", (s, a) -> {
          int level = Commands.integer(a, "level");
          if (level < 0 || level > 9) throw new IllegalArgumentException("Level must be 0-9");
          s.paragraph("outlineLevel", "" + level);
          return null;
        });

    // Design tab: page color, watermark and page borders of the active section.
    c.edit("design.pageColor", "color?:any",
        "Page color RRGGBB, or none/null for white paper", (s, a) -> {
          var v = a.get("color");
          var sec = s.activeSection().raw;
          s.section(
              sec.decor(
                  sec.decor.pageColor(
                      v == null || "none".equals(v)
                          ? -1
                          : Integer.parseInt(color(String.valueOf(v), "ffffff"), 16))));
          return null;
        });
    c.edit("design.watermark", "text?:string,color?:string",
        "Diagonal text watermark behind the page; empty text removes it", (s, a) -> {
          var sec = s.activeSection().raw;
          String text = Commands.str(a, "text", "CONFIDENTIAL");
          int rgb =
              a.containsKey("color") ? Integer.parseInt(color(Commands.str(a, "color"), "c0c0c0"), 16) : 0xc0c0c0;
          s.section(sec.decor(sec.decor.watermark(text, rgb)));
          return null;
        });
    c.edit(
        "design.pageBorders", "kind:string,style?:string,width?:number,color?:string,space?:number",
        "Page borders: kind none, all, outside, top, bottom, left or right; space is from the page edge",
        (s, a) -> {
          var sec = s.activeSection().raw;
          var side =
              new ParagraphBorders.Side(
                  Commands.number(a, "width", .5f),
                  a.containsKey("color") ? Integer.parseInt(color(Commands.str(a, "color"), "000000"), 16) : 0,
                  Commands.number(a, "space", 24),
                  Commands.str(a, "style", "single"));
          s.section(
              sec.decor(
                  sec.decor.borders(
                      ParagraphBorders.preset(Commands.str(a, "kind"), side, sec.decor.borders))));
          return null;
        });

    c.edit("layout.pageNumberFormat", "format:string,start?:integer",
        "Section page-number format (decimal, upperRoman, lowerRoman, upperLetter, lowerLetter) and optional restart",
        (s, a) -> {
          var sec = s.activeSection().raw;
          sec = sec.decor(sec.decor.numberFormat(Commands.str(a, "format")));
          if (a.containsKey("start")) sec = sec.numbering(Commands.integer(a, "start"));
          s.section(sec);
          return null;
        });
    c.edit("layout.lineNumbers", "countBy:integer,restart?:string",
        "Margin line numbers: countBy (0 = off) and restart page, section or continuous",
        (s, a) -> {
          var sec = s.activeSection().raw;
          s.section(
              sec.decor(
                  sec.decor.lineNumbers(
                      Commands.integer(a, "countBy"),
                      Commands.str(a, "restart", sec.decor.lineRestart))));
          return null;
        });

    // Borders and shading.
    c.edit("para.borders", "kind:string,style?:string,width?:number,color?:string,space?:number",
        "Paragraph borders: kind none, all, outside, top, bottom, left or right", (s, a) -> {
          Paragraph p = current(s);
          var side =
              new ParagraphBorders.Side(
                  Commands.number(a, "width", .5f),
                  a.containsKey("color") ? Integer.parseInt(color(Commands.str(a, "color"), "000000"), 16) : 0,
                  Commands.number(a, "space", 1),
                  Commands.str(a, "style", "single"));
          s.paragraph(
              "borders",
              ParagraphBorders.preset(Commands.str(a, "kind"), side, p.format.borders).encode());
          return null;
        });
    c.edit("para.shading", "color?:any",
        "Paragraph shading RRGGBB, or none/null to clear", (s, a) -> {
          var v = a.get("color");
          s.paragraph(
              "shading", v == null || "none".equals(v) ? "" : color(String.valueOf(v), "ffffff"));
          return null;
        });
    c.edit("insert.horizontalLine", "",
        "Insert an empty paragraph with a bottom border after the caret paragraph", (s, a) -> {
          Paragraph p = current(s);
          s.select(s.selection().block, p.text.length(), p.text.length());
          s.enterParagraph();
          // The rule and the paragraph after it start plain, without the caret paragraph's fill.
          s.paragraph("shading", "");
          s.paragraph(
              "borders",
              new ParagraphBorders(
                      ParagraphBorders.Side.NONE,
                      ParagraphBorders.Side.NONE,
                      new ParagraphBorders.Side(.75f, 0xa0a0a0, 1, "single"),
                      ParagraphBorders.Side.NONE)
                  .encode());
          s.enterParagraph();
          s.paragraph("borders", "");
          s.paragraph("shading", "");
          return null;
        });

    // Lists.
    c.edit("para.bullets", "kind?:string,off?:boolean",
        "Toggle bullets, or turn lists off", (s, a) -> list(s, "bullet", a));
    c.edit("para.numbering", "kind?:string,off?:boolean",
        "Toggle numbering with a number format (decimal, lowerLetter, upperRoman, ...)", (s, a) -> {
          list(s, "number", a);
          if (a.containsKey("kind") && !Boolean.TRUE.equals(a.get("off")))
            s.paragraph("listFormat", Commands.str(a, "kind"));
          return null;
        });
    c.edit("para.multilevel", "kind:string",
        "Multilevel list: outline (1. a. i.), legal (1.1.1.) or bullets; off removes the list",
        (s, a) -> {
          String kind = Commands.str(a, "kind");
          if ("off".equals(kind)) {
            s.paragraph("list", "");
            return null;
          }
          if ("bullets".equals(kind)) {
            s.paragraph("list", "bullet");
            s.paragraph("listFormat", "decimal");
            return null;
          }
          if (!"outline".equals(kind) && !"legal".equals(kind))
            throw new IllegalArgumentException("Kind must be outline, legal, bullets or off");
          s.paragraph("list", "number");
          s.paragraph("listFormat", kind);
          return null;
        });
    c.edit("para.defineNumber", "format:string,label?:string",
        "Define a number format: format (decimal, lowerLetter, ...) and a Word label like %1) or (%1)",
        (s, a) -> {
          String f = Commands.str(a, "format");
          String value = a.containsKey("label") ? f + "|" + Commands.str(a, "label") : f;
          if (!ListNumbers.valid(value) || "bullet".equals(f))
            throw new IllegalArgumentException("Invalid number format or label");
          s.paragraph("list", "number");
          s.paragraph("listFormat", value);
          return null;
        });
    c.edit("para.defineBullet", "symbol:string",
        "Define a bullet character (any symbol, e.g. \u2713 or \u2794)", (s, a) -> {
          var v = "bullet|" + Commands.str(a, "symbol");
          if (!ListNumbers.valid(v)) throw new IllegalArgumentException("Invalid bullet symbol");
          s.paragraph("list", "bullet");
          s.paragraph("listFormat", v);
          return null;
        });
    c.edit("list.define", "kind:string,format?:string,label?:string",
        "Define the caret list: kind outline|legal|bullets|number with optional format and label",
        (s, a) ->
            "number".equals(Commands.str(a, "kind"))
                ? c.execute(
                    s,
                    "para.defineNumber",
                    a.containsKey("label")
                        ? Json.object("format", a.containsKey("format") ? a.get("format") : "decimal", "label", a.get("label"))
                        : Json.object("format", a.containsKey("format") ? a.get("format") : "decimal"))
                : c.execute(s, "para.multilevel", Json.object("kind", a.get("kind"))));
    c.edit("para.listLevel", "level:integer",
        "Set the list level (0-8)", (s, a) -> {
          int level = Commands.integer(a, "level");
          if (level < 0 || level > 8) throw new IllegalArgumentException("Level must be 0-8");
          s.paragraph("listLevel", "" + level);
          return null;
        });
    c.edit("para.restartNumbering", "",
        "Restart numbering at 1 from this paragraph", (s, a) -> {
          s.paragraph("listStart", "1");
          return null;
        });
    c.edit("para.setNumberingValue", "value:integer",
        "Start numbering at the given value", (s, a) -> {
          int v = Commands.integer(a, "value");
          if (v < 0) throw new IllegalArgumentException("Value must not be negative");
          s.paragraph("listStart", "" + v);
          return null;
        });

    // Styles.
    c.edit("para.style", "style:string",
        "Apply a paragraph style by id", (s, a) -> {
          s.applyStyle(Commands.str(a, "style"));
          return null;
        });
    style(c, "para.normal", "Normal");
    style(c, "para.heading1", "Heading1");
    style(c, "para.heading2", "Heading2");
    style(c, "para.heading3", "Heading3");

    // Editing.
    c.query("edit.copy", "",
        "Copy the selection; returns the copied text", (s, a) -> {
          WorkflowCommands.record(s);
          return s.selectedText();
        });
    c.edit("edit.cut", "",
        "Delete the selection; returns the removed text", (s, a) -> {
          var text = s.selectedText();
          WorkflowCommands.record(s);
          if (!s.selection().collapsed()) s.replace("");
          return text;
        });
    c.edit("edit.paste", "text:string",
        "Paste plain text at the selection", (s, a) -> {
          s.pasteText(Commands.str(a, "text"));
          return null;
        });
    c.query("select.all", "",
        "Select the whole document body", (s, a) -> {
          var d = s.document();
          int last = d.blocks.size() - 1;
          var b = d.blocks.get(last);
          s.selectRange(0, 0, last, b instanceof Paragraph p ? p.text.length() : 0);
          return null;
        });
    c.query("select.text", "text:string",
        "Select the first occurrence of literal text in a paragraph", (s, a) -> {
          String needle = Commands.str(a, "text");
          if (needle.isEmpty()) throw new IllegalArgumentException("Text is empty");
          var d = s.document();
          for (int i = 0; i < d.blocks.size(); i++)
            if (d.blocks.get(i) instanceof Paragraph) {
              int at = ((Paragraph) d.blocks.get(i)).text.plainText().indexOf(needle);
              if (at >= 0) {
                s.select(i, at, at + needle.length());
                return true;
              }
            }
          return false;
        });
    c.edit("text.newParagraph", "",
        "Split the paragraph at the caret", (s, a) -> {
          s.enterParagraph();
          return null;
        });
    c.edit("text.columnBreak", "",
        "Start the following text in the next column", (s, a) -> {
          s.columnBreak();
          return null;
        });
    c.edit("text.delete", "",
        "Delete forward", (s, a) -> {
          s.deleteForward();
          return null;
        });
    c.edit("text.tab", "",
        "Insert a tab", (s, a) -> {
          s.replace("\t");
          return null;
        });
    insertText(c, "text.nbsp", "Insert a non-breaking space", "\u00a0");
    insertText(c, "text.nbHyphen", "Insert a non-breaking hyphen", "\u2011");
    insertText(c, "text.optionalHyphen", "Insert an optional hyphen", "\u00ad");
    c.edit("insert.pageBreak", "",
        "Insert a page break before the text after the caret", (s, a) -> {
          s.enterParagraph();
          s.paragraph("pageBreak", "true");
          return null;
        });

    // Tables (the caret must be in a table cell).
    table(c, "table.deleteRow", "deleteRow", false);
    table(c, "table.deleteColumn", "deleteColumn", false);
    c.edit("table.deleteTable", "",
        "Delete the table at the caret", (s, a) -> {
          int block = tableBlock(s);
          // deleteTable leaves for the story holding the table (body or enclosing cell).
          s.deleteTable(block);
          return null;
        });
  }

  private static void toggle(Commands c, String id, String description, final String key) {
    c.register(
        id,
        description,
        "value?:boolean",
        true,
        (s, a) ->
            format(
                s,
                key,
                a.containsKey("value") ? "" + a.get("value") : "" + !flag(s.activeStyle(), key)));
  }

  private static boolean flag(TextStyle t, String key) {
    var f = t.characters;
    if ("bold".equals(key)) return t.bold;
    if ("italic".equals(key)) return t.italic;
    if ("strike".equals(key)) return t.strike;
    if ("doubleUnderline".equals(key)) return f.doubleUnderline;
    if ("doubleStrike".equals(key)) return f.doubleStrike;
    if ("smallCaps".equals(key)) return f.smallCaps;
    if ("allCaps".equals(key)) return f.allCaps;
    if ("hidden".equals(key)) return f.hidden;
    if ("outline".equals(key)) return f.outline;
    if ("shadow".equals(key)) return f.shadow;
    if ("emboss".equals(key)) return f.emboss;
    if ("engrave".equals(key)) return f.engrave;
    throw new IllegalArgumentException(key);
  }

  private static void script(Commands c, String id, final String value) {
    c.register(
        id,
        "Toggle " + value,
        "",
        true,
        (s, a) -> format(s, "script", value.equals(s.activeStyle().script) ? "baseline" : value));
  }

  private static void align(Commands c, String id, final String value) {
    c.register(
        id,
        "Align paragraphs " + value,
        "",
        true,
        (s, a) -> {
          s.paragraph("align", value);
          return null;
        });
  }

  private static void flag(Commands c, String id, String description, final String key) {
    c.register(
        id,
        description,
        "value?:boolean",
        true,
        (s, a) -> {
          Paragraph p = current(s);
          boolean now =
              "keepNext".equals(key)
                  ? p.keepNext
                  : "pageBreak".equals(key)
                      ? p.pageBreak
                      : "keepLines".equals(key) ? p.format.keepLines : p.format.widowControl;
          s.paragraph(key, a.containsKey("value") ? "" + a.get("value") : "" + !now);
          return null;
        });
  }

  private static void style(Commands c, String id, final String style) {
    c.register(
        id,
        "Apply the " + style + " style",
        "",
        true,
        (s, a) -> {
          s.applyStyle(style);
          return null;
        });
  }

  private static void insertText(Commands c, String id, String description, final String text) {
    c.register(
        id,
        description,
        "",
        true,
        (s, a) -> {
          s.replace(text);
          return null;
        });
  }

  private static void table(Commands c, String id, final String action, final boolean after) {
    c.register(
        id,
        "Table " + action + (after ? " after" : "") + " at the caret cell",
        "",
        true,
        (s, a) -> {
          int block = tableBlock(s);
          int index =
              action.endsWith("Row")
                  ? s.cellRow() + (after ? 1 : 0)
                  : s.cellColumn() + (after ? 1 : 0);
          s.tableStructure(block, action, index);
          return null;
        });
  }

  private static int tableBlock(Session s) {
    if (!s.inCell()) throw new IllegalArgumentException("The caret is not in a table");
    return s.cellBlock();
  }

  private static Object list(Session s, String kind, Map<String, Object> a) {
    boolean off = Boolean.TRUE.equals(a.get("off")) || kind.equals(current(s).list) && !a.containsKey("kind");
    s.paragraph("list", off ? "" : kind);
    return null;
  }

  private static Object indent(Session s, float delta) {
    s.paragraph("indent", trim(Math.max(0, current(s).indent + delta)));
    return null;
  }

  private static Paragraph current(Session s) {
    var b = s.document().blocks.get(s.selection().block);
    if (!(b instanceof Paragraph)) throw new IllegalArgumentException("Not in a paragraph");
    return (Paragraph) b;
  }

  private static Object format(Session s, String key, String value) {
    s.format(key, value);
    return null;
  }

  static float step(float size, boolean grow) {
    if (grow) {
      for (float f : SIZES) if (f > size + .01f) return f;
      return Math.min(1638, (float) Math.floor(size / 10) * 10 + 10);
    }
    for (int i = SIZES.length - 1; i >= 0; i--) if (SIZES[i] < size - .01f) return SIZES[i];
    return Math.max(1, size - 1);
  }

  private static String color(String value, String auto) {
    String v = value.startsWith("#") ? value.substring(1) : value;
    if ("auto".equals(v) || "none".equals(v)) return auto;
    if (v.length() != 6) throw new IllegalArgumentException("Color must be RRGGBB");
    Integer.parseInt(v, 16);
    return v.toLowerCase();
  }

  private static String hex(int rgb) {
    String h = Integer.toHexString(0x1000000 | (rgb & 0xffffff));
    return h.substring(1);
  }

  private static String number(Map<String, Object> a, String k) {
    return trim(((Number) a.get(k)).floatValue());
  }

  private static String trim(float f) {
    return f == (int) f ? Integer.toString((int) f) : Float.toString(f);
  }
}
