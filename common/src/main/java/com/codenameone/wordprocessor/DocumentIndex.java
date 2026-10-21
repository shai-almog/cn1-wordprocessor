package com.codenameone.wordprocessor;

import java.util.*;

/** Editable index and shared pagination engine for authority reference tables. */
public final class DocumentIndex {
  private DocumentIndex() {}

  public static final class Options {
    public final String title, leader;
    public final boolean runIn, headings, rightAlign;
    public Options(String title, boolean runIn, boolean headings, boolean rightAlign, String leader) {
      if (title == null || title.length() > 1000 || title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0
          || !Arrays.asList("none", "dot", "hyphen", "underscore", "middleDot").contains(leader))
        throw new IllegalArgumentException("Invalid index settings");
      this.title = title; this.runIn = runIn; this.headings = headings;
      this.rightAlign = rightAlign; this.leader = leader;
    }
    String instruction(String kind) {
      if ("TOA".equals(kind)) return "TOA";
      return "INDEX" + (headings ? " \\h \"A\"" : "") + (rightAlign ? " \\e \"\t\"" : "") + (runIn ? " \\r" : "");
    }
  }

  public static InlineField marker(Paragraph p) { return marker(p, "INDEX"); }

  static InlineField marker(Paragraph p, String kind) {
    if (!p.text.hasFields()) return null;
    for (InlineField f : p.text.fields())
      if (kind.equals(new FieldCode(f.instruction).name)) return f;
    return null;
  }

  public static boolean entryStyle(String style) {
    return "ListEntry".equals(style) || "IndexLetter".equals(style)
        || style.length() == 6 && style.startsWith("Index") && style.charAt(5) >= '1' && style.charAt(5) <= '9';
  }

  public static int[] range(Document d) { return range(d, "INDEX"); }

  static int[] range(Document d, String kind) {
    int i = -1;
    for (Block block : d.blocks) {
      i++;
      if (!(block instanceof Paragraph) || marker((Paragraph) block, kind) == null) continue;
      int end = i + 1;
      while (end < d.blocks.size() && d.blocks.get(end) instanceof Paragraph
          && ("BIBLIOGRAPHY".equals(kind) ? Citations.entryStyle(d.paragraph(end).style) : "TOA".equals(kind) ? authorityStyle(d.paragraph(end).style) : entryStyle(d.paragraph(end).style))
          && marker(d.paragraph(end), kind) == null) end++;
      return new int[]{i, end};
    }
    return null;
  }

  public static Options options(Document d) { return options(d, "INDEX"); }

  static boolean authorityStyle(String style) { return "ListEntry".equals(style) || "TableofAuthorities".equals(style); }

  static Options options(Document d, String kind) {
    int[] range = range(d, kind);
    if ("TOA".equals(kind)) {
      if (range == null) return new Options("Table of Authorities", false, false, true, "dot");
      var header = d.paragraph(range[0]);
      var code = new FieldCode(marker(header, kind).instruction);
      if (!code.switches.isEmpty()) throw new IllegalArgumentException("TOA field switches cannot be regenerated yet");
      var leader = "dot";
      if (range[1] > range[0] + 1 && !d.paragraph(range[0] + 1).format.tabs.isEmpty())
        leader = d.paragraph(range[0] + 1).format.tabs.get(0).leader;
      return new Options(header.text.plainText(), false, false, true, leader);
    }
    if (range == null) return new Options("Index", false, true, false, "dot");
    var p = d.paragraph(range[0]);
    var code = new FieldCode(marker(p).instruction);
    for (String key : code.switches.keySet())
      if (!Arrays.asList("h", "e", "r", "c", "*").contains(key))
        throw new IllegalArgumentException("Unsupported INDEX switch: " + key);
    if (code.switches.containsKey("c") && !"1".equals(code.switches.get("c")))
      throw new IllegalArgumentException("Multiple index columns are not implemented yet");
    if (code.switches.containsKey("e") && !"\t".equals(code.switches.get("e")) && !", ".equals(code.switches.get("e")))
      throw new IllegalArgumentException("Custom index separators are not implemented yet");
    if (code.switches.containsKey("h") && !"A".equals(code.switches.get("h")))
      throw new IllegalArgumentException("Custom index headings are not implemented yet");
    var leader = "dot";
    for (int i = range[0] + 1; i < range[1]; i++) {
      List<TabStop> tabs = d.paragraph(i).format.tabs;
      if (!tabs.isEmpty()) { leader = tabs.get(tabs.size() - 1).leader; break; }
    }
    return new Options(p.text.plainText(), code.switches.containsKey("r"), code.switches.containsKey("h"),
        "\t".equals(code.switches.get("e")), leader);
  }

  private static String quote(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
  private static String checked(String value, boolean required) {
    if (value == null || value.length() > 200 || required && value.trim().isEmpty())
      throw new IllegalArgumentException("An index entry must contain 1 to 200 characters");
    for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) >= 127 && value.charAt(i) <= 159)
      throw new IllegalArgumentException("Index entries cannot contain control characters");
    return value.trim();
  }

  /** Mark All adds the first same-case whole-word match in each body or table paragraph. */
  public static int mark(Session session, String entry, String subentry, String see, String bookmark,
      boolean bold, boolean italic, boolean all, String search) {
    String main = checked(entry, true), sub = checked(subentry, false), cross = checked(see, false);
    if (!bookmark.isEmpty() && !session.document().bookmarks.containsKey(bookmark))
      throw new IllegalArgumentException("Unknown bookmark: " + bookmark);
    if (!cross.isEmpty() && !bookmark.isEmpty()) throw new IllegalArgumentException("Choose a cross-reference or page range");
    var instruction = "XE \"" + quote(main + (sub.isEmpty() ? "" : ":" + sub)) + "\""
        + (cross.isEmpty() ? "" : " \\t \"" + quote(cross) + "\"")
        + (bookmark.isEmpty() ? "" : " \\r " + bookmark) + (bold ? " \\b" : "") + (italic ? " \\i" : "");
    final var field = new InlineField(instruction, "", false, false);
    if (all && (search == null || search.isEmpty())) throw new IllegalArgumentException("Enter text to mark");
    final int[] count = {0};
    session.transact(s -> {
      if (!all) {
        var selection = s.selection(); s.select(selection.endBlock(), selection.end(), selection.end());
        s.insertField(field); count[0] = 1;
      } else {
        s.leaveCell();
        // Every paragraph of the body and of table cells at any depth, in document order.
        for (MixedRange.Covered c : MixedRange.paragraphs(s.document())) {
          int at = match(c.paragraph, search, instruction);
          if (at < 0) continue;
          s.enterCellPath(c.path);
          s.select(c.block, at, at);
          s.insertField(field);
          s.leaveCell();
          count[0]++;
        }
      }
    });
    return count[0];
  }

  private static int match(Paragraph p, String search, String instruction) {
    if (entryStyle(p.style) || p.style.startsWith("TOC") || marker(p) != null) return -1;
    for (int i = 0; i < p.text.length(); i++) {
      var f = p.text.fieldAt(i);
      if (f != null && instruction.equals(f.instruction)) return -1;
    }
    // Raw model offsets preserve the positions of atomic fields and note references.
    var text = p.text.toString();
    for (int start = text.indexOf(search); start >= 0; start = text.indexOf(search, start + 1)) {
      int end = start + search.length();
      if ((start == 0 || !wordCharacter(text.charAt(start - 1)))
          && (end == text.length() || !wordCharacter(text.charAt(end)))) return end;
    }
    return -1;
  }

  private static boolean wordCharacter(char c) {
    return Unicode.letterOrDigit(c) || c >= 0xD800 && c <= 0xDFFF;
  }

  public static void remove(Session session) { remove(session, "INDEX"); }

  static void remove(Session session, String kind) {
    if (session.inCell()) throw new IllegalArgumentException("Reference tables belong in the document body");
    int[] r = range(session.document(), kind); if (r == null) return;
    session.transact(s -> {
      var d = s.document(); Sequence<Block> blocks = d.blocks; long next = d.nextId;
      for (int i = r[1] - 1; i >= r[0]; i--) blocks = blocks.remove(i);
      if (blocks.size() == 0) blocks = blocks.append(new Paragraph("p" + next++, new RichText()));
      s.replaceDocument(d.blocks(blocks, next));
      int at = Math.min(r[0], blocks.size() - 1);
      if (blocks.get(at) instanceof Paragraph) s.select(at, 0, 0);
    });
  }

  public static void update(Session session, TableContents.Paginator paginator) { update(session, paginator, "INDEX"); }

  static void update(Session session, TableContents.Paginator paginator, String kind) {
    int[] range = range(session.document(), kind);
    if (range == null || marker(session.document().paragraph(range[0]), kind).locked) return;
    Options options = options(session.document(), kind);
    var selection = session.selection();
    var anchor = session.editingDocument().blocks.get(selection.anchorBlock).id();
    var focus = session.editingDocument().blocks.get(selection.block).id();
    String table = session.inCell() ? session.document().blocks.get(session.rootCellBlock()).id() : null;
    int[] path = session.cellPath();
    session.transact(s -> {
      s.leaveCell(); insert(s, options, paginator, kind);
      if (table != null) {
        path[0] = ReviewComment.block(s.document(), table);
        s.enterCellPath(path);
      }
      int a = ReviewComment.block(s.editingDocument(), anchor), f = ReviewComment.block(s.editingDocument(), focus);
      if (a >= 0 && f >= 0 && s.editingDocument().blocks.get(a) instanceof Paragraph && s.editingDocument().blocks.get(f) instanceof Paragraph)
        s.selectRange(a, Math.min(selection.anchor, s.editingDocument().paragraph(a).text.length()),
            f, Math.min(selection.caret, s.editingDocument().paragraph(f).text.length()));
    });
  }

  public static void insert(Session session, Options options, TableContents.Paginator paginator) { insert(session, options, paginator, "INDEX"); }

  static void insert(Session session, Options options, TableContents.Paginator paginator, String kind) {
    if (session.inCell()) throw new IllegalArgumentException("Reference tables belong in the document body");
    session.transact(s -> {
      int[] old = range(s.document(), kind); int at;
      if (old != null) { at = old[0]; remove(s, kind); }
      else {
        if (!s.selection().collapsed()) s.replace("");
        if (s.selection().caret > 0) s.replace("\n");
        at = s.selection().block;
      }
      var base = s.document(); long firstId = base.nextId;
      String previous = null;
      for (int pass = 0; pass < 8; pass++) {
        var entries = collect(base, paginator.layout(s.document()), kind);
        var generated = generate(entries, options, firstId,
            base.sectionAt(Math.min(at, base.blocks.size() - 1)).raw.page.contentWidth(), kind);
        var signature = new StringBuilder();
        for (Paragraph p : generated) signature.append(p.text.plainText()).append('\n');
        if (signature.toString().equals(previous)) { s.select(at, 0, 0); return; }
        previous = signature.toString();
        Sequence<Block> blocks = base.blocks;
        for (int i = 0; i < generated.size(); i++) blocks = blocks.insert(at + i, generated.get(i));
        s.replaceDocument(base.blocks(blocks, firstId + generated.size()));
      }
      throw new IllegalArgumentException("Reference page numbers did not stabilize; shorten the table or change page layout");
    });
  }

  private static final class Entry {
    final List<String> levels;
    final LinkedHashMap<String, TextStyle> pages = new LinkedHashMap<>();
    final Set<String> see = new LinkedHashSet<>();
    Entry(List<String> levels) { this.levels = new ArrayList<>(levels); }
  }

  private static SortedMap<String, Entry> collect(Document d, LayoutEngine.Result layout, String kind) {
    var entries = new TreeMap<String, Entry>();
    var lines = new HashMap<String, List<LayoutEngine.Line>>();
    for (LayoutEngine.Page page : layout.pages) for (LayoutEngine.Line line : page.lines) {
      var group = lines.get(line.paragraphId);
      if (group == null) lines.put(line.paragraphId, group = new ArrayList<LayoutEngine.Line>());
      group.add(line);
    }
    for (Block b : d.blocks) {
      if (b instanceof Paragraph paragraph) collectParagraph(d, paragraph, layout, lines, entries, kind);
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++) for (int c = 0; c < t.columns(); c++) {
          var span = t.span(r, c); if (span.row != r || span.column != c) continue;
          for (Paragraph p : t.cell(r, c)) collectParagraph(d, p, layout, lines, entries, kind);
        }
      }
    }
    return entries;
  }

  private static void collectParagraph(Document d, Paragraph p, LayoutEngine.Result layout,
      Map<String, List<LayoutEngine.Line>> lines, SortedMap<String, Entry> entries, String kind) {
    p.text.visit(new RichText.Visitor() {
      public void run(int offset, String text, TextStyle style) {}
      public void field(int offset, InlineField field, TextStyle style) {
        if (style.revision != null && !style.revision.inserted()) return;
        var code = new FieldCode(field.instruction);
        boolean authorities = "TOA".equals(kind);
        String label = authorities ? code.switches.get("l") : code.arguments.isEmpty() ? null : code.arguments.get(0);
        if (!(authorities ? "TA" : "XE").equals(code.name) || label == null) return;
        var levels = new ArrayList<String>(); var key = "";
        for (String part : authorities ? new String[]{label} : TextUtil.split(label, ':')) {
          if (part.trim().isEmpty()) continue;
          levels.add(part.trim()); key += part.trim().toLowerCase() + '\u0000';
          if (!entries.containsKey(key)) entries.put(key, new Entry(levels));
          if (levels.size() == 9) break;
        }
        if (levels.isEmpty()) return;
        var entry = entries.get(key);
        if (!authorities && code.switches.containsKey("t")) { entry.see.add(code.switches.get("t")); return; }
        int page = page(layout, lines.get(p.id), offset);
        var reference = "" + page;
        if (!authorities && code.switches.containsKey("r")) {
          var mark = d.bookmarks.get(code.switches.get("r"));
          if (mark == null) { reference = "Error! Bookmark not defined."; }
          else {
            int start = page(layout, lines.get(mark.startId), mark.start);
            int end = page(layout, lines.get(mark.endId), mark.end);
            reference = "" + start + (end > start ? "\u2013" + end : "");
          }
        }
        var previous = entry.pages.get(reference);
        boolean bold = !authorities && code.switches.containsKey("b") || previous != null && previous.bold;
        boolean italic = !authorities && code.switches.containsKey("i") || previous != null && previous.italic;
        entry.pages.put(reference, TextStyle.DEFAULT.with("bold", "" + bold).with("italic", "" + italic));
      }
    });
  }

  private static int page(LayoutEngine.Result layout, List<LayoutEngine.Line> lines, int offset) {
    if (lines == null || lines.isEmpty()) return 1;
    for (LayoutEngine.Line line : lines) if (offset >= line.start && offset < line.end)
      return layout.pages.get(line.page).number;
    return layout.pages.get(lines.get(lines.size() - 1).page).number;
  }

  private static RichText references(RichText text, Entry entry, String separator) {
    boolean first = true;
    for (Map.Entry<String, TextStyle> page : entry.pages.entrySet()) {
      text = text.concat(RichText.of(first ? separator : ", ")).concat(RichText.of(page.getKey(), page.getValue())); first = false;
    }
    for (String see : entry.see) {
      text = text.concat(RichText.of(first ? ". " : "; ")).concat(RichText.of(see, TextStyle.DEFAULT.with("italic", "true"))); first = false;
    }
    return text;
  }

  private static List<Paragraph> generate(SortedMap<String, Entry> entries, Options options, long id, float width, String kind) {
    boolean authorities = "TOA".equals(kind);
    var out = new ArrayList<Paragraph>();
    out.add(new Paragraph("p" + id++, RichText.of(options.title, TextStyle.DEFAULT.with("bold", "true").with("size", "18"))
        .concat(RichText.field(new InlineField(options.instruction(kind), "", false, false), TextStyle.DEFAULT)))
        .with("style", authorities ? "TOAHeading" : "IndexHeading").with("keepNext", "true"));
    String letter = null;
    var values = new ArrayList<Entry>(entries.values());
    for (int i = 0; i < values.size(); i++) {
      var entry = values.get(i); var main = entry.levels.get(0);
      String group = Unicode.letter(main.charAt(0)) ? main.substring(0, 1).toUpperCase() : "#";
      if (options.headings && !group.equals(letter)) {
        out.add(new Paragraph("p" + id++, RichText.of(group, TextStyle.DEFAULT.with("bold", "true")))
            .with("style", "IndexLetter").with("keepNext", "true")); letter = group;
      }
      int level = entry.levels.size() - 1;
      RichText text = references(RichText.of(entry.levels.get(level)), entry, options.rightAlign ? "\t" : ", ");
      if (options.runIn) {
        boolean first = true;
        while (i + 1 < values.size() && values.get(i + 1).levels.size() > 1
            && main.equalsIgnoreCase(values.get(i + 1).levels.get(0))) {
          var sub = values.get(++i);
          var label = "";
          for (int l = 1; l < sub.levels.size(); l++) label += (l == 1 ? "" : ", ") + sub.levels.get(l);
          text = text.concat(RichText.of((first ? ": " : "; ") + label)); first = false;
          text = references(text, sub, ", ");
        }
      }
      float indent = authorities ? 0 : 18 * (level + 1);
      var p = new Paragraph("p" + id++, text).with("style", authorities ? "TableofAuthorities" : "Index" + (level + 1))
          .with("indent", "" + indent).with("firstIndent", authorities ? "0" : "-18").with("after", "0");
      if (options.rightAlign) p = p.with("tabs", TabStop.encode(Collections.singletonList(
          new TabStop(Math.max(18, width - indent), "right", options.leader))));
      out.add(p);
    }
    return out;
  }
}
