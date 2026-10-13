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
            .append("\">");
    PageSpec p = d.page;
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
        b.append("</table>");
      } else throw new IllegalArgumentException("Unknown block");
    }
    return b.append("</document>").toString();
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
        .append("\" before=\"")
        .append(p.before)
        .append("\" after=\"")
        .append(p.after)
        .append("\" indent=\"")
        .append(p.indent)
        .append("\" spacing=\"")
        .append(p.lineSpacing)
        .append("\" pageBreak=\"")
        .append(p.pageBreak)
        .append("\" keepNext=\"")
        .append(p.keepNext)
        .append("\">");
    p.text.visit(
        new RichText.Visitor() {
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
                .append("\">")
                .append(Xml.escape(text))
                .append("</r>");
          }
        });
    b.append("</p>");
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
          blocks = blocks.append(new Table(id(c, ids), rows));
        } else if (!"page".equals(Xml.local(c))) throw new IOException("Unknown document element");
      }
      long next = Long.parseLong(Xml.attr(e, "next", "1"));
      for (String id : ids)
        if ((id.startsWith("p") || id.startsWith("t")) && TextUtil.digits(id, 1)) next = Math.max(next, Long.parseLong(id.substring(1)) + 1);
      return new Document(blocks, spec, Xml.attr(e, "title", "Untitled"), next);
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
    for (Element r : Xml.children(e, "r")) {
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
              Xml.attr(r, "link", ""));
      text = text.concat(RichText.of(Xml.text(r), s));
    }
    return new Paragraph(
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
        flag(e, "keepNext"));
  }
}
