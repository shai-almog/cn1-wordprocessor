package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Explicitly bounded DOCX subset. Lossy edits require the caller to opt in. */
public final class Docx {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

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
    // The large parts' CRCs are verified on another thread while they are parsed here.
    var checks = new ZipParts.Deferred();
    var parts = ZipParts.read(input, checks);
    Opened opened;
    try {
      opened = read(parts);
    } catch (IOException | RuntimeException failure) {
      checks.await(); // corruption explains the failure better than the parse error
      throw failure;
    }
    checks.await();
    return opened;
  }

  private static Opened read(Map<String, byte[]> parts) throws IOException {
    var main = parts.get("word/document.xml");
    if (main == null) throw new IOException("Missing word/document.xml");
    Element root = Xml.parse(main);
    Element body = Xml.child(root, "body");
    if (body == null) throw new IOException("Missing document body");
    var warnings = new LinkedHashSet<String>();
    DocxLists.Read lists = DocxLists.read(parts.get("word/numbering.xml"), warnings);
    var styles =
        new DocxStyles(parts.get("word/styles.xml"), parts.get("word/settings.xml"), warnings);
    var links = new DocxLinks(parts);
    var comments = new DocxComments(parts);
    var pictures = new DocxPictures(parts);
    long[] id = {1};
    links.notes = new DocxNotes(parts, id, warnings, styles);
    var blocks = new Sequence<Block>();
    var sectionElements = new LinkedHashMap<String, Element>();
    Paragraph dropLetter = null;
    String dropSpec = null;
    for (Element c : DocxToc.body(body, warnings)) {
      String kind = Xml.local(c);
      if ("p".equals(kind)) {
        var paragraphWarnings = new LinkedHashSet<String>();
        Paragraph p = paragraph(c, id, paragraphWarnings, lists, styles, links, comments);
        Element frame = Xml.child(c, "pPr") == null ? null : Xml.child(Xml.child(c, "pPr"), "framePr");
        String dropMode = frame == null ? "" : Xml.attr(frame, "dropCap", "");
        if (("drop".equals(dropMode) || "margin".equals(dropMode)) && p.text.length() > 0 && p.text.length() <= 4) {
          // The framed letter joins the paragraph that follows it.
          dropLetter = p;
          float hSpace = 0;
          try {
            hSpace = Integer.parseInt(Xml.attr(frame, "hSpace", "0")) / 20f;
          } catch (NumberFormatException ignored) {
            // keep zero
          }
          String lines = Xml.attr(frame, "lines", "3");
          dropSpec = dropMode + "," + lines + "," + hSpace + ",";
          continue;
        }
        if (dropLetter != null) {
          var letter = dropLetter.text;
          var font = letter.styleAt(0).font;
          if (p.text.length() > 0) {
            letter = letter.format(0, letter.length(), "size", String.valueOf(p.text.styleAt(0).size));
            if (!font.equals(p.text.styleAt(0).font)) dropSpec += font.replace(",", "");
          }
          try {
            p = p.text(letter.concat(p.text)).with("dropCap", ParagraphFormat.dropCap(dropSpec));
          } catch (IllegalArgumentException invalid) {
            p = p.text(letter.concat(p.text));
          }
          dropLetter = null;
        }
        paragraphWarnings.remove("Run feature: drawing");
        paragraphWarnings.remove("Paragraph feature: sectPr");
        Element paragraphProps = Xml.child(c, "pPr");
        Element section = paragraphProps == null ? null : Xml.child(paragraphProps, "sectPr");
        if (section != null) sectionElements.put(p.id, section);
        warnings.addAll(paragraphWarnings);
        var images = pictures.read(c, id, warnings);
        if (images.isEmpty() || p.text.length() > 0 || section != null) blocks = blocks.append(p);
        if (!images.isEmpty() && p.text.length() > 0)
          warnings.add("Pictures embedded in text moved after their paragraph");
        Element props = Xml.child(c, "pPr"), jc = props == null ? null : Xml.child(props, "jc");
        String align = jc == null ? "left" : Xml.attr(jc, "val", "left");
        if (!"center".equals(align) && !"right".equals(align)) align = "left";
        for (Picture image : images)
          blocks = blocks.append(image.resize(image.width, image.height, image.alt, align));
      } else if ("tbl".equals(kind)) {
        Table table = readTable(c, id, warnings, lists, styles, links, comments);
        if (table != null) blocks = blocks.append(table);
      } else if (!"sectPr".equals(kind)) warnings.add("Document feature: " + kind);
    }
    if (blocks.size() == 0
        || !(blocks.get(blocks.size() - 1) instanceof Paragraph)
        || sectionElements.containsKey(blocks.get(blocks.size() - 1).id()))
      blocks = blocks.append(new Paragraph("p" + id[0]++, new RichText()));
    var sections = new LinkedHashMap<String, Section>();
    for (Map.Entry<String, Element> sectionEntry : sectionElements.entrySet())
      sections.put(
          sectionEntry.getKey(), readSection(parts, sectionEntry.getValue(), id, warnings, styles));
    Section last = readSection(parts, Xml.child(body, "sectPr"), id, warnings, styles);
    Element background = Xml.child(root, "background");
    if (background != null) {
      String fill = Xml.attr(background, "color", "auto");
      if (TextUtil.hex(fill, 6)) {
        // Word's page color is document-wide; the model stores it per section.
        int rgb = Integer.parseInt(fill, 16);
        for (Map.Entry<String, Section> sec : sections.entrySet())
          sec.setValue(sec.getValue().decor(sec.getValue().decor.pageColor(rgb)));
        last = last.decor(last.decor.pageColor(rgb));
      }
      if (Xml.children(background, null).size() > 0)
        warnings.add("Page background fill effects shown as a solid color");
    }
    var document =
        new Document(blocks, last.page, "Imported document", id[0]).sections(sections, last);
    document = document.comments(comments.read(document, warnings));
    document = document.bookmarks(comments.bookmarks.read(document, warnings));
    Element settings =
        parts.containsKey("word/settings.xml") ? Xml.parse(parts.get("word/settings.xml")) : null;
    document =
        document
            .notes(NoteFormats.read(settings, warnings))
            .bibliography(BibliographyFormats.read(parts, warnings))
            .properties(DocxProperties.read(parts, settings, warnings));
    Element tracking = settings == null ? null : Xml.child(settings, "trackRevisions");
    document =
        RevisionFormats.reserveIds(document.trackChanges(tracking != null && enabled(tracking)));
    var definitions =
        new LinkedHashMap<String, ParagraphStyle>(ParagraphStyle.defaults());
    for (Map.Entry<String, String> styleEntry : styles.paragraphNames().entrySet()) {
      Element p =
          Xml.parse(
              "<w:p><w:pPr><w:pStyle w:val=\""
                  + Xml.escape(styleEntry.getKey())
                  + "\"/></w:pPr></w:p>");
      Paragraph template =
          paragraph(
              p,
              new long[] {1},
              warnings,
              lists,
              styles,
              links,
              new DocxComments(Collections.<String, byte[]>emptyMap()),
              true);
      definitions.put(
          styleEntry.getKey(),
          new ParagraphStyle(
              styleEntry.getKey(),
              styleEntry.getValue(),
              template,
              styles.text(styleEntry.getKey(), null)));
    }
    document = Notes.normalize(document.styles(definitions));

    return new Opened(document, warnings, parts);
  }

  private static Table readTable(
      Element table,
      long[] id,
      Set<String> warnings,
      DocxLists.Read lists,
      DocxStyles styles,
      DocxLinks links,
      DocxComments comments) {
    var rows = new ArrayList<List<Sequence<Paragraph>>>();
    var spans = new ArrayList<int[]>();
    for (Element row : Xml.children(table, "tr")) {
      var cells = new ArrayList<Sequence<Paragraph>>();
      for (Element cell : Xml.children(row, "tc")) {
        Element props = Xml.child(cell, "tcPr"),
            grid = props == null ? null : Xml.child(props, "gridSpan"),
            vertical = props == null ? null : Xml.child(props, "vMerge");
        int width = grid == null ? 1 : Integer.parseInt(Xml.attr(grid, "val", "1"));
        if (width < 1 || width > 100)
          throw new IllegalArgumentException("Invalid table column span");
        int column = cells.size();
        if (column + width > 100 || rows.size() >= 1000)
          throw new IllegalArgumentException("Table size limit exceeded");
        boolean continuation =
            vertical != null && !"restart".equals(Xml.attr(vertical, "val", "continue"));
        var content = new Sequence<Paragraph>();
        for (Element p : Xml.children(cell, "p"))
          content = content.append(paragraph(p, id, warnings, lists, styles, links, comments));
        if (content.size() == 0)
          content = content.append(new Paragraph("p" + id[0]++, new RichText()));
        if (continuation) {
          int[] found = null;
          for (int[] old : spans)
            if (old[1] == column && old[3] == width && old[0] + old[2] == rows.size()) found = old;
          if (found == null) warnings.add("Unmatched vertical table merge requires conversion");
          else {
            found[2]++;
            if (!Table.cellText(content).isEmpty())
              warnings.add("Content in a vertical merge continuation requires conversion");
          }
        } else if (width > 1 || vertical != null)
          spans.add(new int[] {rows.size(), column, 1, width});
        cells.add(content);
        for (int i = 1; i < width; i++)
          cells.add(new Sequence<Paragraph>().append(new Paragraph("p" + id[0]++, new RichText())));
        if (props != null)
          for (Element prop : Xml.children(props, null)) {
            String name = Xml.local(prop);
            if (!"gridSpan".equals(name)
                && !"vMerge".equals(name)
                && !"tcW".equals(name)
                && !"tcBorders".equals(name)
                && !"tcMar".equals(name)
                && !"shd".equals(name)
                && !"vAlign".equals(name)) warnings.add("Table cell property: " + name);
          }
        for (Element child : Xml.children(cell, null))
          if (!"p".equals(Xml.local(child)) && !"tcPr".equals(Xml.local(child)))
            warnings.add("Nested table content needs conversion");
      }
      if (!cells.isEmpty()) rows.add(cells);
    }
    if (rows.isEmpty()) return null;
    int columns = 0;
    for (List<Sequence<Paragraph>> row : rows) columns = Math.max(columns, row.size());
    for (List<Sequence<Paragraph>> row : rows)
      while (row.size() < columns)
        row.add(new Sequence<Paragraph>().append(new Paragraph("p" + id[0]++, new RichText())));
    var merges = new ArrayList<Table.Merge>();
    for (int[] span : spans)
      if (span[2] > 1 || span[3] > 1)
        merges.add(new Table.Merge(span[0], span[1], span[2], span[3]));
    return TableFormats.readDocx(table, new Table("t" + id[0]++, rows, merges), warnings);
  }

  /** Placeholder for w:br type="column" while runs are read; never stored in a document. */
static final char COLUMN_BREAK = '\u000B';

  static final String COLUMN_BREAK_TEXT = String.valueOf(COLUMN_BREAK);

  private static float twips(Element e, String key, int fallback) {
    String v = Xml.attr(e, key, null);
    return (v == null ? fallback : Xml.number(v)) / 20f;
  }

  static Paragraph paragraph(
      Element e,
      long[] id,
      Set<String> warnings,
      DocxLists.Read lists,
      DocxStyles styles,
      DocxLinks links,
      DocxComments comments) {
    return paragraph(e, id, warnings, lists, styles, links, comments, false);
  }

  private static Paragraph paragraph(
      Element e,
      long[] id,
      Set<String> warnings,
      DocxLists.Read lists,
      DocxStyles styles,
      DocxLinks links,
      DocxComments comments,
      boolean styleTemplate) {
    var text = new RichText();
    String style = styles.defaultParagraph(), align = "left", list = "";
    float before = 0, after = 6, indent = 0, line = 1.2f;
    boolean pageBreak = false, keep = false;
    int listLevel = 0, listStart = 0;
    String listFormat = "decimal", listId = "";
    var layout = styles.baseFormat();
    Element props = Xml.child(e, "pPr");
    if (props != null && Xml.child(props, "pStyle") != null)
      style = Xml.attr(Xml.child(props, "pStyle"), "val", style);
    var properties = styles.paragraphProperties(style);
    if (!styleTemplate)
      for (int i = properties.size() - 1; i >= 0; i--)
        if ("outlineLvl".equals(Xml.local(properties.get(i)))) properties.remove(i);
    if (props != null) properties.addAll(Xml.children(props, null));
    for (Element p : properties) {
      String kind = Xml.local(p);
      if ("pStyle".equals(kind)) style = Xml.attr(p, "val", "Normal");
      else if ("numPr".equals(kind)) {
        Element n = Xml.child(p, "numId"), level = Xml.child(p, "ilvl");
        listId = n == null ? "" : Xml.attr(n, "val", "");
        listLevel = level == null ? 0 : Integer.parseInt(Xml.attr(level, "val", "0"));
      } else if ("jc".equals(kind)) align = Xml.attr(p, "val", "left");
      else if ("spacing".equals(kind)) {
        before = twips(p, "before", Math.round(before * 20));
        after = twips(p, "after", Math.round(after * 20));
        // A spacing element that only overrides before/after must retain the
        // line rule and amount inherited from the paragraph's named style.
        String rule = Xml.attr(p, "lineRule", layout.lineRule);
        float stored = line * ("auto".equals(layout.lineRule) ? 240 : 20);
        String amount = Xml.attr(p, "line", null);
        line = Math.max(.06f, (amount == null ? stored : Xml.number(amount)) / ("auto".equals(rule) ? 240f : 20f));
        layout = layout.with("lineRule", rule);
      } else if ("ind".equals(kind)) {
        indent = Math.max(0, twips(p, "left", 0));
        layout =
            layout.withIndents(
                Math.max(0, twips(p, "right", 0)), twips(p, "firstLine", 0) - twips(p, "hanging", 0));
      } else if ("keepLines".equals(kind))
        layout = layout.with("keepLines", String.valueOf(enabled(p)));
      else if ("widowControl".equals(kind))
        layout = layout.with("widowControl", String.valueOf(enabled(p)));
      else if ("bidi".equals(kind)) layout = layout.with("rtl", String.valueOf(enabled(p)));
      else if (ParagraphFormat.ASIAN.contains(kind)) {
        var off = layout.asianOff;
        if (!enabled(p)) off = off.isEmpty() ? kind : off + "," + kind;
        layout = layout.with("asianOff", off);
      }
      else if ("outlineLvl".equals(kind)) {
        int level = Integer.parseInt(Xml.attr(p, "val", "9"));
        if (level < 0 || level > 9)
          throw new IllegalArgumentException("Invalid DOCX outline level");
        layout = layout.with("outlineLevel", "" + (level == 9 ? 0 : level + 1));
      } else if ("tabs".equals(kind)) {
        var stops = new ArrayList<TabStop>(layout.tabs);
        for (Element tab : Xml.children(p, "tab")) {
          float position = twips(tab, "pos", 0);
          String alignment = Xml.attr(tab, "val", "left"), leader = Xml.attr(tab, "leader", "none");
          for (int i = stops.size() - 1; i >= 0; i--)
            if (Math.abs(stops.get(i).position - position) < .01f) stops.remove(i);
          if ("clear".equals(alignment)) continue;
          try {
            stops.add(new TabStop(position, alignment, leader));
          } catch (IllegalArgumentException unsupported) {
            warnings.add("Unsupported tab stop: " + alignment + "/" + leader);
          }
        }
        layout = layout.with("tabs", TabStop.encode(stops));
      } else if ("pBdr".equals(kind))
        layout = layout.with("borders", DocxBorders.read(p, warnings).encode());
      else if ("shd".equals(kind)) {
        int fill = DocxBorders.shading(p);
        layout =
            layout.with(
                "shading", fill < 0 ? "" : Integer.toHexString(0x1000000 | fill).substring(1));
      } else if ("pageBreakBefore".equals(kind)) pageBreak = enabled(p);
      else if ("keepNext".equals(kind)) keep = enabled(p);
      else if (!"rPr".equals(kind) && !"pPrChange".equals(kind))
        warnings.add("Paragraph feature: " + kind);
    }
    List<TabStop> stops = layout.tabs;
    boolean periodic = stops.size() == 64;
    float interval = stops.isEmpty() ? 36 : stops.get(0).position;
    for (int i = 0; i < stops.size(); i++) {
      var stop = stops.get(i);
      periodic &=
          "left".equals(stop.alignment)
              && "none".equals(stop.leader)
              && Math.abs(stop.position - (i + 1) * interval) < .01f;
    }
    if (periodic && interval > 0 && interval <= 1000)
      layout = layout.with("tabInterval", String.valueOf(interval)).with("tabs", "");
    final var paragraphStyle = style;
    text =
        DocxFields.read(
            e, (run, link) -> run(run, warnings, styles, paragraphStyle, link), links, warnings);
    for (int at = text.plainText().indexOf(COLUMN_BREAK); at >= 0;
        at = text.plainText().indexOf(COLUMN_BREAK)) {
      if (at > 0) warnings.add("Mid-paragraph column break moved to paragraph start");
      text = text.replace(at, at + 1, new RichText());
      layout = layout.with("columnBreak", "true");
    }
    if (!listId.isEmpty() && !"0".equals(listId)) {
      var definition = lists.take(listId, listLevel);
      if (definition == null) warnings.add("Unresolved list numbering");
      else {
        list = definition.kind;
        listStart = definition.start;
        listFormat = definition.format;
      }
    } else lists.body();
    if (!list.isEmpty()) indent = Math.max(0, indent - 18 * (listLevel + 1));
    var paragraph =
        new Paragraph(
            "p" + id[0]++,
            text,
            style,
            align,
            list,
            before,
            after,
            indent,
            line,
            pageBreak,
            keep,
            listLevel,
            listStart,
            listFormat,
            layout);
    Element markProps = props == null ? null : Xml.child(props, "rPr");
    if (markProps != null) {
      Element insert = Xml.child(markProps, "ins"), delete = Xml.child(markProps, "del");
      Revision mark = insert == null ? null : RevisionFormats.docxRead(insert);
      if (delete != null) mark = RevisionFormats.docxRead(delete).previous(mark);
      paragraph = paragraph.breakRevision(mark);
    }
    Element prior = props == null ? null : Xml.child(props, "pPrChange");
    if (prior != null && Xml.child(prior, "pPr") != null) {
      var container = new Element("w:p");
      Element previous = Xml.child(prior, "pPr");
      prior.removeChildAt(prior.getChildIndex(previous));
      container.addChild(previous);
      var priorLists = new DocxLists.Read();
      priorLists.definitions.putAll(lists.definitions);
      Paragraph previousParagraph =
          paragraph(container, new long[] {0}, warnings, priorLists, styles, links, comments);
      container.removeChildAt(0);
      prior.addChild(previous);
      paragraph =
          paragraph.formatRevision(
              new Revision(
                  Xml.attr(prior, "id", "0"),
                  "format",
                  Xml.attr(prior, "author", ""),
                  Xml.attr(prior, "date", "")),
              previousParagraph);
      if (!previousParagraph.style.equals(paragraphStyle)) {
        final var previousStyle = previousParagraph.style;
        final var styleChange = paragraph.formatRevision;
        paragraph =
            paragraph.text(
                DocxFields.read(
                    e,
                    (element, link) -> {
                      RichText current = run(element, warnings, styles, paragraphStyle, link);
                      var old =
                          run(element, warnings, styles, previousStyle, link).styleAt(0);
                      return current.mapStyles(
                          value ->
                              value.formatRevision == null
                                  ? value.trackedProperties(old, styleChange)
                                  : value);
                    },
                    links,
                    warnings));
      }
    }
    comments.scan(e, paragraph.id, warnings);
    return paragraph;
  }

  private static boolean enabled(Element e) {
    String v = Xml.attr(e, "val", "true");
    return !("0".equals(v) || "false".equals(v) || "off".equals(v) || "none".equals(v));
  }

  private static RichText run(
      Element e, Set<String> warnings, DocxStyles styles, String paragraphStyle, String link) {
    var style = styles.text(paragraphStyle, Xml.child(e, "rPr"));
    if (!link.isEmpty()) style = style.with("link", link);
    var b = new StringBuilder();
    for (Element c : Xml.children(e, null)) {
      String k = Xml.local(c);
      if ("t".equals(k) || "delText".equals(k)) b.append(Xml.text(c));
      else if ("ruby".equals(k)) {
        // DocxFields keeps the base text; the guide itself is not modeled.
        warnings.add("Phonetic guide (ruby) dropped; base text kept");
      }
      else if ("tab".equals(k)) b.append('\t');
      else if ("br".equals(k) && "column".equals(Xml.attr(c, "type", ""))) {
        // Becomes ParagraphFormat.columnBreak once the paragraph is assembled.
        b.append(COLUMN_BREAK);
      } else if ("br".equals(k) || "cr".equals(k)) {
        b.append('\n');
        if ("page".equals(Xml.attr(c, "type", "")))
          warnings.add("Inline page break converted to line break");
      } else if (!"rPr".equals(k)
          && !"commentReference".equals(k)
          && !"fldChar".equals(k)
          && !"instrText".equals(k)
          && !"delInstrText".equals(k)
          && !"footnoteReference".equals(k)
          && !"endnoteReference".equals(k)
          && !"footnoteRef".equals(k)
          && !"endnoteRef".equals(k)) warnings.add("Run feature: " + k);
    }
    return RichText.of(b.toString(), style);
  }

  public static List<String> conversionWarnings(Document d, Opened source) {
    var warnings = new LinkedHashSet<String>();
    if (source != null && source.document == d) return new ArrayList<>();
    if (source != null) warnings.addAll(source.warnings);

    var tabs = new DocxTabs(d);
    for (Paragraph paragraph : DocxTabs.paragraphs(d))
      exportWarnings(paragraph, warnings, tabs.interval);
    return new ArrayList<>(warnings);
  }

  private static void exportWarnings(Paragraph p, Set<String> warnings, float interval) {
    if (p.format.tabInterval != interval)
      for (TabStop stop : p.format.tabs)
        if ("bar".equals(stop.alignment)) {
          warnings.add(
              "Bar tabs with paragraph-specific default intervals require DOCX conversion");
          break;
        }
    if (!p.list.isEmpty() && !"bullet".equals(p.list) && !"number".equals(p.list))
      warnings.add("Unsupported list format: " + p.list);
    p.text.visit(
        (o, t, style) -> {
          if (!style.characters.ruby.isEmpty())
            warnings.add("Phonetic guides (ruby) are not saved to DOCX");
          if (!"none".equals(style.characters.enclose))
            warnings.add("Enclosed characters are not saved to DOCX");
        });
  }

  public static void write(Document d, OutputStream out) throws IOException {
    write(d, null, out, false);
  }

  public static void write(Document d, Opened source, OutputStream out, boolean allowLoss)
      throws IOException {
    boolean original = source != null && source.document == d;
    var losses = conversionWarnings(d, source);
    if (!losses.isEmpty() && !allowLoss)
      throw new IOException("Save As with explicit conversion required: " + losses);
    final var parts = new LinkedHashMap<String, byte[]>();
    if (source != null) parts.putAll(source.parts);
    if (!original) {
      var lists = new DocxLists(d);
      var tabs = new DocxTabs(d);
      var links = new DocxLinks(parts);
      links.notes = new DocxNotes(d);
      links.styles = d.styles;
      var comments = new DocxComments(d);
      var pictures = new DocxPictures(parts);
      var furniture = new ArrayList<DocxFurniture>();
      boolean even = false;
      for (Section.Range section : d.sectionRanges()) even |= section.raw.furniture.differentEven;
      int sectionIndex = 0;
      for (Section.Range section : d.sectionRanges()) {
        var f = section.raw.furniture;
        furniture.add(
            new DocxFurniture(
                d.page(section.raw.page)
                    .furniture(
                        f.options(f.differentFirst, even, f.headerDistance, f.footerDistance)),
                parts,
                sectionIndex++ == 0 ? "" : "S" + (sectionIndex - 1) + "-"));
      }
      parts.put(
          "word/document.xml",
          ZipParts.utf8(documentXml(d, lists, tabs, links, comments, pictures, furniture)));
      if (source == null) {
        parts.put(
            "[Content_Types].xml",
            ("""
                 <?xml version="1.0"?><Types\
                  xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default\
                  Extension="rels"\
                  ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default\
                  Extension="xml" ContentType="application/xml"/><Override\
                  PartName="/word/document.xml"\
                  ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
                .getBytes("UTF-8"));
        parts.put(
            "_rels/.rels",
            ("""
                 <?xml version="1.0"?><Relationships\
                  xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship\
                  Id="rId1"\
                  Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"\
                  Target="word/document.xml"/></Relationships>""")
                .getBytes("UTF-8"));
      }
      lists.packageParts(parts);
      links.packageParts(parts);
      comments.packageParts(parts);
      pictures.packageParts();
      writeStyles(d, parts, lists, tabs, links);
      for (DocxFurniture part : furniture) part.packageParts(parts, lists, tabs);
      links.notes.packageParts(parts, lists, tabs);
      BibliographyFormats.write(parts, d.bibliography);
      DocxProperties.write(parts, d.properties);
    }
    ZipParts.write(parts, out);
  }

  private static void writeStyles(
      Document d, Map<String, byte[]> parts, DocxLists lists, DocxTabs tabs, DocxLinks links)
      throws IOException {
    // Style definitions are the base that body paragraphs omit against: write them in full.
    var styles = links.styles;
    links.styles = null;
    var xml = new StringBuilder("<w:styles xmlns:w=\"").append(W).append("\">");
    var comments = new DocxComments(Document.empty());
    for (ParagraphStyle style : d.styles.values()) {
      var paragraph = new StringBuilder();
      writeParagraph(paragraph, style.paragraph, lists, tabs.withoutStyles(), links, comments);
      var content = paragraph.toString();
      int first = content.indexOf("<w:pPr>"), last = content.indexOf("</w:pPr>") + 8;
      var props = content.substring(first, last);
      int ps = props.indexOf("<w:pStyle");
      if (ps >= 0) props = props.substring(0, ps) + props.substring(props.indexOf("/>", ps) + 2);
      var run = new StringBuilder();
      writeRun(run, " ", style.text, links);
      var r = run.toString();
      var rpr = r.substring(r.indexOf("<w:rPr>"), r.indexOf("</w:rPr>") + 8);
      xml.append("<w:style w:type=\"paragraph\" w:styleId=\"")
          .append(Xml.escape(style.id))
          .append("\"");
      if ("Normal".equals(style.id)) xml.append(" w:default=\"1\"");
      xml.append("><w:name w:val=\"")
          .append(Xml.escape(style.name))
          .append("\"/><w:qFormat/>")
          .append(props)
          .append(rpr)
          .append("</w:style>");
    }
    links.styles = styles;
    parts.put("word/styles.xml", ZipParts.utf8(xml.append("</w:styles>")));
    String path = "word/_rels/document.xml.rels",
        rels =
            parts.containsKey(path)
                ? new String(parts.get(path), "UTF-8")
                : """
                      <Relationships\
                       xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>""";
    boolean found = false;
    var ids = new HashSet<String>();
    for (Element e : Xml.children(Xml.parse(rels), null)) {
      ids.add(Xml.attr(e, "Id", ""));
      if (Xml.attr(e, "Type", "").endsWith("/styles")) found = true;
    }
    if (!found) {
      var id = "cn1Styles";
      while (ids.contains(id)) id += "x";
      rels =
          DocxLists.insert(
              rels,
              "<Relationship"
                  + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""
                  + " Id=\""
                  + id
                  + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""
                  + " Target=\"styles.xml\"/>");
      parts.put(path, rels.getBytes("UTF-8"));
    }
    path = "[Content_Types].xml";
    if (parts.containsKey(path)) {
      var types = new String(parts.get(path), "UTF-8");
      found = false;
      for (Element e : Xml.children(Xml.parse(types), null))
        if ("/word/styles.xml".equals(Xml.attr(e, "PartName", ""))) found = true;
      if (!found)
        parts.put(
            path,
            DocxLists.insert(
                    types,
                    """
                    <Override\
                     xmlns="http://schemas.openxmlformats.org/package/2006/content-types"\
                     PartName="/word/styles.xml"\
                     ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
                .getBytes("UTF-8"));
    }
  }

  /** Size of the last document.xml: presizes the next one so it is built without regrowth. */
  private static volatile int lastDocumentChars = 1 << 16;

  private static StringBuilder documentXml(
      Document d,
      DocxLists lists,
      DocxTabs tabs,
      DocxLinks links,
      DocxComments comments,
      DocxPictures pictures,
      List<DocxFurniture> furniture) {
    // The part is encoded straight from this builder: a large document's XML is copied as
    // few times as possible, since each copy is allocation the ParparVM collector pays for.
    var b =
        new StringBuilder(lastDocumentChars + lastDocumentChars / 8)
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"")
            .append(W)
            .append(
                "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
    if (d.lastSection.decor.pageColor >= 0)
      b.append("<w:background w:color=\"")
          .append(DocxBorders.hex(d.lastSection.decor.pageColor))
          .append("\"/>");
    b.append("<w:body>");
    int sectionIndex = 0;
    List<Section.Range> sections = d.sectionRanges();
    var contentsRanges = TableContents.ranges(d);
    int[] indexRange = DocumentIndex.range(d);
    if (indexRange != null) contentsRanges.add(indexRange);
    int[] authorities = TableAuthorities.range(d);
    if (authorities != null) contentsRanges.add(authorities);
    int[] bibliography = Citations.range(d);
    if (bibliography != null) contentsRanges.add(bibliography);
    Collections.sort(contentsRanges, (a, c) -> a[0] - c[0]);
    int rangeIndex = 0;
    int[] contents = contentsRanges.isEmpty() ? null : contentsRanges.get(0);
    InlineField contentsField = contents == null ? null : DocxToc.marker(d.paragraph(contents[0]));
    int blockIndex = 0;
    for (Block block : d.blocks) {
      if (contents != null && blockIndex == contents[0])
        b.append(
            "BIBLIOGRAPHY".equals(new FieldCode(contentsField.instruction).name)
                ? DocxToc.START.replace("Table of Contents", "Bibliographies")
                : "TOA".equals(new FieldCode(contentsField.instruction).name)
                    ? DocxToc.START.replace("Table of Contents", "Table of Authorities")
                    : "INDEX".equals(new FieldCode(contentsField.instruction).name)
                        ? DocxToc.START.replace("Table of Contents", "Indexes")
                        : new FieldCode(contentsField.instruction).switches.containsKey("c")
                            ? DocxToc.START.replace("Table of Contents", "Table of Figures")
                            : DocxToc.START);
      var sectionPage = sections.get(sectionIndex).raw.page;
      if (block instanceof Paragraph paragraph) {
        var end = d.sections.get(block.id());
        String sectionXml = end == null ? "" : sectionXml(end, furniture.get(sectionIndex++));
        String prefix = "", suffix = "";
        if (contents != null && contents[1] > contents[0] + 1) {
          if (blockIndex == contents[0])
            paragraph =
                DocxToc.withoutMarker(paragraph)
                    .with(
                        "style",
                        "BIBLIOGRAPHY".equals(new FieldCode(contentsField.instruction).name)
                            ? "BibliographyHeading"
                            : "TOA".equals(new FieldCode(contentsField.instruction).name)
                                ? "TOAHeading"
                                : "INDEX".equals(new FieldCode(contentsField.instruction).name)
                                    ? "IndexHeading"
                                    : "TOCHeading");
          if (blockIndex == contents[0] + 1) prefix = DocxToc.begin(contentsField);
          if (blockIndex == contents[1] - 1) suffix = DocxToc.END_FIELD;
        }
        writeParagraph(b, paragraph, lists, tabs, links, comments, sectionXml, prefix, suffix);
      } else if (block instanceof Picture picture) pictures.write(b, picture);
      else if (block instanceof Table t) {
        b.append("<w:tbl><w:tblPr>");
        TableFormats.sizingXml(b, t.format);
        float tableWidth = t.format.preferredWidth(sectionPage.contentWidth());
        TableFormats.alternativeXml(b, t.format);
        b.append("</w:tblPr><w:tblGrid>");
        for (int c = 0; c < t.columns(); c++)
          b.append("<w:gridCol w:w=\"")
              .append(Math.round(20 * tableWidth * t.format.columnFraction(c)))
              .append("\"/>");
        b.append("</w:tblGrid>");
        for (int r = 0; r < t.rows(); r++) {
          b.append("<w:tr>");
          TableFormats.rowXml(b, t.format, r);
          for (int c = 0; c < t.columns(); c++) {
            var span = t.span(r, c);
            if (span.column != c) continue;
            b.append("<w:tc><w:tcPr>");
            float cellWidth = 0;
            for (int column = c; column < c + span.columns; column++)
              cellWidth += tableWidth * t.format.columnFraction(column);
            TableFormats.cellXml(b, t.format.cell(span.row, span.column), cellWidth);
            var flow = t.format.direction(span.row, span.column);
            if (!"lrTb".equals(flow)) b.append("<w:textDirection w:val=\"").append(flow).append("\"/>");
            if (span.columns > 1)
              b.append("<w:gridSpan w:val=\"").append(span.columns).append("\"/>");
            if (span.rows > 1)
              b.append(r == span.row ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>");
            b.append("</w:tcPr>");
            if (span.row == r)
              for (Paragraph p : t.cell(r, c)) writeParagraph(b, p, lists, tabs, links, comments);
            else b.append("<w:p/>");
            b.append("</w:tc>");
          }
          b.append("</w:tr>");
        }
        b.append("</w:tbl>");
      }
      if (contents != null && blockIndex == contents[1] - 1) {
        b.append("</w:sdtContent></w:sdt>");
        contents = ++rangeIndex < contentsRanges.size() ? contentsRanges.get(rangeIndex) : null;
        contentsField = contents == null ? null : DocxToc.marker(d.paragraph(contents[0]));
      }
      blockIndex++;
    }
    b.append(sectionXml(d.lastSection, furniture.get(furniture.size() - 1)));
    b.append("</w:body></w:document>");
    lastDocumentChars = b.length();
    return b;
  }

  private static String sectionXml(Section section, DocxFurniture furniture) {
    var b = new StringBuilder("<w:sectPr>");
    furniture.references(b);
    NoteFormats.write(b, section.notes, "w:");
    var p = section.page;
    b.append("<w:type w:val=\"").append(section.start).append("\"/>");
    b.append("<w:pgSz w:w=\"")
        .append(Math.round(p.width * 20))
        .append("\" w:h=\"")
        .append(Math.round(p.height * 20))
        .append("\" w:orient=\"")
        .append(p.width > p.height ? "landscape" : "portrait")
        .append("\"/><w:pgMar w:top=\"")
        .append(Math.round(p.top * 20))
        .append("\" w:right=\"")
        .append(Math.round(p.right * 20))
        .append("\" w:bottom=\"")
        .append(Math.round(p.bottom * 20))
        .append("\" w:left=\"")
        .append(Math.round(p.left * 20))
        .append("\" w:header=\"")
        .append(Math.round(section.furniture.headerDistance * 20))
        .append("\" w:footer=\"")
        .append(Math.round(section.furniture.footerDistance * 20))
        .append("\"/>");
    DocxBorders.page(b, section.decor.borders);
    var decor = section.decor;
    if (decor.lineCountBy > 0)
      b.append("<w:lnNumType w:countBy=\"").append(decor.lineCountBy).append("\" w:restart=\"")
          .append("page".equals(decor.lineRestart) ? "newPage" : "section".equals(decor.lineRestart) ? "newSection" : "continuous")
          .append("\"/>");
    if (section.pageStart > 0 || !"decimal".equals(decor.numberFormat)) {
      b.append("<w:pgNumType");
      if (!"decimal".equals(decor.numberFormat)) b.append(" w:fmt=\"").append(decor.numberFormat).append('"');
      if (section.pageStart > 0) b.append(" w:start=\"").append(section.pageStart).append('"');
      b.append("/>");
    }
    var c = section.columns;
    if (!c.equals(Columns.ONE)) {
      b.append("<w:cols w:num=\"").append(c.count).append("\" w:space=\"")
          .append(Math.round(c.space * 20)).append("\" w:sep=\"").append(c.separator ? 1 : 0)
          .append("\" w:equalWidth=\"").append(c.equalWidth() ? 1 : 0).append("\"");
      var w = c.widths();
      if (w.length == 0) b.append("/>");
      else {
        b.append(">");
        for (int i = 0; i < w.length; i += 2)
          b.append("<w:col w:w=\"").append(Math.round(w[i] * 20)).append("\" w:space=\"")
              .append(Math.round(w[i + 1] * 20)).append("\"/>");
        b.append("</w:cols>");
      }
    }
    var vAlign = decor.layout("verticalAlign", "top");
    if (!"top".equals(vAlign)) b.append("<w:vAlign w:val=\"").append(vAlign).append("\"/>");
    var flow = decor.layout("textDirection", "lrTb");
    if (!"lrTb".equals(flow)) b.append("<w:textDirection w:val=\"").append(flow).append("\"/>");
    if ("true".equals(decor.layout("rtl", ""))) b.append("<w:bidi/>");
    var grid = decor.layout("grid", "default");
    if (!"default".equals(grid)) {
      b.append("<w:docGrid w:type=\"").append(grid).append('"');
      var pitch = decor.layout("gridPitch", "");
      if (!pitch.isEmpty()) b.append(" w:linePitch=\"").append(Math.round(Float.parseFloat(pitch) * 20)).append('"');
      var chars = decor.layout("charPitch", "");
      if (!chars.isEmpty())
        b.append(" w:charSpace=\"").append(Math.round(Float.parseFloat(chars) * 4096)).append('"');
      b.append("/>");
    }
    return b.append("</w:sectPr>").toString();
  }

  private static Section readSection(
      Map<String, byte[]> parts, Element e, long[] id, Set<String> warnings, DocxStyles styles)
      throws IOException {
    var page = PageSpec.A4;
    var start = "nextPage";
    int pageStart = 0;
    var numberFormat = "decimal";
    var columns = Columns.ONE;
    if (e != null) {
      Element cols = Xml.child(e, "cols");
      if (cols != null) {
        try {
          var each = Xml.children(cols, "col");
          boolean equal = !"0".equals(Xml.attr(cols, "equalWidth", "1"))
              && !"false".equals(Xml.attr(cols, "equalWidth", "1"));
          int count = Integer.parseInt(Xml.attr(cols, "num", equal || each.isEmpty() ? "1" : "" + each.size()));
          float[] widths = new float[equal || each.isEmpty() ? 0 : each.size() * 2];
          for (int i = 0; i < widths.length / 2; i++) {
            widths[i * 2] = twips(each.get(i), "w", 0);
            widths[i * 2 + 1] = twips(each.get(i), "space", 0);
          }
          if (widths.length > 0) count = widths.length / 2;
          String sep = Xml.attr(cols, "sep", "0");
          columns = new Columns(count, twips(cols, "space", 720), "1".equals(sep) || "true".equals(sep), widths);
        } catch (RuntimeException ex) {
          warnings.add("Invalid section columns");
        }
      }
      Element size = Xml.child(e, "pgSz"),
          margin = Xml.child(e, "pgMar"),
          type = Xml.child(e, "type"),
          numbers = Xml.child(e, "pgNumType");
      try {
        if (size != null && margin != null)
          page =
              new PageSpec(
                  twips(size, "w", 11906),
                  twips(size, "h", 16838),
                  twips(margin, "top", 1080),
                  twips(margin, "right", 1080),
                  twips(margin, "bottom", 1080),
                  twips(margin, "left", 1080));
      } catch (RuntimeException ex) {
        warnings.add("Invalid section geometry; using A4");
      }
      if (type != null) start = Xml.attr(type, "val", "nextPage");
      if (!Arrays.asList("nextPage", "continuous", "oddPage", "evenPage").contains(start)) {
        warnings.add("Unsupported section start: " + start);
        start = "nextPage";
      }
      if (numbers != null) {
        try {
          pageStart = Integer.parseInt(Xml.attr(numbers, "start", "0"));
        } catch (RuntimeException ex) {
          warnings.add("Invalid page-number restart");
        }
        if (pageStart < 0 || pageStart > 1000000) {
          pageStart = 0;
          warnings.add("Invalid page-number restart");
        }
        numberFormat = Xml.attr(numbers, "fmt", "decimal");
        if (!PageDecor.NUMBER_FORMATS.contains(numberFormat)) {
          warnings.add("Section page-number format " + numberFormat + " shown as decimal");
          numberFormat = "decimal";
        }
      }
      for (Element child : Xml.children(e, null))
        if (!Arrays.asList(
                "pgSz",
                "pgMar",
                "type",
                "pgNumType",
                "cols",
                "pgBorders",
                "lnNumType",
                "titlePg",
                "headerReference",
                "footerReference",
                "footnotePr",
                "endnotePr")
            .contains(Xml.local(child))) warnings.add("Section feature: " + Xml.local(child));
    }
    PageFurniture furniture =
        DocxFurniture.read(
            parts,
            e,
            id,
            warnings,
            DocxLists.read(parts.get("word/numbering.xml"), warnings),
            styles);
    var decor = PageDecor.NONE;
    Element pageBorders = e == null ? null : Xml.child(e, "pgBorders");
    if (pageBorders != null) {
      try {
        decor = decor.borders(DocxBorders.readPage(pageBorders, warnings));
      } catch (RuntimeException ex) {
        warnings.add("Invalid page borders");
      }
    }
    decor = decor.numberFormat(numberFormat);
    if (e != null) {
      try {
        Element v = Xml.child(e, "vAlign");
        if (v != null) decor = decor.withLayout("verticalAlign", Xml.attr(v, "val", "top"));
        Element flow = Xml.child(e, "textDirection");
        if (flow != null) decor = decor.withLayout("textDirection", Xml.attr(flow, "val", "lrTb"));
        Element bidi = Xml.child(e, "bidi");
        if (bidi != null) decor = decor.withLayout("rtl", String.valueOf(enabled(bidi)));
        Element grid = Xml.child(e, "docGrid");
        if (grid != null) {
          decor = decor.withLayout("grid", Xml.attr(grid, "type", "default"));
          String pitch = Xml.attr(grid, "linePitch", "");
          if (!pitch.isEmpty() && !"default".equals(decor.layout("grid", "default")))
            decor = decor.withLayout("gridPitch", String.valueOf(Integer.parseInt(pitch) / 20f));
          String chars = Xml.attr(grid, "charSpace", "");
          if (!chars.isEmpty() && Integer.parseInt(chars) > 0 && !"lines".equals(decor.layout("grid", "")))
            decor = decor.withLayout("charPitch", String.valueOf(Integer.parseInt(chars) / 4096f));
        }
      } catch (RuntimeException ex) {
        warnings.add("Invalid section layout options");
      }
    }
    Element lines = e == null ? null : Xml.child(e, "lnNumType");
    if (lines != null) {
      try {
        int by = Math.min(100, Integer.parseInt(Xml.attr(lines, "countBy", "1")));
        String restart = Xml.attr(lines, "restart", "newPage");
        decor =
            decor.lineNumbers(
                by,
                "newSection".equals(restart) ? "section" : "continuous".equals(restart) ? "continuous" : "page");
        if (!Xml.attr(lines, "start", "").isEmpty() && !"1".equals(Xml.attr(lines, "start", "1")))
          warnings.add("Line numbers start at 1");
      } catch (RuntimeException ex) {
        warnings.add("Invalid line numbering");
      }
    }
    return new Section(
        page, furniture, start, pageStart, NoteFormats.read(e, warnings), columns, decor);
  }

  static void writeParagraph(
      final StringBuilder b,
      Paragraph p,
      DocxLists lists,
      DocxTabs tabs,
      final DocxLinks links,
      DocxComments comments) {
    writeParagraph(b, p, lists, tabs, links, comments, "");
  }

  private static void writeParagraph(
      final StringBuilder b,
      Paragraph p,
      DocxLists lists,
      DocxTabs tabs,
      final DocxLinks links,
      DocxComments comments,
      String section) {
    writeParagraph(b, p, lists, tabs, links, comments, section, "", "");
  }

  private static void writeParagraph(
      StringBuilder b,
      Paragraph p,
      DocxLists lists,
      DocxTabs tabs,
      DocxLinks links,
      DocxComments comments,
      String section,
      String prefix,
      String suffix) {
    if (!p.format.dropCap.isEmpty() && p.text.length() > 1 && !p.text.objectAt(0)
        && !Character.isWhitespace(p.text.charAt(0))) {
      // Word keeps a drop cap as its own framed paragraph holding the letter.
      int skip = Graphemes.next(p.text, 0);
      var base = p.text.styleAt(0);
      float size = base.size * ((p.format.dropLines() - 1) * 1.2f + .9f) / .7f;
      var letter = p.text.slice(0, skip).format(0, skip, "size", String.valueOf(Math.round(size)));
      if (!p.format.dropFont().isEmpty()) letter = letter.format(0, skip, "font", p.format.dropFont());
      writeParagraph(b, new Paragraph(p.id + "-dropcap", letter).with("dropCap", p.format.dropCap)
          .with("before", "0").with("after", "0"), lists, tabs, links, comments, "", "", "");
      p = p.text(p.text.slice(skip, p.text.length())).with("dropCap", "");
    }
    // A style's definition is written by this same code from its own paragraph, with every
    // element below always present (Asian options when off). The reader applies the style's
    // pPr and then the direct one, so an element whose inputs equal the style's is omitted.
    var styleDefinition = links.styles == null ? null : links.styles.get(p.style);
    Paragraph base = styleDefinition == null ? null : styleDefinition.paragraph;
    b.append("<w:p><w:pPr><w:pStyle w:val=\"").append(Xml.escape(p.style)).append("\"/>");
    if (base == null || !p.align.equals(base.align))
      b.append("<w:jc w:val=\"")
          .append(Xml.escape("justify".equals(p.align) ? "both" : p.align))
          .append("\"/>");
    if (base == null
        || p.before != base.before
        || p.after != base.after
        || p.lineSpacing != base.lineSpacing
        || !p.format.lineRule.equals(base.format.lineRule))
      b.append("<w:spacing w:before=\"")
        .append(Math.round(p.before * 20))
        .append("\" w:after=\"")
        .append(Math.round(p.after * 20))
        .append("\" w:line=\"")
        .append(Math.round(p.lineSpacing * ("auto".equals(p.format.lineRule) ? 240 : 20)))
        .append("\" w:lineRule=\"")
        .append(p.format.lineRule)
        .append("\"/>");
    if (base == null
        || p.indent != base.indent
        || !p.list.equals(base.list)
        || p.listLevel != base.listLevel
        || p.format.rightIndent != base.format.rightIndent
        || p.format.firstIndent != base.format.firstIndent)
      b.append("<w:ind w:left=\"")
        .append(Math.round((p.indent + (p.list.isEmpty() ? 0 : 18 * (p.listLevel + 1))) * 20))
        .append("\" w:right=\"")
        .append(Math.round(p.format.rightIndent * 20))
        .append(p.format.firstIndent < 0 ? "\" w:hanging=\"" : "\" w:firstLine=\"")
        .append(Math.round(Math.abs(p.format.firstIndent) * 20))
        .append("\"/>");
    if (lists.ids.containsKey(p))
      b.append("<w:numPr><w:ilvl w:val=\"")
          .append(p.listLevel)
          .append("\"/><w:numId w:val=\"")
          .append(lists.ids.get(p))
          .append("\"/></w:numPr>");
    if (p.pageBreak) b.append("<w:pageBreakBefore/>");
    if (p.keepNext) b.append("<w:keepNext/>");
    if (p.format.outlineLevel >= 0)
      b.append("<w:outlineLvl w:val=\"")
          .append(p.format.outlineLevel == 0 ? 9 : p.format.outlineLevel - 1)
          .append("\"/>");
    if (base == null || p.format.keepLines != base.format.keepLines)
      b.append(p.format.keepLines ? "<w:keepLines/>" : "<w:keepLines w:val=\"0\"/>");
    if (!p.format.dropCap.isEmpty())
      b.append("<w:framePr w:dropCap=\"").append(p.format.dropMode()).append("\" w:lines=\"")
          .append(p.format.dropLines()).append("\" w:hSpace=\"").append(Math.round(p.format.dropDistance() * 20))
          .append("\" w:wrap=\"around\" w:vAnchor=\"text\" w:hAnchor=\"text\"/>");
    if (base == null || p.format.widowControl != base.format.widowControl)
      b.append(p.format.widowControl ? "<w:widowControl/>" : "<w:widowControl w:val=\"0\"/>");
    for (String option : ParagraphFormat.ASIAN)
      if (!p.format.asian(option) && (base == null || base.format.asian(option)))
        b.append("<w:").append(option).append(" w:val=\"0\"/>");
    if (p.format.rtl) b.append("<w:bidi/>");
    DocxBorders.write(b, p.format);
    tabs.write(b, p);
    if (p.breakRevision != null) {
      b.append("<w:rPr>");
      for (var r = p.breakRevision; r != null; r = r.previous) {
        RevisionFormats.start(b, r.previous(null));
        RevisionFormats.end(b, r.previous(null));
      }
      b.append("</w:rPr>");
    }
    if (p.formatRevision != null) {
      var change = p.formatRevision;
      b.append("<w:pPrChange w:id=\"")
          .append(Xml.escape(change.id))
          .append("\" w:author=\"")
          .append(Xml.escape(change.author))
          .append("\" w:date=\"")
          .append(Xml.escape(change.date))
          .append("\">");
      var previous = new StringBuilder();
      writeParagraph(
          previous, p.previousFormat, lists, tabs, links, new DocxComments(Document.empty()));
      var content = previous.toString();
      b.append(content.substring(content.indexOf("<w:pPr>"), content.lastIndexOf("</w:pPr>") + 8));
      b.append("</w:pPrChange>");
    }
    b.append(section).append("</w:pPr>");
    if (p.format.columnBreak) b.append("<w:r><w:br w:type=\"column\"/></w:r>");
    b.append(prefix);
    var outer = links.base;
    var style = links.styles == null ? null : links.styles.get(p.style);
    links.base = style == null ? null : style.text;
    comments.content(b, p, links);
    links.base = outer;
    b.append(suffix).append("</w:p>");
  }

  /**
   * A document has few distinct run styles: reuse the markup per style, and per (paragraph-style
   * base, style) pair for the compact form. TextStyle is immutable with a cached hash.
   */
  private static final Map<TextStyle, String> RUN_PROPERTIES = new HashMap<>();

  private static final Map<TextStyle, Map<TextStyle, String>> RUN_DIFFERENCES = new HashMap<>();

  static void writeRunProperties(StringBuilder b, TextStyle s) {
    String cached;
    synchronized (RUN_PROPERTIES) {
      cached = RUN_PROPERTIES.get(s);
    }
    if (cached == null) {
      var x = new StringBuilder(640);
      buildRunProperties(x, s, null);
      cached = x.toString();
      synchronized (RUN_PROPERTIES) {
        if (RUN_PROPERTIES.size() > 512) RUN_PROPERTIES.clear();
        RUN_PROPERTIES.put(s, cached);
      }
    }
    b.append(cached);
  }

  /**
   * Writes only the properties that differ from {@code base}, the paragraph style's run format
   * as written to styles.xml (every style carries a complete rPr and no basedOn, so the reader
   * resolves an omitted property to exactly that value). Null base writes everything.
   */
  static void writeRunProperties(StringBuilder b, TextStyle s, TextStyle base) {
    if (base == null || s.formatRevision != null) {
      writeRunProperties(b, s);
      return;
    }
    String cached;
    synchronized (RUN_DIFFERENCES) {
      var forBase = RUN_DIFFERENCES.get(base);
      cached = forBase == null ? null : forBase.get(s);
    }
    if (cached == null) {
      var x = new StringBuilder(160);
      buildRunProperties(x, s, base);
      cached = x.toString();
      synchronized (RUN_DIFFERENCES) {
        if (RUN_DIFFERENCES.size() > 64) RUN_DIFFERENCES.clear();
        var forBase = RUN_DIFFERENCES.get(base);
        if (forBase == null) RUN_DIFFERENCES.put(base, forBase = new HashMap<>());
        if (forBase.size() > 512) forBase.clear();
        forBase.put(s, cached);
      }
    }
    b.append(cached);
  }

  private static final int BORDER = 16, LANGUAGE = 17;

  /** The rPr children in a fixed order; optional ones are "" when absent. */
  private static String[] runProperties(TextStyle s) {
    var c = s.characters;
    String[] tags = {
      "dstrike", "smallCaps", "caps", "vanish", "outline", "shadow", "emboss", "imprint"
    };
    boolean[] flags = {
      c.doubleStrike, c.smallCaps, c.allCaps, c.hidden, c.outline, c.shadow, c.emboss, c.engrave
    };
    var out = new String[22];
    out[0] = "<w:rFonts w:ascii=\"" + Xml.escape(s.font) + "\" w:hAnsi=\"" + Xml.escape(s.font) + "\"/>";
    out[1] = "<w:sz w:val=\"" + Math.round(s.size * 2) + "\"/>";
    out[2] = "<w:color w:val=\"" + TextUtil.padded(s.color, 16, 6) + "\"/>";
    out[3] =
        "<w:shd w:fill=\""
            + (c.shading >= 0 ? c.value("shading") : TextUtil.padded(s.background, 16, 6))
            + "\"/>";
    out[4] = s.bold ? "<w:b/>" : "<w:b w:val=\"0\"/>";
    out[5] = s.italic ? "<w:i/>" : "<w:i w:val=\"0\"/>";
    out[6] = "<w:u w:val=\"" + c.underlineStyle + "\" w:color=\"" + c.value("underlineColor") + "\"/>";
    out[7] = s.strike ? "<w:strike/>" : "<w:strike w:val=\"0\"/>";
    for (int i = 0; i < tags.length; i++)
      out[8 + i] = "<w:" + tags[i] + " w:val=\"" + (flags[i] ? "1" : "0") + "\"/>";
    out[BORDER] =
        c.border >= 0
            ? "<w:bdr w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"" + c.value("border") + "\"/>"
            : "";
    out[LANGUAGE] = c.lang.isEmpty() ? "" : "<w:lang w:val=\"" + Xml.escape(c.lang) + "\"/>";
    out[18] = "<w:spacing w:val=\"" + Math.round(c.spacing * 20) + "\"/>";
    out[19] = "<w:w w:val=\"" + Math.round(c.scale) + "\"/>";
    out[20] = "<w:position w:val=\"" + Math.round(c.position * 2) + "\"/>";
    out[21] = "<w:vertAlign w:val=\"" + s.script + "\"/>";
    return out;
  }

  private static void buildRunProperties(StringBuilder b, TextStyle s, TextStyle base) {
    String[] own = runProperties(s), inherited = base == null ? null : runProperties(base);
    // An absent border or language cannot be expressed as a difference: write everything.
    if (inherited != null
        && ((own[BORDER].isEmpty() && !inherited[BORDER].isEmpty())
            || (own[LANGUAGE].isEmpty() && !inherited[LANGUAGE].isEmpty()))) inherited = null;
    int start = b.length();
    b.append("<w:rPr>");
    int open = b.length();
    for (int i = 0; i < own.length; i++)
      if (inherited == null || !own[i].equals(inherited[i])) b.append(own[i]);
    if (s.formatRevision != null) {
      RevisionFormats.formatStart(b, s.formatRevision);
      writeRunProperties(b, s.previousStyle);
      b.append("</w:rPrChange>");
    }
    if (b.length() == open) b.setLength(start);
    else b.append("</w:rPr>");
  }

  static void writeRun(StringBuilder b, String text, TextStyle s, DocxLinks links) {
    writeRun(b, text, s, links, s.revision != null && !s.revision.inserted());
  }

  static void writeRun(
      StringBuilder b, String text, TextStyle s, DocxLinks links, boolean deleted) {
    RevisionFormats.start(b, s.revision);
    if (!s.link.isEmpty()) {
      if (s.link.startsWith("#"))
        b.append("<w:hyperlink w:anchor=\"").append(Xml.escape(s.link.substring(1))).append("\">");
      else b.append("<w:hyperlink r:id=\"").append(Xml.escape(links.id(s.link))).append("\">");
    }
    b.append("<w:r>");
    writeRunProperties(b, s, links == null ? null : links.base);
    String[] lines = TextUtil.split(text, '\n');
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) b.append("<w:br/>");
      String[] tabs = TextUtil.split(lines[i], '\t');
      for (int j = 0; j < tabs.length; j++) {
        if (j > 0) b.append("<w:tab/>");
        String tag = deleted ? "delText" : "t";
        b.append("<w:")
            .append(tag)
            .append(" xml:space=\"preserve\">")
            .append(Xml.escape(tabs[j]))
            .append("</w:")
            .append(tag)
            .append(">");
      }
    }
    b.append("</w:r>");
    if (!s.link.isEmpty()) b.append("</w:hyperlink>");
    RevisionFormats.end(b, s.revision);
  }
}
