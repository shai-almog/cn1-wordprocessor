package com.codenameone.wordprocessor;

import com.codename1.ui.RichTextFormat;
import com.codename1.ui.editor.HtmlImporter;
import com.codename1.ui.editor.RichBlocks;
import com.codename1.ui.editor.RichTextImporter;

/**
 * Adapters to CN1's existing text format parsers. Tables/objects need DOCX or native project
 * storage.
 */
public final class TextFormats {
  private TextFormats() {}

  public static Document read(String text, RichTextFormat format) {
    HtmlImporter.Result r = RichTextImporter.parse(text, format);
    String[] lines = TextUtil.split(r.getText(), '\n');
    Sequence<Block> blocks = new Sequence<Block>();
    int offset = 0;
    for (int i = 0; i < lines.length; i++) {
      RichText content = new RichText();
      int a = 0;
      while (a < lines[i].length()) {
        int b = a + 1;
        com.codename1.ui.editor.TextStyle source = r.getStyles().get(offset + a);
        while (b < lines[i].length() && r.getStyles().get(offset + b).equals(source)) b++;
        TextStyle style = map(source);
        if (offset + a < r.getLinks().size() && r.getLinks().get(offset + a) != null)
          style = style.with("link", r.getLinks().get(offset + a));
        content = content.concat(RichText.of(lines[i].substring(a, b), style));
        a = b;
      }
      Paragraph p = new Paragraph("p" + (i + 1), content);
      if (i < r.getBlocks().size()) {
        RichBlocks.BlockAttr attr = r.getBlocks().get(i);
        if (attr.type >= RichBlocks.H1 && attr.type <= (RichBlocks.H1 + 5))
          p = p.with("style", "Heading" + (attr.type - RichBlocks.H1 + 1));
        if (attr.listType != RichBlocks.LIST_NONE)
          p = p.with("list", attr.listType == RichBlocks.LIST_ORDERED ? "number" : "bullet");
        p = p.with("indent", Integer.toString(attr.indent * 18));
      }
      blocks = blocks.append(p);
      offset += lines[i].length() + 1;
    }
    return new Document(blocks, PageSpec.A4, "Imported document", lines.length + 1);
  }

  private static TextStyle map(com.codename1.ui.editor.TextStyle s) {
    return new TextStyle(
        "Serif",
        s.getFontSizePx() > 0 ? s.getFontSizePx() * .75f : 12,
        s.getForeColor() >= 0 ? s.getForeColor() : 0x202833,
        s.isBold(),
        s.isItalic(),
        s.isUnderline(),
        s.isStrike(),
        s.getHighlight() >= 0 ? s.getHighlight() : 0xffffff,
        "");
  }

  public static String html(Document doc) {
    StringBuilder b =
        new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>")
            .append(Xml.escape(doc.title))
            .append("</title></head><body>");
    for (Block block : doc.blocks) {
      if (block instanceof Paragraph) paragraph(b, (Paragraph) block);
      else if (block instanceof Picture) {
        Picture pic = (Picture) block;
        if (pic.drawing != null) {
          b.append("<p style=\"text-align:").append(pic.align).append("\">");
          svg(b, pic);
          b.append("</p>");
          continue;
        }
        b.append("<p style=\"text-align:")
            .append(pic.align)
            .append("\"><img alt=\"")
            .append(Xml.escape(pic.alt))
            .append("\" style=\"width:")
            .append(pic.width)
            .append("pt;height:")
            .append(pic.height)
            .append("pt\" src=\"data:")
            .append(pic.mime)
            .append(";base64,")
            .append(com.codename1.util.Base64.encodeNoNewline(pic.bytes()))
            .append("\"></p>");
      } else if (block instanceof Table) {
        Table t = (Table) block;
        b.append("<table style=\"").append(TableFormats.css(t.format)).append("\"");
        if (!t.format.title.isEmpty())
          b.append(" aria-label=\"").append(Xml.attribute(t.format.title)).append("\"");
        if (!t.format.description.isEmpty())
          b.append(" aria-description=\"").append(Xml.attribute(t.format.description)).append("\"");
        b.append("><colgroup>");
        for (int c = 0; c < t.columns(); c++)
          b.append("<col style=\"width:").append(t.format.columnFraction(c) * 100).append("%\">");
        b.append("</colgroup>");
        for (int r = 0; r < t.rows(); r++) {
          if (r == 0 && t.format.headerRows > 0) b.append("<thead>");
          b.append("<tr style=\"height:").append(t.format.minHeight(r)).append("pt\">");
          for (int c = 0; c < t.columns(); c++) {
            if (t.covered(r, c)) continue;
            Table.Merge span = t.span(r, c);
            b.append("<td rowspan=\"")
                .append(span.rows)
                .append("\" colspan=\"")
                .append(span.columns)
                .append("\" style=\"")
                .append(TableFormats.css(t.format.cell(r, c)))
                .append("\">");
            for (Paragraph p : t.cell(r, c)) paragraph(b, p);
            b.append("</td>");
          }
          b.append("</tr>");
          if (r + 1 == t.format.headerRows) b.append("</thead>");
        }
        b.append("</table>");
      }
    }
    for (String kind : new String[] {"footnote", "endnote"}) {
      boolean heading = false;
      for (Note note : Notes.all(doc)) {
        if (!kind.equals(note.kind)) continue;
        if (!heading) {
          b.append("<hr><section><h2>")
              .append("footnote".equals(kind) ? "Footnotes" : "Endnotes")
              .append("</h2>");
          heading = true;
        }
        b.append("<div id=\"note-")
            .append(Xml.escape(note.id))
            .append("\"><a href=\"#reference-")
            .append(Xml.escape(note.id))
            .append("\">")
            .append(Xml.escape(note.label))
            .append("</a>");
        for (Paragraph p : note.paragraphs) paragraph(b, p);
        b.append("</div>");
      }
      if (heading) b.append("</section>");
    }
    return b.append("</body></html>").toString();
  }

  private static void paragraph(final StringBuilder b, Paragraph p) {
    String tag =
        (p.style.length() == 8
                && p.style.startsWith("Heading")
                && p.style.charAt(7) >= '1'
                && p.style.charAt(7) <= '6')
            ? "h" + p.style.substring(7)
            : "p";
    b.append('<')
        .append(tag)
        .append(" style=\"white-space:pre-wrap;text-align:")
        .append(Xml.escape(p.align))
        .append(";margin-bottom:")
        .append(p.after)
        .append("pt\">");
    p.text.visit(
        new RichText.Visitor() {
          public void note(int offset, Note note, TextStyle style) {
            b.append("<sup id=\"reference-")
                .append(Xml.escape(note.id))
                .append("\"><a href=\"#note-")
                .append(Xml.escape(note.id))
                .append("\">")
                .append(Xml.escape(note.label))
                .append("</a></sup>");
          }

          public void run(int offset, String text, TextStyle s) {
            if (!s.link.isEmpty()) b.append("<a href=\"").append(Xml.escape(s.link)).append("\">");
            if (!"baseline".equals(s.script))
              b.append("superscript".equals(s.script) ? "<sup>" : "<sub>");
            b.append("<span style=\"font-size:")
                .append(s.renderedSize())
                .append("pt;color:#")
                .append(TextUtil.padded(s.color, 16, 6))
                .append(";background-color:#")
                .append(TextUtil.padded(s.background, 16, 6))
                .append(";font-weight:")
                .append(s.bold ? "bold" : "normal")
                .append(";font-style:")
                .append(s.italic ? "italic" : "normal")
                .append(";text-decoration:")
                .append(s.hasUnderline() ? "underline " : "")
                .append(s.strike || s.characters.doubleStrike ? "line-through" : "")
                .append(
                    s.characters.doubleUnderline || s.characters.doubleStrike
                        ? ";text-decoration-style:double"
                        : "")
                .append(";text-decoration-color:")
                .append(
                    s.characters.underlineColor < 0
                        ? "currentColor"
                        : "#" + TextUtil.padded(s.characters.underlineColor, 16, 6))
                .append(
                    s.characters.underlineStyle.startsWith("wav")
                        ? ";text-decoration-style:wavy"
                        : s.characters.underlineStyle.startsWith("dot")
                            ? ";text-decoration-style:dotted"
                            : s.characters.underlineStyle.startsWith("dash")
                                ? ";text-decoration-style:dashed"
                                : "")
                .append(
                    s.characters.outline
                        ? ";-webkit-text-stroke:.5pt"
                              + " currentColor;-webkit-text-fill-color:transparent"
                        : "")
                .append(s.characters.shadow ? ";text-shadow:1pt 1pt #808080" : "")
                .append(s.characters.emboss ? ";text-shadow:-1pt -1pt white,1pt 1pt #707070" : "")
                .append(s.characters.engrave ? ";text-shadow:-1pt -1pt #707070,1pt 1pt white" : "")
                .append(
                    s.characters.shading >= 0
                        ? ";background-color:#" + s.characters.value("shading")
                        : "")
                .append(
                    s.characters.border >= 0
                        ? ";border:.5pt solid #" + s.characters.value("border")
                        : "")
                .append(
                    "circle".equals(s.characters.enclose)
                        ? ";border:.5pt solid currentColor;border-radius:50%"
                        : "none".equals(s.characters.enclose)
                            ? ""
                            : ";border:.5pt solid currentColor")
                .append(s.characters.smallCaps ? ";font-variant:small-caps" : "")
                .append(s.characters.allCaps ? ";text-transform:uppercase" : "")
                .append(s.characters.hidden ? ";display:none" : "")
                .append(";letter-spacing:")
                .append(s.characters.spacing)
                .append("pt")
                .append(";position:relative;top:")
                .append(-s.characters.position)
                .append("pt")
                .append(";font-stretch:")
                .append(s.characters.scale)
                .append('%')
                .append("\">")
                .append(Xml.escape(text))
                .append("</span>");
            if (!"baseline".equals(s.script))
              b.append("superscript".equals(s.script) ? "</sup>" : "</sub>");
            if (!s.link.isEmpty()) b.append("</a>");
          }
        });
    b.append("</").append(tag).append('>');
  }

  public static String write(Document d, RichTextFormat format) {
    if (format == RichTextFormat.PLAIN_TEXT) {
      StringBuilder text = new StringBuilder(d.plainText());
      for (String kind : new String[] {"footnote", "endnote"}) {
        boolean heading = false;
        for (Note note : Notes.all(d))
          if (kind.equals(note.kind)) {
            if (!heading) {
              text.append("\n\n").append("footnote".equals(kind) ? "Footnotes" : "Endnotes");
              heading = true;
            }
            text.append('\n').append(note.label).append(" ").append(note.plainText());
          }
      }
      return text.toString();
    }
    if (format != RichTextFormat.HTML)
      for (Block block : d.blocks)
        if (block instanceof Picture)
          throw new IllegalArgumentException(
              "This format cannot preserve embedded pictures. Use DOCX, HTML, PDF or"
                  + " CN1 document.");
    String html = html(d);
    return format == RichTextFormat.HTML ? html : RichTextImporter.fromHtml(html, format);
  }

  /** Inline SVG for a drawing, from the same primitives the canvas and PDF paint. */
  static void svg(StringBuilder b, Picture pic) {
    DrawingOps.Ops ops = DrawingOps.build(pic.drawing, pic.width, pic.height);
    b.append("<svg xmlns=\"http://www.w3.org/2000/svg\" role=\"img\" aria-label=\"")
        .append(Xml.attribute(pic.alt)).append("\" width=\"").append(pic.width).append("pt\" height=\"")
        .append(pic.height).append("pt\" viewBox=\"0 0 ").append(pic.width).append(' ').append(pic.height)
        .append("\">");
    for (DrawingOps.Path p : ops.paths) {
      b.append(p.closed ? "<polygon" : "<polyline").append(" points=\"");
      for (int i = 0; i < p.points.length; i += 2)
        b.append(i == 0 ? "" : " ").append(p.points[i]).append(',').append(p.points[i + 1]);
      b.append("\" fill=\"").append(p.closed && p.fill >= 0 ? css(p.fill) : "none").append("\" stroke=\"")
          .append(p.stroke >= 0 ? css(p.stroke) : "none").append("\" stroke-width=\"").append(p.width)
          .append("\" stroke-linejoin=\"round\" stroke-linecap=\"round\"");
      if (p.alpha < 255) b.append(" opacity=\"").append(p.alpha / 255f).append('"');
      b.append("/>");
    }
    for (DrawingOps.Text t : ops.texts) {
      String anchor = "center".equals(t.align) ? "middle" : "right".equals(t.align) ? "end" : "start";
      float x = "center".equals(t.align) ? t.x + t.width / 2 : "right".equals(t.align) ? t.x + t.width : t.x;
      b.append("<text x=\"").append(x).append("\" y=\"").append(t.y + t.size).append("\" font-size=\"")
          .append(t.size).append("\" text-anchor=\"").append(anchor).append("\" fill=\"").append(css(t.color)).append('"');
      if (t.bold) b.append(" font-weight=\"bold\"");
      if (t.italic) b.append(" font-style=\"italic\"");
      if (t.outline >= 0) b.append(" stroke=\"").append(css(t.outline)).append("\" stroke-width=\"0.75\"");
      if (t.rotation != 0)
        b.append(" transform=\"rotate(").append(t.rotation).append(' ').append(t.x).append(' ').append(t.y).append(")\"");
      b.append('>').append(Xml.escape(t.text)).append("</text>");
    }
    b.append("</svg>");
  }

  private static String css(int rgb) {
    return "#" + Integer.toHexString(0x1000000 | (rgb & 0xffffff)).substring(1);
  }
}
