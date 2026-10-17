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

  private static final List<String> ALIGN = Arrays.asList("left", "center", "right", "both");

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
    c.register(
        "format.underline",
        "Toggle underline, or set an underline style",
        "style?:string",
        true,
        (s, a) -> {
          if (!a.containsKey("style")) s.format("underline", "" + !s.activeStyle().underline);
          else if ("none".equals(a.get("style"))) s.format("underline", "false");
          else {
            s.format("underline", "true");
            s.format("underlineStyle", Commands.str(a, "style"));
          }
          return null;
        });
    c.register(
        "format.font",
        "Set the font family",
        "name:string",
        true,
        (s, a) -> format(s, "font", Commands.str(a, "name")));
    c.register(
        "format.size",
        "Set the font size in points (1-1638)",
        "size:number",
        true,
        (s, a) -> {
          float size = ((Number) a.get("size")).floatValue();
          if (size < 1 || size > 1638) throw new IllegalArgumentException("Size must be 1-1638");
          return format(s, "size", trim(size));
        });
    c.register(
        "format.growFont",
        "Increase to the next font size",
        "",
        true,
        (s, a) -> format(s, "size", trim(step(s.activeStyle().size, true))));
    c.register(
        "format.shrinkFont",
        "Decrease to the previous font size",
        "",
        true,
        (s, a) -> format(s, "size", trim(step(s.activeStyle().size, false))));
    c.register(
        "format.color",
        "Set the font color (RRGGBB or auto)",
        "color:string",
        true,
        (s, a) -> format(s, "color", color(Commands.str(a, "color"), hex(TextStyle.DEFAULT.color))));
    c.register(
        "format.highlight",
        "Set the highlight color (RRGGBB or none)",
        "color:string",
        true,
        (s, a) -> format(s, "background", color(Commands.str(a, "color"), hex(TextStyle.DEFAULT.background))));
    c.register(
        "format.shading",
        "Set character shading (RRGGBB or none)",
        "color:string",
        true,
        (s, a) -> format(s, "shading", color(Commands.str(a, "color"), "none")));
    c.register(
        "format.border",
        "Set a character border (RRGGBB or none)",
        "color:string",
        true,
        (s, a) -> format(s, "border", color(Commands.str(a, "color"), "none")));
    c.register(
        "format.phonetic",
        "Set the phonetic guide (ruby) text; empty removes it",
        "text:string",
        true,
        (s, a) -> format(s, "ruby", Commands.str(a, "text")));
    c.register(
        "format.enclose",
        "Enclose characters: none, circle, square, triangle or diamond",
        "shape:string",
        true,
        (s, a) -> {
          String shape = Commands.str(a, "shape");
          if (!java.util.Arrays.asList(CharacterFormat.ENCLOSURES).contains(shape))
            throw new IllegalArgumentException("Unknown enclosure: " + shape);
          return format(s, "enclose", shape);
        });
    c.register(
        "review.language",
        "Set the proofing language tag of the selection (e.g. en-US; empty inherits)",
        "lang:string",
        true,
        (s, a) -> {
          String lang = Commands.str(a, "lang");
          for (int i = 0; i < lang.length(); i++) {
            char ch = lang.charAt(i);
            if (!(ch == '-' || (ch < 128 && Character.isLetterOrDigit(ch))))
              throw new IllegalArgumentException("Invalid language tag");
          }
          return format(s, "lang", lang);
        });
    c.register(
        "format.textEffects",
        "Apply outline, shadow, emboss and engrave together; omitted effects are unchanged",
        "outline?:boolean,shadow?:boolean,emboss?:boolean,engrave?:boolean",
        true,
        (s, a) -> {
          for (String k : new String[] {"outline", "shadow", "emboss", "engrave"})
            if (a.containsKey(k)) s.format(k, String.valueOf(Boolean.TRUE.equals(a.get(k))));
          return null;
        });
    c.register(
        "format.spacing",
        "Character spacing in points",
        "value:number",
        true,
        (s, a) -> format(s, "characterSpacing", number(a, "value")));
    c.register(
        "format.scale",
        "Character scale in percent",
        "value:number",
        true,
        (s, a) -> format(s, "characterScale", number(a, "value")));
    c.register(
        "format.position",
        "Raise (positive) or lower character position in points",
        "value:number",
        true,
        (s, a) -> format(s, "characterPosition", number(a, "value")));
    c.register(
        "format.clear",
        "Clear direct character formatting",
        "",
        true,
        (s, a) -> {
          TextStyle d = TextStyle.DEFAULT;
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
    c.register(
        "para.align",
        "Set paragraph alignment (left, center, right, both)",
        "value:string",
        true,
        (s, a) -> {
          String v = "justify".equals(a.get("value")) ? "both" : Commands.str(a, "value");
          if (!ALIGN.contains(v)) throw new IllegalArgumentException("Unknown alignment " + v);
          s.paragraph("align", v);
          return null;
        });
    c.register(
        "para.indent",
        "Increase the left indent by one 36pt step",
        "",
        true,
        (s, a) -> indent(s, 36));
    c.register(
        "para.outdent",
        "Decrease the left indent by one 36pt step",
        "",
        true,
        (s, a) -> indent(s, -36));
    c.register(
        "para.indents",
        "Set left/right/first-line indents in points",
        "left?:number,right?:number,firstLine?:number",
        true,
        (s, a) -> {
          if (a.containsKey("left")) s.paragraph("indent", number(a, "left"));
          if (a.containsKey("right")) s.paragraph("rightIndent", number(a, "right"));
          if (a.containsKey("firstLine")) s.paragraph("firstIndent", number(a, "firstLine"));
          return null;
        });
    c.register(
        "para.hangingIndent",
        "Hang the first line by the given amount (default 36pt)",
        "by?:number",
        true,
        (s, a) -> {
          float by = a.containsKey("by") ? ((Number) a.get("by")).floatValue() : 36;
          Paragraph p = current(s);
          if (p.indent < by) s.paragraph("indent", trim(by));
          s.paragraph("firstIndent", trim(-by));
          return null;
        });
    c.register(
        "para.spacing",
        "Space before/after in points",
        "before?:number,after?:number",
        true,
        (s, a) -> {
          if (a.containsKey("before")) s.paragraph("before", number(a, "before"));
          if (a.containsKey("after")) s.paragraph("after", number(a, "after"));
          return null;
        });
    c.register(
        "para.addSpaceBefore",
        "Toggle 12pt space before the paragraph",
        "",
        true,
        (s, a) -> {
          s.paragraph("before", current(s).before > 0 ? "0" : "12");
          return null;
        });
    c.register(
        "para.removeSpaceAfter",
        "Toggle space after the paragraph",
        "",
        true,
        (s, a) -> {
          s.paragraph("after", current(s).after > 0 ? "0" : "8");
          return null;
        });
    c.register(
        "para.lineSpacing",
        "Line spacing as a multiple (value) or exact points (exactly) or minimum (atLeast)",
        "value?:number,exactly?:number,atLeast?:number",
        true,
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
    c.register(
        "para.outlineLevel",
        "Outline level 1-9, or 0 for body text",
        "level:integer",
        true,
        (s, a) -> {
          int level = Commands.integer(a, "level");
          if (level < 0 || level > 9) throw new IllegalArgumentException("Level must be 0-9");
          s.paragraph("outlineLevel", "" + level);
          return null;
        });

    // Design tab: page color, watermark and page borders of the active section.
    c.register(
        "design.pageColor",
        "Page color RRGGBB, or none/null for white paper",
        "color?:any",
        true,
        (s, a) -> {
          Object v = a.get("color");
          Section sec = s.activeSection().raw;
          s.section(
              sec.decor(
                  sec.decor.pageColor(
                      v == null || "none".equals(v)
                          ? -1
                          : Integer.parseInt(color(String.valueOf(v), "ffffff"), 16))));
          return null;
        });
    c.register(
        "design.watermark",
        "Diagonal text watermark behind the page; empty text removes it",
        "text?:string,color?:string",
        true,
        (s, a) -> {
          Section sec = s.activeSection().raw;
          String text = a.containsKey("text") ? Commands.str(a, "text") : "CONFIDENTIAL";
          int rgb =
              a.containsKey("color") ? Integer.parseInt(color(Commands.str(a, "color"), "c0c0c0"), 16) : 0xc0c0c0;
          s.section(sec.decor(sec.decor.watermark(text, rgb)));
          return null;
        });
    c.register(
        "design.pageBorders",
        "Page borders: kind none, all, outside, top, bottom, left or right; space is from the page edge",
        "kind:string,style?:string,width?:number,color?:string,space?:number",
        true,
        (s, a) -> {
          Section sec = s.activeSection().raw;
          ParagraphBorders.Side side =
              new ParagraphBorders.Side(
                  a.containsKey("width") ? ((Number) a.get("width")).floatValue() : .5f,
                  a.containsKey("color") ? Integer.parseInt(color(Commands.str(a, "color"), "000000"), 16) : 0,
                  a.containsKey("space") ? ((Number) a.get("space")).floatValue() : 24,
                  a.containsKey("style") ? Commands.str(a, "style") : "single");
          s.section(
              sec.decor(
                  sec.decor.borders(
                      ParagraphBorders.preset(Commands.str(a, "kind"), side, sec.decor.borders))));
          return null;
        });

    c.register(
        "layout.pageNumberFormat",
        "Section page-number format (decimal, upperRoman, lowerRoman, upperLetter, lowerLetter) and optional restart",
        "format:string,start?:integer",
        true,
        (s, a) -> {
          Section sec = s.activeSection().raw;
          sec = sec.decor(sec.decor.numberFormat(Commands.str(a, "format")));
          if (a.containsKey("start")) sec = sec.numbering(Commands.integer(a, "start"));
          s.section(sec);
          return null;
        });
    c.register(
        "layout.lineNumbers",
        "Margin line numbers: countBy (0 = off) and restart page, section or continuous",
        "countBy:integer,restart?:string",
        true,
        (s, a) -> {
          Section sec = s.activeSection().raw;
          s.section(
              sec.decor(
                  sec.decor.lineNumbers(
                      Commands.integer(a, "countBy"),
                      a.containsKey("restart") ? Commands.str(a, "restart") : sec.decor.lineRestart)));
          return null;
        });

    // Borders and shading.
    c.register(
        "para.borders",
        "Paragraph borders: kind none, all, outside, top, bottom, left or right",
        "kind:string,style?:string,width?:number,color?:string,space?:number",
        true,
        (s, a) -> {
          Paragraph p = current(s);
          ParagraphBorders.Side side =
              new ParagraphBorders.Side(
                  a.containsKey("width") ? ((Number) a.get("width")).floatValue() : .5f,
                  a.containsKey("color") ? Integer.parseInt(color(Commands.str(a, "color"), "000000"), 16) : 0,
                  a.containsKey("space") ? ((Number) a.get("space")).floatValue() : 1,
                  a.containsKey("style") ? Commands.str(a, "style") : "single");
          s.paragraph(
              "borders",
              ParagraphBorders.preset(Commands.str(a, "kind"), side, p.format.borders).encode());
          return null;
        });
    c.register(
        "para.shading",
        "Paragraph shading RRGGBB, or none/null to clear",
        "color?:any",
        true,
        (s, a) -> {
          Object v = a.get("color");
          s.paragraph(
              "shading", v == null || "none".equals(v) ? "" : color(String.valueOf(v), "ffffff"));
          return null;
        });
    c.register(
        "insert.horizontalLine",
        "Insert an empty paragraph with a bottom border after the caret paragraph",
        "",
        true,
        (s, a) -> {
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
    c.register(
        "para.bullets",
        "Toggle bullets, or turn lists off",
        "kind?:string,off?:boolean",
        true,
        (s, a) -> list(s, "bullet", a));
    c.register(
        "para.numbering",
        "Toggle numbering with a number format (decimal, lowerLetter, upperRoman, ...)",
        "kind?:string,off?:boolean",
        true,
        (s, a) -> {
          list(s, "number", a);
          if (a.containsKey("kind") && !Boolean.TRUE.equals(a.get("off")))
            s.paragraph("listFormat", Commands.str(a, "kind"));
          return null;
        });
    c.register(
        "para.multilevel",
        "Multilevel list: outline (1. a. i.), legal (1.1.1.) or bullets; off removes the list",
        "kind:string",
        true,
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
    c.register(
        "para.defineNumber",
        "Define a number format: format (decimal, lowerLetter, ...) and a Word label like %1) or (%1)",
        "format:string,label?:string",
        true,
        (s, a) -> {
          String f = Commands.str(a, "format");
          String value = a.containsKey("label") ? f + "|" + Commands.str(a, "label") : f;
          if (!ListNumbers.valid(value) || "bullet".equals(f))
            throw new IllegalArgumentException("Invalid number format or label");
          s.paragraph("list", "number");
          s.paragraph("listFormat", value);
          return null;
        });
    c.register(
        "para.defineBullet",
        "Define a bullet character (any symbol, e.g. \u2713 or \u2794)",
        "symbol:string",
        true,
        (s, a) -> {
          String v = "bullet|" + Commands.str(a, "symbol");
          if (!ListNumbers.valid(v)) throw new IllegalArgumentException("Invalid bullet symbol");
          s.paragraph("list", "bullet");
          s.paragraph("listFormat", v);
          return null;
        });
    c.register(
        "list.define",
        "Define the caret list: kind outline|legal|bullets|number with optional format and label",
        "kind:string,format?:string,label?:string",
        true,
        (s, a) ->
            "number".equals(Commands.str(a, "kind"))
                ? c.execute(
                    s,
                    "para.defineNumber",
                    a.containsKey("label")
                        ? Json.object("format", a.containsKey("format") ? a.get("format") : "decimal", "label", a.get("label"))
                        : Json.object("format", a.containsKey("format") ? a.get("format") : "decimal"))
                : c.execute(s, "para.multilevel", Json.object("kind", a.get("kind"))));
    c.register(
        "para.listLevel",
        "Set the list level (0-8)",
        "level:integer",
        true,
        (s, a) -> {
          int level = Commands.integer(a, "level");
          if (level < 0 || level > 8) throw new IllegalArgumentException("Level must be 0-8");
          s.paragraph("listLevel", "" + level);
          return null;
        });
    c.register(
        "para.restartNumbering",
        "Restart numbering at 1 from this paragraph",
        "",
        true,
        (s, a) -> {
          s.paragraph("listStart", "1");
          return null;
        });
    c.register(
        "para.setNumberingValue",
        "Start numbering at the given value",
        "value:integer",
        true,
        (s, a) -> {
          int v = Commands.integer(a, "value");
          if (v < 0) throw new IllegalArgumentException("Value must not be negative");
          s.paragraph("listStart", "" + v);
          return null;
        });

    // Styles.
    c.register(
        "para.style",
        "Apply a paragraph style by id",
        "style:string",
        true,
        (s, a) -> {
          s.applyStyle(Commands.str(a, "style"));
          return null;
        });
    style(c, "para.normal", "Normal");
    style(c, "para.heading1", "Heading1");
    style(c, "para.heading2", "Heading2");
    style(c, "para.heading3", "Heading3");

    // Editing.
    c.register(
        "edit.copy",
        "Copy the selection; returns the copied text",
        "",
        false,
        (s, a) -> s.selectedText());
    c.register(
        "edit.cut",
        "Delete the selection; returns the removed text",
        "",
        true,
        (s, a) -> {
          String text = s.selectedText();
          if (!s.selection().collapsed()) s.replace("");
          return text;
        });
    c.register(
        "edit.paste",
        "Paste plain text at the selection",
        "text:string",
        true,
        (s, a) -> {
          s.pasteText(Commands.str(a, "text"));
          return null;
        });
    c.register(
        "select.all",
        "Select the whole document body",
        "",
        false,
        (s, a) -> {
          Document d = s.document();
          int last = d.blocks.size() - 1;
          Block b = d.blocks.get(last);
          s.selectRange(0, 0, last, b instanceof Paragraph ? ((Paragraph) b).text.length() : 0);
          return null;
        });
    c.register(
        "select.text",
        "Select the first occurrence of literal text in a paragraph",
        "text:string",
        false,
        (s, a) -> {
          String needle = Commands.str(a, "text");
          if (needle.isEmpty()) throw new IllegalArgumentException("Text is empty");
          Document d = s.document();
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
    c.register(
        "text.newParagraph",
        "Split the paragraph at the caret",
        "",
        true,
        (s, a) -> {
          s.enterParagraph();
          return null;
        });
    c.register(
        "text.columnBreak",
        "Start the following text in the next column",
        "",
        true,
        (s, a) -> {
          s.columnBreak();
          return null;
        });
    c.register(
        "text.delete",
        "Delete forward",
        "",
        true,
        (s, a) -> {
          s.deleteForward();
          return null;
        });
    c.register(
        "text.tab",
        "Insert a tab",
        "",
        true,
        (s, a) -> {
          s.replace("\t");
          return null;
        });
    insertText(c, "text.nbsp", "Insert a non-breaking space", "\u00a0");
    insertText(c, "text.nbHyphen", "Insert a non-breaking hyphen", "\u2011");
    insertText(c, "text.optionalHyphen", "Insert an optional hyphen", "\u00ad");
    c.register(
        "insert.pageBreak",
        "Insert a page break before the text after the caret",
        "",
        true,
        (s, a) -> {
          s.enterParagraph();
          s.paragraph("pageBreak", "true");
          return null;
        });

    // Tables (the caret must be in a table cell).
    table(c, "table.deleteRow", "deleteRow", false);
    table(c, "table.deleteColumn", "deleteColumn", false);
    c.register(
        "table.deleteTable",
        "Delete the table at the caret",
        "",
        true,
        (s, a) -> {
          int block = tableBlock(s);
          s.leaveCell();
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
    CharacterFormat f = t.characters;
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
          s.leaveCell();
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
    Block b = s.document().blocks.get(s.selection().block);
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
