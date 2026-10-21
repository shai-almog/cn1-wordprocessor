package com.codenameone.wordprocessor;

import java.util.Arrays;
import java.util.Map;

/**
 * Catalog ids for caret movement, selection units, find, fields, outline levels, header/footer
 * options, page size and table properties. Like {@link CatalogCommands}, each id drives an
 * existing session operation.
 */
final class CatalogNavigationCommands implements Commands.Extension {
  /** Name, width and height in points. */
  private static final Object[][] PAPER = {
    {"Letter", 612f, 792f},
    {"Legal", 612f, 1008f},
    {"Executive", 522f, 756f},
    {"Tabloid", 792f, 1224f},
    {"A3", 841.89f, 1190.55f},
    {"A4", 595.28f, 841.89f},
    {"A5", 419.53f, 595.28f},
    {"B5", 498.9f, 708.66f}
  };

  public void register(final Commands c) {
    // Caret movement and selection units.
    caret(c, "caret.wordLeft", s -> TextNavigation.word(s.document(), b(s), o(s), false));
    caret(c, "caret.wordRight", s -> TextNavigation.word(s.document(), b(s), o(s), true));
    caret(c, "caret.paraUp", s -> TextNavigation.paragraph(s.document(), b(s), o(s), false));
    caret(c, "caret.paraDown", s -> TextNavigation.paragraph(s.document(), b(s), o(s), true));
    caret(c, "caret.docStart", s -> TextNavigation.boundary(s.document(), false));
    caret(c, "caret.docEnd", s -> TextNavigation.boundary(s.document(), true));
    c.query("select.word", "",
        "Select the word at the caret", (s, a) -> {
          int[] start = TextNavigation.word(s.document(), b(s), o(s) + 1, false);
          int[] end = TextNavigation.word(s.document(), start[0], start[1], true);
          if (end[0] != start[0]) end = TextNavigation.paragraphEnd(s.document(), start[0], 0);
          s.selectRange(start[0], start[1], end[0], end[1]);
          return s.selectedText();
        });
    c.query("select.sentence", "",
        "Select the sentence at the caret", (s, a) -> {
          var text = paragraph(s).text.plainText();
          int at = Math.min(o(s), text.length()), start = at, end = at;
          while (start > 0 && !sentenceEnd(text, start - 1)) start--;
          while (start < text.length() && text.charAt(start) == ' ') start++;
          while (end < text.length() && !sentenceEnd(text, end)) end++;
          if (end < text.length()) end++;
          while (end < text.length() && text.charAt(end) == ' ') end++;
          s.select(b(s), start, end);
          return s.selectedText();
        });
    c.query("select.paragraph", "",
        "Select the paragraph at the caret", (s, a) -> {
          s.select(b(s), 0, paragraph(s).text.length());
          return s.selectedText();
        });
    c.edit("text.lineBreak", "",
        "Insert a line break within the paragraph", (s, a) -> {
          s.replace("\n");
          return null;
        });
    c.edit("text.deleteWordBack", "",
        "Delete to the previous word start", (s, a) -> {
          TextNavigation.deleteWord(s, false);
          return null;
        });
    c.edit("text.deleteWordForward", "",
        "Delete to the next word start", (s, a) -> {
          TextNavigation.deleteWord(s, true);
          return null;
        });

    // Find, replace and history.
    find(c, "edit.findNext", true);
    find(c, "edit.findPrevious", false);
    c.edit("edit.replaceAll", "text:string,replacement:string",
        "Replace every occurrence of literal text", (s, a) -> c.execute(s, "replace.all", a));
    c.query("edit.undoTo", "count:integer",
        "Undo the given number of steps", (s, a) -> {
          int count = Commands.integer(a, "count"), done = 0;
          if (count < 1) throw new IllegalArgumentException("Count must be positive");
          while (done < count && s.canUndo()) {
            s.undo();
            done++;
          }
          return done;
        });

    // Fields.
    fieldFlag(c, "fields.lock", "Lock fields in the selection", true, true);
    fieldFlag(c, "fields.unlock", "Unlock fields in the selection", true, false);
    fieldFlag(c, "fields.toggleCode", "Toggle field codes in the selection", false, false);
    fieldStep(c, "fields.next", true);
    fieldStep(c, "fields.previous", false);
    c.edit("fields.unlink", "",
        "Replace fields in the selection with their current results", (s, a) -> {
          int block = b(s), from = s.selection().start(), to = s.selection().end(), count = 0;
          if (s.selection().startBlock() != s.selection().endBlock())
            throw new IllegalArgumentException("Select within one paragraph");
          for (int i = Math.max(to, from + 1) - 1; i >= Math.max(0, from - 1); i--) {
            var f = paragraph(s).text.fieldAt(i);
            if (f == null || (from == to ? i != from && i != from - 1 : i >= to)) continue;
            s.select(block, i, i + 1);
            s.replace(f.result);
            count++;
          }
          return count;
        });
    c.edit("insert.pageNumber", "",
        "Insert a PAGE field", (s, a) -> {
          s.insertField(new InlineField("PAGE", "1", false, false));
          return null;
        });
    c.edit("insert.dateTime", "format?:string,update?:boolean",
        "Insert a DATE field with an optional picture (\\@ format)", (s, a) -> {
          String format = Commands.str(a, "format", "M/d/yyyy");
          if (format.indexOf('"') >= 0) throw new IllegalArgumentException("Invalid format");
          s.insertField(
              new InlineField(
                  "DATE \\@ \"" + format + "\"", "", Boolean.FALSE.equals(a.get("update")), false));
          return null;
        });
    c.edit("insert.symbol", "text?:string,code?:integer",
        "Insert a character by text or code point", (s, a) -> {
          String text =
              a.containsKey("code")
                  ? new String(Character.toChars(Commands.integer(a, "code")))
                  : Commands.str(a, "text");
          if (text.isEmpty()) throw new IllegalArgumentException("Nothing to insert");
          s.replace(text);
          return null;
        });
    c.edit("insert.table", "rows:integer,cols:integer",
        "Insert a table at the caret", (s, a) -> {
          s.insertTable(Commands.integer(a, "rows"), Commands.integer(a, "cols"));
          return null;
        });
    c.edit("insert.removeLink", "",
        "Remove hyperlinks from the selection", (s, a) -> {
          s.format("link", "");
          return null;
        });

    // Comments under the Review tab ids.
    c.edit("review.newComment", "text:string,author?:string",
        "Comment on the selected range", (s, a) -> {
          s.addComment(
              Commands.str(a, "author", s.reviewAuthor()),
              Commands.str(a, "text"));
          return null;
        });
    c.query("review.nextComment", "",
        "Move to the next comment", (s, a) -> s.navigateComment(false));
    c.query("review.previousComment", "",
        "Move to the previous comment", (s, a) -> s.navigateComment(true));

    // Outline levels through heading styles.
    outline(c, "outline.promote", -1);
    outline(c, "para.promote", -1);
    outline(c, "outline.demote", 1);
    outline(c, "para.demote", 1);
    c.edit("outline.promoteToHeading1", "",
        "Make the paragraph Heading 1", (s, a) -> heading(s, 1));
    c.edit("outline.demoteToBody", "",
        "Make the paragraph body text", (s, a) -> heading(s, 0));
    c.edit("outline.level", "level:integer",
        "Set the heading level 1-9, or 0 for body text",
        (s, a) -> heading(s, Commands.integer(a, "level")));

    // Section options.
    c.edit("layout.differentFirstPage", "value?:boolean",
        "Use a different header/footer on the first page of the section", (s, a) -> {
          var f = s.activeSection().raw.furniture;
          boolean v = Commands.flag(a, "value", !f.differentFirst);
          s.section(
              s.activeSection()
                  .raw
                  .furniture(f.options(v, f.differentEven, f.headerDistance, f.footerDistance)));
          return null;
        });
    c.edit("layout.differentOddEven", "value?:boolean",
        "Use different odd and even page headers/footers", (s, a) -> {
          var f = s.activeSection().raw.furniture;
          boolean v = Commands.flag(a, "value", !f.differentEven);
          s.section(
              s.activeSection()
                  .raw
                  .furniture(f.options(f.differentFirst, v, f.headerDistance, f.footerDistance)));
          return null;
        });
    // Headers and footers of the caret's section.
    for (final boolean footer : new boolean[] {false, true}) {
      final String kind = footer ? "Footer" : "Header";
      c.register(
          "insert." + kind.toLowerCase(),
          "Set the section's " + kind.toLowerCase() + " text (variant: primary, first or even)",
          "text:string,align?:string,variant?:string",
          true,
          (s, a) -> {
            String align = Commands.str(a, "align", "left");
            if (!Arrays.asList("left", "center", "right", "both").contains(align))
              throw new IllegalArgumentException("Unknown alignment " + align);
            String key = variant(a, kind);
            var story = new Sequence<Paragraph>();
            for (String line : TextUtil.split(Commands.str(a, "text"), '\n'))
              story =
                  story.append(
                      new Paragraph("hf" + story.size(), RichText.of(line, TextStyle.DEFAULT))
                          .with("align", align));
            var raw = s.activeSection().raw;
            s.section(raw.furniture(raw.furniture.story(key, story)));
            return null;
          });
      c.register(
          "insert.remove" + kind,
          "Remove every " + kind.toLowerCase() + " variant from the section",
          "",
          true,
          (s, a) -> {
            var raw = s.activeSection().raw;
            var f = raw.furniture;
            for (String key : PageFurniture.KEYS)
              if (key.endsWith(kind) || key.equals(kind.toLowerCase()))
                f = f.story(key, new Sequence<>());
            s.section(raw.furniture(f));
            return null;
          });
    }
    c.edit("layout.linkToPrevious", "story:string,value?:boolean",
        "Link a header/footer variant to the previous section, or unlink it with a copy",
        (s, a) -> {
          String key = Commands.str(a, "story");
          PageFurniture.check(key);
          var range = s.activeSection();
          if (range.first == 0) throw new IllegalArgumentException("The first section has no previous");
          boolean linked = !range.raw.furniture.stories.containsKey(key);
          boolean link = Commands.flag(a, "value", !linked);
          PageFurniture f =
              link
                  ? range.raw.furniture.inherit(key)
                  : range.raw.furniture.blocks(key, range.resolved.furniture.blocks(key));
          s.section(range.raw.furniture(f));
          return link;
        });
    c.edit("hf.position", "header?:number,footer?:number",
        "Header and footer distance from the page edge in points", (s, a) -> {
          var raw = s.activeSection().raw;
          var f = raw.furniture;
          float h = Commands.number(a, "header", f.headerDistance);
          float b = Commands.number(a, "footer", f.footerDistance);
          if (h > raw.page.height / 2 || b > raw.page.height / 2)
            throw new IllegalArgumentException("Distance exceeds half the page");
          s.section(raw.furniture(f.options(f.differentFirst, f.differentEven, h, b)));
          return null;
        });
    c.edit("layout.size", "name?:string,width?:number,height?:number",
        "Paper size by name (Letter, Legal, Executive, Tabloid, A3, A4, A5, B5) or width/height",
        (s, a) -> {
          var p = s.activeSection().raw.page;
          float w, h;
          if (a.containsKey("name")) {
            Object[] paper = paper(Commands.str(a, "name"));
            w = (Float) paper[1];
            h = (Float) paper[2];
          } else if (a.containsKey("width") && a.containsKey("height")) {
            w = ((Number) a.get("width")).floatValue();
            h = ((Number) a.get("height")).floatValue();
          } else throw new IllegalArgumentException("Give a name or width and height");
          if (w < 72 || h < 72 || w > 1584 || h > 1584)
            throw new IllegalArgumentException("Page size must be 1-22 inches");
          if (p.width > p.height) {
            float t = w;
            w = h;
            h = t;
          }
          s.section(
              s.activeSection()
                  .raw
                  .page(new PageSpec(w, h, p.top, p.right, p.bottom, p.left)));
          return null;
        });

    // Table properties at the caret cell.
    c.edit("table.repeatHeader", "value?:boolean",
        "Toggle repeating the caret row (and rows above) as a header", (s, a) -> {
          Table t = table(s);
          int rows = s.cellRow() + 1;
          boolean on =
              Commands.flag(a, "value", t.format.headerRows < rows);
          s.formatTable(s.cellBlock(), t.format.headerRows(on ? rows : 0));
          return null;
        });
    c.edit("table.shading", "color?:string",
        "Shade the selected cells (RRGGBB, or null/none to clear)", (s, a) -> {
          var v = a.get("color");
          int color =
              v == null || "none".equals(v) ? 0xffffff : Integer.parseInt(hex((String) v), 16);
          cells(s, cell -> cell(cell, color, cell.horizontalPadding, cell.verticalPadding));
          return null;
        });
    c.edit("table.borders", "kind:string,color?:string,width?:number",
        "Borders on the selected cells: all, none, outside, inside, insideH, insideV, top, bottom, left, right",
        (s, a) -> {
          tableBorders(
              s,
              Commands.str(a, "kind"),
              a.containsKey("color") ? Integer.parseInt(hex(Commands.str(a, "color")), 16) : 0,
              Commands.number(a, "width", .5f));
          return null;
        });
    c.edit("table.style", "style:string",
        "Apply a built-in table style", (s, a) -> {
          tableStyle(s, Commands.str(a, "style"));
          return null;
        });
    c.edit("table.cellMargins", "horizontal?:number,vertical?:number",
        "Cell padding in points for the selected cells", (s, a) -> {
          final Float hz = a.containsKey("horizontal") ? (Float) ((Number) a.get("horizontal")).floatValue() : null;
          final Float vt = a.containsKey("vertical") ? (Float) ((Number) a.get("vertical")).floatValue() : null;
          if (hz != null && (hz < 0 || hz > 72) || vt != null && (vt < 0 || vt > 72))
            throw new IllegalArgumentException("Margins must be 0-72pt");
          cells(
              s,
              cell ->
                  cell(
                      cell,
                      cell.background,
                      hz == null ? cell.horizontalPadding : hz,
                      vt == null ? cell.verticalPadding : vt));
          return null;
        });
  }

  /** Word's table border gallery over the selected cells (or the caret cell). */
  private static void tableBorders(Session s, String kind, int color, float width) {
    Table t = table(s);
    var range = s.cellSelection();
    int r0 = range == null ? s.cellRow() : range.firstRow,
        r1 = range == null ? s.cellRow() : range.lastRow,
        c0 = range == null ? s.cellColumn() : range.firstColumn,
        c1 = range == null ? s.cellColumn() : range.lastColumn;
    if ("all".equals(kind) || "none".equals(kind) || "outside".equals(kind) || "inside".equals(kind)
        || "insideH".equals(kind) || "insideV".equals(kind) || "top".equals(kind)
        || "bottom".equals(kind) || "left".equals(kind) || "right".equals(kind)) {
      var f = t.format;
      for (int r = r0; r <= r1; r++)
        for (int c = c0; c <= c1; c++) {
          var cell = f.cell(r, c);
          int sides = cell.borderWidth > 0 ? cell.sides : 0, edge = 0;
          int outer =
              (r == r0 ? TableFormat.Cell.TOP : 0)
                  | (r == r1 ? TableFormat.Cell.BOTTOM : 0)
                  | (c == c0 ? TableFormat.Cell.LEFT : 0)
                  | (c == c1 ? TableFormat.Cell.RIGHT : 0);
          int insideH = (r > r0 ? TableFormat.Cell.TOP : 0) | (r < r1 ? TableFormat.Cell.BOTTOM : 0);
          int insideV = (c > c0 ? TableFormat.Cell.LEFT : 0) | (c < c1 ? TableFormat.Cell.RIGHT : 0);
          if ("all".equals(kind)) sides = TableFormat.Cell.ALL;
          else if ("none".equals(kind)) sides = 0;
          else if ("outside".equals(kind)) sides |= outer;
          else if ("inside".equals(kind)) sides |= insideH | insideV;
          else if ("insideH".equals(kind)) sides |= insideH;
          else if ("insideV".equals(kind)) sides |= insideV;
          else {
            edge =
                outer
                    & ("top".equals(kind)
                        ? TableFormat.Cell.TOP
                        : "bottom".equals(kind)
                            ? TableFormat.Cell.BOTTOM
                            : "left".equals(kind) ? TableFormat.Cell.LEFT : TableFormat.Cell.RIGHT);
            sides |= edge;
          }
          f =
              f.cell(
                  r,
                  c,
                  sides == 0
                      ? cell.borders(cell.borderColor, 0, TableFormat.Cell.ALL)
                      : cell.borders(color, width, sides));
        }
      s.formatTable(s.cellBlock(), f);
    } else throw new IllegalArgumentException("Unknown border kind " + kind);
  }

  /** Built-in table styles as direct cell formatting (no linked style is kept). */
  private static void tableStyle(Session s, String style) {
    TableStyleCommands.applyStyle(s, style);
  }

  private interface Target {
    int[] at(Session s);
  }

  private interface CellEdit {
    TableFormat.Cell apply(TableFormat.Cell cell);
  }

  private static void caret(Commands c, String id, final Target target) {
    c.register(
        id,
        "Move the caret",
        "extend?:boolean",
        false,
        (s, a) -> {
          var to = target.at(s);
          var sel = s.selection();
          if (Boolean.TRUE.equals(a.get("extend")))
            s.selectRange(sel.anchorBlock, sel.anchor, to[0], to[1]);
          else s.select(to[0], to[1], to[1]);
          return null;
        });
  }

  private static void find(Commands c, String id, final boolean forward) {
    c.register(
        id,
        forward ? "Select the next match, wrapping" : "Select the previous match, wrapping",
        "text:string,matchCase?:boolean",
        false,
        (s, a) -> {
          String needle = Commands.str(a, "text");
          if (needle.isEmpty()) throw new IllegalArgumentException("Text is empty");
          boolean exact = Boolean.TRUE.equals(a.get("matchCase"));
          if (!exact) needle = needle.toLowerCase();
          var d = s.document();
          int n = d.blocks.size(), start = b(s);
          for (int step = 0; step <= n; step++) {
            int i = ((forward ? start + step : start - step) % n + n) % n;
            if (!(d.blocks.get(i) instanceof Paragraph)) continue;
            var text = ((Paragraph) d.blocks.get(i)).text.plainText();
            if (!exact) text = text.toLowerCase();
            int at;
            if (forward)
              at = text.indexOf(needle, step == 0 ? s.selection().end() : 0);
            else {
              int from = step == 0 ? s.selection().start() - 1 : text.length();
              at = from < 0 ? -1 : text.lastIndexOf(needle, from);
            }
            if (at < 0 && step == n) break;
            if (at >= 0) {
              s.select(i, at, at + needle.length());
              return true;
            }
          }
          return false;
        });
  }

  private static void fieldFlag(
      Commands c, String id, String description, final boolean lock, final boolean value) {
    c.register(
        id,
        description,
        "",
        true,
        (s, a) -> {
          final var para = paragraph(s).id;
          final int from = s.selection().start(), to = s.selection().end();
          final int[] count = {0};
          s.mapFields(
              (p, offset, f) -> {
                boolean inside =
                    para.equals(p)
                        && (from == to ? offset == from || offset == from - 1 : offset >= from && offset < to);
                if (!inside) return f;
                count[0]++;
                return lock ? f.locked(value) : f.showCode(!f.showCode);
              });
          if (count[0] == 0) throw new IllegalArgumentException("Select a field");
          return count[0];
        });
  }

  private static void fieldStep(Commands c, String id, final boolean forward) {
    c.register(
        id,
        forward ? "Select the next field" : "Select the previous field",
        "",
        false,
        (s, a) -> {
          var d = s.document();
          int block = b(s);
          int offset = forward ? s.selection().end() : s.selection().start() - 1;
          for (int i = block; forward ? i < d.blocks.size() : i >= 0; i += forward ? 1 : -1) {
            if (!(d.blocks.get(i) instanceof Paragraph)) continue;
            var t = ((Paragraph) d.blocks.get(i)).text;
            int k = i == block ? offset : forward ? 0 : t.length() - 1;
            for (; forward ? k < t.length() : k >= 0; k += forward ? 1 : -1)
              if (t.fieldAt(k) != null) {
                s.select(i, k, k + 1);
                return true;
              }
          }
          return false;
        });
  }

  private static void outline(Commands c, String id, final int delta) {
    c.register(
        id,
        delta < 0 ? "Promote one outline level" : "Demote one outline level",
        "",
        true,
        (s, a) -> {
          int level = level(paragraph(s));
          if (level == 0) return delta > 0 ? null : heading(s, 9);
          return heading(s, Math.max(1, level + delta) > 9 ? 0 : Math.max(1, level + delta));
        });
  }

  private static int level(Paragraph p) {
    if (p.style != null && p.style.startsWith("Heading") && p.style.length() == 8) {
      char d = p.style.charAt(7);
      if (d >= '1' && d <= '9') return d - '0';
    }
    return 0;
  }

  private static Object heading(Session s, int level) {
    if (level < 0 || level > 9) throw new IllegalArgumentException("Level must be 0-9");
    String style = level == 0 ? "Normal" : "Heading" + level;
    if (!s.document().styles.containsKey(style) && !ParagraphStyle.defaults().containsKey(style))
      s.defineStyle(heading(level));
    s.applyStyle(style);
    return null;
  }

  private static ParagraphStyle heading(int level) {
    var six = ParagraphStyle.defaults().get("Heading6");
    return new ParagraphStyle(
        "Heading" + level, "Heading " + level, six.paragraph.with("outlineLevel", "" + level), six.text);
  }

  private static String variant(Map<String, Object> a, String kind) {
    String v = Commands.str(a, "variant", "primary");
    if ("primary".equals(v)) return kind.toLowerCase();
    if ("first".equals(v) || "even".equals(v)) return v + kind;
    throw new IllegalArgumentException("variant: primary, first or even");
  }

  private static Object[] paper(String name) {
    for (Object[] p : PAPER) if (((String) p[0]).equalsIgnoreCase(name)) return p;
    throw new IllegalArgumentException("Unknown paper size " + name);
  }

  private static Table table(Session s) {
    if (!s.inCell()) throw new IllegalArgumentException("The caret is not in a table");
    return (Table) s.levelDocument().blocks.get(s.cellBlock());
  }

  private static void cells(Session s, CellEdit edit) {
    Table t = table(s);
    var range = s.cellSelection();
    int r0 = range == null ? s.cellRow() : range.firstRow,
        r1 = range == null ? s.cellRow() : range.lastRow,
        c0 = range == null ? s.cellColumn() : range.firstColumn,
        c1 = range == null ? s.cellColumn() : range.lastColumn;
    var f = t.format;
    for (int r = r0; r <= r1; r++)
      for (int c = c0; c <= c1; c++) f = f.cell(r, c, edit.apply(f.cell(r, c)));
    s.formatTable(s.cellBlock(), f);
  }

  private static TableFormat.Cell cell(TableFormat.Cell c, int background, float h, float v) {
    return c.background(background).padding(h, v);
  }

  private static String hex(String v) {
    String h = v.startsWith("#") ? v.substring(1) : v;
    if (h.length() != 6) throw new IllegalArgumentException("Color must be RRGGBB");
    return h;
  }

  private static boolean sentenceEnd(String text, int i) {
    char ch = text.charAt(i);
    return (ch == '.' || ch == '!' || ch == '?') && (i + 1 >= text.length() || text.charAt(i + 1) == ' ');
  }

  private static Paragraph paragraph(Session s) {
    var b = s.document().blocks.get(b(s));
    if (!(b instanceof Paragraph)) throw new IllegalArgumentException("Not in a paragraph");
    return (Paragraph) b;
  }

  private static int b(Session s) {
    return s.selection().block;
  }

  private static int o(Session s) {
    return s.selection().caret;
  }
}
