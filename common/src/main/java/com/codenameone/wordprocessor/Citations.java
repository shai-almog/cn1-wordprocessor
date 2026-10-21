package com.codenameone.wordprocessor;

import java.util.*;

/** Citation fields and an editable generated bibliography sharing document transactions. */
public final class Citations {
  private Citations() {}
  public static int[] range(Document d) { return DocumentIndex.range(d, "BIBLIOGRAPHY"); }
  public static boolean entryStyle(String style) { return "Bibliography".equals(style) || "ListEntry".equals(style); }
  private static String quote(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
  private static boolean bare(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || c == '\\' || c == '"') return false;
    }
    return true;
  }
  private static void collect(Paragraph p, Set<String> tags) {
    p.text.visit(new RichText.Visitor() {
      public void run(int offset, String text, TextStyle style) {}
      public void field(int offset, InlineField f, TextStyle style) {
        if (style.revision != null && !style.revision.inserted()) return;
        var code = new FieldCode(f.instruction);
        if ("CITATION".equals(code.name) && !code.arguments.isEmpty()) tags.add(code.arguments.get(0));
      }
    });
  }
  public static List<CitationSource> ordered(Document d) {
    var tags = new LinkedHashSet<String>();
    for (Block block : d.blocks) {
      if (block instanceof Paragraph paragraph) collect(paragraph, tags);
      else if (block instanceof Table table) {
        for (int r = 0; r < table.rows(); r++) for (int c = 0; c < table.columns(); c++)
          if (!table.covered(r, c)) for (Paragraph p : table.cell(r, c)) collect(p, tags);
      }
    }
    var sources = new ArrayList<CitationSource>();
    if (tags.isEmpty()) sources.addAll(d.bibliography.sources.values());
    else for (String tag : tags) if (d.bibliography.sources.containsKey(tag)) sources.add(d.bibliography.sources.get(tag));
    int style = CitationStyles.index(d.bibliography.style);
    if (!CitationStyles.citedOrder(style)) Collections.sort(sources, (a, b) -> key(a, style).compareTo(key(b, style)));
    return sources;
  }
  private static String key(CitationSource source, int style) {
    var authors = CitationSource.names(source.get("author"));
    return (style == 4 || authors.isEmpty() ? source.get("title") : authors.get(0)[0]).toLowerCase();
  }
  public static void refresh(Session session) {
    var d = session.document(); int style = CitationStyles.index(d.bibliography.style);
    var ordered = ordered(d); var numbers = new HashMap<String, Integer>();
    for (int i = 0; i < ordered.size(); i++) numbers.put(ordered.get(i).tag(), i + 1);
    session.mapFields((paragraph, offset, field) -> {
      var code = new FieldCode(field.instruction);
      if (field.locked || !"CITATION".equals(code.name) || code.arguments.isEmpty()) return field;
      // Preserve fields with unsupported composite/source-suppression switches and their cached result.
      for (String key : code.switches.keySet()) if (!Arrays.asList("p", "l", "*").contains(key)) return field;
      var source = d.bibliography.sources.get(code.arguments.get(0));
      if (source == null) return field.result("Error! Source not found: " + code.arguments.get(0));
      String pages = code.switches.containsKey("p") ? code.switches.get("p") : "";
      return field.result(CitationStyles.citation(source, style, numbers.containsKey(source.tag()) ? numbers.get(source.tag()) : ordered.size() + 1, pages));
    });
  }
  public static void source(Session session, CitationSource value) {
    session.transact(s -> { s.bibliography(s.document().bibliography.put(value)); update(s); });
  }
  public static void removeSource(Session session, String tag) {
    if (!session.document().bibliography.sources.containsKey(tag)) throw new IllegalArgumentException("Unknown source: " + tag);
    session.transact(s -> { s.bibliography(s.document().bibliography.remove(tag)); update(s); });
  }
  public static void style(Session session, String style) {
    var canonical = CitationStyles.NAMES[CitationStyles.index(style)];
    session.transact(s -> { s.bibliography(s.document().bibliography.style(canonical)); update(s); });
  }
  public static String insert(Session session, String tag, CitationSource source, String pages) {
    if (pages == null || pages.length() > 200 || pages.indexOf('\n') >= 0 || pages.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Invalid citation page range");
    String selected = tag == null || tag.isEmpty() ? source == null ? "" : source.tag() : tag;
    session.transact(s -> {
      if (source != null) s.bibliography(s.document().bibliography.put(source));
      if (!s.document().bibliography.sources.containsKey(selected)) throw new IllegalArgumentException("Unknown source: " + selected);
      s.insertField(new InlineField("CITATION " + selected + (pages.isEmpty() ? "" : " \\p " + (bare(pages) ? pages : quote(pages))), "", false, false));
      update(s);
    });
    return selected;
  }
  public static void remove(Session session) { DocumentIndex.remove(session, "BIBLIOGRAPHY"); }
  public static void update(Session session) {
    session.transact(s -> {
      refresh(s);
      int[] range = range(s.document());
      if (range == null || DocumentIndex.marker(s.document().paragraph(range[0]), "BIBLIOGRAPHY").locked) return;
      var selected = s.selection();
      String anchor = s.editingDocument().blocks.get(selected.anchorBlock).id(), focus = s.editingDocument().blocks.get(selected.block).id();
      // The outermost table is found again by id (the bibliography may shift body blocks).
      String table = s.inCell() ? s.document().blocks.get(s.rootCellBlock()).id() : null;
      int[] path = s.cellPath();
      var title = s.document().paragraph(range[0]).text.plainText();
      s.leaveCell(); bibliography(s, title);
      if (table != null) {
        path[0] = ReviewComment.block(s.document(), table);
        s.enterCellPath(path);
      }
      int a = ReviewComment.block(s.editingDocument(), anchor), f = ReviewComment.block(s.editingDocument(), focus);
      if (a >= 0 && f >= 0 && s.editingDocument().blocks.get(a) instanceof Paragraph && s.editingDocument().blocks.get(f) instanceof Paragraph)
        s.selectRange(a, Math.min(selected.anchor, s.editingDocument().paragraph(a).text.length()), f, Math.min(selected.caret, s.editingDocument().paragraph(f).text.length()));
    });
  }
  public static void bibliography(Session session, String title) {
    if (session.inCell()) throw new IllegalArgumentException("Bibliographies belong in the document body");
    int style = CitationStyles.index(session.document().bibliography.style);
    String heading = title == null ? CitationStyles.title(style) : title;
    if (heading.length() > 1000 || heading.indexOf('\n') >= 0 || heading.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Invalid bibliography title");
    session.transact(s -> {
      refresh(s); int[] old = range(s.document()); int at;
      if (old != null) {
        InlineField marker = DocumentIndex.marker(s.document().paragraph(old[0]), "BIBLIOGRAPHY");
        if (marker.locked) throw new IllegalArgumentException("Unlock the bibliography before changing it");
        if (!new FieldCode(marker.instruction).switches.isEmpty()) throw new IllegalArgumentException("Filtered bibliography fields cannot be regenerated yet");
        at = old[0]; remove(s);
      } else {
        if (!s.selection().collapsed()) s.replace("");
        if (s.selection().caret > 0) s.replace("\n"); at = s.selection().block;
      }
      var d = s.document(); long next = d.nextId; Sequence<Block> blocks = d.blocks;
      var header = new Paragraph("p" + next++, RichText.of(heading, TextStyle.DEFAULT.with("bold", "true").with("size", "18"))
          .concat(RichText.field(new InlineField("BIBLIOGRAPHY", "", false, false), TextStyle.DEFAULT)))
          .with("style", "BibliographyHeading").with("keepNext", "true");
      blocks = blocks.insert(at, header); int n = 0;
      for (CitationSource source : ordered(d)) {
        var p = new Paragraph("p" + next++, RichText.of(CitationStyles.entry(source, style, ++n))).with("style", "Bibliography");
        if (!CitationStyles.numbered(style)) p = p.with("indent", "36").with("firstIndent", "-36");
        blocks = blocks.insert(at + n, p);
      }
      s.replaceDocument(d.blocks(blocks, next)); s.select(at, 0, 0);
    });
  }
}
