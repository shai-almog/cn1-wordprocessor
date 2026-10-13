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
      else if (block instanceof Table) {
        Table t = (Table) block;
        b.append("<table border=\"1\">");
        for (int r = 0; r < t.rows(); r++) {
          b.append("<tr>");
          for (int c = 0; c < t.columns(); c++) {
            b.append("<td>");
            for (Paragraph p : t.cell(r, c)) paragraph(b, p);
            b.append("</td>");
          }
          b.append("</tr>");
        }
        b.append("</table>");
      }
    }
    return b.append("</body></html>").toString();
  }

  private static void paragraph(final StringBuilder b, Paragraph p) {
    String tag = (p.style.length() == 8 && p.style.startsWith("Heading") && p.style.charAt(7) >= '1' && p.style.charAt(7) <= '6') ? "h" + p.style.substring(7) : "p";
    b.append('<')
        .append(tag)
        .append(" style=\"white-space:pre-wrap;text-align:")
        .append(Xml.escape(p.align))
        .append(";margin-bottom:")
        .append(p.after)
        .append("pt\">");
    p.text.visit(
        (offset, text, s) -> {
          if (!s.link.isEmpty()) b.append("<a href=\"").append(Xml.escape(s.link)).append("\">");
          b.append("<span style=\"font-size:")
              .append(s.size)
              .append("pt;color:#")
              .append(TextUtil.padded(s.color, 16, 6))
              .append(";background-color:#")
              .append(TextUtil.padded(s.background, 16, 6))
              .append(";font-weight:")
              .append(s.bold ? "bold" : "normal")
              .append(";font-style:")
              .append(s.italic ? "italic" : "normal")
              .append(";text-decoration:")
              .append(s.underline ? "underline " : "")
              .append(s.strike ? "line-through" : "")
              .append("\">")
              .append(Xml.escape(text))
              .append("</span>");
          if (!s.link.isEmpty()) b.append("</a>");
        });
    b.append("</").append(tag).append('>');
  }

  public static String write(Document d, RichTextFormat format) {
    if (format == RichTextFormat.PLAIN_TEXT) return d.plainText();
    String html = html(d);
    return format == RichTextFormat.HTML ? html : RichTextImporter.fromHtml(html, format);
  }
}
