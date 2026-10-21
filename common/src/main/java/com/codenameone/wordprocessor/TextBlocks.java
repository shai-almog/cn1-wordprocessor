package com.codenameone.wordprocessor;

import com.codename1.ui.RichTextFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Tables and embedded pictures for the HTML, Markdown and RTF text adapters. CN1's rich-text
 * importer only understands inline text, so import lifts each table/picture out of the source,
 * leaves a marker token in its place, and {@link TextFormats#read} swaps the marker back for the
 * model block. Export does the reverse: blocks become marker paragraphs for the text converter,
 * then the marker is replaced with the format's own table/picture syntax (GFM pipe tables and
 * {@code ![alt](data:)} images; RTF {@code \trowd} rows and {@code \pict} PNG/JPEG data).
 *
 * <p>All scanners are bounded: tables are clipped to {@link #MAX_ROWS} x {@link #MAX_COLUMNS},
 * spans are clamped, and undecodable or oversized pictures are dropped rather than failing the
 * whole import.
 */
final class TextBlocks {
  static final int MAX_ROWS = 1000, MAX_COLUMNS = 64;
  private static final String MARK = "QQWPBLK", END = "KLBPWQQ";

  final List<Block> blocks = new ArrayList<Block>();
  final RichTextFormat format;
  long next = 1;

  /** Where relative image paths resolve (the source file or page), or null. */
  String base;
  /** Fetches http/https/file pictures; null leaves external pictures out. */
  TextFormats.ImageSource images;
  /** External pictures that couldn't be loaded. */
  final List<String> warnings = new ArrayList<String>();

  TextBlocks(RichTextFormat format) {
    this.format = format;
  }

  static String marker(int i) {
    return MARK + i + END;
  }

  /** Index of the marker starting at {@code at}, or -1. Sets {@code end[0]} past it. */
  static int markerAt(String s, int at, int count, int[] end) {
    if (!s.startsWith(MARK, at)) return -1;
    int p = at + MARK.length(), v = 0, digits = 0;
    while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9' && digits < 7) {
      v = v * 10 + (s.charAt(p) - '0');
      p++;
      digits++;
    }
    if (digits == 0 || !s.startsWith(END, p) || v >= count) return -1;
    end[0] = p + END.length();
    return v;
  }

  private String add(Block b) {
    blocks.add(b);
    return marker(blocks.size() - 1);
  }

  String id() {
    return "x" + (next++);
  }

  // ------------------------------------------------------------------ import

  String extract(String text) {
    if (format == RichTextFormat.HTML) return html(text);
    if (format == RichTextFormat.MARKDOWN) return markdown(text);
    if (format == RichTextFormat.RTF) return rtf(text);
    return text;
  }

  private Sequence<Block> cell(String source) {
    var out = new Sequence<Block>();
    if (source.trim().length() > 0) {
      try {
        // HTML cells may hold nested tables (Wordcraft nests tables in cells); read them too.
        // Pictures in cells (<img>) are kept as cell pictures, as Wordcraft's cells hold them.
        String lower = source.toLowerCase();
        // RTF cells keep their \\pict groups as cell pictures too (Wordcraft's RTF reader).
        boolean nested = format == RichTextFormat.HTML && (lower.indexOf("<table") >= 0 || lower.indexOf("<img") >= 0)
            || format == RichTextFormat.RTF && source.indexOf("{\\pict") >= 0;
        Document d = TextFormats.read(source, format, nested);
        for (Block b : d.blocks)
          if (b instanceof Table inner) {
            long[] n = {next};
            out = out.append(inner.renumbered("x", "x", n));
            next = n[0];
          } else if (b instanceof Picture pic) out = out.append(pic.copy(id()));
          else if (b instanceof Paragraph p && (p.text.length() > 0 || out.size() == 0))
            out = out.append(new Paragraph(id(), p.text).with("align", p.align));
        if (out.size() > 0 && !(out.get(out.size() - 1) instanceof Paragraph))
          out = out.append(new Paragraph(id(), new RichText()));
      } catch (RuntimeException ignored) {
        // A malformed cell keeps the table; it just loses that cell's text.
      }
    }
    if (out.size() == 0) out = out.append(new Paragraph(id(), new RichText()));
    return out;
  }

  private static final class Spec {
    String source;
    /** Content already read (an RTF cell holding nested tables), used instead of source. */
    Sequence<Block> blocks;
    int rows = 1, columns = 1;
    boolean hMergeStart, hMerge, vMergeStart, vMerge;
    String align = "";
  }

  /** HTML-style placement with an occupancy grid, clamped so spans never overlap. */
  private String table(List<List<Spec>> rows) {
    var t = buildTable(rows);
    return t == null ? "" : add(t);
  }

  private Table buildTable(List<List<Spec>> rows) {
    int rowCount = Math.min(rows.size(), MAX_ROWS);
    if (rowCount == 0) return null;
    boolean[][] used = new boolean[rowCount][MAX_COLUMNS];
    var placed = new ArrayList<int[]>();
    var specs = new ArrayList<Spec>();
    int columns = 0;
    for (int r = 0; r < rowCount; r++) {
      int c = 0;
      for (Spec s : rows.get(r)) {
        while (c < MAX_COLUMNS && used[r][c]) c++;
        if (c >= MAX_COLUMNS) break;
        int w = 1;
        while (w < s.columns && c + w < MAX_COLUMNS && !used[r][c + w]) w++;
        int h = Math.max(1, Math.min(s.rows, rowCount - r));
        for (int y = 1; y < h; y++)
          for (int x = 0; x < w; x++)
            if (used[r + y][c + x]) {
              h = y;
              break;
            }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) used[r + y][c + x] = true;
        placed.add(new int[] {r, c, h, w});
        specs.add(s);
        columns = Math.max(columns, c + w);
        c += w;
      }
    }
    if (columns == 0) return null;
    var cells = new ArrayList<List<Sequence<Block>>>();
    for (int r = 0; r < rowCount; r++) {
      var row = new ArrayList<Sequence<Block>>();
      for (int c = 0; c < columns; c++) row.add(null);
      cells.add(row);
    }
    var merges = new ArrayList<Table.Merge>();
    for (int i = 0; i < placed.size(); i++) {
      int[] p = placed.get(i);
      Sequence<Block> content =
          specs.get(i).blocks != null ? specs.get(i).blocks : cell(specs.get(i).source);
      if (specs.get(i).align.length() > 0) {
        var aligned = new Sequence<Block>();
        for (Block block : content)
          aligned =
              aligned.append(
                  block instanceof Paragraph para ? para.with("align", specs.get(i).align) : block);
        content = aligned;
      }
      cells.get(p[0]).set(p[1], content);
      if (p[2] > 1 || p[3] > 1) merges.add(new Table.Merge(p[0], p[1], p[2], p[3]));
    }
    for (List<Sequence<Block>> row : cells)
      for (int c = 0; c < columns; c++)
        if (row.get(c) == null) row.set(c, new Sequence<Block>().append(new Paragraph(id(), new RichText())));
    Table t;
    try {
      t = Table.of(id(), cells, merges);
    } catch (IllegalArgumentException e) {
      t = Table.of(id(), cells, new ArrayList<Table.Merge>());
    }
    return t;
  }

  private String picture(byte[] data, float width, float height, String alt) {
    try {
      int[] shape = Picture.shape(data);
      if (shape == null || shape[0] <= 0 || shape[1] <= 0) return "";
      float w = width > 0 ? width : shape[0] * .75f, h = height > 0 ? height : shape[1] * .75f;
      if (width > 0 && height <= 0) h = width * shape[1] / shape[0];
      if (height > 0 && width <= 0) w = height * shape[0] / shape[1];
      float scale = Math.max(w, h) > 10000 ? 10000 / Math.max(w, h) : 1;
      return add(new Picture(id(), data, Math.max(1, w * scale), Math.max(1, h * scale), alt == null ? "" : alt, "left"));
    } catch (RuntimeException e) {
      return "";
    }
  }

  /** Largest external picture fetched during import. */
  static final int MAX_EXTERNAL_PICTURE = 20 * 1024 * 1024;

  /** A data: picture, or an http/https/file/relative one fetched through {@link #images}. */
  private String sourcePicture(String uri, float w, float h, String alt) {
    String data = dataPicture(uri, w, h, alt);
    if (data != null || images == null) return data;
    String u = resolve(base, uri.trim().replace("&amp;", "&"));
    if (u == null) return null;
    try {
      String mark = picture(images.fetch(u, MAX_EXTERNAL_PICTURE), w, h, alt);
      if (mark.isEmpty()) warnings.add("Picture " + u + " isn't a supported image");
      return mark;
    } catch (Exception e) {
      warnings.add("Couldn't load picture " + u + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      return "";
    }
  }

  /** Resolves an image reference against the source; null when it isn't fetchable. */
  static String resolve(String base, String uri) {
    String lower = uri.toLowerCase();
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) return uri;
    int colon = uri.indexOf(':'), slash0 = uri.indexOf('/');
    if (uri.isEmpty() || uri.startsWith("#") || colon > 1 && (slash0 < 0 || colon < slash0)) return null;
    if (base == null) return uri.startsWith("/") ? uri : null;
    if (uri.startsWith("/")) {
      int host = base.indexOf("://");
      int slash = host < 0 ? -1 : base.indexOf('/', host + 3);
      return host >= 0 && base.toLowerCase().startsWith("http") ? (slash < 0 ? base : base.substring(0, slash)) + uri : uri;
    }
    String dir = base.substring(0, base.lastIndexOf('/') + 1);
    String path = dir + uri;
    // Collapse "./" and "dir/../" segments.
    int scheme = path.indexOf("://");
    String head = "", rest = path;
    if (scheme >= 0) {
      int slash = path.indexOf('/', scheme + 3);
      if (slash < 0) return path;
      head = path.substring(0, slash);
      rest = path.substring(slash);
    }
    var parts = new ArrayList<String>();
    for (String seg : TextUtil.split(rest, '/')) {
      if (".".equals(seg)) continue;
      if ("..".equals(seg)) {
        if (parts.size() > 1) parts.remove(parts.size() - 1);
      } else parts.add(seg);
    }
    var b = new StringBuilder(head);
    for (int i = 0; i < parts.size(); i++) b.append(i == 0 ? "" : "/").append(parts.get(i));
    return b.toString();
  }

  private String dataPicture(String uri, float w, float h, String alt) {
    String lower = uri.trim().toLowerCase();
    if (!lower.startsWith("data:image/")) return null;
    int comma = lower.indexOf(',');
    if (comma < 0 || lower.lastIndexOf(";base64", comma) < 0 || uri.length() > 30 * 1024 * 1024) return null;
    try {
      String payload = uri.trim().substring(comma + 1);
      var clean = new StringBuilder(payload.length());
      for (int i = 0; i < payload.length(); i++) {
        char ch = payload.charAt(i);
        if (ch > ' ') clean.append(ch);
      }
      return picture(com.codename1.util.Base64.decode(clean.toString().getBytes("UTF-8")), w, h, alt);
    } catch (Exception e) {
      return "";
    }
  }

  // HTML ------------------------------------------------------------------

  private static int tagEnd(String s, int from) {
    char quote = 0;
    for (int i = from; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0;
      } else if (c == '"' || c == '\'') quote = c;
      else if (c == '>') return i;
    }
    return s.length() - 1;
  }

  private static boolean tag(String lc, int at, String name) {
    if (!lc.startsWith(name, at)) return false;
    int e = at + name.length();
    if (e >= lc.length()) return false;
    char c = lc.charAt(e);
    return c == '>' || c == ' ' || c == '/' || c == '\t' || c == '\n' || c == '\r';
  }

  static String attr(String tag, String name) {
    String lc = tag.toLowerCase();
    int i = 0;
    while ((i = lc.indexOf(name, i)) >= 0) {
      char before = i == 0 ? ' ' : lc.charAt(i - 1);
      int p = i + name.length();
      while (p < lc.length() && lc.charAt(p) == ' ') p++;
      if ((before == ' ' || before == '\t' || before == '\n' || before == '\r') && p < lc.length() && lc.charAt(p) == '=') {
        p++;
        while (p < lc.length() && lc.charAt(p) == ' ') p++;
        if (p >= lc.length()) return "";
        char q = tag.charAt(p);
        if (q == '"' || q == '\'') {
          int e = tag.indexOf(q, p + 1);
          return unescape(tag.substring(p + 1, e < 0 ? tag.length() : e));
        }
        int e = p;
        while (e < tag.length() && tag.charAt(e) > ' ' && tag.charAt(e) != '>') e++;
        return unescape(tag.substring(p, e));
      }
      i = p;
    }
    return null;
  }

  private static String unescape(String s) {
    if (s.indexOf('&') < 0) return s;
    return s.replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&amp;", "&");
  }

  private static int number(String s, int fallback) {
    if (s == null) return fallback;
    int v = 0, i = 0;
    s = s.trim();
    while (i < s.length() && i < 6 && s.charAt(i) >= '0' && s.charAt(i) <= '9') v = v * 10 + s.charAt(i++) - '0';
    return i == 0 ? fallback : v;
  }

  private static float length(String tag, String name) {
    String style = attr(tag, "style");
    if (style != null) {
      String lc = style.toLowerCase();
      int i = lc.indexOf(name + ":");
      while (i > 0 && lc.charAt(i - 1) != ';' && lc.charAt(i - 1) != ' ')
        i = lc.indexOf(name + ":", i + 1);
      if (i >= 0) {
        int p = i + name.length() + 1, e = p;
        while (e < lc.length() && lc.charAt(e) != ';') e++;
        String v = lc.substring(p, e).trim();
        try {
          if (v.endsWith("pt")) return Float.parseFloat(v.substring(0, v.length() - 2).trim());
          if (v.endsWith("px")) return Float.parseFloat(v.substring(0, v.length() - 2).trim()) * .75f;
        } catch (NumberFormatException ignored) {
          // fall through to the attribute
        }
      }
    }
    int px = number(attr(tag, name), -1);
    return px > 0 ? px * .75f : -1;
  }

  private String html(String text) {
    String lc = text.toLowerCase();
    var out = new StringBuilder(text.length());
    int i = 0;
    while (i < text.length()) {
      int lt = text.indexOf('<', i);
      if (lt < 0) break;
      if (tag(lc, lt, "<img")) {
        int e = tagEnd(text, lt);
        String t = text.substring(lt, e + 1);
        String src = attr(t, "src");
        String mark = src == null ? null : sourcePicture(src, length(t, "width"), length(t, "height"), attr(t, "alt"));
        if (mark != null) {
          out.append(text, i, lt).append(mark);
          i = e + 1;
          continue;
        }
      } else if (tag(lc, lt, "<table")) {
        int end = closing(lc, lt);
        out.append(text, i, lt).append(' ').append(htmlTable(text, lc, lt, end)).append(' ');
        i = Math.min(text.length(), lc.indexOf('>', end) < 0 ? text.length() : lc.indexOf('>', end) + 1);
        continue;
      }
      out.append(text, i, lt + 1);
      i = lt + 1;
    }
    if (i < text.length()) out.append(text, i, text.length());
    return out.toString();
  }

  /** Index of the {@code </table} closing the table at {@code start}, or the text length. */
  private static int closing(String lc, int start) {
    int depth = 0, i = start;
    while (i < lc.length()) {
      int lt = lc.indexOf('<', i);
      if (lt < 0) break;
      if (tag(lc, lt, "<table")) depth++;
      else if (tag(lc, lt, "</table") && --depth == 0) return lt;
      i = lt + 1;
    }
    return lc.length();
  }

  private String htmlTable(String text, String lc, int start, int end) {
    var rows = new ArrayList<List<Spec>>();
    List<Spec> row = null;
    Spec cell = null;
    int contentStart = 0, i = tagEnd(text, start) + 1;
    while (i < end) {
      int lt = lc.indexOf('<', i);
      if (lt < 0 || lt >= end) break;
      if (tag(lc, lt, "<table")) {
        i = closing(lc, lt) + 1;
        continue;
      }
      boolean tr = tag(lc, lt, "<tr"), td = tag(lc, lt, "<td") || tag(lc, lt, "<th");
      boolean close = tag(lc, lt, "</tr") || tag(lc, lt, "</td") || tag(lc, lt, "</th")
          || tag(lc, lt, "</tbody") || tag(lc, lt, "</thead") || tag(lc, lt, "</tfoot");
      if (tr || td || close) {
        if (cell != null) {
          cell.source = text.substring(contentStart, lt);
          cell = null;
        }
        int e = tagEnd(text, lt);
        if (tr || (td && row == null)) {
          if (rows.size() >= MAX_ROWS) break;
          row = new ArrayList<Spec>();
          rows.add(row);
        }
        if (tag(lc, lt, "</tr")) row = null;
        if (td && row.size() < MAX_COLUMNS) {
          String t = text.substring(lt, e + 1);
          cell = new Spec();
          cell.rows = Math.max(1, Math.min(MAX_ROWS, number(attr(t, "rowspan"), 1)));
          cell.columns = Math.max(1, Math.min(MAX_COLUMNS, number(attr(t, "colspan"), 1)));
          row.add(cell);
          contentStart = e + 1;
        }
        i = e + 1;
      } else i = lt + 1;
    }
    if (cell != null) cell.source = text.substring(contentStart, Math.min(end, text.length()));
    for (int r = rows.size() - 1; r >= 0; r--) if (rows.get(r).isEmpty()) rows.remove(r);
    for (List<Spec> rr : rows) for (Spec s : rr) if (s.source == null) s.source = "";
    return table(rows);
  }

  // Markdown --------------------------------------------------------------

  private static boolean delimiterRow(String line) {
    String t = line.trim();
    if (t.indexOf('-') < 0 || t.indexOf('|') < 0 && !t.startsWith("-")) return false;
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c != '|' && c != '-' && c != ':' && c != ' ' && c != '\t') return false;
    }
    return true;
  }

  static List<String> pipeCells(String line) {
    String t = line.trim();
    if (t.startsWith("|")) t = t.substring(1);
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
    var cells = new ArrayList<String>();
    var cur = new StringBuilder();
    boolean code = false;
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
        cur.append('|');
        i++;
      } else if (c == '`') {
        code = !code;
        cur.append(c);
      } else if (c == '|' && !code) {
        // A bare "||" is an empty cell, as in GFM and Wordcraft's markdown reader (no
        // MultiMarkdown colspans).
        cells.add(cur.toString().trim());
        cur.setLength(0);
      } else cur.append(c);
    }
    cells.add(cur.toString().trim());
    return cells;
  }

  private String markdown(String text) {
    String[] lines = TextUtil.split(text, '\n');
    var out = new StringBuilder(text.length());
    boolean fence = false;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
      if (line.trim().startsWith("```") || line.trim().startsWith("~~~")) fence = !fence;
      if (!fence && line.indexOf('|') >= 0 && i + 1 < lines.length && delimiterRow(lines[i + 1])) {
        List<String> head = pipeCells(line);
        List<String> delim = pipeCells(lines[i + 1].trim());
        var rows = new ArrayList<List<Spec>>();
        int j = i;
        while (j < lines.length && rows.size() < MAX_ROWS) {
          if (j == i + 1) {
            j++;
            continue;
          }
          String l = lines[j];
          if (l.trim().length() == 0 || l.indexOf('|') < 0) break;
          List<String> raw = pipeCells(l);
          var row = new ArrayList<Spec>();
          for (int c = 0; c < head.size() && c < MAX_COLUMNS; c++) {
            Spec s = new Spec();
            String v = c < raw.size() ? raw.get(c) : "";
            s.source = v.replace("<br>", "\n\n").replace("<br/>", "\n\n");
            String d = c < delim.size() && delim.get(c) != null ? delim.get(c) : "";
            s.align = d.startsWith(":") && d.endsWith(":") && d.length() > 1 ? "center" : d.endsWith(":") ? "right" : "";
            row.add(s);
          }
          rows.add(row);
          j++;
        }
        out.append('\n').append(table(rows)).append("\n\n");
        i = j - 1;
        continue;
      }
      out.append(fence ? line : markdownImages(line)).append('\n');
    }
    return out.toString();
  }

  private String markdownImages(String line) {
    int i = line.indexOf("![");
    if (i < 0) return line;
    var out = new StringBuilder();
    int from = 0;
    while (i >= 0) {
      int close = line.indexOf("](", i + 2);
      if (close < 0) break;
      int end = line.indexOf(')', close + 2);
      if (end < 0) break;
      String target = line.substring(close + 2, end).trim();
      int space = target.indexOf(' ');
      if (space > 0) target = target.substring(0, space);
      String mark = sourcePicture(target, -1, -1, line.substring(i + 2, close).replace("\\]", "]").replace("\\[", "["));
      if (mark != null) {
        out.append(line, from, i).append(mark);
        from = end + 1;
      }
      i = line.indexOf("![", end);
    }
    return out.append(line, from, line.length()).toString();
  }

  // RTF -------------------------------------------------------------------

  /** Control word at {@code at} (which must be a backslash), or "" for a control symbol. */
  private static String word(String s, int at) {
    int e = at + 1;
    while (e < s.length() && (s.charAt(e) >= 'a' && s.charAt(e) <= 'z')) e++;
    return s.substring(at + 1, e);
  }

  private static int wordEnd(String s, int at) {
    int e = at + 1;
    while (e < s.length() && s.charAt(e) >= 'a' && s.charAt(e) <= 'z') e++;
    if (e < s.length() && s.charAt(e) == '-') e++;
    while (e < s.length() && s.charAt(e) >= '0' && s.charAt(e) <= '9') e++;
    if (e < s.length() && s.charAt(e) == ' ') e++;
    return e == at + 1 ? Math.min(s.length(), at + 2) : e;
  }

  private static int param(String s, int at) {
    int e = at + 1 + word(s, at).length(), v = 0, digits = 0;
    boolean neg = e < s.length() && s.charAt(e) == '-';
    if (neg) e++;
    while (e < s.length() && s.charAt(e) >= '0' && s.charAt(e) <= '9' && digits < 9) {
      v = v * 10 + s.charAt(e++) - '0';
      digits++;
    }
    return neg ? -v : v;
  }

  /** Index just past the group opened at {@code open}. */
  private static int groupEnd(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\') i++;
      else if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return i + 1;
    }
    return s.length();
  }

  private String rtf(String text) {
    var out = new StringBuilder(text.length());
    int i = 0, copied = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '{' && (text.startsWith("{\\pict", i) || text.startsWith("{\\*\\shppict", i) || text.startsWith("{\\nonshppict", i))) {
        int end = groupEnd(text, i);
        out.append(text, copied, i);
        if (!text.startsWith("{\\nonshppict", i)) {
          int p = text.indexOf("{\\pict", i);
          if (p >= 0 && p < end) out.append(' ').append(rtfPicture(text, p, groupEnd(text, p))).append(' ');
        }
        i = copied = end;
        continue;
      }
      if (c == '\\') {
        String w = word(text, i);
        if ("trowd".equals(w)) {
          out.append(text, copied, i);
          i = copied = rtfTable(text, i, out);
          continue;
        }
        i = wordEnd(text, i);
        continue;
      }
      i++;
    }
    return out.append(text, copied, text.length()).toString();
  }

  private String rtfPicture(String s, int start, int end) {
    int kind = 0, picw = 0, pich = 0, goalW = 0, goalH = 0, sx = 100, sy = 100;
    var hex = new StringBuilder();
    for (int i = start + 1; i < end - 1; i++) {
      char c = s.charAt(i);
      if (c == '\\') {
        String w = word(s, i);
        if ("bin".equals(w)) return "";
        if ("pngblip".equals(w)) kind = 1;
        else if ("jpegblip".equals(w)) kind = 2;
        else if ("picw".equals(w)) picw = param(s, i);
        else if ("pich".equals(w)) pich = param(s, i);
        else if ("picwgoal".equals(w)) goalW = param(s, i);
        else if ("pichgoal".equals(w)) goalH = param(s, i);
        else if ("picscalex".equals(w)) sx = param(s, i);
        else if ("picscaley".equals(w)) sy = param(s, i);
        i = wordEnd(s, i) - 1;
      } else if (c == '{') i = groupEnd(s, i) - 1;
      else if (TextUtil.hex(c)) hex.append(c);
    }
    if (kind == 0 || hex.length() < 48 || hex.length() > 40 * 1024 * 1024) return "";
    String digits = hex.toString();
    byte[] data = new byte[digits.length() / 2];
    for (int i = 0; i < data.length; i++)
      data[i] = (byte) (Character.digit(digits.charAt(i * 2), 16) * 16 + Character.digit(digits.charAt(i * 2 + 1), 16));
    float w = goalW > 0 ? goalW / 20f * Math.max(1, sx) / 100f : -1;
    float h = goalH > 0 ? goalH / 20f * Math.max(1, sy) / 100f : -1;
    return picture(data, Math.min(w, 10000), Math.min(h, 10000), "");
  }

  /** Parses consecutive {@code \trowd...\row} rows from {@code start}; returns the end index. */
  private int rtfTable(String s, int start, StringBuilder out) {
    var rows = new ArrayList<List<Spec>>();
    int i = start;
    while (i < s.length() && "trowd".equals(word(s, i)) && rows.size() < MAX_ROWS) {
      var defs = new ArrayList<Spec>();
      Spec pending = new Spec();
      var contents = new ArrayList<String>();
      int contentStart = -1;
      i = wordEnd(s, i);
      boolean done = false;
      while (i < s.length() && !done) {
        char c = s.charAt(i);
        if (c == '{' && (s.startsWith("{\\*\\nesttableprops", i) || s.startsWith("{\\nonesttables", i))) {
          // A nested table's row properties (and its fallback text) belong to the cell content.
          if (contentStart < 0) contentStart = i;
          i = groupEnd(s, i);
          continue;
        }
        if (c == '\\') {
          String w = word(s, i);
          int e = wordEnd(s, i);
          if ("cellx".equals(w)) {
            if (defs.size() < MAX_COLUMNS) defs.add(pending);
            pending = new Spec();
          } else if ("clmgf".equals(w)) pending.hMergeStart = true;
          else if ("clmrg".equals(w)) pending.hMerge = true;
          else if ("clvmgf".equals(w)) pending.vMergeStart = true;
          else if ("clvmrg".equals(w)) pending.vMerge = true;
          else if ("cell".equals(w)) {
            contents.add(contentStart < 0 ? "" : s.substring(contentStart, i));
            contentStart = e;
          } else if ("row".equals(w)) done = true;
          else if ("trowd".equals(w) && !contents.isEmpty()) {
            e = i;
            done = true;
          } else if (contentStart < 0 && !w.startsWith("cl") && !w.startsWith("tr") && !w.startsWith("brdr")
              && !"cellx".equals(w) && w.length() > 0) contentStart = i;
          i = e;
        } else {
          if (contentStart < 0 && c > ' ') contentStart = i;
          i++;
        }
      }
      var row = new ArrayList<Spec>();
      for (int c = 0; c < Math.max(defs.size(), Math.min(contents.size(), MAX_COLUMNS)); c++) {
        Spec d = c < defs.size() ? defs.get(c) : new Spec();
        String content = c < contents.size() ? contents.get(c) : "";
        d.source = rtfFragment(content);
        if (content.indexOf("\\nestcell") >= 0) d.blocks = nestedCell(content);
        row.add(d);
      }
      rows.add(row);
      int j = i;
      while (j < s.length() && (s.charAt(j) <= ' ')) j++;
      if (j < s.length() && s.charAt(j) == '\\' && "trowd".equals(word(s, j))) i = j;
      else break;
    }
    var grid = mergedGrid(rows);
    out.append(' ').append(table(grid)).append(' ');
    return i;
  }

  /** A paragraph of RTF cell content with its table depth (\\itap) and how it ends. */
  private static final class Unit {
    final String rtf;
    final int depth;
    final boolean cellEnd;
    /** Row properties of a nested row end ({\\*\\nesttableprops}), else null. */
    final List<Spec> row;

    Unit(String rtf, int depth, boolean cellEnd, List<Spec> row) {
      this.rtf = rtf;
      this.depth = depth;
      this.cellEnd = cellEnd;
      this.row = row;
    }
  }

  /**
   * Reads an RTF cell holding nested tables: paragraphs at \\itap 1 stay paragraphs, deeper runs
   * (cells ended by \\nestcell, rows by {\\*\\nesttableprops ...\\nestrow}) become tables in the cell.
   */
  private Sequence<Block> nestedCell(String content) {
    var units = new ArrayList<Unit>();
    var cur = new StringBuilder();
    int depth = 1;
    for (int i = 0; i < content.length() && units.size() < 100000; ) {
      char c = content.charAt(i);
      if (c == '{') {
        int end = groupEnd(content, i);
        if (content.startsWith("{\\*\\nesttableprops", i)) {
          var row = new ArrayList<Spec>();
          Spec pending = new Spec();
          for (int k = i + 1; k < end - 1; ) {
            if (content.charAt(k) != '\\') {
              k++;
              continue;
            }
            String w = word(content, k);
            if ("cellx".equals(w)) {
              if (row.size() < MAX_COLUMNS) row.add(pending);
              pending = new Spec();
            } else if ("clmgf".equals(w)) pending.hMergeStart = true;
            else if ("clmrg".equals(w)) pending.hMerge = true;
            else if ("clvmgf".equals(w)) pending.vMergeStart = true;
            else if ("clvmrg".equals(w)) pending.vMerge = true;
            k = wordEnd(content, k);
          }
          int d = units.isEmpty() ? 2 : Math.max(2, units.get(units.size() - 1).depth);
          units.add(new Unit("", d, false, row));
          cur.setLength(0);
        } else if (!content.startsWith("{\\nonesttables", i)) cur.append(content, i, end);
        // (the \\nonesttables group is fallback text for readers without nested tables)
        i = end;
        continue;
      }
      if (c != '\\') {
        cur.append(c);
        i++;
        continue;
      }
      String w = word(content, i);
      int e = wordEnd(content, i);
      if ("pard".equals(w)) depth = 1;
      else if ("itap".equals(w)) depth = Math.max(1, Math.min(16, param(content, i)));
      if ("par".equals(w) || "nestcell".equals(w)) {
        units.add(new Unit(cur.toString(), depth, "nestcell".equals(w), null));
        cur.setLength(0);
      } else cur.append(content, i, e);
      i = e;
    }
    if (cur.toString().trim().length() > 0) units.add(new Unit(cur.toString(), 1, false, null));
    return nestedBlocks(units, 1);
  }

  private Sequence<Block> nestedBlocks(List<Unit> units, int level) {
    var out = new Sequence<Block>();
    for (int i = 0; i < units.size(); ) {
      var u = units.get(i);
      if (u.depth > level) {
        int j = i;
        while (j < units.size() && units.get(j).depth > level) j++;
        var t = nestedTable(units.subList(i, j), level + 1);
        if (t != null) out = out.append(t);
        i = j;
        continue;
      }
      i++;
      if (u.row != null) continue;
      for (Block b : cell(rtfFragment(u.rtf))) out = out.append(b);
    }
    if (out.size() == 0 || !(out.get(out.size() - 1) instanceof Paragraph))
      out = out.append(new Paragraph(id(), new RichText()));
    return out;
  }

  private Table nestedTable(List<Unit> units, int level) {
    var rows = new ArrayList<List<Spec>>();
    var cells = new ArrayList<Sequence<Block>>();
    var cell = new ArrayList<Unit>();
    for (Unit u : units) {
      if (u.row != null && u.depth == level) {
        if (!cell.isEmpty()) cells.add(nestedBlocks(cell, level));
        cell.clear();
        rows.add(nestedRow(u.row, cells));
        cells.clear();
        if (rows.size() > MAX_ROWS) return null;
        continue;
      }
      cell.add(u);
      if (u.depth == level && u.cellEnd) {
        cells.add(nestedBlocks(cell, level));
        cell.clear();
      }
    }
    if (!cell.isEmpty()) cells.add(nestedBlocks(cell, level));
    if (!cells.isEmpty()) rows.add(nestedRow(new ArrayList<Spec>(), cells));
    return buildTable(mergedGrid(rows));
  }

  private List<Spec> nestedRow(List<Spec> defs, List<Sequence<Block>> cells) {
    var row = new ArrayList<Spec>();
    for (int c = 0; c < Math.max(defs.size(), Math.min(cells.size(), MAX_COLUMNS)); c++) {
      Spec d = c < defs.size() ? defs.get(c) : new Spec();
      d.blocks =
          c < cells.size()
              ? cells.get(c)
              : new Sequence<Block>().append(new Paragraph(id(), new RichText()));
      row.add(d);
    }
    return row;
  }

  /** RTF merge flags as spans, with merged-away cells removed for the grid. */
  private static List<List<Spec>> mergedGrid(List<List<Spec>> rows) {
    for (int r = 0; r < rows.size(); r++) {
      List<Spec> row = rows.get(r);
      for (int c = 0; c < row.size(); c++) {
        Spec d = row.get(c);
        if (d.hMergeStart)
          for (int k = c + 1; k < row.size() && row.get(k).hMerge; k++) d.columns++;
        if (d.vMergeStart)
          for (int k = r + 1; k < rows.size() && c < rows.get(k).size() && rows.get(k).get(c).vMerge; k++) d.rows++;
      }
    }
    var grid = new ArrayList<List<Spec>>();
    for (List<Spec> row : rows) {
      var kept = new ArrayList<Spec>();
      for (Spec d : row) if (!d.hMerge && !d.vMerge) kept.add(d);
      grid.add(kept);
    }
    return grid;
  }

  private static String rtfFragment(String body) {
    int depth = 0, low = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\\') i++;
      else if (c == '{') depth++;
      else if (c == '}') low = Math.min(low, --depth);
    }
    var b = new StringBuilder("{\\rtf1\\ansi ");
    for (int i = 0; i < -low; i++) b.append('{');
    b.append(body);
    for (int i = 0; i < depth - low; i++) b.append('}');
    return b.append('}').toString();
  }

  // ------------------------------------------------------------------ export

  static String markdownTable(Table t) {
    var b = new StringBuilder();
    for (int r = 0; r < t.rows(); r++) {
      b.append('|');
      for (int c = 0; c < t.columns(); c++) {
        // Spanned columns are written as empty cells ("| a |  |"), as Wordcraft's markdown
        // writer does (table_md); the span itself is not representable in GFM output.
        b.append(' ');
        if (!t.covered(r, c)) {
          boolean first = true;
          for (Block block : t.blocks(r, c)) {
            if (block instanceof Table) continue;
            if (!first) b.append("<br>");
            first = false;
            // Cell pictures as images in the cell (Wordcraft's table_md writes a paragraph's
            // images inline); a drawing, which has no image data here, as its alt text figure.
            if (block instanceof Picture pic)
              b.append(pic.drawing == null ? markdownPicture(pic) : "![" + pic.alt.replace(']', ' ').replace('\n', ' ') + "]()");
            else markdownRuns(b, (Paragraph) block);
          }
        }
        b.append(" |");
      }
      b.append('\n');
      if (r == 0) {
        b.append('|');
        for (int c = 0; c < t.columns(); c++) {
          String align = t.covered(0, c) ? null : t.cell(0, c).get(0).align;
          b.append("center".equals(align) ? " :---: |" : "right".equals(align) ? " ---: |" : " --- |");
        }
        b.append('\n');
      }
    }
    return b.toString();
  }

  private static void markdownRuns(final StringBuilder b, Paragraph p) {
    p.text.visit(
        new RichText.Visitor() {
          public void note(int offset, Note note, TextStyle style) {}

          public void run(int offset, String text, TextStyle s) {
            var e = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
              char c = text.charAt(i);
              if (c == '|' || c == '\\' || c == '*' || c == '_' || c == '`' || c == '[' || c == ']' || c == '<')
                e.append('\\');
              e.append(c == '\n' || c == '\r' || c == '\t' ? ' ' : c);
            }
            if (e.toString().trim().length() == 0) {
              b.append(e);
              return;
            }
            String wrap = s.bold && s.italic ? "***" : s.bold ? "**" : s.italic ? "*" : "";
            if (s.strike) wrap = "~~" + wrap;
            b.append(wrap).append(e).append(new StringBuilder(wrap).reverse());
          }
        });
  }

  static String markdownPicture(Picture p) {
    var alt = new StringBuilder();
    for (int i = 0; i < p.alt.length(); i++) {
      char c = p.alt.charAt(i);
      if (c == '[' || c == ']' || c == '\\') alt.append('\\');
      alt.append(c == '\n' || c == '\r' ? ' ' : c);
    }
    return "![" + alt + "](data:" + p.mime + ";base64," + com.codename1.util.Base64.encodeNoNewline(p.bytes()) + ")";
  }

  static String rtfPicture(Picture p) {
    byte[] data = p.bytes();
    var b = new StringBuilder(data.length * 2 + 128);
    b.append("{\\pict").append("image/png".equals(p.mime) ? "\\pngblip" : "\\jpegblip")
        .append("\\picw").append(p.pixelWidth).append("\\pich").append(p.pixelHeight)
        .append("\\picwgoal").append(Math.round(p.width * 20)).append("\\pichgoal").append(Math.round(p.height * 20))
        .append('\n');
    for (int i = 0; i < data.length; i++) {
      b.append(TextUtil.padded(data[i] & 255, 16, 2));
      if (i % 64 == 63) b.append('\n');
    }
    return b.append('}').toString();
  }

  static String rtfTable(Table t) {
    var b = new StringBuilder();
    rtfTable(b, t, 1, 9360);
    return b.toString();
  }

  /**
   * Writes the table at depth {@code depth}: outer rows as \\trowd ... \\cell ... \\row, nested
   * rows (Word's RTF 1.8 form) with \\itapN paragraphs ended by \\nestcell and row properties in
   * {\\*\\nesttableprops ... \\nestrow}{\\nonesttables\\par}.
   */
  private static void rtfTable(StringBuilder b, Table t, int depth, float width) {
    for (int r = 0; r < t.rows(); r++) {
      var defs = new StringBuilder("\\trowd\\trgaph108\\trleft0");
      float x = 0;
      for (int c = 0; c < t.columns(); c++) {
        Table.Merge owner = owner(t, r, c);
        if (owner.rows > 1) defs.append(owner.row == r ? "\\clvmgf" : "\\clvmrg");
        if (owner.columns > 1) defs.append(owner.column == c ? "\\clmgf" : "\\clmrg");
        x += t.format.columnFraction(c) * width;
        defs.append("\\cellx").append(Math.round(x));
      }
      if (depth == 1) b.append(defs).append('\n');
      for (int c = 0; c < t.columns(); c++) {
        float cellWidth = t.format.columnFraction(c) * width;
        if (t.covered(r, c)) {
          b.append("\\pard\\plain\\intbl");
          if (depth > 1) b.append("\\itap").append(depth);
          b.append(' ').append(depth > 1 ? "\\nestcell\n" : "\\cell\n");
          continue;
        }
        var blocks = t.blocks(r, c);
        for (int n = 0; n < blocks.size(); n++) {
          var block = blocks.get(n);
          if (block instanceof Table inner) {
            rtfTable(b, inner, depth + 1, cellWidth);
            continue;
          }
          if (block instanceof Picture pic) {
            // A cell picture as its own cell paragraph holding \pict (Wordcraft's RTF writer
            // writes a cell's pictures in its paragraphs); drawings have no RTF picture form.
            if (pic.drawing != null) continue;
            b.append("\\pard\\plain\\intbl");
            if (depth > 1) b.append("\\itap").append(depth);
            b.append(' ');
            if ("center".equals(pic.align)) b.append("\\qc ");
            else if ("right".equals(pic.align)) b.append("\\qr ");
            b.append(rtfPicture(pic));
            if (n < blocks.size() - 1) b.append("\\par\n");
            continue;
          }
          if (!(block instanceof Paragraph p)) continue;
          b.append("\\pard\\plain\\intbl");
          if (depth > 1) b.append("\\itap").append(depth);
          b.append(' ');
          if ("center".equals(p.align)) b.append("\\qc ");
          else if ("right".equals(p.align)) b.append("\\qr ");
          rtfRuns(b, p);
          if (n < blocks.size() - 1) b.append("\\par\n");
        }
        b.append(depth > 1 ? "\\nestcell\n" : "\\cell\n");
      }
      if (depth == 1) b.append("\\row\n");
      else
        b.append("{\\*\\nesttableprops")
            .append(defs)
            .append("\\nestrow}{\\nonesttables\\par}\n");
    }
  }

  private static Table.Merge owner(Table t, int r, int c) {
    for (Table.Merge m : t.merges) if (m.contains(r, c)) return m;
    return new Table.Merge(r, c, 1, 1);
  }

  private static void rtfRuns(final StringBuilder b, Paragraph p) {
    p.text.visit(
        new RichText.Visitor() {
          public void note(int offset, Note note, TextStyle style) {}

          public void run(int offset, String text, TextStyle s) {
            b.append('{');
            if (s.bold) b.append("\\b");
            if (s.italic) b.append("\\i");
            if (s.hasUnderline()) b.append("\\ul");
            if (s.strike) b.append("\\strike");
            if (s.bold || s.italic || s.hasUnderline() || s.strike) b.append(' ');
            for (int i = 0; i < text.length(); i++) {
              char c = text.charAt(i);
              if (c == '\\' || c == '{' || c == '}') b.append('\\').append(c);
              else if (c == '\t') b.append("\\tab ");
              else if (c == '\n' || c == '\r') b.append("\\line ");
              else if (c > 126) b.append("\\u").append((int) (short) c).append('?');
              else b.append(c);
            }
            b.append('}');
          }
        });
  }
}
