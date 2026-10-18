package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Catalog ids for paragraph moves, the format painter, repeat, go back, Unicode toggling, field
 * codes and small insert helpers. State that Word keeps per window (painted format, last command,
 * recent edit positions) is kept per {@link Commands} instance.
 */
final class CatalogEditingCommands implements Commands.Extension {
  private static final String[] CHARACTER_KEYS = {
    "doubleUnderline", "doubleStrike", "smallCaps", "allCaps", "hidden", "characterSpacing",
    "characterScale", "characterPosition", "underlineStyle", "outline", "shadow", "emboss", "engrave"
  };

  private TextStyle paintedText;
  private Paragraph paintedParagraph;

  public void register(final Commands c) {
    c.edit("para.moveUp", "",
        "Move the caret paragraph above the previous paragraph", (s, a) -> move(s, true));
    c.edit("para.moveDown", "",
        "Move the caret paragraph below the next paragraph", (s, a) -> move(s, false));
    c.edit("outline.moveUp", "",
        "Move the paragraph up in the outline", (s, a) -> move(s, true));
    c.edit("outline.moveDown", "",
        "Move the paragraph down in the outline", (s, a) -> move(s, false));

    c.query("edit.copyFormat", "",
        "Format painter: remember the character and paragraph format at the caret", (s, a) -> {
          paintedText = s.activeStyle();
          paintedParagraph = s.document().paragraph(s.selection().block);
          return true;
        });
    c.edit("edit.pasteFormat", "",
        "Format painter: apply the remembered format to the selection", (s, a) -> {
          if (paintedText == null) throw new IllegalStateException("Copy a format first");
          if (!s.selection().collapsed()) {
            var t = paintedText;
            s.format("font", t.font);
            s.format("size", String.valueOf(t.size));
            s.format("color", hex(t.color));
            s.format("bold", String.valueOf(t.bold));
            s.format("italic", String.valueOf(t.italic));
            s.format("strike", String.valueOf(t.strike));
            s.format("background", hex(t.background));
            s.format("script", t.script);
            for (String key : CHARACTER_KEYS) s.format(key, t.characters.value(key));
          }
          var p = paintedParagraph;
          s.paragraph("style", p.style);
          s.paragraph("align", p.align);
          s.paragraph("before", String.valueOf(p.before));
          s.paragraph("after", String.valueOf(p.after));
          s.paragraph("indent", String.valueOf(p.indent));
          s.paragraph("spacing", String.valueOf(p.lineSpacing));
          s.paragraph("keepNext", String.valueOf(p.keepNext));
          var f = p.format;
          s.paragraph("rightIndent", String.valueOf(f.rightIndent));
          s.paragraph("firstIndent", String.valueOf(f.firstIndent));
          s.paragraph("keepLines", String.valueOf(f.keepLines));
          s.paragraph("widowControl", String.valueOf(f.widowControl));
          s.paragraph("lineRule", f.lineRule);
          s.paragraph("borders", f.borders.encode());
          s.paragraph("shading", f.shading < 0 ? "" : hex(f.shading));
          return null;
        });

    c.query("edit.repeat", "",
        "Repeat the last editing command", (s, a) -> {
          if (c.lastId == null) throw new IllegalStateException("Nothing to repeat");
          return c.execute(s, c.lastId, c.lastArgs);
        });
    c.query("edit.goBack", "",
        "Return the caret to an earlier edit location (cycles through the last three)", (s, a) -> {
          var spots = new ArrayList<int[]>(c.editSpots);
          var d = s.document();
          for (int k = spots.size() - 1; k >= 0; k--) {
            var at = spots.get(k);
            if (at[0] >= d.blocks.size() || !(d.blocks.get(at[0]) instanceof Paragraph)) continue;
            var x = s.selection();
            if (at[0] == x.block && at[1] == x.caret) continue;
            int offset = Math.min(at[1], d.paragraph(at[0]).text.length());
            s.select(at[0], offset, offset);
            c.editSpots.remove(k);
            c.editSpots.add(0, at);
            return Json.object("block", at[0], "caret", offset);
          }
          return false;
        });

    c.edit("text.toggleUnicode", "",
        "Alt+X: convert the hex code before the caret to its character, or the character to its code",
        (s, a) -> {
          var x = s.selection();
          var text = s.document().paragraph(x.block).text.plainText();
          int end = x.caret, start = end;
          // ASCII-only test: ParparVM's Character.digit accepts some non-ASCII letters (e.g. U+00E9).
          while (start > 0 && end - start < 6 && hexDigit(text.charAt(start - 1))) start--;
          int codeStart = start >= 2 && text.regionMatches(true, start - 2, "U+", 0, 2) ? start - 2 : start;
          if (start < end) {
            int cp = Integer.parseInt(text.substring(start, end), 16);
            if (cp > 0 && Character.isValidCodePoint(cp) && (cp < 0xd800 || cp > 0xdfff)) {
              s.select(x.block, codeStart, end);
              s.replace(new String(Character.toChars(cp)));
              return Integer.toHexString(cp).toUpperCase();
            }
          }
          if (end == 0) throw new IllegalArgumentException("No character before the caret");
          // String.codePointBefore is unavailable on ParparVM.
          char low = text.charAt(end - 1);
          int cp =
              Character.isLowSurrogate(low) && end >= 2 && Character.isHighSurrogate(text.charAt(end - 2))
                  ? Character.toCodePoint(text.charAt(end - 2), low)
                  : low;
          var code = Integer.toHexString(cp).toUpperCase();
          while (code.length() < 4) code = "0" + code;
          s.select(x.block, end - Character.charCount(cp), end);
          s.replace(code);
          return code;
        });

    c.edit("fields.setCode", "code:string",
        "Replace the instruction of the field at the caret; the result refreshes on update",
        (s, a) -> {
          final String code = Commands.str(a, "code");
          final var para = s.document().paragraph(s.selection().block).id;
          final int from = s.selection().start(), to = s.selection().end();
          final int[] count = {0};
          s.mapFields(
              (p, offset, f) -> {
                boolean inside =
                    para.equals(p)
                        && (from == to ? offset == from || offset == from - 1 : offset >= from && offset < to);
                if (!inside) return f;
                count[0]++;
                return new InlineField(code, f.result, f.locked, f.showCode);
              });
          if (count[0] == 0) throw new IllegalArgumentException("Select a field");
          return count[0];
        });

    c.edit("view.fieldCodes", "value?:boolean",
        "Alt+F9: show field codes instead of results for every field, or switch back", (s, a) -> {
          final boolean[] any = {false};
          s.mapFields(
              (p, offset, f) -> {
                any[0] |= f.showCode;
                return f;
              });
          final boolean show = Commands.flag(a, "value", !any[0]);
          s.mapFields((p, offset, f) -> f.showCode(show));
          return show;
        });
    c.edit("insert.blankPage", "",
        "Insert a blank page at the caret", (s, a) -> {
          c.execute(s, "insert.pageBreak");
          c.execute(s, "insert.pageBreak");
          var x = s.selection();
          s.select(x.block - 1, 0, 0);
          return null;
        });
    c.edit("insert.docProperty", "name:string",
        "Insert a document property field (title, author, subject...)", (s, a) -> {
          String name = Commands.str(a, "name");
          boolean valid = !name.isEmpty() && name.length() <= 40 && Unicode.letter(name.charAt(0));
          for (int i = 0; i < name.length(); i++)
            valid &= name.charAt(i) < 128 && Unicode.letterOrDigit(name.charAt(i));
          if (!valid) throw new IllegalArgumentException("Invalid property name");
          String value = "title".equalsIgnoreCase(name) ? s.document().title : "";
          s.insertField(new InlineField("DOCPROPERTY " + name, value, false, false));
          return null;
        });
  }

  /** Swaps the caret paragraph with its neighbor; tracked documents record a move instead. */
  private static Object move(Session s, boolean up) {
    var d = s.document();
    var x = s.selection();
    int i = x.block, j = up ? i - 1 : i + 1;
    if (j < 0 || j >= d.blocks.size()) return false;
    if (!(d.blocks.get(j) instanceof Paragraph))
      throw new IllegalArgumentException("Cannot move a paragraph past a table");
    int top = Math.min(i, j);
    if (d.trackChanges) {
      if (top + 2 >= d.blocks.size() || !(d.blocks.get(top + 2) instanceof Paragraph))
        throw new IllegalArgumentException("Moving the last paragraph is not tracked");
      s.selectRange(top + 1, 0, top + 2, 0);
      s.moveSelectionTo(top, 0, s.copySelection());
    } else {
      s.replaceDocument(
          d.blocks(d.blocks.set(top, d.blocks.get(top + 1)).set(top + 1, d.blocks.get(top)), d.nextId));
    }
    int offset = Math.min(x.caret, s.document().paragraph(j).text.length());
    s.select(j, offset, offset);
    return j;
  }

  private static String hex(int rgb) {
    return Integer.toHexString(0x1000000 | (rgb & 0xffffff)).substring(1);
  }

  private static boolean hexDigit(char c) {
    return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
  }
}
