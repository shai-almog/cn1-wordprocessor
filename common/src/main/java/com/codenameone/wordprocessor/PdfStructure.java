package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * Logical structure for PDF export: the tagged structure tree with its parent tree, link
 * annotations, the heading outline and document information.
 */
final class PdfStructure {
  static final class Element {
    final String type, alt;
    final Element parent;
    final List<Object> kids = new ArrayList<>();
    int id;

    Element(String type, Element parent, String alt) {
      this.type = type;
      this.parent = parent;
      this.alt = alt;
      if (parent != null) parent.kids.add(this);
    }
  }

  private final Document document;
  final Element root = new Element("Document", null, null);
  private final Map<String, Element> elements = new HashMap<>();
  // Per page, the element owning each marked-content identifier.
  private final List<List<Element>> parents = new ArrayList<>();
  private final List<Object[]> headings = new ArrayList<>();
  private final Set<String> outlined = new HashSet<>();

  PdfStructure(Document document) {
    this.document = document;
  }

  /** The structure element for a body, table-cell or note line; null for page furniture. */
  Element element(LayoutEngine.Line line, boolean note) {
    if (note) return paragraph("n:" + line.paragraphId, root, "P");
    if (line.row < 0) {
      int level = level(line);
      return paragraph(
          "p:" + line.block + ":" + line.paragraphId, root, level > 0 ? "H" + Math.min(6, level) : "P");
    }
    var table = paragraph("t:" + line.block, root, "Table");
    var row = paragraph("r:" + line.block + ":" + line.row, table, "TR");
    var cell = paragraph("c:" + line.block + ":" + line.row + ":" + line.column, row, "TD");
    return paragraph(
        "cp:" + line.block + ":" + line.row + ":" + line.column + ":" + line.paragraphId, cell, "P");
  }

  Element figure(String alt) {
    return new Element("Figure", root, alt == null || alt.isEmpty() ? "Picture" : alt);
  }

  private Element paragraph(String key, Element parent, String type) {
    var e = elements.get(key);
    if (e == null) elements.put(key, e = new Element(type, parent, null));
    return e;
  }

  int level(LayoutEngine.Line line) {
    if (document == null || line.block < 0 || line.block >= document.blocks.size()) return 0;
    var block = document.blocks.get(line.block);
    if (!(block instanceof Paragraph) || line.paragraphId == null
        || !line.paragraphId.equals(((Paragraph) block).id)) return 0;
    int level = OutlineView.level(document, (Paragraph) block);
    return level >= 1 && level <= 9 ? level : 0;
  }

  /** Records a heading's first line as an outline entry at the line top. */
  void heading(LayoutEngine.Line line, int page, float y) {
    int level = level(line);
    if (level == 0 || !outlined.add(line.block + ":" + line.paragraphId)) return;
    var text = ((Paragraph) document.blocks.get(line.block)).text.plainText().trim();
    if (text.length() > 200) text = text.substring(0, 200);
    if (!text.isEmpty()) headings.add(new Object[] {level, text, page, y});
  }

  /** Allocates the next marked-content identifier on a page for an element. */
  int mcid(int page, Element e) {
    while (parents.size() <= page) parents.add(new ArrayList<Element>());
    var list = parents.get(page);
    list.add(e);
    e.kids.add(new int[] {page, list.size() - 1});
    return list.size() - 1;
  }

  /** Writes the structure tree; returns the StructTreeRoot object number. */
  int writeTree(List<byte[]> objects, int[] pages) throws IOException {
    var all = new ArrayList<Element>();
    collect(root, all);
    int rootId = objects.size() + 1;
    objects.add(null);
    for (Element e : all) {
      objects.add(null);
      e.id = objects.size();
    }
    for (Element e : all) {
      var b = new StringBuilder("<< /Type /StructElem /S /").append(e.type).append(" /P ")
          .append(e.parent == null ? rootId : e.parent.id).append(" 0 R");
      if (e.alt != null) b.append(" /Alt ").append(text(e.alt));
      b.append(" /K [");
      for (Object k : e.kids)
        if (k instanceof Element) b.append(((Element) k).id).append(" 0 R ");
        else {
          int[] m = (int[]) k;
          b.append("<< /Type /MCR /Pg ").append(pages[m[0]]).append(" 0 R /MCID ")
              .append(m[1]).append(" >> ");
        }
      objects.set(e.id - 1, ascii(b.append("] >>").toString()));
    }
    var nums = new StringBuilder();
    for (int p = 0; p < parents.size(); p++) {
      nums.append(p).append(" [");
      for (Element e : parents.get(p)) nums.append(e.id).append(" 0 R ");
      nums.append("] ");
    }
    objects.add(ascii("<< /Nums [" + nums + "] >>"));
    int tree = objects.size();
    objects.set(rootId - 1, ascii("<< /Type /StructTreeRoot /K " + root.id + " 0 R /ParentTree "
        + tree + " 0 R /ParentTreeNextKey " + parents.size() + " >>"));
    return rootId;
  }

  private static void collect(Element e, List<Element> out) {
    out.add(e);
    for (Object k : e.kids) if (k instanceof Element) collect((Element) k, out);
  }

  /** Writes the heading outline; returns the Outlines object number or 0 when there are none. */
  int writeOutline(List<byte[]> objects, int[] pages) throws IOException {
    if (headings.isEmpty()) return 0;
    int base = objects.size() + 1, n = headings.size();
    // Parent of each entry: the nearest earlier entry with a lower level, else the root (-1).
    int[] parent = new int[n];
    for (int i = 0; i < n; i++) {
      parent[i] = -1;
      for (int j = i - 1; j >= 0; j--)
        if ((Integer) headings.get(j)[0] < (Integer) headings.get(i)[0]) {
          parent[i] = j;
          break;
        }
    }
    for (int i = 0; i <= n; i++) objects.add(null);
    for (int i = -1; i < n; i++) {
      int first = -1, last = -1, count = 0;
      for (int j = 0; j < n; j++)
        if (parent[j] == i) {
          if (first < 0) first = j;
          last = j;
          count++;
        }
      var b = new StringBuilder("<< ");
      if (i < 0) b.append("/Type /Outlines ");
      else {
        var h = headings.get(i);
        int prev = -1, next = -1;
        for (int j = i - 1; j >= 0 && prev < 0; j--) if (parent[j] == parent[i]) prev = j;
        for (int j = i + 1; j < n && next < 0; j++) if (parent[j] == parent[i]) next = j;
        b.append("/Title ").append(text((String) h[1])).append(" /Parent ")
            .append(base + parent[i] + 1).append(" 0 R /Dest [")
            .append(pages[(Integer) h[2]]).append(" 0 R /XYZ 0 ")
            .append(PdfExporter.n((Float) h[3])).append(" 0] ");
        if (prev >= 0) b.append("/Prev ").append(base + prev + 1).append(" 0 R ");
        if (next >= 0) b.append("/Next ").append(base + next + 1).append(" 0 R ");
      }
      if (first >= 0)
        b.append("/First ").append(base + first + 1).append(" 0 R /Last ")
            .append(base + last + 1).append(" 0 R /Count ").append(count).append(' ');
      objects.set(base + i, ascii(b.append(">>").toString()));
    }
    return base;
  }

  /** Document information dictionary entries and the catalog language, if known. */
  String info(LayoutEngine.Result layout) throws IOException {
    var b = new StringBuilder("<< /Producer (CN1 Word Processor) /Creator (CN1 Word Processor)");
    if (document != null) {
      String[][] keys = {{"title", "Title"}, {"author", "Author"}, {"subject", "Subject"},
        {"keywords", "Keywords"}};
      for (String[] k : keys) {
        var value = document.properties.get(k[0]);
        if ((value == null || value.isEmpty()) && "title".equals(k[0])) value = document.title;
        if (value != null && !value.isEmpty()) b.append(" /").append(k[1]).append(' ').append(text(value));
      }
    }
    var c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    b.append(" /CreationDate (D:").append(c.get(Calendar.YEAR));
    int[] parts = {c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
      c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)};
    for (int part : parts) b.append(TextUtil.padded(part, 10, 2));
    return b.append("Z) >>").toString();
  }

  static String language(LayoutEngine.Result layout) {
    for (LayoutEngine.Page page : layout.pages)
      for (LayoutEngine.Line line : page.lines)
        for (LayoutEngine.Span span : line.spans) {
          var lang = span.style.characters.lang;
          if (lang != null && !lang.isEmpty() && validLanguage(lang)) return lang;
        }
    return "";
  }

  private static boolean validLanguage(String lang) {
    for (int i = 0; i < lang.length(); i++) {
      char c = lang.charAt(i);
      if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-'))
        return false;
    }
    return true;
  }

  /** A link annotation's action, or null for unsupported targets. */
  String action(String link, LayoutEngine.Result layout, int[] pages) {
    if (link.startsWith("#")) {
      if (document == null) return null;
      var mark = document.bookmarks.get(link.substring(1));
      if (mark == null) return null;
      for (int p = 0; p < layout.pages.size(); p++)
        for (LayoutEngine.Line line : layout.pages.get(p).lines)
          if (mark.startId.equals(line.paragraphId) && mark.start <= Math.max(line.end, line.start))
            return "/Dest [" + pages[p] + " 0 R /XYZ 0 "
                + PdfExporter.n(layout.pages.get(p).spec.height - line.y) + " 0]";
      return null;
    }
    var lower = link.toLowerCase();
    if (!(lower.startsWith("http://") || lower.startsWith("https://")
        || lower.startsWith("mailto:") || lower.startsWith("ftp://"))) return null;
    var b = new StringBuilder("/A << /S /URI /URI (");
    byte[] bytes;
    try {
      bytes = link.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      return null;
    }
    for (byte value : bytes) {
      int c = value & 255;
      if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
      else if (c < 32 || c > 126) b.append('\\').append(TextUtil.padded(c, 8, 3));
      else b.append((char) c);
    }
    return b.append(") >>").toString();
  }

  /** A PDF text string: UTF-16BE with byte-order mark, as hex. */
  static String text(String value) {
    var b = new StringBuilder("<FEFF");
    for (int i = 0; i < value.length(); i++) b.append(TextUtil.padded(value.charAt(i), 16, 4));
    return b.append('>').toString();
  }

  private static byte[] ascii(String s) throws IOException {
    return s.getBytes("ISO-8859-1");
  }
}
