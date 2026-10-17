package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Versioned lossless application format. Unknown versions are rejected. */
public final class DocumentXml {
  private DocumentXml() {}

  public static String write(Document d) {
    StringBuilder b =
        new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><document version=\"1\" title=\"")
            .append(Xml.escape(d.title))
            .append("\" next=\"")
            .append(d.nextId)
            .append("\" trackChanges=\"")
            .append(d.trackChanges)
            .append("\">");
    BibliographyFormats.nativeWrite(b, d.bibliography);
    if (!d.properties.equals(DocumentProperties.EMPTY)) {
      b.append("<properties protection=\"").append(d.properties.protection).append('"');
      for (String key : DocumentProperties.KEYS)
        if (!d.properties.get(key).isEmpty())
          b.append(' ').append(key).append("=\"").append(Xml.escape(d.properties.get(key))).append('"');
      b.append(">");
      for (Map.Entry<String, String> e : d.properties.settings().entrySet())
        b.append("<setting key=\"").append(Xml.escape(e.getKey())).append("\" value=\"")
            .append(Xml.escape(e.getValue())).append("\"/>");
      b.append("</properties>");
    }
    PageSpec p = d.page;
    page(b, p);
    furniture(b, d.furniture);
    b.append("<noteOptions>");
    NoteFormats.write(b, d.notes, "");
    b.append("</noteOptions>");
    b.append("<sections start=\"")
        .append(d.lastSection.start)
        .append("\" pageStart=\"")
        .append(d.lastSection.pageStart)
        .append("\">");
    NoteFormats.write(b, d.lastSection.notes, "");
    columns(b, d.lastSection.columns);
    decor(b, d.lastSection.decor);
    for (Map.Entry<String, Section> entry : d.sections.entrySet()) {
      Section section = entry.getValue();
      b.append("<section end=\"")
          .append(Xml.escape(entry.getKey()))
          .append("\" start=\"")
          .append(section.start)
          .append("\" pageStart=\"")
          .append(section.pageStart)
          .append("\">");
      page(b, section.page);
      furniture(b, section.furniture);
      NoteFormats.write(b, section.notes, "");
      columns(b, section.columns);
      decor(b, section.decor);
      b.append("</section>");
    }
    b.append("</sections>");
    b.append("<styles>");
    for (ParagraphStyle style : d.styles.values()) {
      b.append("<style id=\"")
          .append(Xml.escape(style.id))
          .append("\" name=\"")
          .append(Xml.escape(style.name))
          .append("\">");
      paragraph(b, style.paragraph);
      b.append("</style>");
    }
    b.append("</styles>");
    for (Block block : d.blocks) {
      if (block instanceof Paragraph) paragraph(b, (Paragraph) block);
      else if (block instanceof Table) {
        Table t = (Table) block;
        b.append("<table id=\"").append(Xml.escape(t.id())).append("\">");
        for (int r = 0; r < t.rows(); r++) {
          b.append("<row>");
          for (int c = 0; c < t.columns(); c++) {
            b.append("<cell>");
            for (Paragraph para : t.cell(r, c)) paragraph(b, para);
            b.append("</cell>");
          }
          b.append("</row>");
        }
        for (Table.Merge m : t.merges)
          b.append("<merge row=\"")
              .append(m.row)
              .append("\" column=\"")
              .append(m.column)
              .append("\" rows=\"")
              .append(m.rows)
              .append("\" columns=\"")
              .append(m.columns)
              .append("\"/>");
        TableFormats.nativeXml(b, t.format);
        b.append("</table>");
      } else if (block instanceof Picture) {
        Picture picture = (Picture) block;
        b.append("<picture id=\"")
            .append(Xml.escape(picture.id()))
            .append("\" width=\"")
            .append(picture.width)
            .append("\" height=\"")
            .append(picture.height)
            .append("\" align=\"")
            .append(picture.align)
            .append("\" alt=\"")
            .append(Xml.escape(picture.alt))
            .append("\"");
        Picture.Look look = picture.look;
        if (!Picture.Look.NONE.equals(look))
          b.append(" crop=\"")
              .append(look.cropLeft)
              .append(',')
              .append(look.cropTop)
              .append(',')
              .append(look.cropRight)
              .append(',')
              .append(look.cropBottom)
              .append("\" border=\"")
              .append(look.borderColor)
              .append(',')
              .append(look.borderWidth)
              .append("\" rotation=\"")
              .append(look.rotation)
              .append("\" flip=\"")
              .append(look.flipH ? "h" : "")
              .append(look.flipV ? "v" : "")
              .append('"');
        if (look != null && look.adjusted())
          b.append(" adjust=\"").append(look.brightness).append(',').append(look.contrast).append(',')
              .append(look.transparency).append(',').append(look.colorMode).append(',')
              .append(look.effect).append(',').append(look.backgroundTolerance).append('"');
        if (picture.drawing != null)
          b.append(" drawing=\"").append(Xml.attribute(picture.drawing.encode())).append('"');
        b.append(">")
            .append(com.codename1.util.Base64.encodeNoNewline(picture.bytes()))
            .append("</picture>");
      } else throw new IllegalArgumentException("Unknown block");
    }
    for (Bookmark mark : d.bookmarks.values()) {
      b.append("<bookmark name=\"")
          .append(Xml.escape(mark.name))
          .append("\" startId=\"")
          .append(Xml.escape(mark.startId))
          .append("\" start=\"")
          .append(mark.start)
          .append("\" endId=\"")
          .append(Xml.escape(mark.endId))
          .append("\" end=\"")
          .append(mark.end)
          .append("\"/>");
    }
    for (ReviewComment c : d.comments) {
      b.append("<comment id=\"")
          .append(Xml.escape(c.id))
          .append("\" author=\"")
          .append(Xml.escape(c.author))
          .append("\" startId=\"")
          .append(Xml.escape(c.startId))
          .append("\" start=\"")
          .append(c.start)
          .append("\" endId=\"")
          .append(Xml.escape(c.endId))
          .append("\" end=\"")
          .append(c.end)
          .append("\" resolved=\"")
          .append(c.resolved)
          .append("\"><text>")
          .append(Xml.escape(c.text))
          .append("</text>");
      for (ReviewComment.Reply reply : c.replies)
        b.append("<reply author=\"")
            .append(Xml.escape(reply.author))
            .append("\">")
            .append(Xml.escape(reply.text))
            .append("</reply>");
      b.append("</comment>");
    }
    return b.append("</document>").toString();
  }

  private static void page(StringBuilder b, PageSpec p) {
    b.append("<page width=\"")
        .append(p.width)
        .append("\" height=\"")
        .append(p.height)
        .append("\" top=\"")
        .append(p.top)
        .append("\" right=\"")
        .append(p.right)
        .append("\" bottom=\"")
        .append(p.bottom)
        .append("\" left=\"")
        .append(p.left)
        .append("\"/>");
  }

  private static void furniture(StringBuilder b, PageFurniture furniture) {
    b.append("<furniture first=\"")
        .append(furniture.differentFirst)
        .append("\" even=\"")
        .append(furniture.differentEven)
        .append("\" header=\"")
        .append(furniture.headerDistance)
        .append("\" footer=\"")
        .append(furniture.footerDistance)
        .append("\">");
    for (String key : PageFurniture.KEYS)
      if (furniture.stories.containsKey(key)) {
        b.append("<story key=\"").append(key).append("\">");
        for (Paragraph para : furniture.get(key)) paragraph(b, para);
        b.append("</story>");
      }
    b.append("</furniture>");
  }

  private static PageSpec readPage(Element page) throws IOException {
    if (page == null) throw new IOException("Missing section page geometry");
    return new PageSpec(
        f(page, "width"),
        f(page, "height"),
        f(page, "top"),
        f(page, "right"),
        f(page, "bottom"),
        f(page, "left"));
  }

  private static PageFurniture readFurniture(Element e, Set<String> ids) throws IOException {
    if (e == null) return PageFurniture.EMPTY;
    Map<String, Sequence<Paragraph>> stories = new LinkedHashMap<String, Sequence<Paragraph>>();
    for (Element story : Xml.children(e, "story")) {
      String key = Xml.attr(story, "key", "");
      if (stories.containsKey(key)) throw new IOException("Duplicate header/footer story");
      Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
      for (Element para : Xml.children(story, "p"))
        paragraphs = paragraphs.append(readParagraph(para, ids));
      stories.put(key, paragraphs);
    }
    return new PageFurniture(
        stories, flag(e, "first"), flag(e, "even"), f(e, "header"), f(e, "footer"));
  }

  private static List<ReviewComment.Reply> readReplies(Element comment) {
    List<ReviewComment.Reply> replies = new ArrayList<ReviewComment.Reply>();
    for (Element r : Xml.children(comment, "reply"))
      replies.add(new ReviewComment.Reply(Xml.attr(r, "author", "Author"), Xml.text(r)));
    return replies;
  }

  private static void paragraph(final StringBuilder b, Paragraph p) {
    b.append("<p id=\"")
        .append(Xml.escape(p.id))
        .append("\" style=\"")
        .append(Xml.escape(p.style))
        .append("\" align=\"")
        .append(Xml.escape(p.align))
        .append("\" list=\"")
        .append(Xml.escape(p.list))
        .append("\" listLevel=\"")
        .append(p.listLevel)
        .append("\" listStart=\"")
        .append(p.listStart)
        .append("\" listFormat=\"")
        .append(p.listFormat)
        .append("\" before=\"")
        .append(p.before)
        .append("\" after=\"")
        .append(p.after)
        .append("\" indent=\"")
        .append(p.indent)
        .append("\" spacing=\"")
        .append(p.lineSpacing)
        .append("\" lineRule=\"")
        .append(p.format.lineRule)
        .append("\" pageBreak=\"")
        .append(p.pageBreak)
        .append("\" keepNext=\"")
        .append(p.keepNext)
        .append("\" rightIndent=\"")
        .append(p.format.rightIndent)
        .append("\" firstIndent=\"")
        .append(p.format.firstIndent)
        .append("\" tabInterval=\"")
        .append(p.format.tabInterval)
        .append("\" tabs=\"")
        .append(TabStop.encode(p.format.tabs))
        .append("\" keepLines=\"")
        .append(p.format.keepLines)
        .append("\" widowControl=\"")
        .append(p.format.widowControl)
        .append("\" outlineLevel=\"")
        .append(p.format.outlineLevel)
        .append("\"");
    if (p.format.columnBreak) b.append(" columnBreak=\"true\"");
    if (p.format.rtl) b.append(" rtl=\"true\"");
    if (!p.format.asianOff.isEmpty()) b.append(" asianOff=\"").append(p.format.asianOff).append('"');
    if (!p.format.dropCap.isEmpty()) b.append(" dropCap=\"").append(Xml.escape(p.format.dropCap)).append('"');
    if (!p.format.borders.isEmpty())
      b.append(" borders=\"").append(p.format.borders.encode()).append('"');
    if (p.format.shading >= 0)
      b.append(" shading=\"")
          .append(Integer.toHexString(0x1000000 | p.format.shading).substring(1))
          .append('"');
    RevisionFormats.attributes(b, p.breakRevision, "break-");
    RevisionFormats.attributes(b, p.formatRevision, "format-");
    b.append(">");
    if (p.previousFormat != null) {
      b.append("<previousFormat>");
      paragraph(b, p.previousFormat);
      b.append("</previousFormat>");
    }
    p.text.visit(
        new RichText.Visitor() {
          public void note(int o, Note note, TextStyle style) {
            b.append("<note id=\"")
                .append(Xml.escape(note.id))
                .append("\" kind=\"")
                .append(note.kind)
                .append("\" mark=\"")
                .append(Xml.escape(note.mark))
                .append("\" label=\"")
                .append(Xml.escape(note.label))
                .append("\">");
            run(o, "", style);
            for (Paragraph paragraph : note.paragraphs) paragraph(b, paragraph);
            b.append("</note>");
          }

          public void field(int o, InlineField field, TextStyle style) {
            b.append("<field instruction=\"")
                .append(Xml.escape(field.instruction))
                .append("\" result=\"")
                .append(Xml.escape(field.result))
                .append("\" locked=\"")
                .append(field.locked)
                .append("\" showCode=\"")
                .append(field.showCode)
                .append("\">");
            run(o, "", style);
            b.append("</field>");
          }

          public void run(int o, String text, TextStyle s) {
            b.append("<r font=\"")
                .append(Xml.escape(s.font))
                .append("\" size=\"")
                .append(s.size)
                .append("\" color=\"")
                .append(Integer.toHexString(s.color))
                .append("\" background=\"")
                .append(Integer.toHexString(s.background))
                .append("\" bold=\"")
                .append(s.bold)
                .append("\" italic=\"")
                .append(s.italic)
                .append("\" underline=\"")
                .append(s.underline)
                .append("\" strike=\"")
                .append(s.strike)
                .append("\" link=\"")
                .append(Xml.escape(s.link))
                .append("\" script=\"")
                .append(s.script)
                .append("\"");
            RevisionFormats.characterAttributes(b, s, "");
            RevisionFormats.attributes(b, s.revision, "revision-");
            RevisionFormats.attributes(b, s.formatRevision, "format-");
            if (s.previousStyle != null)
              RevisionFormats.styleAttributes(b, s.previousStyle, "before-");
            b.append(">").append(Xml.escape(text)).append("</r>");
          }
        });
    b.append("</p>");
  }

  private static Picture.Look pictureLook(Element c) {
    if (Xml.attr(c, "crop", "").isEmpty()) return Picture.Look.NONE;
    String[] crop = TextUtil.split(Xml.attr(c, "crop", "0,0,0,0"), ',');
    String[] border = TextUtil.split(Xml.attr(c, "border", "-1,0"), ',');
    String flip = Xml.attr(c, "flip", "");
    return new Picture.Look(
        Float.parseFloat(crop[0]),
        Float.parseFloat(crop[1]),
        Float.parseFloat(crop[2]),
        Float.parseFloat(crop[3]),
        Integer.parseInt(border[0]),
        Float.parseFloat(border[1]),
        Integer.parseInt(Xml.attr(c, "rotation", "0")),
        flip.indexOf('h') >= 0,
        flip.indexOf('v') >= 0);
  }

  private static Picture.Look pictureAdjust(Element c, Picture.Look look) {
    String value = Xml.attr(c, "adjust", "");
    if (value.isEmpty()) return look;
    String[] v = TextUtil.split(value, ',');
    Picture.Look k = look.adjust(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), v[3]);
    if (v.length > 5) k = k.effect(v[4]).removeBackground(Integer.parseInt(v[5]));
    return k;
  }

  public static Document read(String xml) throws IOException {
    Element e = Xml.parse(xml);
    if (!"document".equals(Xml.local(e)) || !"1".equals(Xml.attr(e, "version", "")))
      throw new IOException("Unsupported document version");
    try {
      Element page = Xml.child(e, "page");
      if (page == null) throw new IOException("Missing page geometry");
      PageSpec spec =
          new PageSpec(
              f(page, "width"),
              f(page, "height"),
              f(page, "top"),
              f(page, "right"),
              f(page, "bottom"),
              f(page, "left"));
      Sequence<Block> blocks = new Sequence<Block>();
      Set<String> ids = new HashSet<String>();
      PageFurniture furniture = PageFurniture.EMPTY;
      List<ReviewComment> comments = new ArrayList<ReviewComment>();
      Map<String, Bookmark> bookmarks = new LinkedHashMap<String, Bookmark>();
      Map<String, ParagraphStyle> definitions =
          new LinkedHashMap<String, ParagraphStyle>(ParagraphStyle.defaults());
      for (Element c : Xml.children(e, null)) {
        if ("p".equals(Xml.local(c))) blocks = blocks.append(readParagraph(c, ids));
        else if ("table".equals(Xml.local(c))) {
          List<List<Sequence<Paragraph>>> rows = new ArrayList<List<Sequence<Paragraph>>>();
          for (Element row : Xml.children(c, "row")) {
            List<Sequence<Paragraph>> cells = new ArrayList<Sequence<Paragraph>>();
            for (Element cell : Xml.children(row, "cell")) {
              Sequence<Paragraph> paras = new Sequence<Paragraph>();
              for (Element p : Xml.children(cell, "p")) paras = paras.append(readParagraph(p, ids));
              cells.add(paras);
            }
            rows.add(cells);
          }
          List<Table.Merge> merges = new ArrayList<Table.Merge>();
          for (Element m : Xml.children(c, "merge"))
            merges.add(
                new Table.Merge(
                    Integer.parseInt(Xml.attr(m, "row", "-1")),
                        Integer.parseInt(Xml.attr(m, "column", "-1")),
                    Integer.parseInt(Xml.attr(m, "rows", "1")),
                        Integer.parseInt(Xml.attr(m, "columns", "1"))));
          blocks =
              blocks.append(
                  new Table(
                      id(c, ids),
                      rows,
                      merges,
                      TableFormats.nativeRead(c, rows.size(), rows.get(0).size())));
        } else if ("picture".equals(Xml.local(c))) {
          blocks =
              blocks.append(
                  new Picture(
                      id(c, ids),
                      com.codename1.util.Base64.decode(Xml.text(c).getBytes("UTF-8")),
                      f(c, "width"),
                      f(c, "height"),
                      Xml.attr(c, "alt", ""),
                      Xml.attr(c, "align", "left"),
                      pictureAdjust(c, pictureLook(c))));
          if (!Xml.attr(c, "drawing", "").isEmpty()) {
            Picture last = (Picture) blocks.get(blocks.size() - 1);
            blocks =
                blocks.set(
                    blocks.size() - 1, last.drawing(Drawing.decode(Xml.attr(c, "drawing", ""))));
          }
        } else if ("bookmark".equals(Xml.local(c))) {
          Bookmark mark =
              new Bookmark(
                  Xml.attr(c, "name", ""),
                  Xml.attr(c, "startId", ""),
                  Integer.parseInt(Xml.attr(c, "start", "0")),
                  Xml.attr(c, "endId", ""),
                  Integer.parseInt(Xml.attr(c, "end", "0")));
          if (bookmarks.put(mark.name, mark) != null)
            throw new IOException("Duplicate bookmark name");
        } else if ("comment".equals(Xml.local(c)))
          comments.add(
              new ReviewComment(
                  id(c, ids),
                  Xml.attr(c, "author", "Author"),
                  Xml.child(c, "text") == null ? Xml.text(c) : Xml.text(Xml.child(c, "text")),
                  Xml.attr(c, "startId", ""),
                  Integer.parseInt(Xml.attr(c, "start", "0")),
                  Xml.attr(c, "endId", ""),
                  Integer.parseInt(Xml.attr(c, "end", "0")),
                  flag(c, "resolved"),
                  readReplies(c)));
        else if ("furniture".equals(Xml.local(c))) {
          furniture = readFurniture(c, ids);
        } else if ("sections".equals(Xml.local(c))
            || "noteOptions".equals(Xml.local(c))
            || "bibliography".equals(Xml.local(c))
            || "properties".equals(Xml.local(c))) {
          // Read after body IDs have been collected.
        } else if ("styles".equals(Xml.local(c))) {
          for (Element style : Xml.children(c, "style")) {
            Paragraph p = readParagraph(Xml.child(style, "p"), new HashSet<String>());
            String key = Xml.attr(style, "id", "");
            definitions.put(
                key, new ParagraphStyle(key, Xml.attr(style, "name", key), p, p.text.styleAt(0)));
          }
        } else if (!"page".equals(Xml.local(c))) throw new IOException("Unknown document element");
      }
      long next = Long.parseLong(Xml.attr(e, "next", "1"));
      for (String id : ids)
        if ((id.startsWith("p") || id.startsWith("t") || id.startsWith("c") || id.startsWith("i"))
            && TextUtil.digits(id, 1)) next = Math.max(next, Long.parseLong(id.substring(1)) + 1);
      Document result =
          new Document(blocks, spec, Xml.attr(e, "title", "Untitled"), next, comments)
              .styles(definitions)
              .furniture(furniture)
              .bookmarks(bookmarks)
              .notes(NoteFormats.read(Xml.child(e, "noteOptions"), null))
              .bibliography(BibliographyFormats.nativeRead(Xml.child(e, "bibliography")))
              .properties(readProperties(Xml.child(e, "properties")));
      Element sectionRoot = Xml.child(e, "sections");
      if (sectionRoot != null) {
        Map<String, Section> sections = new LinkedHashMap<String, Section>();
        for (Element item : Xml.children(sectionRoot, "section")) {
          String end = Xml.attr(item, "end", "");
          if (sections.containsKey(end)) throw new IOException("Duplicate section boundary");
          sections.put(
              end,
              new Section(
                  readPage(Xml.child(item, "page")),
                  readFurniture(Xml.child(item, "furniture"), ids),
                  Xml.attr(item, "start", "nextPage"),
                  Integer.parseInt(Xml.attr(item, "pageStart", "0")),
                  NoteFormats.read(item, null),
                  readColumns(item),
                  readDecor(item)));
        }
        result =
            result.sections(
                sections,
                new Section(
                    spec,
                    furniture,
                    Xml.attr(sectionRoot, "start", "nextPage"),
                    Integer.parseInt(Xml.attr(sectionRoot, "pageStart", "0")),
                    NoteFormats.read(sectionRoot, null),
                    readColumns(sectionRoot),
                    readDecor(sectionRoot)));
        for (String id : ids)
          if (id.startsWith("p") && TextUtil.digits(id, 1))
            next = Math.max(next, Long.parseLong(id.substring(1)) + 1);
        result = result.blocks(result.blocks, next);
      }
      for (ReviewComment c : comments) {
        if (!ReviewComment.validRange(result, c.startId, c.start, c.endId, c.end))
          throw new IOException("Invalid comment anchor");
      }
      return RevisionFormats.reserveIds(result.trackChanges(flag(e, "trackChanges")));
    } catch (IllegalArgumentException ex) {
      throw new IOException("Invalid document values", ex);
    }
  }

  private static String id(Element e, Set<String> ids) throws IOException {
    String id = Xml.attr(e, "id", "");
    if (id.isEmpty() || !ids.add(id)) throw new IOException("Missing or duplicate block id");
    return id;
  }

  private static float f(Element e, String k) {
    return Float.parseFloat(Xml.attr(e, k, "0"));
  }

  private static boolean flag(Element e, String k) {
    return "true".equals(Xml.attr(e, k, "false"));
  }

  private static Paragraph readParagraph(Element e, Set<String> ids) throws IOException {
    RichText text = new RichText();
    for (Element node : Xml.children(e, null)) {
      if ("previousFormat".equals(Xml.local(node))) continue;
      boolean field = "field".equals(Xml.local(node));
      boolean note = "note".equals(Xml.local(node));
      if (!field && !note && !"r".equals(Xml.local(node)))
        throw new IOException("Unknown paragraph content");
      Element r = field || note ? Xml.child(node, "r") : node;
      if (r == null) throw new IOException("Missing field character formatting");
      TextStyle s =
          new TextStyle(
                  Xml.attr(r, "font", "Serif"),
                  Float.parseFloat(Xml.attr(r, "size", "12")),
                  Integer.parseInt(Xml.attr(r, "color", "202833"), 16),
                  flag(r, "bold"),
                  flag(r, "italic"),
                  flag(r, "underline"),
                  flag(r, "strike"),
                  Integer.parseInt(Xml.attr(r, "background", "ffffff"), 16),
                  Xml.attr(r, "link", ""),
                  Xml.attr(r, "script", "baseline"))
              .revision(RevisionFormats.nativeRead(r, "revision-"));
      s = RevisionFormats.readCharacters(r, "", s);
      Revision formatting = RevisionFormats.nativeRead(r, "format-");
      if (formatting != null)
        s = s.formatRevision(formatting, RevisionFormats.readStyle(r, "before-"));
      if (note) {
        String noteId = id(node, ids);
        Sequence<Paragraph> story = new Sequence<Paragraph>();
        for (Element p : Xml.children(node, "p")) story = story.append(readParagraph(p, ids));
        text =
            text.concat(
                RichText.note(
                    new Note(
                        noteId,
                        Xml.attr(node, "kind", "footnote"),
                        Xml.attr(node, "mark", ""),
                        Xml.attr(node, "label", "1"),
                        story),
                    s));
        continue;
      }
      text =
          text.concat(
              field
                  ? RichText.field(
                      new InlineField(
                          Xml.attr(node, "instruction", ""),
                          Xml.attr(node, "result", ""),
                          flag(node, "locked"),
                          flag(node, "showCode")),
                      s)
                  : RichText.of(Xml.text(r), s));
    }
    Paragraph result =
        new Paragraph(
                id(e, ids),
                text,
                Xml.attr(e, "style", "Normal"),
                Xml.attr(e, "align", "left"),
                Xml.attr(e, "list", ""),
                f(e, "before"),
                f(e, "after"),
                f(e, "indent"),
                Float.parseFloat(Xml.attr(e, "spacing", "1.2")),
                flag(e, "pageBreak"),
                flag(e, "keepNext"),
                Integer.parseInt(Xml.attr(e, "listLevel", "0")),
                Integer.parseInt(Xml.attr(e, "listStart", "0")),
                Xml.attr(e, "listFormat", "decimal"),
                new ParagraphFormat(
                    f(e, "rightIndent"),
                    f(e, "firstIndent"),
                    Float.parseFloat(Xml.attr(e, "tabInterval", "36")),
                    flag(e, "keepLines"),
                    Boolean.parseBoolean(Xml.attr(e, "widowControl", "true")),
                    TabStop.decode(Xml.attr(e, "tabs", "")),
                    Integer.parseInt(Xml.attr(e, "outlineLevel", "-1")),
                    Xml.attr(e, "lineRule", "auto"),
                    flag(e, "columnBreak"),
                    ParagraphBorders.decode(Xml.attr(e, "borders", "")),
                    Xml.attr(e, "shading", "").isEmpty()
                        ? -1
                        : Integer.parseInt(Xml.attr(e, "shading", ""), 16),
                    flag(e, "rtl"),
                    Xml.attr(e, "asianOff", ""),
                    Xml.attr(e, "dropCap", "")))
            .breakRevision(RevisionFormats.nativeRead(e, "break-"));
    Revision change = RevisionFormats.nativeRead(e, "format-");
    if (change != null) {
      Element previous = Xml.child(e, "previousFormat");
      if (previous == null || Xml.child(previous, "p") == null)
        throw new IOException("Missing previous paragraph properties");
      result =
          result.formatRevision(
              change, readParagraph(Xml.child(previous, "p"), new HashSet<String>()));
    }
    return result;
  }

  private static void columns(StringBuilder b, Columns c) {
    if (c.equals(Columns.ONE)) return;
    b.append("<columns count=\"").append(c.count).append("\" space=\"").append(c.space)
        .append("\" separator=\"").append(c.separator).append("\">");
    float[] w = c.widths();
    for (int i = 0; i < w.length; i += 2)
      b.append("<col width=\"").append(w[i]).append("\" space=\"").append(w[i + 1]).append("\"/>");
    b.append("</columns>");
  }

  private static DocumentProperties readProperties(Element e) {
    if (e == null) return DocumentProperties.EMPTY;
    DocumentProperties p = DocumentProperties.EMPTY.protection(Xml.attr(e, "protection", "none"));
    for (String key : DocumentProperties.KEYS) p = p.with(key, Xml.attr(e, key, ""));
    for (Element s : Xml.children(e, "setting"))
      p = p.withSetting(Xml.attr(s, "key", ""), Xml.attr(s, "value", ""));
    return p;
  }

  private static void decor(StringBuilder b, PageDecor d) {
    if (d.equals(PageDecor.NONE)) return;
    b.append("<decor pageColor=\"").append(d.pageColor).append("\" watermark=\"")
        .append(Xml.escape(d.watermark)).append("\" watermarkColor=\"").append(d.watermarkColor)
        .append("\" borders=\"").append(Xml.escape(d.borders.encode()))
        .append("\" numberFormat=\"").append(d.numberFormat)
        .append("\" lineCountBy=\"").append(d.lineCountBy)
        .append("\" lineRestart=\"").append(d.lineRestart).append('"');
    for (Map.Entry<String, String> e : d.layout().entrySet())
      b.append(' ').append(e.getKey()).append("=\"").append(Xml.escape(e.getValue())).append('"');
    b.append("/>");
  }

  private static PageDecor readDecor(Element parent) throws IOException {
    Element e = Xml.child(parent, "decor");
    if (e == null) return PageDecor.NONE;
    try {
      PageDecor d = new PageDecor(
          Integer.parseInt(Xml.attr(e, "pageColor", "-1")),
          Xml.attr(e, "watermark", ""),
          Integer.parseInt(Xml.attr(e, "watermarkColor", "12632256")),
          ParagraphBorders.decode(Xml.attr(e, "borders", "")),
          Xml.attr(e, "numberFormat", "decimal"),
          Integer.parseInt(Xml.attr(e, "lineCountBy", "0")),
          Xml.attr(e, "lineRestart", "page"));
      for (String key : PageDecor.LAYOUT_KEYS) d = d.withLayout(key, Xml.attr(e, key, ""));
      return d;
    } catch (IllegalArgumentException ex) {
      throw new IOException("Invalid page decoration", ex);
    }
  }

  private static Columns readColumns(Element parent) throws IOException {
    Element e = Xml.child(parent, "columns");
    if (e == null) return Columns.ONE;
    List<Element> cols = Xml.children(e, "col");
    float[] widths = new float[cols.size() * 2];
    for (int i = 0; i < cols.size(); i++) {
      widths[i * 2] = Float.parseFloat(Xml.attr(cols.get(i), "width", "0"));
      widths[i * 2 + 1] = Float.parseFloat(Xml.attr(cols.get(i), "space", "0"));
    }
    try {
      return new Columns(
          Integer.parseInt(Xml.attr(e, "count", "1")),
          Float.parseFloat(Xml.attr(e, "space", "36")),
          Boolean.parseBoolean(Xml.attr(e, "separator", "false")),
          widths);
    } catch (IllegalArgumentException ex) {
      throw new IOException("Invalid section columns", ex);
    }
  }
}
