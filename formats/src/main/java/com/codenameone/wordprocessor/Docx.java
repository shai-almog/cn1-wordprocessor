package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Explicitly bounded DOCX subset. Lossy edits require the caller to opt in. */
public final class Docx {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final int MAX_ENTRY = 32 * 1024 * 1024,
      MAX_TOTAL = 128 * 1024 * 1024,
      MAX_ENTRIES = 4096;

  public static final class Opened {
    public final Document document;
    public final List<String> warnings;
    private final Map<String, byte[]> parts;

    Opened(Document d, Set<String> warnings, Map<String, byte[]> parts) {
      document = d;
      this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
      this.parts = parts;
    }
  }

  private Docx() {}

  public static Opened read(InputStream input) throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<String, byte[]>();
    ZipInputStream zip = new ZipInputStream(input);
    ZipEntry entry;
    int total = 0, count = 0;
    while ((entry = zip.getNextEntry()) != null) {
      if (++count > MAX_ENTRIES) throw new IOException("Too many package entries");
      String name = entry.getName();
      if (name.startsWith("/")
          || name.contains("..")
          || name.contains("\\")
          || parts.containsKey(name)) throw new IOException("Invalid or duplicate package path");
      if (entry.isDirectory()) continue;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = zip.read(buf)) != -1) {
        total += n;
        if (out.size() + n > MAX_ENTRY || total > MAX_TOTAL)
          throw new IOException("DOCX package exceeds size limit");
        out.write(buf, 0, n);
      }
      parts.put(name, out.toByteArray());
    }
    byte[] main = parts.get("word/document.xml");
    if (main == null) throw new IOException("Missing word/document.xml");
    Element root = Xml.parse(main);
    Element body = Xml.child(root, "body");
    if (body == null) throw new IOException("Missing document body");
    Set<String> warnings = new LinkedHashSet<String>();
    long[] id = {1};
    Sequence<Block> blocks = new Sequence<Block>();
    PageSpec page = PageSpec.A4;
    for (Element c : Xml.children(body, null)) {
      String kind = Xml.local(c);
      if ("p".equals(kind)) blocks = blocks.append(paragraph(c, id, warnings));
      else if ("tbl".equals(kind)) {
        List<List<Sequence<Paragraph>>> rows = new ArrayList<List<Sequence<Paragraph>>>();
        for (Element row : Xml.children(c, "tr")) {
          List<Sequence<Paragraph>> cells = new ArrayList<Sequence<Paragraph>>();
          for (Element cell : Xml.children(row, "tc")) {
            Sequence<Paragraph> ps = new Sequence<Paragraph>();
            for (Element p : Xml.children(cell, "p")) ps = ps.append(paragraph(p, id, warnings));
            if (ps.size() == 0) ps = ps.append(new Paragraph("p" + id[0]++, new RichText()));
            cells.add(ps);
            for (Element prop : Xml.children(cell, null))
              if (!"p".equals(Xml.local(prop)))
                warnings.add("Table cell properties or nested content need conversion");
          }
          if (!cells.isEmpty()) rows.add(cells);
        }
        if (!rows.isEmpty()) {
          int max = 0;
          for (List<Sequence<Paragraph>> row : rows) max = Math.max(max, row.size());
          for (List<Sequence<Paragraph>> row : rows)
            while (row.size() < max)
              row.add(
                  new Sequence<Paragraph>().append(new Paragraph("p" + id[0]++, new RichText())));
          blocks = blocks.append(new Table("t" + id[0]++, rows));
          warnings.add(
              "Table widths, merges, borders and row properties are not preserved during editing");
        }
      } else if ("sectPr".equals(kind)) {
        Element sz = Xml.child(c, "pgSz"), m = Xml.child(c, "pgMar");
        try {
          if (sz != null && m != null)
            page =
                new PageSpec(
                    twips(sz, "w", 11906),
                    twips(sz, "h", 16838),
                    twips(m, "top", 1080),
                    twips(m, "right", 1080),
                    twips(m, "bottom", 1080),
                    twips(m, "left", 1080));
        } catch (RuntimeException ex) {
          warnings.add("Unsupported page geometry; using A4");
        }
        for (Element p : Xml.children(c, null))
          if (!"pgSz".equals(Xml.local(p)) && !"pgMar".equals(Xml.local(p)))
            warnings.add("Section feature: " + Xml.local(p));
      } else warnings.add("Document feature: " + kind);
    }
    if (blocks.size() == 0) blocks = blocks.append(new Paragraph("p" + id[0]++, new RichText()));
    if (parts.containsKey("word/styles.xml"))
      warnings.add("Named style inheritance is not resolved");
    if (parts.containsKey("word/numbering.xml"))
      warnings.add("Numbering definitions are not resolved");
    return new Opened(new Document(blocks, page, "Imported document", id[0]), warnings, parts);
  }

  private static float twips(Element e, String key, int fallback) {
    return Float.parseFloat(Xml.attr(e, key, Integer.toString(fallback))) / 20f;
  }

  private static Paragraph paragraph(Element e, long[] id, Set<String> warnings) {
    RichText text = new RichText();
    String style = "Normal", align = "left";
    float before = 0, after = 6, indent = 0, line = 1.2f;
    boolean pageBreak = false, keep = false;
    Element props = Xml.child(e, "pPr");
    if (props != null)
      for (Element p : Xml.children(props, null)) {
        String kind = Xml.local(p);
        if ("pStyle".equals(kind)) style = Xml.attr(p, "val", "Normal");
        else if ("jc".equals(kind)) align = Xml.attr(p, "val", "left");
        else if ("spacing".equals(kind)) {
          before = twips(p, "before", 0);
          after = twips(p, "after", 120);
          line = Float.parseFloat(Xml.attr(p, "line", "288")) / 240f;
          if (!"auto".equals(Xml.attr(p, "lineRule", "auto")))
            warnings.add("Exact line spacing is approximated");
          line = Math.max(1, line);
        } else if ("ind".equals(kind)) indent = Math.max(0, twips(p, "left", 0));
        else if ("pageBreakBefore".equals(kind)) pageBreak = enabled(p);
        else if ("keepNext".equals(kind)) keep = enabled(p);
        else warnings.add("Paragraph feature: " + kind);
      }
    for (Element c : Xml.children(e, null)) {
      if ("r".equals(Xml.local(c))) text = text.concat(run(c, warnings));
      else if (!"pPr".equals(Xml.local(c))) {
        warnings.add("Inline feature: " + Xml.local(c));
        for (Element r : Xml.children(c, "r")) text = text.concat(run(r, warnings));
      }
    }
    return new Paragraph(
        "p" + id[0]++, text, style, align, "", before, after, indent, line, pageBreak, keep);
  }

  private static boolean enabled(Element e) {
    String v = Xml.attr(e, "val", "true");
    return !("0".equals(v) || "false".equals(v) || "off".equals(v) || "none".equals(v));
  }

  private static RichText run(Element e, Set<String> warnings) {
    TextStyle defaults = TextStyle.DEFAULT;
    String font = defaults.font;
    float size = defaults.size;
    int color = defaults.color, background = defaults.background;
    boolean bold = defaults.bold, italic = defaults.italic,
        underline = defaults.underline, strike = defaults.strike;
    Element props = Xml.child(e, "rPr");
    if (props != null)
      for (Element p : Xml.children(props, null)) {
        String k = Xml.local(p);
        if ("b".equals(k)) bold = enabled(p);
        else if ("i".equals(k)) italic = enabled(p);
        else if ("u".equals(k)) underline = enabled(p);
        else if ("strike".equals(k)) strike = enabled(p);
        else if ("sz".equals(k)) size = Float.parseFloat(Xml.attr(p, "val", "24")) / 2;
        else if ("color".equals(k)) {
          String v = Xml.attr(p, "val", "202833");
          if (!"auto".equals(v)) color = Integer.parseInt(v, 16);
        } else if ("rFonts".equals(k)) font = Xml.attr(p, "ascii", "Serif");
        else if ("shd".equals(k)) background = Integer.parseInt(Xml.attr(p, "fill", "ffffff"), 16);
        else warnings.add("Character feature: " + k);
      }
    TextStyle style = new TextStyle(font, size, color, bold, italic, underline, strike,
        background, defaults.link);
    StringBuilder b = new StringBuilder();
    for (Element c : Xml.children(e, null)) {
      String k = Xml.local(c);
      if ("t".equals(k)) b.append(Xml.text(c));
      else if ("tab".equals(k)) b.append('\t');
      else if ("br".equals(k) || "cr".equals(k)) {
        b.append('\n');
        if ("page".equals(Xml.attr(c, "type", "")))
          warnings.add("Inline page break converted to line break");
      } else if (!"rPr".equals(k)) warnings.add("Run feature: " + k);
    }
    return RichText.of(b.toString(), style);
  }

  public static List<String> conversionWarnings(Document d, Opened source) {
    LinkedHashSet<String> warnings = new LinkedHashSet<String>();
    if (source != null && source.document == d) return new ArrayList<String>();
    if (source != null) warnings.addAll(source.warnings);
    for (Block b : d.blocks) {
      if (b instanceof Paragraph) exportWarnings((Paragraph) b, warnings);
      else if (b instanceof Table) {
        Table t = (Table) b;
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            for (Paragraph p : t.cell(r, c)) exportWarnings(p, warnings);
      }
    }
    return new ArrayList<String>(warnings);
  }

  private static void exportWarnings(Paragraph p, Set<String> warnings) {
    if (!p.list.isEmpty())
      warnings.add("List numbering is not exported by the initial DOCX writer");
    p.text.visit(
        (o, text, style) -> {
          if (!style.link.isEmpty())
            warnings.add("Hyperlink targets are not exported by the initial DOCX writer");
        });
  }

  public static void write(Document d, OutputStream out) throws IOException {
    write(d, null, out, false);
  }

  public static void write(Document d, Opened source, OutputStream out, boolean allowLoss)
      throws IOException {
    boolean original = source != null && source.document == d;
    List<String> losses = conversionWarnings(d, source);
    if (!losses.isEmpty() && !allowLoss)
      throw new IOException("Save As with explicit conversion required: " + losses);
    final Map<String, byte[]> parts = new LinkedHashMap<String, byte[]>();
    if (source != null) parts.putAll(source.parts);
    if (!original) {
      parts.put("word/document.xml", documentXml(d).getBytes("UTF-8"));
      if (source == null) {
        parts.put(
            "[Content_Types].xml",
            ("<?xml version=\"1.0\"?><Types"
                 + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                 + " Extension=\"rels\""
                 + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                 + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
                 + " PartName=\"/word/document.xml\""
                 + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>")
                .getBytes("UTF-8"));
        parts.put(
            "_rels/.rels",
            ("<?xml version=\"1.0\"?><Relationships"
                 + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                 + " Id=\"rId1\""
                 + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                 + " Target=\"word/document.xml\"/></Relationships>")
                .getBytes("UTF-8"));
      }
    }
    ZipOutputStream zip = new ZipOutputStream(out);
    for (Map.Entry<String, byte[]> e : parts.entrySet()) {
      ZipEntry z = new ZipEntry(e.getKey());
      z.setTime(0);
      zip.putNextEntry(z);
      zip.write(e.getValue());
      zip.closeEntry();
    }
    zip.finish();
    zip.flush();
  }

  private static String documentXml(Document d) {
    StringBuilder b =
        new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"")
            .append(W)
            .append("\"><w:body>");
    for (Block block : d.blocks) {
      if (block instanceof Paragraph) writeParagraph(b, (Paragraph) block);
      else if (block instanceof Table) {
        Table t = (Table) block;
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>");
        for (int c = 0; c < t.columns(); c++)
          b.append("<w:gridCol w:w=\"")
              .append(Math.round(d.page.contentWidth() * 20 / t.columns()))
              .append("\"/>");
        b.append("</w:tblGrid>");
        for (int r = 0; r < t.rows(); r++) {
          b.append("<w:tr>");
          for (int c = 0; c < t.columns(); c++) {
            b.append("<w:tc>");
            for (Paragraph p : t.cell(r, c)) writeParagraph(b, p);
            b.append("</w:tc>");
          }
          b.append("</w:tr>");
        }
        b.append("</w:tbl>");
      }
    }
    PageSpec p = d.page;
    b.append("<w:sectPr><w:pgSz w:w=\"")
        .append(Math.round(p.width * 20))
        .append("\" w:h=\"")
        .append(Math.round(p.height * 20))
        .append("\"/><w:pgMar w:top=\"")
        .append(Math.round(p.top * 20))
        .append("\" w:right=\"")
        .append(Math.round(p.right * 20))
        .append("\" w:bottom=\"")
        .append(Math.round(p.bottom * 20))
        .append("\" w:left=\"")
        .append(Math.round(p.left * 20))
        .append("\"/></w:sectPr></w:body></w:document>");
    return b.toString();
  }

  private static void writeParagraph(final StringBuilder b, Paragraph p) {
    b.append("<w:p><w:pPr><w:pStyle w:val=\"")
        .append(Xml.escape(p.style))
        .append("\"/><w:jc w:val=\"")
        .append(Xml.escape(p.align))
        .append("\"/><w:spacing w:before=\"")
        .append(Math.round(p.before * 20))
        .append("\" w:after=\"")
        .append(Math.round(p.after * 20))
        .append("\" w:line=\"")
        .append(Math.round(p.lineSpacing * 240))
        .append("\" w:lineRule=\"auto\"/><w:ind w:left=\"")
        .append(Math.round(p.indent * 20))
        .append("\"/>");
    if (p.pageBreak) b.append("<w:pageBreakBefore/>");
    if (p.keepNext) b.append("<w:keepNext/>");
    b.append("</w:pPr>");
    p.text.visit(
        new RichText.Visitor() {
          public void run(int o, String text, TextStyle s) {
            b.append("<w:r><w:rPr><w:rFonts w:ascii=\"")
                .append(Xml.escape(s.font))
                .append("\" w:hAnsi=\"")
                .append(Xml.escape(s.font))
                .append("\"/><w:sz w:val=\"")
                .append(Math.round(s.size * 2))
                .append("\"/><w:color w:val=\"")
                .append(String.format(java.util.Locale.ROOT, "%06x", s.color))
                .append("\"/><w:shd w:fill=\"")
                .append(String.format(java.util.Locale.ROOT, "%06x", s.background))
                .append("\"/>");
            if (s.bold) b.append("<w:b/>");
            if (s.italic) b.append("<w:i/>");
            if (s.underline) b.append("<w:u w:val=\"single\"/>");
            if (s.strike) b.append("<w:strike/>");
            b.append("</w:rPr>");
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
              if (i > 0) b.append("<w:br/>");
              String[] tabs = lines[i].split("\t", -1);
              for (int j = 0; j < tabs.length; j++) {
                if (j > 0) b.append("<w:tab/>");
                b.append("<w:t xml:space=\"preserve\">")
                    .append(Xml.escape(tabs[j]))
                    .append("</w:t>");
              }
            }
            b.append("</w:r>");
          }
        });
    b.append("</w:p>");
  }
}
