package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Multi-level bullet/decimal/letter/Roman numbering and per-level restart overrides. */
final class DocxLists {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  static final class Definition {
    String kind = "number", format = "decimal";
    int start = 1;

    Definition copy() {
      Definition d = new Definition();
      d.kind = kind;
      d.format = format;
      d.start = start;
      return d;
    }
  }

  static final class Read {
    final Map<String, Definition[]> definitions = new HashMap<String, Definition[]>();
    final Map<String, int[]> counters = new HashMap<String, int[]>();
    String previous = "";

    Definition take(String id, int level) {
      Definition[] ds = definitions.get(id);
      if (ds == null || level < 0 || level > 8 || ds[level] == null) return null;
      int[] counts = counters.get(id);
      if (counts == null) {
        counts = new int[9];
        counters.put(id, counts);
      }
      Definition d = ds[level].copy();
      counts[level] = counts[level] == 0 ? d.start : counts[level] + 1;
      d.start = counts[level];
      for (int i = level + 1; i < 9; i++) counts[i] = 0;
      // Emit an explicit restart only at the beginning of a sequence or a non-default restart.
      if (previous.equals(id) && (counts[level] != ds[level].start || ds[level].start == 1))
        d.start = 0;
      previous = id;
      return d;
    }

    void body() {
      previous = "";
    }
  }

  static Read read(byte[] bytes, Set<String> warnings) throws IOException {
    Read result = new Read();
    if (bytes == null) return result;
    Element root = Xml.parse(bytes);
    Map<String, Definition[]> abstracts = new HashMap<String, Definition[]>();
    for (Element a : Xml.children(root, "abstractNum")) {
      Definition[] ds = new Definition[9];
      for (Element l : Xml.children(a, "lvl")) {
        int level = Integer.parseInt(Xml.attr(l, "ilvl", "0"));
        if (level >= 0 && level < 9) ds[level] = definition(l, level, warnings);
      }
      abstracts.put(Xml.attr(a, "abstractNumId", ""), ds);
    }
    for (Element n : Xml.children(root, "num")) {
      Element a = Xml.child(n, "abstractNumId");
      if (a == null) continue;
      Definition[] base = abstracts.get(Xml.attr(a, "val", ""));
      if (base == null) continue;
      Definition[] ds = new Definition[9];
      for (int i = 0; i < 9; i++) if (base[i] != null) ds[i] = base[i].copy();
      for (Element over : Xml.children(n, "lvlOverride")) {
        int level = Integer.parseInt(Xml.attr(over, "ilvl", "0"));
        if (level < 0 || level > 8) continue;
        Element lvl = Xml.child(over, "lvl"), start = Xml.child(over, "startOverride");
        if (lvl != null) ds[level] = definition(lvl, level, warnings);
        if (start != null && ds[level] != null)
          ds[level].start = Math.max(1, Integer.parseInt(Xml.attr(start, "val", "1")));
      }
      result.definitions.put(Xml.attr(n, "numId", ""), ds);
    }
    return result;
  }

  private static Definition definition(Element l, int level, Set<String> warnings) {
    Definition d = new Definition();
    Element fmt = Xml.child(l, "numFmt"),
        start = Xml.child(l, "start"),
        label = Xml.child(l, "lvlText");
    String format = fmt == null ? "decimal" : Xml.attr(fmt, "val", "decimal");
    if ("bullet".equals(format)) d.kind = "bullet";
    else if (Arrays.asList("decimal", "lowerLetter", "upperLetter", "lowerRoman", "upperRoman")
        .contains(format)) d.format = format;
    else warnings.add("Numbering format: " + format);
    if (start != null) d.start = Math.max(1, Integer.parseInt(Xml.attr(start, "val", "1")));
    String text = label == null ? null : Xml.attr(label, "val", "");
    boolean bullet = "bullet".equals(d.kind);
    if (text != null
        && !text.equals(bullet ? ListNumbers.BULLETS[level % 3] : "%" + (level + 1) + ".")) {
      String custom = (bullet ? "bullet" : d.format) + "|" + text;
      if (!text.isEmpty() && ListNumbers.valid(custom)) d.format = custom;
      else warnings.add("Custom list label is approximated");
    }
    return d;
  }

  private static final class Group {
    final Paragraph[] levels = new Paragraph[9];
    final int[] starts = new int[9];

    Group(int[] counts, Paragraph p) {
      for (int i = 0; i < 9; i++) starts[i] = i <= p.listLevel ? counts[i] + 1 : 1;
      if (p.listStart > 0) starts[p.listLevel] = p.listStart;
    }
  }

  final Map<Paragraph, Integer> ids = new IdentityHashMap<Paragraph, Integer>();
  private final List<Paragraph> historical = new ArrayList<Paragraph>();
  final List<Group> groups = new ArrayList<Group>();
  private final int[] counts = new int[9];
  private Group current;
  private String previous = "";

  DocxLists(Document d) {
    for (Block b : d.blocks) {
      if (b instanceof Paragraph) add((Paragraph) b);
      else if (b instanceof Table) {
        Table t = (Table) b;
        reset();
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++) {
            reset();
            for (Paragraph p : t.cell(r, c)) add(p);
          }
        reset();
      }
    }
    for (Section.Range section : d.sectionRanges())
      for (String key : PageFurniture.KEYS) {
        reset();
        for (Paragraph para : section.raw.furniture.get(key)) add(para);
      }
    for (Note note : Notes.all(d)) {
      reset();
      for (Paragraph p : note.paragraphs) add(p);
    }
    for (int i = 0; i < historical.size(); i++) {
      reset();
      add(historical.get(i));
    }
  }

  private void reset() {
    previous = "";
    current = null;
    Arrays.fill(counts, 0);
  }

  private void add(Paragraph p) {
    if (p.previousFormat != null) historical.add(p.previousFormat);
    if (p.list.isEmpty()) {
      reset();
      return;
    }
    if (!p.list.equals(previous)) reset();
    Paragraph prior = current == null ? null : current.levels[p.listLevel];
    if (current == null
        || p.listStart > 0
        || prior != null && !prior.listFormat.equals(p.listFormat)) {
      current = new Group(counts, p);
      groups.add(current);
    }
    current.levels[p.listLevel] = p;
    counts[p.listLevel] = p.listStart > 0 ? p.listStart : counts[p.listLevel] + 1;
    for (int i = p.listLevel + 1; i < 9; i++) counts[i] = 0;
    ids.put(p, groups.size());
    previous = p.list;
  }

  String xml() {
    StringBuilder b = new StringBuilder("<w:numbering xmlns:w=\"").append(W).append("\">");
    for (int i = 0; i < groups.size(); i++) {
      int id = i + 1;
      Group g = groups.get(i);
      b.append("<w:abstractNum w:abstractNumId=\"")
          .append(id)
          .append("\"><w:multiLevelType w:val=\"multilevel\"/>");
      Paragraph sample = null;
      for (Paragraph p : g.levels) if (p != null && sample == null) sample = p;
      for (int level = 0; level < 9; level++) {
        Paragraph p = g.levels[level];
        // Unused levels follow the group's multilevel preset (outline/legal) when there is one.
        String fmt =
            p != null ? p.listFormat : sample != null ? ListNumbers.base(sample.listFormat) : "decimal";
        boolean bullet = p != null && "bullet".equals(p.list);
        b.append("<w:lvl w:ilvl=\"")
            .append(level)
            .append("\"><w:start w:val=\"")
            .append(g.starts[level])
            .append("\"/><w:numFmt w:val=\"")
            .append(bullet ? "bullet" : ListNumbers.levelFormat(fmt, level))
            .append("\"/><w:lvlText w:val=\"")
            .append(
                Xml.escape(
                    bullet
                        ? (ListNumbers.template(fmt) != null ? ListNumbers.template(fmt) : ListNumbers.BULLETS[level % 3])
                        : ListNumbers.levelText(fmt, level)))
            .append("\"/><w:lvlJc w:val=\"left\"/></w:lvl>");
      }
      b.append("</w:abstractNum><w:num w:numId=\"")
          .append(id)
          .append("\"><w:abstractNumId w:val=\"")
          .append(id)
          .append("\"/></w:num>");
    }
    return b.append("</w:numbering>").toString();
  }

  void packageParts(Map<String, byte[]> parts) throws IOException {
    if (groups.isEmpty()) return;
    parts.put("word/numbering.xml", xml().getBytes("UTF-8"));
    String path = "word/_rels/document.xml.rels";
    String rels =
        parts.containsKey(path)
            ? new String(parts.get(path), "UTF-8")
            : "<Relationships"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>";
    boolean found = false;
    Set<String> ids = new HashSet<String>();
    for (Element rel : Xml.children(Xml.parse(rels), null)) {
      ids.add(Xml.attr(rel, "Id", ""));
      if ("numbering.xml".equals(Xml.attr(rel, "Target", ""))
          && Xml.attr(rel, "Type", "").endsWith("/numbering")) found = true;
    }
    if (!found) {
      String id = "cn1Numbering";
      while (ids.contains(id)) id += "1";
      rels =
          insert(
              rels,
              "<Relationship xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""
                  + " Id=\""
                  + id
                  + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\""
                  + " Target=\"numbering.xml\"/>");
      parts.put(path, rels.getBytes("UTF-8"));
    }
    path = "[Content_Types].xml";
    if (parts.containsKey(path)) {
      String types = new String(parts.get(path), "UTF-8");
      boolean has = false;
      for (Element e : Xml.children(Xml.parse(types), null))
        if ("/word/numbering.xml".equals(Xml.attr(e, "PartName", ""))) has = true;
      if (!has)
        parts.put(
            path,
            insert(
                    types,
                    "<Override"
                        + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\""
                        + " PartName=\"/word/numbering.xml\""
                        + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>")
                .getBytes("UTF-8"));
    }
  }

  static String insert(String xml, String child) throws IOException {
    int end = xml.lastIndexOf("</");
    if (end < 0) {
      int close = xml.lastIndexOf("/>");
      if (close < 0) throw new IOException("Invalid package XML");
      return xml.substring(0, close) + ">" + child + "</" + Xml.parse(xml).getTagName() + ">";
    }
    return xml.substring(0, end) + child + xml.substring(end);
  }
}
