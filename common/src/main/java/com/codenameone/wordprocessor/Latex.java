package com.codenameone.wordprocessor;

import java.util.*;

/**
 * LaTeX ({@code .tex}) import and export.
 *
 * <p><b>Export</b> writes a standalone LaTeX2e {@code article} that compiles with pdfLaTeX, XeLaTeX
 * or LuaLaTeX using only packages every distribution ships ({@code fontenc}, {@code inputenc},
 * {@code textcomp}, {@code ulem}, {@code xcolor}, {@code multirow}, {@code longtable}, {@code
 * hyperref}). Headings become unnumbered sectioning commands (Word headings carry no numbers), lists
 * {@code itemize}/{@code enumerate}, tables {@code tabular} (or {@code longtable} when long) with
 * {@code \multicolumn}/{@code \multirow} for merges, notes {@code \footnote}, bookmarks {@code
 * \label} and cross-references {@code \hyperref}/{@code \pageref}. Like Wordcraft, pictures cannot
 * live inside a single {@code .tex} file, so each is replaced by its alternative text.
 *
 * <p><b>Import</b> reads the everyday subset documents are written in. It is not a TeX engine:
 * macros are not expanded, unknown commands are dropped while their braced arguments are kept as
 * text, and layout commands are dropped with their arguments. Math is kept as italic linear text
 * because the model has no equation object. The parser never throws on hostile input: nesting is
 * depth-limited and the source size is capped.
 */
public final class Latex {
  /** Largest source read (characters); the rest is cut off. */
  static final int MAX_SOURCE = 32 << 20;

  private static final int MAX_NEST = 32, END = 0, CELL = 1, ROW = 2;
  private static final int DEFAULT_COLOR = 0x202833;
  private static final String MONO = "Courier New";

  private Latex() {}

  // ================================================================================== export

  /** Writes {@code d} as a standalone LaTeX source file. */
  public static String write(Document d) {
    return new Writer(d).document();
  }

  private static final class Writer {
    final Document d;
    StringBuilder b = new StringBuilder();
    final Map<String, List<String>> labels = new HashMap<>();
    final List<String> lists = new ArrayList<>();
    boolean ulem, color, multirow, longtable, amsmath;

    Writer(Document d) {
      this.d = d;
      for (Bookmark m : d.bookmarks.values()) {
        List<String> l = labels.get(m.startId);
        if (l == null) labels.put(m.startId, l = new ArrayList<>());
        l.add(m.name);
      }
    }

    String document() {
      String title = property("title"), author = property("author");
      boolean skipping = true;
      for (Block block : d.blocks) {
        if (block instanceof Paragraph p) {
          if (skipping && !title.isEmpty() && "Title".equals(p.style)
              && p.text.plainText().trim().equals(title)) continue;
          if (skipping && !author.isEmpty() && "Subtitle".equals(p.style)
              && p.text.plainText().trim().equals(author)) continue;
          skipping = false;
          paragraph(p);
        } else {
          skipping = false;
          closeLists(0);
          if (block instanceof Table t) table(t);
          else if (block instanceof Picture pic && pic.drawing != null && "equation".equals(pic.drawing.kind)) {
            // Equations are written as math, not as a picture placeholder.
            amsmath = true;
            String number = pic.drawing.get("number", "");
            String math = Equation.latex(Equation.of(pic.drawing));
            if ("false".equals(pic.drawing.get("display", "true"))) b.append('$').append(math).append("$\n\n");
            else if (number.isEmpty()) b.append("\\[").append(math).append("\\]\n\n");
            else
              b.append("\\begin{equation*}").append(math).append("\\tag{").append(escape(number))
                  .append("}\\end{equation*}\n\n");
          } else if (block instanceof Picture pic)
            b.append("\\begin{center}\\emph{[Picture: ")
                .append(escape(pic.alt.isEmpty() ? "image" : pic.alt))
                .append("]}\\end{center}\n\n");
        }
      }
      closeLists(0);
      var head = new StringBuilder("\\documentclass{article}\n");
      head.append("\\usepackage[T1]{fontenc}\n\\usepackage[utf8]{inputenc}\n\\usepackage{textcomp}\n");
      if (ulem) head.append("\\usepackage[normalem]{ulem}\n");
      if (amsmath) head.append("\\usepackage{amsmath}\n");
      if (color) head.append("\\usepackage{xcolor}\n");
      if (multirow) head.append("\\usepackage{multirow}\n");
      if (longtable) head.append("\\usepackage{longtable}\n");
      head.append("\\usepackage{hyperref}\n");
      if (!title.isEmpty())
        head.append("\\title{").append(escape(title)).append("}\n\\author{").append(escape(author))
            .append("}\n\\date{}\n");
      head.append("\n\\begin{document}\n");
      if (!title.isEmpty()) head.append("\\maketitle\n");
      head.append('\n');
      return head.append(b).append("\\end{document}\n").toString();
    }

    String property(String key) {
      String v = d.properties.get(key);
      return v == null ? "" : v.trim();
    }

    void closeLists(int depth) {
      while (lists.size() > depth)
        b.append("\\end{").append(lists.remove(lists.size() - 1)).append("}\n");
      if (depth == 0 && b.length() > 0 && b.charAt(b.length() - 1) != '\n') b.append('\n');
    }

    void paragraph(Paragraph p) {
      if (p.pageBreak) b.append("\\newpage\n");
      int heading = heading(p.style);
      if (p.list.isEmpty() || heading > 0) closeLists(0);
      else {
        int depth = Math.min(4, Math.max(1, p.listLevel + 1));
        String kind = "bullet".equals(p.list) ? "itemize" : "enumerate";
        if (lists.size() > depth) closeLists(depth);
        if (lists.size() == depth && !lists.get(depth - 1).equals(kind)) closeLists(depth - 1);
        while (lists.size() < depth) {
          b.append("\\begin{").append(kind).append("}\n");
          lists.add(kind);
        }
        b.append("\\item{} ");
        labels(p);
        inline(p.text);
        b.append('\n');
        return;
      }
      String text = p.text.plainText();
      if (text.trim().isEmpty() && !labels.containsKey(p.id)) return;
      if (heading > 0) {
        b.append(heading == 1 ? "\\section*{" : heading == 2 ? "\\subsection*{"
            : heading == 3 ? "\\subsubsection*{" : "\\paragraph*{");
        inline(p.text);
        b.append("}");
        labels(p);
        b.append("\n\n");
        return;
      }
      String env = "center".equals(p.align) ? "center" : "right".equals(p.align) ? "flushright" : null;
      if ("Title".equals(p.style)) env = "center";
      if (env != null) b.append("\\begin{").append(env).append("}\n");
      if (p.indent >= 18 && env == null) b.append("\\begin{quote}\n");
      if ("Title".equals(p.style)) b.append("{\\LARGE ");
      labels(p);
      inline(p.text);
      if ("Title".equals(p.style)) b.append("\\par}");
      b.append('\n');
      if (p.indent >= 18 && env == null) b.append("\\end{quote}\n");
      if (env != null) b.append("\\end{").append(env).append("}\n");
      b.append('\n');
    }

    void labels(Paragraph p) {
      List<String> l = labels.get(p.id);
      if (l != null) for (String name : l) b.append("\\label{").append(name).append('}');
    }

    void inline(RichText text) {
      text.visit(
          new RichText.Visitor() {
            public void run(int offset, String value, TextStyle s) {
              if (s.revision != null && "delete".equals(s.revision.kind)) return;
              span(escape(value), s);
            }

            public void field(int offset, InlineField f, TextStyle s) {
              if (Equation.inline(f)) {
                amsmath = true;
                b.append('$').append(Equation.latex(Equation.fromLinear(Equation.inlineText(f)))).append('$');
                return;
              }
              String[] parts = TextUtil.split(f.instruction.trim(), ' ');
              String name = parts.length > 1 ? parts[1] : "";
              if (("REF".equals(parts[0]) || "PAGEREF".equals(parts[0])) && Bookmark.validName(name)) {
                if ("PAGEREF".equals(parts[0])) b.append("\\pageref{").append(name).append('}');
                else span("\\hyperref[" + name + "]{" + escape(f.display()) + "}", s.with("link", ""));
              } else run(offset, f.display(), s);
            }

            public void note(int offset, Note note, TextStyle s) {
              b.append("\\footnote{");
              boolean first = true;
              for (Paragraph p : note.paragraphs) {
                if (!first) b.append("\\par ");
                first = false;
                inline(p.text);
              }
              b.append('}');
            }
          });
    }

    void span(String body, TextStyle s) {
      if (body.isEmpty()) return;
      String t = body;
      if ("superscript".equals(s.script)) t = "\\textsuperscript{" + t + "}";
      else if ("subscript".equals(s.script)) t = "\\textsubscript{" + t + "}";
      String font = s.font.toLowerCase();
      if (font.contains("courier") || font.contains("mono") || font.contains("consolas"))
        t = "\\texttt{" + t + "}";
      if (s.characters.smallCaps) t = "\\textsc{" + t + "}";
      if (s.strike || s.characters.doubleStrike) {
        ulem = true;
        t = "\\sout{" + t + "}";
      }
      if (s.hasUnderline()) {
        ulem = true;
        t = "\\uline{" + t + "}";
      }
      if (s.italic) t = "\\textit{" + t + "}";
      if (s.bold) t = "\\textbf{" + t + "}";
      int rgb = s.color & 0xffffff;
      if (rgb != DEFAULT_COLOR && rgb != 0) {
        color = true;
        t = "\\textcolor[HTML]{" + TextUtil.padded(rgb, 16, 6).toUpperCase() + "}{" + t + "}";
      }
      if (Math.abs(s.size - 12) > .01f)
        t = "{\\fontsize{" + number(s.size) + "}{" + number(s.size * 1.2f) + "}\\selectfont{}" + t + "}";
      if (!s.link.isEmpty()) {
        if (s.link.startsWith("#") && Bookmark.validName(s.link.substring(1)))
          t = "\\hyperref[" + s.link.substring(1) + "]{" + t + "}";
        else t = "\\href{" + url(s.link) + "}{" + t + "}";
      }
      b.append(t);
    }

    void table(Table t) {
      boolean longer = t.rows() > 30;
      longtable |= longer;
      String env = longer ? "longtable" : "tabular";
      var spec = new StringBuilder("|");
      String[] aligns = new String[t.columns()];
      for (int c = 0; c < t.columns(); c++) {
        aligns[c] = "l";
        var cell = t.cell(0, c);
        if (cell.size() > 0) {
          String a = cell.get(0).align;
          aligns[c] = "center".equals(a) ? "c" : "right".equals(a) ? "r" : "l";
        }
        spec.append(aligns[c]).append('|');
      }
      if (!longer) b.append("\\begin{center}\n");
      b.append("\\begin{").append(env).append("}{").append(spec).append("}\n\\hline\n");
      for (int r = 0; r < t.rows(); r++) {
        boolean first = true;
        for (int c = 0; c < t.columns(); c++) {
          Table.Merge m = null;
          for (Table.Merge x : t.merges) if (x.contains(r, c)) m = x;
          if (m != null && !(c == m.column && r >= m.row)) continue;
          if (!first) b.append(" & ");
          first = false;
          int cols = m == null ? 1 : m.columns, rows = m == null ? 1 : m.rows;
          String body = "";
          if (m == null || m.row == r) {
            StringBuilder outer = b;
            b = new StringBuilder();
            for (Paragraph p : t.cell(r, c)) {
              if (b.length() > 0) b.append(' ');
              inline(p.text);
            }
            body = b.toString();
            b = outer;
          }
          if (rows > 1 && m != null && m.row == r) {
            multirow = true;
            body = "\\multirow{" + rows + "}{*}{" + body + "}";
          }
          if (cols > 1) body = "\\multicolumn{" + cols + "}{" + (c == 0 ? "|" : "") + aligns[c] + "|}{" + body + "}";
          b.append(body);
        }
        b.append(" \\\\\n");
        boolean full = true;
        for (Table.Merge x : t.merges) if (x.rows > 1 && r >= x.row && r < x.row + x.rows - 1) full = false;
        if (full) b.append("\\hline\n");
        else
          for (int c = 0; c < t.columns(); c++) {
            boolean spanning = false;
            for (Table.Merge x : t.merges)
              if (x.rows > 1 && r >= x.row && r < x.row + x.rows - 1 && c >= x.column && c < x.column + x.columns)
                spanning = true;
            if (!spanning) b.append("\\cline{").append(c + 1).append('-').append(c + 1).append('}');
          }
        if (!full) b.append('\n');
      }
      b.append("\\end{").append(env).append("}\n");
      if (!longer) b.append("\\end{center}\n");
      b.append('\n');
    }
  }

  private static int heading(String style) {
    if (style.length() == 8 && style.startsWith("Heading") && style.charAt(7) >= '1' && style.charAt(7) <= '9')
      return style.charAt(7) - '0';
    return 0;
  }

  private static String number(float f) {
    int i = Math.round(f * 10);
    return i % 10 == 0 ? "" + (i / 10) : (i / 10) + "." + (i % 10);
  }

  private static String url(String u) {
    var b = new StringBuilder();
    for (int i = 0; i < u.length(); i++) {
      char c = u.charAt(i);
      if (c == '%' || c == '#' || c == '{' || c == '}' || c == '\\' || c == '~') b.append('\\');
      if (c >= ' ') b.append(c);
    }
    return b.toString();
  }

  /** Escapes TeX's special characters, ligatures and common Unicode punctuation. */
  static String escape(String s) {
    var b = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
      switch (c) {
        case '\\': b.append("\\textbackslash{}"); break;
        case '{': case '}': case '$': case '&': case '%': case '#': case '_':
          b.append('\\').append(c);
          break;
        case '~': b.append("\\textasciitilde{}"); break;
        case '^': b.append("\\textasciicircum{}"); break;
        case '<': b.append("\\textless{}"); break;
        case '>': b.append("\\textgreater{}"); break;
        case '|': b.append("\\textbar{}"); break;
        case '"': b.append("\\textquotedbl{}"); break;
        case '`': b.append("\\textasciigrave{}"); break;
        case '\'': b.append(next == '\'' ? "'{}" : "'"); break;
        case '-': b.append(next == '-' ? "-{}" : "-"); break;
        case '\n': b.append("\\newline{}"); break;
        case '\t': b.append("\\quad{}"); break;
        case '\u00a0': b.append('~'); break;
        case '\u2013': b.append("--"); break;
        case '\u2014': b.append("---"); break;
        case '\u2026': b.append("\\ldots{}"); break;
        case '\u201c': b.append("``"); break;
        case '\u201d': b.append("''"); break;
        case '\u2018': b.append('`'); break;
        case '\u2019': b.append(next == '\'' ? "'{}" : "'"); break;
        default:
          if (c >= ' ' && c != '\u007f') b.append(c);
      }
    }
    return b.toString();
  }

  // ================================================================================== import

  /** Reads a LaTeX source. Never throws on malformed input. */
  public static Document read(String source) {
    if (source == null) source = "";
    if (source.length() > MAX_SOURCE) source = source.substring(0, MAX_SOURCE);
    return new Reader(source).document();
  }

  private static final class Sink {
    final boolean body;
    final List<Block> blocks = new ArrayList<>();
    RichText text = new RichText();
    final StringBuilder buf = new StringBuilder();
    TextStyle bufStyle;
    boolean space, item, pageBreak, lastHeading;
    String style = "Normal", align = "left", list = "", lastId;
    int level, indent;
    final List<Object[]> labels = new ArrayList<>();

    Sink(boolean body) {
      this.body = body;
    }

    void add(String s, TextStyle st) {
      if (s.isEmpty()) return;
      if (space && length() > 0) {
        space = false;
        add(" ", st);
      }
      space = false;
      if (bufStyle != null && bufStyle != st) spill();
      bufStyle = st;
      buf.append(s);
    }

    void spill() {
      if (buf.length() > 0) text = text.concat(RichText.of(buf.toString(), bufStyle));
      buf.setLength(0);
      bufStyle = null;
    }

    int length() {
      return text.length() + buf.length();
    }

    void object(RichText r) {
      if (space && length() > 0) {
        space = false;
        add(" ", r.styleAt(0));
      }
      space = false;
      spill();
      text = text.concat(r);
    }
  }

  private static final class Reader {
    final String s;
    final int n;
    int i;
    long next = 1;
    TextStyle style = TextStyle.DEFAULT;
    Sink sink = new Sink(true);
    final List<String> lists = new ArrayList<>();
    final Map<String, Bookmark> bookmarks = new LinkedHashMap<>();
    String title = "", author = "";
    int cellCols = 1, cellRows = 1;
    boolean inNote;

    Reader(String s) {
      this.s = s;
      this.n = s.length();
    }

    Document document() {
      int begin = s.indexOf("\\begin{document}");
      if (begin >= 0) {
        preamble(begin);
        i = begin + "\\begin{document}".length();
        content("document", false, false, 1);
      } else content(null, false, false, 0);
      flush();
      var blocks = new Sequence<Block>();
      for (Block block : sink.blocks) blocks = blocks.append(block);
      if (blocks.size() == 0) blocks = blocks.append(new Paragraph("p" + next++, new RichText()));
      Document d = new Document(blocks, PageSpec.A4, title.isEmpty() ? "Imported document" : title, next);
      if (!bookmarks.isEmpty()) d = d.bookmarks(bookmarks);
      if (!title.isEmpty() || !author.isEmpty())
        d = d.properties(DocumentProperties.EMPTY.with("title", title).with("author", author));
      return Notes.numbered(d, null);
    }

    void preamble(int end) {
      while (i < end) {
        char c = s.charAt(i);
        if (c == '%') {
          while (i < end && s.charAt(i) != '\n') i++;
        } else if (c == '\\') {
          String name = command();
          if ("title".equals(name)) title = plainArg();
          else if ("author".equals(name)) author = plainArg();
        } else i++;
      }
    }

    String plainArg() {
      Sink saved = sink;
      TextStyle st = style;
      sink = new Sink(false);
      boolean note = inNote;
      inNote = true;
      arg(2);
      flush();
      var b = new StringBuilder();
      for (Block block : sink.blocks) {
        if (b.length() > 0) b.append(' ');
        b.append(((Paragraph) block).text.plainText());
      }
      sink = saved;
      style = st;
      inNote = note;
      return b.toString().replace('\n', ' ').trim();
    }

    // ---- scanning helpers

    /** Reads a command name after a backslash; i is left after the name (and spaces for words). */
    String command() {
      i++;
      if (i >= n) return "";
      char c = s.charAt(i);
      if (!letter(c)) {
        i++;
        return String.valueOf(c);
      }
      int a = i;
      while (i < n && letter(s.charAt(i))) i++;
      String name = s.substring(a, i);
      if (i < n && s.charAt(i) == '*') {
        name += "*";
        i++;
      }
      skipSpaces();
      return name;
    }

    void skipSpaces() {
      boolean line = false;
      while (i < n) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\t' || c == '\r') i++;
        else if (c == '\n' && !line && !(i + 1 < n && blankAfter(i + 1))) {
          line = true;
          i++;
        } else break;
      }
    }

    boolean blankAfter(int at) {
      while (at < n && (s.charAt(at) == ' ' || s.charAt(at) == '\t' || s.charAt(at) == '\r')) at++;
      return at < n && s.charAt(at) == '\n';
    }

    void skipWhite() {
      while (i < n && s.charAt(i) <= ' ') i++;
    }

    /** An optional [..] argument as raw text, or null. */
    String optional() {
      int save = i;
      skipWhite();
      if (i >= n || s.charAt(i) != '[') {
        i = save;
        return null;
      }
      int depth = 0, a = ++i;
      while (i < n) {
        char c = s.charAt(i);
        if (c == '\\') i++;
        else if (c == '{') depth++;
        else if (c == '}') depth--;
        else if (c == ']' && depth <= 0) break;
        i++;
      }
      String r = s.substring(a, Math.min(i, n));
      if (i < n) i++;
      return r;
    }

    /** A mandatory argument as raw text (braced group or single token). */
    String raw() {
      skipWhite();
      if (i >= n) return "";
      char c = s.charAt(i);
      if (c != '{') {
        if (c == '\\') {
          int a = i;
          command();
          return s.substring(a, i).trim();
        }
        i++;
        return String.valueOf(c);
      }
      int depth = 0, a = i + 1;
      while (i < n) {
        c = s.charAt(i);
        if (c == '\\') i++;
        else if (c == '{') depth++;
        else if (c == '}' && --depth == 0) break;
        i++;
      }
      String r = s.substring(a, Math.min(i, n));
      if (i < n) i++;
      return r;
    }

    /** Parses a mandatory argument into the current sink with the current style. */
    void arg(int depth) {
      skipWhite();
      if (i >= n) return;
      TextStyle saved = style;
      String align = sink.align;
      if (s.charAt(i) == '{' && depth <= MAX_NEST) {
        i++;
        content(null, true, false, depth + 1);
      } else if (s.charAt(i) == '{') raw();
      else if (s.charAt(i) == '\\') one(depth + 1);
      else text(String.valueOf(s.charAt(i++)));
      style = saved;
      sink.align = align;
    }

    void one(int depth) {
      int a = i;
      String name = command();
      macro(name, depth, a);
    }

    void styled(String key, String value, int depth) {
      TextStyle saved = style;
      style = style.with(key, value);
      arg(depth);
      style = saved;
    }

    void text(String t) {
      sink.add(t, style);
    }

    void space() {
      if (sink.length() > 0) sink.space = true;
    }

    // ---- the content loop

    int content(String env, boolean group, boolean cells, int depth) {
      while (i < n) {
        char c = s.charAt(i);
        switch (c) {
          case '}':
            i++;
            if (group) return END;
            break;
          case '{':
            i++;
            if (depth <= MAX_NEST) {
              TextStyle saved = style;
              String align = sink.align;
              content(null, true, false, depth + 1);
              style = saved;
              sink.align = align;
            }
            break;
          case '%':
            while (i < n && s.charAt(i) != '\n') i++;
            if (i < n) i++;
            while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            if (i < n && s.charAt(i) == '\n') paragraphBreak();
            break;
          case '\n':
            i++;
            if (blankAfter(i) || (i < n && s.charAt(i) == '\n')) paragraphBreak();
            else space();
            break;
          case ' ': case '\t': case '\r':
            i++;
            space();
            break;
          case '$':
            math();
            break;
          case '~':
            i++;
            text("\u00a0");
            break;
          case '&':
            i++;
            if (cells) return CELL;
            space();
            break;
          case '-':
            if (s.startsWith("---", i)) { text("\u2014"); i += 3; }
            else if (s.startsWith("--", i)) { text("\u2013"); i += 2; }
            else { text("-"); i++; }
            break;
          case '`':
            if (s.startsWith("``", i)) { text("\u201c"); i += 2; }
            else { text("\u2018"); i++; }
            break;
          case '\'':
            if (s.startsWith("''", i)) { text("\u201d"); i += 2; }
            else { text("'"); i++; }
            break;
          case '\\': {
            int at = i;
            String name = command();
            if ("\\".equals(name) || "tabularnewline".equals(name)) {
              optional();
              if (cells) return ROW;
              newline();
            } else if ("end".equals(name)) {
              String e = raw().trim();
              if (e.equals(env)) return END;
            } else if ("begin".equals(name)) {
              String e = raw().trim();
              if (depth <= MAX_NEST) environment(e, depth + 1);
            } else macro(name, depth, at);
            break;
          }
          default: {
            int a = i;
            while (i < n && "{}%\n \t\r$~&-`'\\".indexOf(s.charAt(i)) < 0) i++;
            if (i == a) i++;
            text(s.substring(a, i));
          }
        }
      }
      return END;
    }

    void newline() {
      sink.space = false;
      text("\n");
    }

    void paragraphBreak() {
      while (i < n && s.charAt(i) <= ' ') i++;
      flush();
    }

    void flush() {
      Sink k = sink;
      k.spill();
      k.space = false;
      if (k.text.length() == 0 && !k.item && k.labels.isEmpty()) return;
      RichText t = k.text;
      int end = t.length();
      while (end > 0 && t.charAt(end - 1) == ' ' && !t.objectAt(end - 1)) end--;
      if (end < t.length()) t = t.slice(0, end);
      String id = "p" + next++;
      var p = new Paragraph(id, t).with("style", k.style).with("align", k.align);
      if (!k.list.isEmpty())
        p = p.with("list", k.list).with("listLevel", "" + k.level);
      else if (k.indent > 0) p = p.with("indent", "" + k.indent);
      if (k.pageBreak) p = p.with("pageBreak", "true");
      for (Object[] l : k.labels) {
        String name = (String) l[0];
        int at = Math.min((Integer) l[1], t.length());
        if (k.body && !bookmarks.containsKey(name)) bookmarks.put(name, new Bookmark(name, id, at, id, at));
      }
      k.labels.clear();
      k.blocks.add(p);
      k.lastId = id;
      k.lastHeading = Latex.heading(k.style) > 0;
      k.text = new RichText();
      k.item = false;
      k.pageBreak = false;
      if (k.list.length() > 0) {
        k.list = "";
        k.indent = 18 * (k.level + 1);
      }
      if (!"Normal".equals(k.style)) k.style = "Normal";
    }

    void heading(String styleName, int depth) {
      flush();
      String saved = sink.style;
      sink.style = styleName;
      optional();
      TextStyle st = style;
      arg(depth);
      style = st;
      flush();
      sink.style = saved;
    }

    void label(String raw) {
      String name = labelName(raw);
      if (name == null) return;
      sink.spill();
      if (sink.length() == 0 && sink.lastHeading && sink.lastId != null && sink.body) {
        if (!bookmarks.containsKey(name))
          bookmarks.put(name, new Bookmark(name, sink.lastId, 0, sink.lastId, 0));
        return;
      }
      sink.labels.add(new Object[] {name, sink.length()});
    }

    void field(String rawName, String result, boolean page) {
      String name = labelName(rawName);
      if (name == null) {
        text(result);
        return;
      }
      String instruction = (page ? "PAGEREF " : "REF ") + name + " \\h";
      sink.object(RichText.field(new InlineField(instruction, result, false, false), style));
    }

    void math() {
      boolean display = s.startsWith("$$", i);
      i += display ? 2 : 1;
      int a = i;
      String close = display ? "$$" : "$";
      while (i < n && !s.startsWith(close, i)) i += s.charAt(i) == '\\' ? 2 : 1;
      String body = s.substring(a, Math.min(i, n));
      i = Math.min(n, i + close.length());
      mathText(body, display);
    }

    void mathText(String body, boolean display) {
      // As in Wordcraft: $...$ and \(...\) become inline equations; $$...$$, \[...\] and the
      // equation environments become display (block) equations.
      String linear = linear(body).trim();
      if (linear.isEmpty()) return;
      if (display && !sink.body) {
        // Table cells and notes hold paragraphs only: a centered paragraph with the equation.
        flush();
        String align = sink.align;
        sink.align = "center";
        sink.object(RichText.field(Equation.inline(linear), style));
        flush();
        sink.align = align;
      } else if (display) {
        flush();
        var d = Drawing.of("equation").with("text", linear).with("display", "true");
        sink.blocks.add(Picture.drawing("i" + next++, d, 468,
            Math.max(36, Equation.height(Equation.fromLinear(linear), 12)), "", "center"));
      } else sink.object(RichText.field(Equation.inline(linear), style));
    }

    void verbatim(String env) {
      flush();
      String close = "\\end{" + env + "}";
      int end = s.indexOf(close, i);
      if (end < 0) end = n;
      String body = s.substring(i, end);
      i = Math.min(n, end + close.length());
      if (body.startsWith("\n")) body = body.substring(1);
      else if (body.startsWith("\r\n")) body = body.substring(2);
      String[] lines = TextUtil.split(body, '\n');
      int count = lines.length;
      if (count > 0 && lines[count - 1].trim().isEmpty()) count--;
      TextStyle mono = style.with("font", MONO);
      for (int k = 0; k < count; k++) {
        String line = lines[k];
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        sink.add(line.replace('\t', ' '), mono);
        sink.item = true;
        flush();
      }
    }

    void environment(String e, int depth) {
      String base = e.endsWith("*") ? e.substring(0, e.length() - 1) : e;
      switch (base) {
        case "itemize": case "enumerate": case "description": {
          flush();
          lists.add(base);
          int indent = sink.indent;
          content(e, false, false, depth);
          flush();
          lists.remove(lists.size() - 1);
          sink.indent = indent;
          return;
        }
        case "quote": case "quotation": case "verse": case "abstract": {
          flush();
          if ("abstract".equals(base)) {
            sink.style = "Heading2";
            text("Abstract");
            flush();
          }
          int indent = sink.indent;
          sink.indent = indent + 36;
          content(e, false, false, depth);
          flush();
          sink.indent = indent;
          return;
        }
        case "center": case "flushleft": case "flushright": {
          flush();
          String align = sink.align;
          sink.align = "center".equals(base) ? "center" : "flushright".equals(base) ? "right" : "left";
          content(e, false, false, depth);
          flush();
          sink.align = align;
          return;
        }
        case "verbatim": case "lstlisting": case "Verbatim": case "minted": case "alltt": case "comment":
          if ("minted".equals(base)) {
            optional();
            raw();
          } else optional();
          if ("comment".equals(base)) {
            int end = s.indexOf("\\end{comment}", i);
            i = end < 0 ? n : end + "\\end{comment}".length();
            return;
          }
          verbatim(e);
          return;
        case "tabular": case "tabularx": case "longtable": case "array": case "supertabular":
          if ("tabular*".equals(e) || "tabularx".equals(base)) raw();
          optional();
          table(e, raw(), depth);
          return;
        case "equation": case "align": case "displaymath": case "gather": case "multline":
        case "eqnarray": case "math": {
          String close = "\\end{" + e + "}";
          int end = s.indexOf(close, i);
          if (end < 0) end = n;
          String body = s.substring(i, end);
          i = Math.min(n, end + close.length());
          mathText(body, !"math".equals(base));
          return;
        }
        case "figure": case "table": case "wrapfigure": case "minipage":
          optional();
          if ("wrapfigure".equals(base) || "minipage".equals(base)) raw();
          flush();
          content(e, false, false, depth);
          flush();
          return;
        case "thebibliography":
          raw();
          flush();
          sink.style = "Heading1";
          text("References");
          flush();
          lists.add("enumerate");
          content(e, false, false, depth);
          flush();
          lists.remove(lists.size() - 1);
          return;
        default:
          content(e, false, false, depth);
      }
    }

    void item(int depth) {
      flush();
      String label = optional();
      String kind = lists.isEmpty() ? "itemize" : lists.get(lists.size() - 1);
      if (!"description".equals(kind)) {
        sink.list = "enumerate".equals(kind) ? "number" : "bullet";
        sink.level = Math.max(0, Math.min(8, lists.size() - 1));
      }
      sink.item = true;
      if (label != null) {
        TextStyle saved = style;
        style = style.with("bold", "true");
        sub(label, depth);
        style = saved;
        sink.space = true;
      }
    }

    /** Parses a fragment of source text (an optional argument) into the current sink. */
    void sub(String fragment, int depth) {
      Reader r = new Reader(fragment);
      r.sink = sink;
      r.style = style;
      r.next = next;
      r.inNote = true;
      r.content(null, false, false, depth + 1);
      r.sink.spill();
      next = r.next;
    }

    void footnote(int depth) {
      optional();
      if (inNote || !sink.body) {
        text(" (");
        arg(depth);
        text(")");
        return;
      }
      sink.spill();
      Sink saved = sink;
      TextStyle st = style;
      sink = new Sink(false);
      style = TextStyle.DEFAULT;
      inNote = true;
      arg(depth);
      flush();
      inNote = false;
      var paragraphs = new Sequence<Paragraph>();
      for (Block block : sink.blocks) paragraphs = paragraphs.append((Paragraph) block);
      if (paragraphs.size() == 0) paragraphs = paragraphs.append(new Paragraph("p" + next++, new RichText()));
      sink = saved;
      style = st;
      if (paragraphs.size() > 10000) return;
      sink.object(RichText.note(new Note("n" + next++, "footnote", "", "1", paragraphs), style));
    }

    void table(String env, String spec, int depth) {
      flush();
      List<String> aligns = columns(spec);
      if (!sink.body || inNote) {
        // Tables cannot nest in cells or notes: keep their words.
        content(env, false, false, depth);
        return;
      }
      List<List<Object[]>> rows = new ArrayList<>();
      List<Object[]> row = new ArrayList<>();
      Sink saved = sink;
      TextStyle st = style;
      int guard = 0;
      while (i < n && guard++ < 1000000) {
        sink = new Sink(false);
        int column = 0;
        for (Object[] done : row) column += (Integer) done[1];
        sink.align = aligns.isEmpty() ? "left" : aligns.get(Math.min(column, aligns.size() - 1));
        cellCols = 1;
        cellRows = 1;
        int start = i;
        int r = content(env, false, true, depth);
        flush();
        style = st;
        var paragraphs = new Sequence<Paragraph>();
        for (Block block : sink.blocks) paragraphs = paragraphs.append((Paragraph) block);
        boolean empty = paragraphs.size() == 0;
        if (empty) paragraphs = paragraphs.append(new Paragraph("p" + next++, new RichText()).with("align", sink.align));
        boolean last = r == END;
        if (!(last && empty && row.isEmpty() && cellCols == 1 && cellRows == 1))
          row.add(new Object[] {paragraphs, cellCols, cellRows});
        if (r != CELL) {
          if (!row.isEmpty()) rows.add(row);
          row = new ArrayList<>();
        }
        if (last || i == start) break;
      }
      sink = saved;
      style = st;
      if (rows.size() > 0 && rows.size() <= 10000) addTable(rows, aligns);
    }

    void addTable(List<List<Object[]>> rows, List<String> aligns) {
      int cols = 0;
      for (List<Object[]> row : rows) {
        int w = 0;
        for (Object[] cell : row) w += (Integer) cell[1];
        cols = Math.max(cols, w);
      }
      cols = Math.min(cols, 64);
      if (cols == 0) return;
      int height = rows.size();
      boolean[][] taken = new boolean[height][cols];
      List<List<Sequence<Paragraph>>> grid = new ArrayList<>();
      for (int r = 0; r < height; r++) {
        List<Sequence<Paragraph>> line = new ArrayList<>();
        for (int c = 0; c < cols; c++) line.add(null);
        grid.add(line);
      }
      List<Table.Merge> merges = new ArrayList<>();
      for (int r = 0; r < height; r++) {
        int c = 0;
        for (Object[] cell : rows.get(r)) {
          if (c >= cols) break;
          int w = Math.max(1, Math.min((Integer) cell[1], cols - c));
          if (taken[r][c]) {
            // The explicit empty cell under a \multirow.
            c += w;
            continue;
          }
          @SuppressWarnings("unchecked")
          Sequence<Paragraph> ps = (Sequence<Paragraph>) cell[0];
          int h = Math.max(1, Math.min((Integer) cell[2], height - r));
          for (int y = r; y < r + h; y++)
            for (int x = c; x < c + w; x++) taken[y][x] = true;
          grid.get(r).set(c, ps);
          if (w > 1 || h > 1) merges.add(new Table.Merge(r, c, h, w));
          c += w;
        }
      }
      for (int r = 0; r < height; r++)
        for (int c = 0; c < cols; c++)
          if (grid.get(r).get(c) == null) {
            String a = aligns.isEmpty() ? "left" : aligns.get(Math.min(c, aligns.size() - 1));
            grid.get(r).set(c, Sequence.<Paragraph>of(Collections.singletonList(
                new Paragraph("p" + next++, new RichText()).with("align", a))));
          }
      try {
        sink.blocks.add(new Table("t" + next++, grid, merges));
      } catch (RuntimeException ex) {
        sink.blocks.add(new Table("t" + next++, grid));
      }
    }

    List<String> columns(String spec) {
      List<String> out = new ArrayList<>();
      int k = 0, len = Math.min(spec.length(), 4096);
      while (k < len && out.size() < 64) {
        char c = spec.charAt(k++);
        if (c == 'l' || c == 'X') out.add("left");
        else if (c == 'c') out.add("center");
        else if (c == 'r') out.add("right");
        else if (c == 'p' || c == 'm' || c == 'b') out.add("left");
        else if (c == '*') {
          // *{n}{spec}: expand once.
          int[] at = {k};
          String count = group(spec, at, len), body = group(spec, at, len);
          k = at[0];
          int times = 0;
          try {
            times = Math.min(64, Integer.parseInt(count.trim()));
          } catch (NumberFormatException ex) {
            times = 1;
          }
          List<String> one = columns(body.length() > 256 ? body.substring(0, 256) : body);
          for (int t = 0; t < times && out.size() < 64; t++) out.addAll(one);
          continue;
        } else continue;
        if ((c == 'p' || c == 'm' || c == 'b') && k < len && spec.charAt(k) == '{') {
          int[] at = {k};
          group(spec, at, len);
          k = at[0];
        }
        if (k < len && spec.charAt(k) == '{' && "@<>!".indexOf(spec.charAt(k - 1)) >= 0) {
          int[] at = {k};
          group(spec, at, len);
          k = at[0];
        }
      }
      // Skip @{..}, >{..}, <{..}, !{..} groups that were not consumed above.
      while (out.size() > 64) out.remove(out.size() - 1);
      return out;
    }

    static String group(String spec, int[] at, int len) {
      int k = at[0];
      while (k < len && spec.charAt(k) == ' ') k++;
      if (k >= len || spec.charAt(k) != '{') {
        at[0] = Math.min(len, k + 1);
        return k < len ? String.valueOf(spec.charAt(k)) : "";
      }
      int depth = 0, a = k + 1;
      while (k < len) {
        char c = spec.charAt(k);
        if (c == '{') depth++;
        else if (c == '}' && --depth == 0) break;
        k++;
      }
      at[0] = Math.min(len, k + 1);
      return spec.substring(a, Math.min(k, len));
    }

    // ---- commands

    void macro(String name, int depth, int at) {
      if (name.isEmpty()) return;
      char first = name.charAt(0);
      if (!letter(first)) {
        symbol(first, depth);
        return;
      }
      switch (name) {
        case "textbf": styled("bold", "true", depth); return;
        case "textit": case "emph": case "textsl": styled("italic", "true", depth); return;
        case "underline": case "uline": styled("underline", "true", depth); return;
        case "sout": case "st": case "xout": styled("strike", "true", depth); return;
        case "textsc": styled("smallCaps", "true", depth); return;
        case "texttt": styled("font", MONO, depth); return;
        case "textsuperscript": styled("script", "superscript", depth); return;
        case "textsubscript": styled("script", "subscript", depth); return;
        case "textrm": case "textsf": case "textmd": case "textup": case "textnormal": case "mbox":
        case "text": case "hbox": case "makebox": case "fbox": case "framebox":
          if ("makebox".equals(name) || "framebox".equals(name)) { optional(); optional(); }
          arg(depth);
          return;
        case "bfseries": case "bf": style = style.with("bold", "true"); return;
        case "itshape": case "it": case "em": case "slshape": case "sl": style = style.with("italic", "true"); return;
        case "scshape": case "sc": style = style.with("smallCaps", "true"); return;
        case "ttfamily": case "tt": style = style.with("font", MONO); return;
        case "upshape": case "mdseries": case "rmfamily": case "sffamily": case "normalfont": return;
        case "tiny": style = style.size(6); return;
        case "scriptsize": style = style.size(8); return;
        case "footnotesize": style = style.size(10); return;
        case "small": style = style.size(11); return;
        case "normalsize": style = style.size(12); return;
        case "large": style = style.size(14); return;
        case "Large": style = style.size(17); return;
        case "LARGE": style = style.size(20); return;
        case "huge": style = style.size(24); return;
        case "Huge": style = style.size(29); return;
        case "fontsize": {
          String size = raw();
          raw();
          float pt = points(size);
          if (pt > 0 && pt < 1000) style = style.size(pt);
          return;
        }
        case "selectfont": return;
        case "textcolor": case "color": case "colorbox": {
          String model = optional();
          String rgb = color(model, raw());
          if ("color".equals(name)) {
            if (rgb != null) style = style.with("color", rgb);
          } else if ("colorbox".equals(name)) arg(depth);
          else if (rgb != null) styled("color", rgb, depth);
          else arg(depth);
          return;
        }
        case "href": {
          String target = unescapeUrl(raw());
          if (target.isEmpty()) arg(depth);
          else styled("link", target, depth);
          return;
        }
        case "url": case "nolinkurl": {
          String target = unescapeUrl(raw());
          TextStyle saved = style;
          if ("url".equals(name)) style = style.with("link", target);
          text(target);
          style = saved;
          return;
        }
        case "hyperref": {
          String label = optional();
          if (label == null) {
            arg(depth);
            return;
          }
          Sink saved = sink;
          sink = new Sink(false);
          TextStyle st = style;
          arg(depth);
          sink.spill();
          String result = sink.text.plainText();
          sink = saved;
          style = st;
          field(label, result, false);
          return;
        }
        case "ref": case "autoref": case "cref": case "Cref": case "eqref": case "nameref": case "vref": {
          String label = raw();
          field(label, label.trim(), false);
          return;
        }
        case "pageref": {
          String label = raw();
          field(label, label.trim(), true);
          return;
        }
        case "label": label(raw()); return;
        case "footnote": case "endnote": footnote(depth); return;
        case "footnotemark": optional(); return;
        case "footnotetext": optional(); text(" ("); arg(depth); text(")"); return;
        case "marginpar": case "thanks": case "todo": optional(); raw(); return;
        case "part": case "chapter": case "section": case "part*": case "chapter*": case "section*":
          heading("Heading1", depth); return;
        case "subsection": case "subsection*": heading("Heading2", depth); return;
        case "subsubsection": case "subsubsection*": heading("Heading3", depth); return;
        case "paragraph": case "paragraph*": heading("Heading4", depth); return;
        case "subparagraph": case "subparagraph*": heading("Heading5", depth); return;
        case "title": title = plainArg(); return;
        case "author": author = plainArg(); return;
        case "maketitle":
          if (!sink.body) return;
          flush();
          if (!title.isEmpty()) {
            sink.style = "Title";
            sink.align = sink.align;
            text(title);
            flush();
          }
          if (!author.isEmpty()) {
            sink.style = "Subtitle";
            text(author);
            flush();
          }
          return;
        case "item": item(depth); return;
        case "bibitem": {
          optional();
          String key = raw();
          item(depth);
          label(key);
          return;
        }
        case "cite": case "citep": case "citet": case "parencite": case "textcite": case "autocite": {
          optional();
          optional();
          text("[" + raw().trim() + "]");
          return;
        }
        case "par": paragraphBreak(); return;
        case "newline": case "linebreak": case "break": optional(); newline(); return;
        case "newpage": case "clearpage": case "pagebreak": case "cleardoublepage":
          optional();
          flush();
          sink.pageBreak = sink.body;
          return;
        case "includegraphics": {
          optional();
          String file = raw().trim();
          TextStyle saved = style;
          style = style.with("italic", "true");
          text("[Picture: " + file + "]");
          style = saved;
          return;
        }
        case "caption": case "caption*": {
          optional();
          flush();
          String align = sink.align;
          sink.align = "center";
          styled("italic", "true", depth);
          flush();
          sink.align = align;
          return;
        }
        case "verb": case "verb*": {
          if (i >= n) return;
          char delim = s.charAt(i++);
          int a = i;
          while (i < n && s.charAt(i) != delim && s.charAt(i) != '\n') i++;
          String body = s.substring(a, i);
          if (i < n) i++;
          TextStyle saved = style;
          style = style.with("font", MONO);
          text(body);
          style = saved;
          return;
        }
        case "centering": sink.align = "center"; return;
        case "raggedright": sink.align = "left"; return;
        case "raggedleft": sink.align = "right"; return;
        case "multicolumn": {
          String count = raw();
          String spec = raw();
          cellCols = Math.max(1, Math.min(64, integer(count)));
          List<String> a = columns(spec);
          if (!a.isEmpty()) sink.align = a.get(0);
          arg(depth);
          return;
        }
        case "multirow": {
          optional();
          String count = raw();
          optional();
          raw();
          optional();
          cellRows = Math.max(1, Math.min(10000, Math.abs(integer(count))));
          arg(depth);
          return;
        }
        case "LaTeX": text("LaTeX"); return;
        case "LaTeXe": text("LaTeX2e"); return;
        case "TeX": text("TeX"); return;
        case "ldots": case "dots": case "textellipsis": text("\u2026"); return;
        case "textendash": text("\u2013"); return;
        case "textemdash": text("\u2014"); return;
        case "textbackslash": text("\\"); return;
        case "textasciitilde": text("~"); return;
        case "textasciicircum": text("^"); return;
        case "textasciigrave": text("`"); return;
        case "textquotedbl": text("\""); return;
        case "textless": text("<"); return;
        case "textgreater": text(">"); return;
        case "textbar": text("|"); return;
        case "textbullet": text("\u2022"); return;
        case "textquoteleft": text("\u2018"); return;
        case "textquoteright": text("\u2019"); return;
        case "textregistered": text("\u00ae"); return;
        case "texttrademark": text("\u2122"); return;
        case "copyright": case "textcopyright": text("\u00a9"); return;
        case "S": case "textsection": text("\u00a7"); return;
        case "P": case "textparagraph": text("\u00b6"); return;
        case "dag": case "textdagger": text("\u2020"); return;
        case "ddag": case "textdaggerdbl": text("\u2021"); return;
        case "pounds": case "textsterling": text("\u00a3"); return;
        case "euro": case "texteuro": text("\u20ac"); return;
        case "textdegree": text("\u00b0"); return;
        case "ss": text("\u00df"); return;
        case "ae": text("\u00e6"); return;
        case "AE": text("\u00c6"); return;
        case "oe": text("\u0153"); return;
        case "OE": text("\u0152"); return;
        case "o": text("\u00f8"); return;
        case "O": text("\u00d8"); return;
        case "aa": text("\u00e5"); return;
        case "AA": text("\u00c5"); return;
        case "l": text("\u0142"); return;
        case "L": text("\u0141"); return;
        case "i": text("\u0131"); return;
        case "quad": case "qquad": text("\t"); return;
        case "enspace": case "thinspace": case "space": case "nobreakspace": text(" "); return;
        case "today": return;
        case "c": case "v": case "u": case "H": case "r": case "k": case "b": case "d": case "t":
          accent(name.charAt(0), depth);
          return;
        case "newcommand": case "renewcommand": case "providecommand": case "newcommand*":
        case "renewcommand*": case "providecommand*": case "DeclareMathOperator":
          raw();
          optional();
          optional();
          raw();
          return;
        case "newenvironment": case "renewenvironment":
          raw();
          optional();
          optional();
          raw();
          raw();
          return;
        case "def": case "gdef": case "edef": case "xdef": {
          while (i < n && s.charAt(i) != '{') i++;
          raw();
          return;
        }
        case "documentclass": case "usepackage": case "RequirePackage":
          optional();
          raw();
          optional();
          return;
        case "addcontentsline": raw(); raw(); raw(); return;
        case "setlength": case "addtolength": case "setcounter": case "addtocounter":
        case "numberwithin": case "renewrobustcmd": case "settowidth":
          raw();
          raw();
          return;
        case "definecolor": raw(); raw(); raw(); return;
        case "vspace": case "vspace*": case "hspace": case "hspace*": case "pagestyle": case "thispagestyle":
        case "bibliographystyle": case "bibliography": case "input": case "include": case "includeonly":
        case "geometry": case "hypersetup": case "graphicspath": case "captionsetup": case "setmainfont":
        case "setsansfont": case "setmonofont": case "linespread": case "cline": case "pagenumbering":
        case "enlargethispage": case "addbibresource": case "lstset": case "newcounter": case "stepcounter":
        case "refstepcounter": case "theoremstyle": case "date": case "newtheorem": case "phantom":
        case "hphantom": case "vphantom": case "rule": case "printbibliography":
          optional();
          raw();
          if ("rule".equals(name) || "newtheorem".equals(name)) raw();
          if ("newtheorem".equals(name)) optional();
          return;
        case "cmidrule": {
          int save = i;
          skipWhite();
          if (i < n && s.charAt(i) == '(') {
            while (i < n && s.charAt(i) != ')') i++;
            if (i < n) i++;
          } else i = save;
          raw();
          return;
        }
        case "hline": case "toprule": case "midrule": case "bottomrule": case "noindent": case "indent":
        case "tableofcontents": case "listoffigures": case "listoftables": case "smallskip":
        case "medskip": case "bigskip": case "hfill": case "vfill": case "null": case "relax":
        case "protect": case "nopagebreak": case "noalign": case "appendix": case "frontmatter":
        case "mainmatter": case "backmatter": case "centerline": case "endinput": case "sloppy":
        case "fussy": case "onecolumn": case "twocolumn": case "and": case "displaystyle":
        case "endfirsthead": case "endhead": case "endfoot": case "endlastfoot": case "item*":
          if ("and".equals(name)) text(", ");
          if ("twocolumn".equals(name)) optional();
          return;
        default:
          // Unknown command: drop it and keep the text of its braced arguments.
          while (true) {
            int save = i;
            if (optional() != null) continue;
            i = save;
            skipWhite();
            if (i < n && s.charAt(i) == '{' && !blankBetween(save, i)) {
              arg(depth);
            } else {
              i = save;
              break;
            }
          }
      }
    }

    boolean blankBetween(int a, int b) {
      int lines = 0;
      for (int k = a; k < b; k++) if (s.charAt(k) == '\n') lines++;
      return lines > 1;
    }

    void symbol(char c, int depth) {
      switch (c) {
        case '{': case '}': case '$': case '&': case '%': case '#': case '_':
          text(String.valueOf(c));
          return;
        case ' ': case '\n': case '\t': text(" "); return;
        case ',': case ';': case ':': text(" "); return;
        case '!': case '@': case '/': case '-': return;
        case '[': {
          int end = s.indexOf("\\]", i);
          if (end < 0) end = n;
          String body = s.substring(i, end);
          i = Math.min(n, end + 2);
          mathText(body, true);
          return;
        }
        case '(': {
          int end = s.indexOf("\\)", i);
          if (end < 0) end = n;
          String body = s.substring(i, end);
          i = Math.min(n, end + 2);
          mathText(body, false);
          return;
        }
        case '\'': case '`': case '^': case '"': case '~': case '=': case '.':
          accent(c, depth);
          return;
        default:
      }
    }

    void accent(char kind, int depth) {
      String base = raw();
      if (base.startsWith("\\")) base = "i".equals(base.substring(1).trim()) ? "i" : base.substring(1).trim();
      if (base.isEmpty()) return;
      char ch = base.charAt(0);
      String from = "aeiouyAEIOUYncNCszSZgrRelLtT";
      String to = null;
      switch (kind) {
        case '\'': to = "\u00e1\u00e9\u00ed\u00f3\u00fa\u00fd\u00c1\u00c9\u00cd\u00d3\u00da\u00dd\u0144\u0107\u0143\u0106\u015b\u017a\u015a\u0179\u01f5\u0155\u0154\u00e9\u013a\u0139\u0000\u0000"; break;
        case '`': to = "\u00e0\u00e8\u00ec\u00f2\u00f9\u1ef3\u00c0\u00c8\u00cc\u00d2\u00d9\u1ef2\u01f9\u0000\u01f8"; break;
        case '^': to = "\u00e2\u00ea\u00ee\u00f4\u00fb\u0177\u00c2\u00ca\u00ce\u00d4\u00db\u0176"; break;
        case '"': to = "\u00e4\u00eb\u00ef\u00f6\u00fc\u00ff\u00c4\u00cb\u00cf\u00d6\u00dc\u0178"; break;
        case '~': to = "\u00e3\u0000\u0129\u00f5\u0169\u0000\u00c3\u0000\u0128\u00d5\u0168\u0000\u00f1\u0000\u00d1"; break;
        case 'c': to = "\u0000\u0229\u0000\u0000\u0000\u0000\u0000\u0228\u0000\u0000\u0000\u0000\u0146\u00e7\u0145\u00c7\u015f\u0000\u015e\u0000\u0123\u0157\u0156\u0000\u013c\u013b\u0163\u0162"; break;
        case 'v': to = "\u01ce\u011b\u01d0\u01d2\u01d4\u0000\u01cd\u011a\u01cf\u01d1\u01d3\u0000\u0148\u010d\u0147\u010c\u0161\u017e\u0160\u017d\u01e7\u0159\u0158\u0000\u013e\u013d\u0165\u0164"; break;
        default:
      }
      int k = from.indexOf(ch);
      String combining = kind == '\'' ? "\u0301" : kind == '`' ? "\u0300" : kind == '^' ? "\u0302"
          : kind == '"' ? "\u0308" : kind == '~' ? "\u0303" : kind == '=' ? "\u0304" : kind == '.' ? "\u0307"
          : kind == 'c' ? "\u0327" : kind == 'v' ? "\u030c" : kind == 'u' ? "\u0306" : kind == 'H' ? "\u030b"
          : kind == 'r' ? "\u030a" : kind == 'k' ? "\u0328" : kind == 'd' ? "\u0323" : kind == 'b' ? "\u0331" : "";
      if (to != null && k >= 0 && k < to.length() && to.charAt(k) != 0) text(to.charAt(k) + base.substring(1));
      else text(ch + combining + base.substring(1));
    }

    String color(String model, String value) {
      String v = value.trim();
      if (model != null) {
        String m = model.trim();
        if ("HTML".equalsIgnoreCase(m) && v.length() == 6 && hex(v)) return v.toLowerCase();
        String[] parts = TextUtil.split(v, ',');
        if (parts.length == 3) {
          try {
            int rgb = 0;
            for (String p : parts) {
              float f = Float.parseFloat(p.trim());
              int x = "RGB".equals(m) ? Math.round(f) : Math.round(f * 255);
              rgb = rgb << 8 | Math.max(0, Math.min(255, x));
            }
            return TextUtil.padded(rgb, 16, 6);
          } catch (NumberFormatException ex) {
            return null;
          }
        }
        if ("gray".equals(m)) {
          try {
            int g = Math.max(0, Math.min(255, Math.round(Float.parseFloat(v) * 255)));
            return TextUtil.padded(g << 16 | g << 8 | g, 16, 6);
          } catch (NumberFormatException ex) {
            return null;
          }
        }
        return null;
      }
      String[] names = {"black", "000000", "white", "ffffff", "red", "ff0000", "green", "00ff00", "blue",
        "0000ff", "cyan", "00ffff", "magenta", "ff00ff", "yellow", "ffff00", "gray", "808080", "darkgray",
        "404040", "lightgray", "bfbfbf", "brown", "bf8040", "orange", "ff8000", "purple", "bf0040", "teal",
        "008080", "olive", "808000", "violet", "800080", "lime", "bfff00", "pink", "ffbfbf"};
      int bang = v.indexOf('!');
      if (bang > 0) v = v.substring(0, bang);
      for (int k = 0; k < names.length; k += 2) if (names[k].equals(v)) return names[k + 1];
      return null;
    }

    static boolean hex(String v) {
      for (int k = 0; k < v.length(); k++)
        if ("0123456789abcdefABCDEF".indexOf(v.charAt(k)) < 0) return false;
      return true;
    }

    static float points(String size) {
      String v = size.trim();
      if (v.endsWith("pt")) v = v.substring(0, v.length() - 2);
      try {
        return Float.parseFloat(v.trim());
      } catch (NumberFormatException ex) {
        return -1;
      }
    }

    static int integer(String v) {
      try {
        return Integer.parseInt(v.trim());
      } catch (NumberFormatException ex) {
        return 1;
      }
    }

    static String unescapeUrl(String u) {
      var b = new StringBuilder();
      for (int k = 0; k < u.length(); k++) {
        char c = u.charAt(k);
        if (c == '\\' && k + 1 < u.length()) c = u.charAt(++k);
        if (c > ' ') b.append(c);
      }
      return b.toString();
    }
  }

  /** A valid bookmark name for a LaTeX label, or null. */
  static String labelName(String raw) {
    var b = new StringBuilder();
    String v = raw.trim();
    for (int k = 0; k < v.length() && b.length() < 40; k++) {
      char c = v.charAt(k);
      b.append(Unicode.letterOrDigit(c) || c == '_' ? c : '_');
    }
    if (b.length() == 0) return null;
    if (!(Unicode.letter(b.charAt(0)) || b.charAt(0) == '_')) {
      b.insert(0, '_');
      if (b.length() > 40) b.setLength(40);
    }
    String name = b.toString();
    return Bookmark.validName(name) ? name : null;
  }

  private static boolean letter(char c) {
    return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '@';
  }

  /** TeX math source as readable linear text: symbols mapped, braces and spacing removed. */
  static String linear(String tex) {
    String[] map = {"alpha", "\u03b1", "beta", "\u03b2", "gamma", "\u03b3", "delta", "\u03b4", "epsilon",
      "\u03b5", "varepsilon", "\u03b5", "zeta", "\u03b6", "eta", "\u03b7", "theta", "\u03b8", "iota", "\u03b9",
      "kappa", "\u03ba", "lambda", "\u03bb", "mu", "\u03bc", "nu", "\u03bd", "xi", "\u03be", "pi", "\u03c0",
      "rho", "\u03c1", "sigma", "\u03c3", "tau", "\u03c4", "phi", "\u03c6", "varphi", "\u03c6", "chi",
      "\u03c7", "psi", "\u03c8", "omega", "\u03c9", "Gamma", "\u0393", "Delta", "\u0394", "Theta", "\u0398",
      "Lambda", "\u039b", "Pi", "\u03a0", "Sigma", "\u03a3", "Phi", "\u03a6", "Psi", "\u03a8", "Omega",
      "\u03a9", "cdot", "\u00b7", "times", "\u00d7", "div", "\u00f7", "pm", "\u00b1", "leq", "\u2264", "le",
      "\u2264", "geq", "\u2265", "ge", "\u2265", "neq", "\u2260", "ne", "\u2260", "approx", "\u2248", "infty",
      "\u221e", "sum", "\u2211", "prod", "\u220f", "int", "\u222b", "partial", "\u2202", "nabla", "\u2207",
      "in", "\u2208", "rightarrow", "\u2192", "to", "\u2192", "leftarrow", "\u2190", "Rightarrow", "\u21d2",
      "sqrt", "\u221a", "ldots", "\u2026", "cdots", "\u22ef", "forall", "\u2200", "exists", "\u2203",
      "equiv", "\u2261", "sim", "\u223c", "propto", "\u221d", "circ", "\u2218", "subset", "\u2282", "cup",
      "\u222a", "cap", "\u2229", "emptyset", "\u2205"};
    var b = new StringBuilder();
    int k = 0, len = tex.length();
    while (k < len) {
      char c = tex.charAt(k);
      if (c == '\\') {
        int a = ++k;
        while (k < len && letter(tex.charAt(k))) k++;
        if (k == a) {
          if (k < len) {
            char x = tex.charAt(k++);
            if (x == '\\') b.append(' ');
            else if ("{}$%&#_".indexOf(x) >= 0) b.append(x);
            else b.append(' ');
          }
          continue;
        }
        String name = tex.substring(a, k);
        if ("frac".equals(name) || "dfrac".equals(name) || "tfrac".equals(name)) {
          int[] at = {k};
          String num = Reader.group(tex, at, len), den = Reader.group(tex, at, len);
          k = at[0];
          b.append('(').append(linear(num)).append(")/(").append(linear(den)).append(')');
          continue;
        }
        boolean found = false;
        for (int m = 0; m < map.length; m += 2)
          if (map[m].equals(name)) {
            b.append(map[m + 1]);
            found = true;
            break;
          }
        if (!found && !("left".equals(name) || "right".equals(name) || "mathrm".equals(name)
            || "mathbf".equals(name) || "mathit".equals(name) || "text".equals(name) || "label".equals(name)
            || "nonumber".equals(name) || "quad".equals(name) || "qquad".equals(name)
            || "displaystyle".equals(name) || "operatorname".equals(name) || "big".equals(name)
            || "Big".equals(name) || "limits".equals(name)))
          b.append(name);
        if ("label".equals(name)) {
          int[] at = {k};
          Reader.group(tex, at, len);
          k = at[0];
        }
        continue;
      }
      if (c == '{' || c == '}' || c == '&' || c == '$') {
        k++;
        continue;
      }
      if (c == '\n' || c == '\r' || c == '\t' || c == '~') c = ' ';
      if (c == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' ')) {
        k++;
        continue;
      }
      if (c >= ' ') b.append(c);
      k++;
    }
    return b.toString().trim();
  }
}
