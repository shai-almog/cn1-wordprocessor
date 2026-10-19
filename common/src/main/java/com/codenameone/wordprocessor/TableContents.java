package com.codenameone.wordprocessor;

import java.util.*;

/** A generated contents story, stored as ordinary paragraphs and portable Word fields. */
public final class TableContents {
  private TableContents() {}

  public interface Paginator {
    LayoutEngine.Result layout(Document document);
  }

  public static final class Options {
    public final int levels;
    public final String title, leader, caption, tcIdentifier;
    public final boolean pageNumbers, rightAlign, hyperlinks, headingStyles, outlineLevels, tcFields;
    public final Map<String, Integer> styleLevels;

    public Options(int levels, String title, boolean pages, boolean right, String leader, boolean links) {
      this(levels, title, pages, right, leader, links, null);
    }

    public Options(int levels, String title, boolean pages, boolean right, String leader, boolean links, String caption) {
      this(levels, title, pages, right, leader, links, caption, true, true, false,
          Collections.<String, Integer>emptyMap(), "");
    }

    public Options(int levels, String title, boolean pages, boolean right, String leader, boolean links,
        String caption, boolean headings, boolean outlines, boolean marked,
        Map<String, Integer> styles, String identifier) {
      if (caption != null && (caption.trim().isEmpty() || caption.length() > 40
          || caption.indexOf('"') >= 0 || caption.indexOf('\\') >= 0
          || caption.indexOf('\n') >= 0 || caption.indexOf('\r') >= 0))
        throw new IllegalArgumentException("Invalid caption label");
      if (levels < 1 || levels > 9 || title == null || title.length() > 1000
          || title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0
          || !Arrays.asList("none", "dot", "hyphen", "underscore", "middleDot").contains(leader))
        throw new IllegalArgumentException("Invalid table of contents options");
      if (styles == null || styles.size() > 64 || identifier == null || identifier.length() > 20
          || identifier.indexOf('"') >= 0 || identifier.indexOf('\\') >= 0
          || identifier.indexOf('\n') >= 0 || identifier.indexOf('\r') >= 0)
        throw new IllegalArgumentException("Invalid contents sources");
      var mapped = new LinkedHashMap<String, Integer>();
      for (Map.Entry<String, Integer> entry : styles.entrySet()) {
        var name = entry.getKey(); var level = entry.getValue();
        if (name == null || name.trim().isEmpty() || name.length() > 128 || level == null
            || level < 1 || level > 9 || name.indexOf(',') >= 0 || name.indexOf(';') >= 0
            || name.indexOf('"') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0)
          throw new IllegalArgumentException("Invalid contents style mapping");
        mapped.put(name, level);
      }
      this.caption = caption; this.levels = levels; this.title = title;
      pageNumbers = pages; rightAlign = right; this.leader = leader; hyperlinks = links;
      headingStyles = headings; outlineLevels = outlines; tcFields = marked;
      styleLevels = Collections.unmodifiableMap(mapped); tcIdentifier = identifier;
    }

    String instruction() {
      var out = new StringBuilder("TOC");
      if (caption != null) out.append(" \\c \"").append(caption).append('"');
      else {
        if (headingStyles) out.append(" \\o \"1-").append(levels).append('"');
        if (outlineLevels) out.append(" \\u");
        if (tcFields) out.append(" \\f ").append(tcIdentifier.isEmpty() ? "" : "\"" + tcIdentifier + "\"")
            .append(" \\l \"1-").append(levels).append('"');
        if (!styleLevels.isEmpty()) {
          out.append(" \\t \""); boolean first = true;
          for (Map.Entry<String, Integer> entry : styleLevels.entrySet()) {
            if (!first) out.append(','); first = false;
            out.append(entry.getKey()).append(',').append(entry.getValue());
          }
          out.append('"');
        }
      }
      if (hyperlinks) out.append(" \\h");
      if (!pageNumbers) out.append(" \\n");
      if (!rightAlign) out.append(" \\p \" \"");
      return out.toString();
    }
  }

  private static InlineField marker(Paragraph paragraph) {
    if (!paragraph.text.hasFields()) return null;
    for (InlineField f : paragraph.text.fields())
      if ("TOC".equals(new FieldCode(f.instruction).name)) return f;
    return null;
  }

  /** Exclusive end. Each generated story owns only its consecutive entry paragraphs. */
  public static List<int[]> ranges(Document document) {
    var ranges = new ArrayList<int[]>();
    for (int i = 0; i < document.blocks.size(); i++) {
      var b = document.blocks.get(i);
      InlineField field = b instanceof Paragraph p ? marker(p) : null;
      if (field == null) continue;
      boolean figures = new FieldCode(field.instruction).switches.containsKey("c");
      int end = i + 1;
      while (end < document.blocks.size() && document.blocks.get(end) instanceof Paragraph) {
        var p = document.paragraph(end);
        if (marker(p) != null || !(figures ? "ListEntry".equals(p.style)
            || "TableofFigures".equals(p.style) : entryLevel(p.style) > 0)) break;
        end++;
      }
      ranges.add(new int[]{i,end});
      i = end - 1;
    }
    return ranges;
  }

  public static int[] range(Document document) { return range(document, null); }

  public static int[] range(Document document, String caption) {
    for (int[] range : ranges(document)) {
      var kind = new FieldCode(marker(document.paragraph(range[0])).instruction).switches.get("c");
      if (caption == null ? kind == null : caption.equals(kind)) return range;
    }
    return null;
  }

  private static int entryLevel(String style) {
    if (style.startsWith("TOC") && style.length() == 4 && style.charAt(3) >= '1' && style.charAt(3) <= '9')
      return style.charAt(3) - '0';
    return 0;
  }

  public static Options options(Document document) {
    return options(document, null);
  }

  public static Options options(Document document, String caption) {
    int[] r = range(document, caption);
    if (r == null) return new Options(3, caption == null ? "Contents" : "", true, true, "dot", true, caption);
    var header = document.paragraph(r[0]);
    var code = new FieldCode(marker(header).instruction);
    int levels = 3;
    String outline = code.switches.containsKey("o") ? code.switches.get("o") : code.switches.get("l");
    if (outline != null && outline.length() == 3 && outline.charAt(0) == '1'
        && outline.charAt(1) == '-' && outline.charAt(2) >= '1' && outline.charAt(2) <= '9') levels = outline.charAt(2) - '0';
    var leader = "dot";
    if (r[1] > r[0] + 1) {
      List<TabStop> tabs = document.paragraph(r[0] + 1).format.tabs;
      if (!tabs.isEmpty()) leader = tabs.get(tabs.size() - 1).leader;
    }
    var styles = new LinkedHashMap<String, Integer>();
    var mapping = code.switches.get("t");
    if (mapping != null) {
      String[] parts = TextUtil.split(mapping.replace(';', ','), ',');
      if (parts.length % 2 != 0) throw new IllegalArgumentException("Invalid TOC style mapping");
      for (int i = 0; i < parts.length; i += 2) styles.put(parts[i].trim(), Integer.parseInt(parts[i + 1].trim()));
    }
    return new Options(levels, header.text.plainText(), !code.switches.containsKey("n"),
        !code.switches.containsKey("p"), leader, code.switches.containsKey("h"), caption,
        code.switches.containsKey("o"), code.switches.containsKey("u"), code.switches.containsKey("f"),
        styles, code.switches.containsKey("f") ? code.switches.get("f") : "");
  }

  /** Mark an entry after the selected source text without deleting that source text. */
  public static void markEntry(Session session, String text, int level, String identifier) {
    if (session.inCell()) throw new IllegalArgumentException("Contents entries belong in body paragraphs");
    if (text == null || text.trim().isEmpty() || text.length() > 2000 || level < 1 || level > 9
        || identifier == null || identifier.length() > 20 || text.indexOf('\n') >= 0
        || text.indexOf('\r') >= 0 || identifier.indexOf('\n') >= 0 || identifier.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Invalid contents entry");
    var instruction = "TC \"" + quoted(text) + "\" \\l " + level
        + (identifier.isEmpty() ? "" : " \\f \"" + quoted(identifier) + "\"");
    session.transact(s -> {
      var selected = s.selection();
      s.select(selected.endBlock(), selected.end(), selected.end());
      s.insertField(new InlineField(instruction, "", false, false));
    });
  }

  private static String quoted(String text) { return text.replace("\\", "\\\\").replace("\"", "\\\""); }

  public static void remove(Session session) { remove(session, null); }

  public static void remove(Session session, String caption) {
    if (session.inCell()) throw new IllegalArgumentException("A table of contents belongs in the main text");
    int[] r = range(session.document(), caption);
    if (r == null) return;
    session.transact(s -> {
      var d = s.document();
      Sequence<Block> blocks = d.blocks;
      for (int i = r[1] - 1; i >= r[0]; i--) blocks = blocks.remove(i);
      long next = d.nextId;
      if (blocks.size() == 0) blocks = blocks.append(new Paragraph("p" + next++, new RichText()));
      s.replaceDocument(d.blocks(blocks, next));
      int at = Math.min(r[0], blocks.size() - 1);
      if (blocks.get(at) instanceof Paragraph) s.select(at, 0, 0);
    });
  }

  public static void update(Session session, Paginator paginator) { update(session, paginator, null); }

  public static void updateFigures(Session session, Paginator paginator) {
    var labels = new LinkedHashSet<String>();
    for (int[] range : ranges(session.document())) {
      var label = new FieldCode(marker(session.document().paragraph(range[0])).instruction).switches.get("c");
      if (label != null) labels.add(label);
    }
    session.transact(s -> { for (String label : labels) update(s, paginator, label); });
  }

  public static void update(Session session, Paginator paginator, String caption) {
    int[] range = range(session.document(), caption);
    if (range == null || marker(session.document().paragraph(range[0])).locked) return;
    var selection = session.selection();
    String tableId = session.inCell() ? session.document().blocks.get(session.cellBlock()).id() : null;
    int row = session.cellRow(), column = session.cellColumn();
    var anchorId = session.editingDocument().blocks.get(selection.anchorBlock).id();
    var focusId = session.editingDocument().blocks.get(selection.block).id();
    session.transact(s -> {
      s.leaveCell();
      insert(s, options(s.document(), caption), paginator);
      if (tableId != null) s.enterCell(ReviewComment.block(s.document(), tableId), row, column);
      var editing = s.editingDocument();
      int anchor = ReviewComment.block(editing, anchorId), focus = ReviewComment.block(editing, focusId);
      if (anchor >= 0 && focus >= 0 && editing.blocks.get(anchor) instanceof Paragraph
          && editing.blocks.get(focus) instanceof Paragraph)
        s.selectRange(anchor, Math.min(selection.anchor, editing.paragraph(anchor).text.length()),
            focus, Math.min(selection.caret, editing.paragraph(focus).text.length()));
    });
  }

  public static void insert(Session session, Options options, Paginator paginator) {
    if (session.inCell()) throw new IllegalArgumentException("A table of contents belongs in the main text");
    session.transact(s -> {
      int[] old = range(s.document(), options.caption);
      int at;
      if (old != null) {
        at = old[0];
        remove(s, options.caption);
      } else {
        // Normal insertion splits at the caret and uses the session's anchor mapping.
        if (!s.selection().collapsed()) s.replace("");
        at = s.selection().block;
        if (s.selection().caret > 0) {
          s.replace("\n");
          at = s.selection().block;
        }
      }
      s.mapFields(SequenceFields.resolver(s.document()));
      var d = s.document();
      var headings = new ArrayList<Paragraph>();
      var levels = new ArrayList<Integer>();
      var sourceOffsets = new ArrayList<Integer>();
      var anchors = new ArrayList<String>();
      var bookmarks = new LinkedHashMap<String, Bookmark>(d.bookmarks);
      var headingBookmarks = new HashMap<String, Bookmark>();
      for (Bookmark mark : bookmarks.values())
        if (mark.name.startsWith("_Toc") && mark.startId.equals(mark.endId))
          headingBookmarks.put(mark.startId + ":" + mark.start + ":" + mark.end, mark);
      for (Block block : d.blocks) if (block instanceof Paragraph p) {
        if (p.style.startsWith("TOC") || marker(p) != null) continue;
        int level = options.caption == null ? sourceLevel(d, p, options)
            : captionMatches(p, options.caption) ? 1 : 0;
        if (level > 0 && !sourceLabel(p, -1).isEmpty()) {
          headings.add(p); levels.add(level); sourceOffsets.add(-1);
        }
        if (options.caption == null && options.tcFields) {
          p.text.visit(new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {}
            public void field(int offset, InlineField field, TextStyle style) {
              var code = new FieldCode(field.instruction);
              if (!"TC".equals(code.name) || code.arguments.isEmpty() || code.arguments.get(0).trim().isEmpty()) return;
              String category = code.switches.containsKey("f") ? code.switches.get("f") : "C";
              if (!options.tcIdentifier.isEmpty() && !options.tcIdentifier.equalsIgnoreCase(category)) return;
              int level = 1;
              try { if (code.switches.containsKey("l")) level = Integer.parseInt(code.switches.get("l").trim()); }
              catch (NumberFormatException invalid) { /* Word treats an invalid TC level as one. */ }
              level = Math.max(1, Math.min(9, level));
              if (level < 1 || level > options.levels) return;
              headings.add(p); levels.add(level); sourceOffsets.add(offset);
            }
          });
        }
      }
      int nextBookmark = 1;
      for (int i = 0; i < headings.size(); i++) {
        var p = headings.get(i); int offset = sourceOffsets.get(i);
        int start = offset < 0 ? 0 : offset, end = offset < 0 ? p.text.length() : offset + 1;
        var existing = headingBookmarks.get(p.id + ":" + start + ":" + end);
        String name = existing == null ? null : existing.name;
        if (name == null) {
          do { name = "_Toc" + nextBookmark++; } while (bookmarks.containsKey(name));
          bookmarks.put(name, new Bookmark(name, p.id, start, p.id, end));
        }
        anchors.add(name);
      }
      d = s.document();
      long next = d.nextId;
      var headerId = "p" + next++;
      var title = RichText.of(options.title, TextStyle.DEFAULT.with("size", options.title.isEmpty() ? "12" : "18").with("bold", "true"))
          .concat(RichText.field(new InlineField(options.instruction(), "", false, false), TextStyle.DEFAULT));
      var generated = new ArrayList<Paragraph>();
      generated.add(new Paragraph(headerId, title).with("style", "TOCHeading").with("keepNext", "true"));
      float width = d.sectionAt(Math.min(at, d.blocks.size() - 1)).raw.page.contentWidth();
      for (int i = 0; i < headings.size(); i++) {
        var textStyle = TextStyle.DEFAULT.with("link", options.hyperlinks ? "#" + anchors.get(i) : "");
        RichText text = RichText.of(sourceLabel(headings.get(i), sourceOffsets.get(i)), textStyle);
        if (options.pageNumbers) text = text.concat(RichText.of(options.rightAlign ? "\t" : " ", textStyle))
            .concat(RichText.field(new InlineField("PAGEREF " + anchors.get(i)
                + (options.hyperlinks ? " \\h" : ""), "1", false, false), textStyle));
        float indent = (levels.get(i) - 1) * 14;
        var entry = new Paragraph("p" + next++, text).with("style", options.caption == null ? "TOC" + levels.get(i) : "ListEntry")
            .with("indent", "" + indent).with("after", "3");
        if (options.pageNumbers && options.rightAlign)
          entry = entry.with("tabs", TabStop.encode(Collections.singletonList(
              new TabStop(Math.max(1, width - indent), "right", options.leader))));
        generated.add(entry);
      }
      for (Paragraph p : generated) if (!s.document().styles.containsKey(p.style))
        s.defineStyle(new ParagraphStyle(p.style,
            "TOCHeading".equals(p.style) ? "Contents heading" : "ListEntry".equals(p.style) ? "List Entry" : "Contents " + entryLevel(p.style),
            p, p.text.styleAt(0)));
      d = s.document().bookmarks(bookmarks);
      Sequence<Block> blocks = d.blocks;
      for (int i = 0; i < generated.size(); i++) blocks = blocks.insert(at + i, generated.get(i));
      s.replaceDocument(d.blocks(blocks, next));
      var sourcePositions = new HashMap<String, Integer>();
      for (int i = 0; i < blocks.size(); i++) sourcePositions.put(blocks.get(i).id(), i);
      // Page references must include the space consumed by the contents itself.
      for (int pass = 0; pass < 6; pass++) {
        var before = s.document();
        s.mapFields(DocumentFields.resolver(before, paginator.layout(before), Collections.<String, String>emptyMap()));
        // Headings can contain REF/PAGE fields. Refresh their copied labels after resolution,
        // then paginate again because the updated label can change the contents story's height.
        var resolved = s.document();
        Sequence<Block> refreshed = resolved.blocks;
        for (int i = 0; i < headings.size(); i++) {
          int source = sourcePositions.get(headings.get(i).id);
          String label = sourceLabel(resolved.paragraph(source), sourceOffsets.get(i));
          var entry = resolved.paragraph(at + 1 + i);
          int labelEnd = entry.text.length() - (options.pageNumbers ? 2 : 0);
          if (!label.equals(entry.text.slice(0, labelEnd).plainText())) {
            var text = RichText.of(label, entry.text.styleAt(0))
                .concat(entry.text.slice(labelEnd, entry.text.length()));
            refreshed = refreshed.set(at + 1 + i, entry.text(text));
          }
        }
        if (refreshed != resolved.blocks) s.replaceDocument(resolved.blocks(refreshed, resolved.nextId));
        if (before == s.document()) break;
      }
      s.select(at, 0, 0);
    });
  }
  private static int sourceLevel(Document document, Paragraph p, Options options) {
    var style = document.styles.get(p.style);
    for (Map.Entry<String, Integer> entry : options.styleLevels.entrySet())
      if (entry.getKey().equals(p.style) || style != null && entry.getKey().equalsIgnoreCase(style.name)) return entry.getValue();
    int direct = p.format.outlineLevel;
    if (options.outlineLevels && direct > 0 && direct <= (options.headingStyles ? options.levels : 9)) return direct;
    if (options.outlineLevels && direct == 0) return 0;
    int heading = SequenceFields.headingLevel(p.style);
    if (heading == 0 && style != null) heading = style.paragraph.format.outlineLevel;
    return options.headingStyles && heading > 0 && heading <= options.levels ? heading : 0;
  }

  private static String sourceLabel(Paragraph paragraph, int offset) {
    if (offset >= 0) {
      var field = paragraph.text.fieldAt(offset);
      if (field == null) return "";
      var code = new FieldCode(field.instruction);
      return code.arguments.isEmpty() ? "" : code.arguments.get(0).trim();
    }
    return paragraph.text.plainText().replace('\t', ' ').replace('\n', ' ').replace('\f', ' ').trim();
  }

  private static boolean captionMatches(Paragraph p, String label) {
    if (!"Caption".equals(p.style)) return false;
    for (int i = 0; i < p.text.length(); i++) {
      var field = p.text.fieldAt(i);
      if (field == null) continue;
      var code = new FieldCode(field.instruction);
      if ("SEQ".equals(code.name) && !code.arguments.isEmpty() && label.equals(code.arguments.get(0))) return true;
    }
    return p.text.plainText().startsWith(label + " ");
  }
}
