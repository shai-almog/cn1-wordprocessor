package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Answers Zotero's document calls against a {@link Session}. Zotero fields are the editor's
 * ordinary atomic inline fields; ids are handed out per field in document order and tracked
 * across inserts and deletes within a session, as Zotero expects stable ids until
 * Document_complete.
 *
 * <p>Limits (see docs/STATUS.md): citations live in body paragraphs only (not table cells,
 * footnotes or endnotes), so note styles are rejected. Document_insertText keeps Zotero's rich
 * formatting as runs and paragraphs; a field is one atomic run, so its result keeps only the
 * formatting shared by the whole result.
 */
public final class ZoteroBridge implements Zotero.Handler {
  public static final int PROTOCOL_VERSION = 3;
  /** Text a new field holds until Zotero writes its result. */
  public static final String PLACEHOLDER = "{Citation}";

  /** The editor-side UI Zotero can ask for. */
  public interface Host {
    /** Shows text; returns the button index Zotero expects (0 = OK/No/Cancel, 1 = Yes...). */
    int alert(String text, int icon, int buttons);

    default void activate() {}
  }

  private final Session session;
  private final Host host;
  private long nextId = 1, docId;
  private final List<Long> ids = new ArrayList<>();
  private Long caretAfter;

  public ZoteroBridge(Session session, Host host) {
    this.session = session;
    this.host = host;
  }

  /**
   * A Zotero field located in the body: its start mark (block, offset), and the end mark
   * (endBlock, endOffset). The result is the text between them, as Wordcraft's FieldStart and
   * FieldEnd hold it. A legacy atomic field has its end at its own offset; a start whose end
   * mark was deleted ends with its paragraph ({@link #hasEnd} false).
   */
  static final class Located {
    final int block, offset;
    final InlineField field;
    int endBlock, endOffset;
    boolean hasEnd;

    Located(int block, int offset, InlineField field) {
      this.block = block;
      this.offset = offset;
      this.field = field;
      endBlock = block;
      endOffset = offset;
    }

    boolean atomic() {
      return !field.rangeStart();
    }

    /** True if (b, o) lies at the start mark or in the text before the end mark. */
    boolean holds(int b, int o) {
      return compare(b, o, block, offset) >= 0 && compare(b, o, endBlock, endOffset) <= 0;
    }
  }

  static int compare(int b1, int o1, int b2, int o2) {
    return b1 != b2 ? Integer.compare(b1, b2) : Integer.compare(o1, o2);
  }

  static List<Located> fields(Document d) {
    var out = new ArrayList<Located>();
    final Located[] open = {null};
    for (int i = 0; i < d.blocks.size(); i++) {
      if (!(d.blocks.get(i) instanceof Paragraph p) || !p.text.hasFields()) continue;
      final int block = i;
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {}

            public void field(int offset, InlineField f, TextStyle style) {
              if (f.rangeEnd() && open[0] != null) {
                open[0].endBlock = block;
                open[0].endOffset = offset;
                open[0].hasEnd = true;
                open[0] = null;
              } else if (Zotero.is(f)) {
                close(d, open[0]);
                var l = new Located(block, offset, f);
                out.add(l);
                open[0] = f.rangeStart() ? l : null;
              }
            }
          });
    }
    close(d, open[0]);
    return out;
  }

  private static void close(Document d, Located l) {
    if (l == null) return;
    l.endBlock = l.block;
    l.endOffset = ((Paragraph) d.blocks.get(l.block)).text.length();
  }

  /** The text between a range field's marks, paragraphs joined by newlines. */
  static String text(Document d, Located f) {
    if (f.atomic()) return f.field.result;
    var b = new StringBuilder();
    for (int i = f.block; i <= f.endBlock; i++) {
      if (!(d.blocks.get(i) instanceof Paragraph p)) continue;
      int from = i == f.block ? f.offset + 1 : 0, to = i == f.endBlock ? f.endOffset : p.text.length();
      if (i > f.block) b.append('\n');
      for (int k = from; k < to; k++) if (!p.text.objectAt(k)) b.append(p.text.charAt(k));
    }
    return b.toString();
  }

  /**
   * Turns atomic Zotero fields in body paragraphs into range fields: a start mark, the result as
   * ordinary runs (keeping its formatted pieces) and an end mark; newlines in a bibliography's
   * result become paragraphs. Returns {@code d} itself when there is nothing to convert.
   */
  public static Document expand(Document d) {
    Sequence<Block> blocks = d.blocks;
    long next = d.nextId;
    boolean changed = false;
    for (int i = blocks.size() - 1; i >= 0; i--) {
      if (!(blocks.get(i) instanceof Paragraph p) || !p.text.hasFields()) continue;
      final var atomic = new ArrayList<Object[]>();
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {}

            public void field(int offset, InlineField f, TextStyle style) {
              if (Zotero.is(f) && !f.rangeStart() && style.revision == null)
                atomic.add(new Object[] {offset, f, style});
            }
          });
      if (atomic.isEmpty()) continue;
      RichText t = p.text;
      for (int k = atomic.size() - 1; k >= 0; k--) {
        int at = (Integer) atomic.get(k)[0];
        var f = (InlineField) atomic.get(k)[1];
        var style = (TextStyle) atomic.get(k)[2];
        RichText r = RichText.field(f.start(), style);
        var pieces = f.piecesOf(f.result);
        if (pieces == null) pieces = InlineField.plainPieces(f.result);
        for (InlineField.Piece piece : pieces)
          if (!piece.text.isEmpty()) r = r.concat(RichText.of(piece.text.replace('\r', ' '), piece.style(style)));
        r = r.concat(RichText.field(InlineField.end(), style));
        t = t.replace(at, at + 1, r);
      }
      // Split at the newlines a bibliography's entries left, one paragraph each.
      var parts = new ArrayList<RichText>();
      int from = 0, length = t.length();
      for (int k = 0; k < length; k++)
        if (t.charAt(k) == '\n' && !t.objectAt(k)) {
          parts.add(t.slice(from, k));
          from = k + 1;
        }
      parts.add(t.slice(from, length));
      blocks = blocks.set(i, p.text(parts.get(0)));
      for (int k = 1; k < parts.size(); k++) blocks = blocks.insert(i + k, p.copy("p" + next++, parts.get(k)));
      changed = true;
    }
    return changed ? d.blocks(blocks, next) : d;
  }

  private List<Located> sync() {
    var list = fields(session.document());
    if (list.size() != ids.size()) {
      ids.clear();
      for (int i = 0; i < list.size(); i++) ids.add(nextId++);
    }
    return list;
  }

  private int index(Object idArg) {
    long id = id(idArg);
    sync();
    int k = ids.indexOf(id);
    if (k < 0) throw new IllegalArgumentException("Field " + id + " no longer exists");
    return k;
  }

  private static long id(Object v) {
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s)
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ex) {
        // falls through
      }
    throw new IllegalArgumentException("Bad field id");
  }

  private static Object arg(List<Object> a, int i) {
    if (i >= a.size()) throw new IllegalArgumentException("Missing argument " + i);
    return a.get(i);
  }

  private static String str(List<Object> a, int i) {
    Object v = arg(a, i);
    if (!(v instanceof String s)) throw new IllegalArgumentException("Argument " + i + " must be text");
    return s;
  }

  private static long num(List<Object> a, int i, long fallback) {
    return i < a.size() && a.get(i) instanceof Number n ? n.longValue() : fallback;
  }

  private static boolean bool(List<Object> a, int i) {
    Object v = i < a.size() ? a.get(i) : null;
    return Boolean.TRUE.equals(v) || "true".equals(v) || "1".equals(v)
        || (v instanceof Number n && n.longValue() != 0);
  }

  private static List<Object> triple(long id, Located f) {
    return Arrays.asList(id, Zotero.code(f.field.instruction), 0);
  }

  private void requireInText(long noteType) {
    if (noteType != 0)
      throw new IllegalArgumentException(
          "Footnote and endnote citation styles are not supported yet; choose an in-text style");
  }

  @Override
  public Object handle(String method, List<Object> a) {
    switch (method) {
      case "Application_getActiveDocument": {
        long theirs = num(a, 0, PROTOCOL_VERSION);
        docId++;
        caretAfter = null;
        return Arrays.asList(Math.max(1, Math.min(PROTOCOL_VERSION, theirs)), docId);
      }
      case "Document_displayAlert":
        return host.alert(str(a, 1), (int) num(a, 2, 0), (int) num(a, 3, 0));
      case "Document_activate":
        host.activate();
        return null;
      case "Document_canInsertField":
        return session.cellSelection() == null
            && session.document().blocks.get(session.selection().block) instanceof Paragraph;
      case "Document_getDocumentData":
        return Zotero.prefs(session.document());
      case "Document_setDocumentData": {
        String data = str(a, 1);
        if (!data.equals(Zotero.prefs(session.document())))
          session.documentProperties(Zotero.prefs(session.document().properties, data));
        return null;
      }
      case "Document_cursorInField": {
        var sel = session.selection();
        var list = sync();
        for (int k = 0; k < list.size(); k++) {
          Located f = list.get(k);
          // The caret at the start mark or anywhere in the field's text, or a selection that
          // overlaps the field, as Wordcraft's cursor_in_field.
          boolean in = sel.collapsed()
              ? f.holds(sel.startBlock(), sel.start())
              : compare(sel.startBlock(), sel.start(), f.endBlock, f.endOffset) <= 0
                  && compare(sel.endBlock(), sel.end(), f.block, f.offset) > 0;
          if (in) return triple(ids.get(k), f);
        }
        return null;
      }
      case "Document_insertField": {
        requireInText(num(a, 2, 0));
        sync();
        // A start mark, the placeholder as ordinary text and an end mark, as one undo step.
        session.transact(
            s -> {
              session.insertField(new InlineField(Zotero.stored("TEMP"), "", false, false).start());
              session.replace(PLACEHOLDER);
              session.insertField(InlineField.end());
            });
        var sel = session.selection();
        var list = fields(session.document());
        for (int k = 0; k < list.size(); k++)
          if (list.get(k).endBlock == sel.block && list.get(k).endOffset == sel.caret - 1) {
            long id = nextId++;
            ids.add(Math.min(k, ids.size()), id);
            caretAfter = id;
            return triple(id, list.get(k));
          }
        throw new IllegalStateException("The new field was not found");
      }
      case "Document_getFields": {
        var list = sync();
        var codes = new ArrayList<Object>();
        var notes = new ArrayList<Object>();
        for (Located f : list) {
          codes.add(Zotero.code(f.field.instruction));
          notes.add(0);
        }
        return Arrays.asList(new ArrayList<Object>(ids), codes, notes);
      }
      case "Document_insertText": {
        insertRich(runs(str(a, 1), true));
        return null;
      }
      case "Document_setBibliographyStyle":
        setBibliographyStyle(a);
        return null;
      case "Document_convert": {
        Object notes = a.size() > 3 ? a.get(3) : null;
        if (notes instanceof List<?> l) for (Object n : l) requireInText(n instanceof Number x ? x.longValue() : 0);
        else requireInText(notes instanceof Number x ? x.longValue() : 0);
        return null;
      }
      case "Field_convert":
        index(arg(a, 1));
        requireInText(num(a, 3, 0));
        return null;
      case "Document_complete": {
        if (caretAfter != null) {
          int k = ids.indexOf(caretAfter);
          var list = sync();
          if (k >= 0 && k < list.size())
            session.select(list.get(k).endBlock, list.get(k).endOffset + 1, list.get(k).endOffset + 1);
        }
        caretAfter = null;
        return null;
      }
      case "Field_delete": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        session.selectRange(f.block, f.offset, f.endBlock, f.endOffset + (f.hasEnd || f.atomic() ? 1 : 0));
        session.replace("");
        ids.remove(k);
        return null;
      }
      case "Field_select": {
        Located f = fields(session.document()).get(index(arg(a, 1)));
        session.selectRange(f.block, f.offset, f.endBlock, f.endOffset + (f.hasEnd || f.atomic() ? 1 : 0));
        return null;
      }
      case "Field_removeCode": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        session.transact(
            s -> {
              if (f.hasEnd) {
                session.select(f.endBlock, f.endOffset, f.endOffset + 1);
                session.replace("");
              }
              session.select(f.block, f.offset, f.offset + 1);
              session.replace(f.atomic() ? f.field.result : "");
            });
        ids.remove(k);
        return null;
      }
      case "Field_getText":
        return text(session.document(), fields(session.document()).get(index(arg(a, 1))));
      case "Field_setText": {
        final int k = index(arg(a, 1));
        final var rich = runs(str(a, 2), bool(a, 3));
        // As Wordcraft's set_text: the text between the marks is replaced by the formatted
        // runs, newlines starting paragraphs; one undo step.
        session.transact(
            s -> {
              Located f = fields(session.document()).get(k);
              if (f.atomic()) {
                replaceField(f, f.field.start());
                session.select(f.block, f.offset + 1, f.offset + 1);
                session.insertField(InlineField.end());
                f = fields(session.document()).get(k);
              }
              session.selectRange(f.block, f.offset + 1, f.endBlock, f.endOffset);
              insertRich(rich);
              if (Zotero.code(f.field.instruction).regionMatches(true, 0, "BIBL", 0, 4)) {
                // The bibliography's paragraphs take the Bibliography style.
                ensureBibliographyStyle();
                var end = session.selection();
                session.selectRange(f.block, f.offset, end.block, end.caret);
                session.applyStyle(BIB_STYLE);
              }
              Located g = fields(session.document()).get(k);
              session.select(g.endBlock, g.endOffset + (g.hasEnd ? 1 : 0), g.endOffset + (g.hasEnd ? 1 : 0));
            });
        return null;
      }
      case "Field_setCode": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        String code = str(a, 2);
        replaceField(
            f, new InlineField(Zotero.stored(code), f.field.result, f.field.locked, f.field.showCode, f.field.formats));
        return null;
      }
      default:
        throw new UnsupportedOperationException("This editor doesn't support " + method);
    }
  }

  static final String BIB_STYLE = "Bibliography";

  private void ensureBibliographyStyle() {
    if (session.document().styles.containsKey(BIB_STYLE)) return;
    ParagraphStyle normal = session.document().styles.get("Normal");
    if (normal == null) normal = ParagraphStyle.defaults().get("Normal");
    session.defineStyle(new ParagraphStyle(BIB_STYLE, BIB_STYLE, normal.paragraph, normal.text));
  }

  private static float twips(long v) {
    return Math.max(-31680, Math.min(31680, v)) / 20f;
  }

  /**
   * Zotero's bibliography paragraph format (first-line indent, body indent, line spacing and entry
   * spacing in twips/240ths, then tab stops in twips) applied to the Bibliography style, as
   * Wordcraft's set_bibliography_style does: no space before, entry spacing after.
   */
  private void setBibliographyStyle(List<Object> a) {
    long first = num(a, 1, 0), body = num(a, 2, 0), line = num(a, 3, 240), entry = num(a, 4, 0);
    var stops = new ArrayList<TabStop>();
    if (a.size() > 5 && a.get(5) instanceof List<?> l)
      for (Object o : l) {
        if (stops.size() >= 64) break;
        if (o instanceof Number n) stops.add(new TabStop(Math.max(0, twips(n.longValue())), "left", "none"));
      }
    ensureBibliographyStyle();
    ParagraphStyle old = session.document().styles.get(BIB_STYLE);
    float spacing = line > 0 ? Math.max(.5f, Math.min(5f, line / 240f)) : 1f;
    Paragraph p = old.paragraph.with("indent", "" + twips(body)).with("firstIndent", "" + twips(first))
        .with("spacing", "" + spacing).with("lineRule", "auto").with("before", "0")
        .with("after", "" + Math.max(0, twips(entry))).with("tabs", TabStop.encode(stops));
    session.defineStyle(new ParagraphStyle(BIB_STYLE, old.name, p, old.text));
  }

  private void replaceField(Located f, InlineField value) {
    final String pid = ((Paragraph) session.document().blocks.get(f.block)).id;
    session.mapFields((paragraph, at, field) -> pid.equals(paragraph) && at == f.offset ? value : field);
  }

  /** One formatted piece of a Zotero rich result; newlines inside separate paragraphs. */
  static final class Run {
    final StringBuilder text = new StringBuilder();
    boolean bold, italic, underline, smallCaps;
    String script = "baseline";

    Run copy() {
      var r = new Run();
      r.bold = bold;
      r.italic = italic;
      r.underline = underline;
      r.smallCaps = smallCaps;
      r.script = script;
      return r;
    }

    boolean sameFormat(Run o) {
      return bold == o.bold && italic == o.italic && underline == o.underline
          && smallCaps == o.smallCaps && script.equals(o.script);
    }

    String[][] format() {
      return new String[][] {
        {"bold", "" + bold}, {"italic", "" + italic}, {"underline", "" + underline},
        {"smallCaps", "" + smallCaps}, {"script", script}
      };
    }
  }

  /** Plain text from Zotero's RTF ("rich") or HTML results; paragraph breaks become newlines. */
  static String plain(String s, boolean rich) {
    if (!rich) return s;
    var b = new StringBuilder();
    for (Run r : runs(s, true)) b.append(r.text);
    return b.toString();
  }

  /**
   * Formatted runs from Zotero's RTF or HTML results: bold, italic, underline, superscript,
   * subscript and small caps are kept; trailing paragraph breaks are dropped.
   */
  static List<Run> runs(String s, boolean rich) {
    var out = new ArrayList<Run>();
    Run cur = new Run();
    if (!rich) {
      cur.text.append(s);
      out.add(cur);
      return out;
    }
    boolean html = s.indexOf('<') >= 0 && s.indexOf("{\\rtf") < 0;
    var stack = new ArrayList<Run>();
    var tags = new ArrayList<String>();
    out.add(cur);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      Run next = null;
      if (html) {
        if (c == '<') {
          int end = s.indexOf('>', i);
          if (end < 0) break;
          String tag = s.substring(i + 1, end).trim();
          String lower = tag.toLowerCase();
          i = end;
          if (lower.startsWith("/")) {
            String name = tagName(lower.substring(1));
            if ("p".equals(name) || "div".equals(name)) append(out, cur, '\n');
            int k = tags.lastIndexOf(name);
            if (k >= 0) {
              next = stack.get(k).copy();
              while (tags.size() > k) {
                tags.remove(tags.size() - 1);
                stack.remove(stack.size() - 1);
              }
            }
          } else {
            String name = tagName(lower);
            if ("br".equals(name)) {
              append(out, cur, '\n');
              continue;
            }
            if (lower.endsWith("/")) continue;
            tags.add(name);
            stack.add(cur.copy());
            next = cur.copy();
            if ("i".equals(name) || "em".equals(name) || "cite".equals(name)) next.italic = true;
            else if ("b".equals(name) || "strong".equals(name)) next.bold = true;
            else if ("u".equals(name)) next.underline = true;
            else if ("sup".equals(name)) next.script = "superscript";
            else if ("sub".equals(name)) next.script = "subscript";
            String style = lower.replace(" ", "");
            if (style.indexOf("font-style:italic") >= 0) next.italic = true;
            if (style.indexOf("font-style:normal") >= 0) next.italic = false;
            if (style.indexOf("font-weight:bold") >= 0) next.bold = true;
            if (style.indexOf("font-weight:normal") >= 0) next.bold = false;
            if (style.indexOf("font-variant:small-caps") >= 0) next.smallCaps = true;
            if (style.indexOf("font-variant:normal") >= 0) next.smallCaps = false;
            if (style.indexOf("text-decoration:underline") >= 0) next.underline = true;
            if (style.indexOf("vertical-align:super") >= 0) next.script = "superscript";
            if (style.indexOf("vertical-align:sub") >= 0) next.script = "subscript";
          }
        } else if (c == '&') {
          int end = s.indexOf(';', i);
          String e = end > i && end - i < 8 ? s.substring(i + 1, end) : "";
          String r = "amp".equals(e) ? "&" : "lt".equals(e) ? "<" : "gt".equals(e) ? ">"
              : "quot".equals(e) ? "\"" : "nbsp".equals(e) ? "\u00a0" : null;
          if (r == null) append(out, cur, c);
          else {
            append(out, cur, r.charAt(0));
            i = end;
          }
        } else if (c != '\r' && c != '\n') append(out, cur, c);
      } else if (c == '{') {
        stack.add(cur.copy());
      } else if (c == '}') {
        if (!stack.isEmpty()) next = stack.remove(stack.size() - 1).copy();
      } else if (c == '\\') {
        if (i + 1 < s.length() && "\\{}".indexOf(s.charAt(i + 1)) >= 0) append(out, cur, s.charAt(++i));
        else {
          int j = i + 1;
          while (j < s.length() && ((s.charAt(j) >= 'a' && s.charAt(j) <= 'z') || (s.charAt(j) >= 'A' && s.charAt(j) <= 'Z'))) j++;
          String word = s.substring(i + 1, j);
          int k = j;
          if (k < s.length() && (s.charAt(k) == '-' || (s.charAt(k) >= '0' && s.charAt(k) <= '9'))) {
            k++;
            while (k < s.length() && s.charAt(k) >= '0' && s.charAt(k) <= '9') k++;
          }
          boolean on = !"0".equals(s.substring(j, k));
          if ("uc".equals(word) || word.isEmpty()) {
            // skip
          } else if ("u".equals(word)) {
            try {
              int v = Integer.parseInt(s.substring(j, k));
              append(out, cur, (char) (v < 0 ? v + 65536 : v));
              if (k < s.length() && s.charAt(k) == '?') k++;
            } catch (NumberFormatException ignored) {
            }
          } else if ("par".equals(word) || "line".equals(word)) append(out, cur, '\n');
          else if ("tab".equals(word)) append(out, cur, '\t');
          else if ("rtf".equals(word)) {
            out.clear();
            cur = new Run();
            out.add(cur);
          } else if ("i".equals(word)) (next = cur.copy()).italic = on;
          else if ("b".equals(word)) (next = cur.copy()).bold = on;
          else if ("ul".equals(word)) (next = cur.copy()).underline = on;
          else if ("ulnone".equals(word)) (next = cur.copy()).underline = false;
          else if ("scaps".equals(word)) (next = cur.copy()).smallCaps = on;
          else if ("super".equals(word)) (next = cur.copy()).script = "superscript";
          else if ("sub".equals(word)) (next = cur.copy()).script = "subscript";
          else if ("nosupersub".equals(word)) (next = cur.copy()).script = "baseline";
          else if ("plain".equals(word)) next = new Run();
          if (k < s.length() && s.charAt(k) == ' ') k++;
          i = k - 1;
        }
      } else if (c != '\r' && c != '\n') append(out, cur, c);
      if (next != null && !next.sameFormat(cur)) {
        if (cur.text.length() == 0) out.remove(out.size() - 1);
        cur = next;
        out.add(cur);
      }
    }
    // Drop trailing paragraph breaks and empty runs.
    for (int k = out.size() - 1; k >= 0; k--) {
      var t = out.get(k).text;
      while (t.length() > 0 && t.charAt(t.length() - 1) == '\n') t.setLength(t.length() - 1);
      if (t.length() > 0) break;
      if (out.size() > 1) out.remove(k);
    }
    return out;
  }

  private static String tagName(String tag) {
    int e = 0;
    while (e < tag.length() && tag.charAt(e) > ' ' && tag.charAt(e) != '/') e++;
    return tag.substring(0, e);
  }

  private static void append(List<Run> out, Run cur, char c) {
    cur.text.append(c);
  }

  /**
   * Replaces the selection with formatted runs as one undo step; each newline starts a new
   * paragraph (bibliography entries), the caret ends after the text.
   */
  private void insertRich(final List<Run> runs) {
    session.transact(
        s -> {
          var all = new StringBuilder();
          for (Run r : runs) all.append(r.text);
          var x = session.selection();
          int block = x.startBlock(), at = x.start();
          session.replace(all.toString());
          var end = session.selection();
          for (Run r : runs) {
            String t = r.text.toString();
            int i = 0;
            while (i < t.length()) {
              int nl = t.indexOf('\n', i);
              int stop = nl < 0 ? t.length() : nl;
              if (stop > i) {
                session.select(block, at, at + (stop - i));
                for (String[] kv : r.format()) session.format(kv[0], kv[1]);
              }
              at += stop - i;
              if (nl < 0) break;
              block++;
              at = 0;
              i = nl + 1;
            }
          }
          session.select(end.block, end.caret, end.caret);
        });
  }

  /** The {@link InlineField#formats} encoding of rich runs ("" when none is formatted). */
  static String formats(List<Run> runs) {
    var b = new StringBuilder();
    boolean any = false;
    for (Run r : runs) {
      if (r.text.length() == 0) continue;
      int flags = (r.bold ? InlineField.BOLD : 0) | (r.italic ? InlineField.ITALIC : 0)
          | (r.underline ? InlineField.UNDERLINE : 0) | (r.smallCaps ? InlineField.SMALL_CAPS : 0)
          | ("superscript".equals(r.script) ? InlineField.SUPER : "subscript".equals(r.script) ? InlineField.SUB : 0);
      any |= flags != 0;
      if (b.length() > 0) b.append(';');
      b.append(r.text.length()).append(':').append(flags);
    }
    return any ? b.toString() : "";
  }

  /** Formatting shared by every visible character of a result, for an atomic field's run. */
  static List<String[]> uniform(List<Run> runs) {
    var out = new ArrayList<String[]>();
    String[][] first = null;
    boolean[] same = null;
    for (Run r : runs) {
      if (r.text.toString().trim().isEmpty()) continue;
      String[][] f = r.format();
      if (first == null) {
        first = f;
        same = new boolean[f.length];
        Arrays.fill(same, true);
      } else for (int k = 0; k < f.length; k++) same[k] &= f[k][1].equals(first[k][1]);
    }
    if (first != null) for (int k = 0; k < first.length; k++) if (same[k]) out.add(first[k]);
    return out;
  }
}
