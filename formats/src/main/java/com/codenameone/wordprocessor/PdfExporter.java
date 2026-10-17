package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * Selectable-text PDF export from the shared pagination result. Native callers embed their document
 * fonts; the no-provider overload uses standard Helvetica. Missing glyphs are rejected.
 */
public final class PdfExporter {
  private PdfExporter() {}

  public interface FontProvider {
    String name(TextStyle style);

    byte[] bytes(String name) throws IOException;
  }

  /** Page color, a lightened diagonal watermark and page borders, under the body. */
  private static void decor(StringBuilder c, PageDecor d, PageSpec spec) throws IOException {
    if (d.pageColor >= 0)
      c.append(color(d.pageColor)).append(" rg 0 0 ").append(n(spec.width)).append(' ')
          .append(n(spec.height)).append(" re f\n");
    if (!d.watermark.isEmpty()) {
      int r = d.watermarkColor >> 16 & 255, g = d.watermarkColor >> 8 & 255, b = d.watermarkColor & 255;
      int light = (r + (255 - r) * 5 / 8) << 16 | (g + (255 - g) * 5 / 8) << 8 | (b + (255 - b) * 5 / 8);
      float size = 54, k = (float) Math.sqrt(.5), half = d.watermark.length() * size * .28f;
      float cx = spec.width / 2 - k * half, cy = spec.height / 2 - k * half;
      c.append("q ").append(color(light)).append(" rg BT /F0 ").append(n(size)).append(" Tf ")
          .append(n(k)).append(' ').append(n(k)).append(' ').append(n(-k)).append(' ').append(n(k))
          .append(' ').append(n(cx)).append(' ').append(n(cy)).append(" Tm (")
          .append(literal(d.watermark)).append(") Tj ET Q\n");
    }
    if (!d.borders.isEmpty()) {
      ParagraphBorders b = d.borders;
      frame(
          c,
          new LayoutEngine.Frame(
              b.left.space, b.top.space, spec.width - b.left.space - b.right.space,
              spec.height - b.bottom.space, b, -1, true, true),
          spec.height);
    }
  }

  private static void frame(StringBuilder c, LayoutEngine.Frame f, float pageHeight) {
    float top = pageHeight - f.top, bottom = pageHeight - f.bottom;
    if (f.shading >= 0)
      c.append(color(f.shading))
          .append(" rg ")
          .append(n(f.x))
          .append(' ')
          .append(n(bottom))
          .append(' ')
          .append(n(f.width))
          .append(' ')
          .append(n(top - bottom))
          .append(" re f\n");
    ParagraphBorders b = f.borders;
    if (f.first) edge(c, b.top, f.x, top - b.top.width / 2, f.x + f.width, top - b.top.width / 2);
    if (f.last)
      edge(c, b.bottom, f.x, bottom + b.bottom.width / 2, f.x + f.width, bottom + b.bottom.width / 2);
    edge(c, b.left, f.x + b.left.width / 2, top, f.x + b.left.width / 2, bottom);
    float r = f.x + f.width - b.right.width / 2;
    edge(c, b.right, r, top, r, bottom);
  }

  private static void edge(
      StringBuilder c, ParagraphBorders.Side s, float x1, float y1, float x2, float y2) {
    if (!s.present()) return;
    boolean dbl = "double".equals(s.style);
    float w = dbl ? s.width / 3 : s.width;
    String dash =
        "dotted".equals(s.style)
            ? "[" + n(s.width) + "] 0 d "
            : "dashed".equals(s.style) ? "[" + n(4 * s.width) + ' ' + n(2 * s.width) + "] 0 d " : "";
    float dx = 0, dy = 0;
    if (dbl) {
      boolean horizontal = y1 == y2;
      dx = horizontal ? 0 : s.width / 3;
      dy = horizontal ? s.width / 3 : 0;
    }
    for (int i = dbl ? -1 : 0; i <= (dbl ? 1 : 0); i += 2)
      c.append("q ")
          .append(color(s.color))
          .append(" RG ")
          .append(n(w))
          .append(" w ")
          .append(dash)
          .append(n(x1 + i * dx))
          .append(' ')
          .append(n(y1 + i * dy))
          .append(" m ")
          .append(n(x2 + i * dx))
          .append(' ')
          .append(n(y2 + i * dy))
          .append(" l S Q\n");
  }

  /**
   * Frame-centered coordinates: rotate clockwise and flip about the center, clip to the frame,
   * draw the enlarged source offset by the crop, then stroke the border.
   */
  private static void picture(
      StringBuilder content, LayoutEngine.PlacedPicture pic, float pageHeight, String name) {
    Picture.Look k = pic.picture.look;
    float w = pic.width, h = pic.height;
    double a = Math.toRadians(k.rotation);
    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
    float fx = k.flipH ? -1 : 1, fy = k.flipV ? -1 : 1;
    content
        .append("q 1 0 0 1 ")
        .append(n(pic.x + w / 2))
        .append(' ')
        .append(n(pageHeight - pic.y - h / 2))
        .append(" cm ")
        .append(n(cos))
        .append(' ')
        .append(n(-sin))
        .append(' ')
        .append(n(sin))
        .append(' ')
        .append(n(cos))
        .append(" 0 0 cm ")
        .append(n(fx))
        .append(" 0 0 ")
        .append(n(fy))
        .append(" 0 0 cm ");
    float fw = w / (1 - k.cropLeft - k.cropRight), fh = h / (1 - k.cropTop - k.cropBottom);
    content
        .append(n(-w / 2))
        .append(' ')
        .append(n(-h / 2))
        .append(' ')
        .append(n(w))
        .append(' ')
        .append(n(h))
        .append(" re W n q ")
        .append(n(fw))
        .append(" 0 0 ")
        .append(n(fh))
        .append(' ')
        .append(n(-w / 2 - fw * k.cropLeft))
        .append(' ')
        .append(n(-h / 2 - fh * k.cropBottom))
        .append(" cm /")
        .append(name)
        .append(" Do Q");
    if (k.borderColor >= 0 && k.borderWidth > 0)
      content
          .append(' ')
          .append(color(k.borderColor))
          .append(" RG ")
          .append(n(k.borderWidth))
          .append(" w ")
          .append(n(-w / 2 + k.borderWidth / 2))
          .append(' ')
          .append(n(-h / 2 + k.borderWidth / 2))
          .append(' ')
          .append(n(w - k.borderWidth))
          .append(' ')
          .append(n(h - k.borderWidth))
          .append(" re S");
    content.append(" Q\n");
  }

  public static byte[] write(Document document) throws IOException {
    return write(document.page, new LayoutEngine(new PdfMetrics()).layout(document, 0));
  }

  /** Paginate and embed with the same font instances, loading each face at most once per export. */
  public static byte[] write(Document document, FontProvider provider) throws IOException {
    if (provider == null) return write(document);
    final PdfFonts fonts = new PdfFonts(provider);
    LayoutEngine.Result layout;
    try {
      layout = new LayoutEngine(new FontMetrics(fonts)).layout(document, 0);
    } catch (FontFailure failure) {
      throw failure.error;
    }
    return writeWithFonts(document.page, layout, fonts);
  }

  /** Headless pagination using the same font advances as embedded PDF export. */
  public static LayoutEngine layoutEngine(FontProvider provider) {
    if (provider == null) return new LayoutEngine(new PdfMetrics());
    return new LayoutEngine(new FontMetrics(new PdfFonts(provider)));
  }

  private static final class FontFailure extends IllegalArgumentException {
    final IOException error;

    FontFailure(IOException error) {
      super(error.getMessage(), error);
      this.error = error;
    }
  }

  private static void drawText(
      StringBuilder b, int face, TextStyle s, float x, float y, String encoded) {
    CharacterFormat c = s.characters;
    if (c.shadow) textPass(b, face, s, x + 1, y - 1, encoded, 0x808080, false);
    if (c.emboss || c.engrave) {
      textPass(b, face, s, x - 1, y + 1, encoded, c.engrave ? 0x707070 : 0xffffff, false);
      textPass(b, face, s, x + 1, y - 1, encoded, c.engrave ? 0xffffff : 0x707070, false);
    }
    textPass(b, face, s, x, y, encoded, s.color, c.outline);
  }

  private static void textPass(
      StringBuilder b,
      int face,
      TextStyle s,
      float x,
      float y,
      String encoded,
      int ink,
      boolean outline) {
    b.append("q ")
        .append(color(ink))
        .append(" RG 0.5 w ")
        .append(color(ink))
        .append(" rg BT /F")
        .append(face)
        .append(' ')
        .append(n(s.renderedSize()))
        .append(" Tf ")
        .append(n(s.characters.scale))
        .append(" Tz ")
        .append(outline ? "1" : "0")
        .append(" Tr 1 0 0 1 ")
        .append(n(x))
        .append(' ')
        .append(n(y))
        .append(" Tm ")
        .append(encoded)
        .append(" Tj ET Q\n");
  }

  private static void rule(StringBuilder b, int color, float x, float y, float end) {
    b.append(color(color))
        .append(" RG 0.5 w ")
        .append(n(x))
        .append(' ')
        .append(n(y))
        .append(" m ")
        .append(n(end))
        .append(' ')
        .append(n(y))
        .append(" l S\n");
  }

  private static final class FontMetrics implements LayoutEngine.Metrics {
    private final PdfFonts fonts;

    FontMetrics(PdfFonts fonts) {
      this.fonts = fonts;
    }

    public float width(String text, TextStyle style) {
      try {
        PdfFonts.TrueType font = fonts.face(style).font;
        float width = 0;
        for (int i = 0; i < text.length(); ) {
          int cp = Character.codePointAt(text, i);
          i += Character.charCount(cp);
          width += font.scaled(font.advance(font.glyph(cp))) * style.renderedSize() / 1000;
        }
        return width;
      } catch (IOException error) {
        throw new FontFailure(error);
      }
    }

    public float ascent(TextStyle style) {
      return style.renderedSize();
    }

    public float height(TextStyle style) {
      return style.renderedSize() * 1.2f;
    }
  }

  public static byte[] write(PageSpec spec, LayoutEngine.Result layout) throws IOException {
    return write(spec, layout, null);
  }

  public static byte[] write(PageSpec fallback, LayoutEngine.Result layout, FontProvider provider)
      throws IOException {
    return writeWithFonts(fallback, layout, provider == null ? null : new PdfFonts(provider));
  }

  private static byte[] writeWithFonts(
      PageSpec fallback, LayoutEngine.Result layout, PdfFonts fonts) throws IOException {
    if (!layout.warnings.isEmpty()) throw new IOException(layout.warnings.get(0));
    if (fonts != null)
      for (LayoutEngine.Page page : layout.pages)
        for (LayoutEngine.Line line : page.allLines())
          for (LayoutEngine.Span span : line.spans)
            if (!span.displayText.isEmpty()) fonts.face(span.style).encode(span.displayText);
    List<byte[]> objects = new ArrayList<byte[]>();
    for (int i = 0; i < 6; i++) objects.add(null);
    objects.set(0, ascii("<< /Type /Catalog /Pages 2 0 R >>"));
    StringBuilder kids = new StringBuilder();
    for (int i = 0; i < layout.pages.size(); i++) kids.append(7 + i * 2).append(" 0 R ");
    objects.set(
        1, ascii("<< /Type /Pages /Count " + layout.pages.size() + " /Kids [" + kids + "] >>"));
    String[] names = {"Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique"};
    for (int i = 0; i < 4; i++)
      objects.set(
          i + 2,
          ascii(
              "<< /Type /Font /Subtype /Type1 /BaseFont /"
                  + names[i]
                  + " /Encoding /WinAnsiEncoding >>"));
    for (int i = 0; i < layout.pages.size() * 2; i++) objects.add(null);
    String embeddedResources = fonts == null ? "" : fonts.install(objects);
    Map<Picture, Integer> imageIds = new HashMap<Picture, Integer>();
    for (LayoutEngine.Page page : layout.pages)
      for (LayoutEngine.PlacedPicture pic : page.pictures)
        if (!imageIds.containsKey(pic.picture))
          imageIds.put(pic.picture, PdfPictures.add(objects, pic.picture));
    int pageIndex = 0;
    for (LayoutEngine.Page page : layout.pages) {
      PageSpec spec = page.spec;
      StringBuilder content = new StringBuilder(), imageResources = new StringBuilder();
      int imageIndex = 0;
      for (float[] rule : page.columnRules)
        content
            .append("q 0.5 w 0 0 0 RG ")
            .append(n(rule[0]))
            .append(' ')
            .append(n(spec.height - rule[1]))
            .append(" m ")
            .append(n(rule[0]))
            .append(' ')
            .append(n(spec.height - rule[2]))
            .append(" l S Q\n");
      decor(content, page.decor, spec);
      for (LayoutEngine.Frame frame : page.frames) frame(content, frame, spec.height);
      for (LayoutEngine.PlacedPicture pic : page.pictures) {
        String name = "Im" + (imageIndex++);
        imageResources
            .append('/')
            .append(name)
            .append(' ')
            .append(imageIds.get(pic.picture))
            .append(" 0 R ");
        picture(content, pic, spec.height, name);
      }
      for (LayoutEngine.Box b : page.boxes) {
        content
            .append(color(b.format.background))
            .append(" rg ")
            .append(color(b.format.borderColor))
            .append(" RG ")
            .append(n(b.format.borderWidth))
            .append(" w ")
            .append(n(b.x))
            .append(' ')
            .append(n(spec.height - b.y - b.height))
            .append(' ')
            .append(n(b.width))
            .append(' ')
            .append(n(b.height))
            .append(
                b.format.borderWidth > 0 && b.format.sides == TableFormat.Cell.ALL
                    ? " re B\n"
                    : " re f\n");
        if (b.format.borderWidth > 0 && b.format.sides != TableFormat.Cell.ALL) {
          float x0 = b.x, x1 = b.x + b.width, y0 = spec.height - b.y, y1 = y0 - b.height;
          float[][] edges = {
            {TableFormat.Cell.TOP, x0, y0, x1, y0},
            {TableFormat.Cell.BOTTOM, x0, y1, x1, y1},
            {TableFormat.Cell.LEFT, x0, y0, x0, y1},
            {TableFormat.Cell.RIGHT, x1, y0, x1, y1}
          };
          for (float[] e : edges)
            if (b.format.side((int) e[0]))
              content
                  .append(n(e[1]))
                  .append(' ')
                  .append(n(e[2]))
                  .append(" m ")
                  .append(n(e[3]))
                  .append(' ')
                  .append(n(e[4]))
                  .append(" l S\n");
        }
      }
      for (LayoutEngine.Line area : page.noteAreas()) {
        float y = spec.height - area.y + 6;
        content
            .append("0 G 0.5 w ")
            .append(n(spec.left))
            .append(' ')
            .append(n(y))
            .append(" m ")
            .append(n(spec.left + Math.min(144, spec.contentWidth() / 3)))
            .append(' ')
            .append(n(y))
            .append(" l S\n");
      }
      for (LayoutEngine.Line line : page.allLines()) {
        if (line.clipped)
          content
              .append("q 0 ")
              .append(n(spec.height - line.y - line.clipHeight))
              .append(' ')
              .append(n(spec.width))
              .append(' ')
              .append(n(line.clipHeight))
              .append(" re W n\n");
        if ("◦".equals(line.marker) || "▪".equals(line.marker)) {
          // These list markers are vector geometry; Helvetica/WinAnsi has no glyph for them.
          float x = line.x - 14, y = spec.height - line.y - line.markerOffset - 8, r = 1.7f;
          content.append("0 g 0 G 0.6 w ");
          if ("▪".equals(line.marker)) {
            content
                .append(n(x - r))
                .append(' ')
                .append(n(y - r))
                .append(' ')
                .append(n(2 * r))
                .append(' ')
                .append(n(2 * r))
                .append(" re f\n");
          } else {
            float k = r * .55228475f;
            content.append(n(x + r)).append(' ').append(n(y)).append(" m ");
            curve(content, x + r, y + k, x + k, y + r, x, y + r);
            curve(content, x - k, y + r, x - r, y + k, x - r, y);
            curve(content, x - r, y - k, x - k, y - r, x, y - r);
            curve(content, x + k, y - r, x + r, y - k, x + r, y);
            content.append("h S\n");
          }
        } else if (!line.marker.isEmpty())
          content
              .append("0 g BT /F0 12 Tf 100 Tz 1 0 0 1 ")
              .append(n(line.x - 18))
              .append(' ')
              .append(n(spec.height - line.y - line.markerOffset - 12))
              .append(" Tm (")
              .append(literal(line.marker))
              .append(") Tj ET\n");
        for (LayoutEngine.Span span : line.spans) {
          if (span.displayText.isEmpty()) continue;
          TextStyle s = span.glyphStyle;
          float x = line.x + span.x, y = spec.height - line.y - span.top - s.renderedSize();
          int face = fonts == null ? (s.bold ? 1 : 0) + (s.italic ? 2 : 0) : fonts.face(s).number;
          String display = span.displayText;
          if ("\t".equals(span.text)) {
            String glyph = display.substring(0, 1);
            float advance;
            if (fonts == null) advance = new PdfMetrics().width(glyph, s.renderedStyle());
            else {
              PdfFonts.TrueType font = fonts.face(s).font;
              advance =
                  font.scaled(font.advance(font.glyph(glyph.charAt(0)))) * s.renderedSize() / 1000f;
            }
            advance = Math.max(0, advance * s.characters.scale / 100 + s.characters.spacing);
            int count = advance <= 0 ? 0 : Math.min(4096, (int) (span.width / advance));
            char[] repeated = new char[count];
            java.util.Arrays.fill(repeated, glyph.charAt(0));
            display = new String(repeated);
          }
          String encoded =
              fonts == null ? "(" + literal(display) + ")" : fonts.face(s).encode(display);
          if (s.background != 0xffffff)
            content
                .append(color(s.background))
                .append(" rg ")
                .append(n(x))
                .append(' ')
                .append(n(spec.height - line.y - line.height))
                .append(' ')
                .append(n(span.width))
                .append(' ')
                .append(n(line.height))
                .append(" re f\n");
          if (s.characters.spacing == 0 && !s.characters.smallCaps) {
            drawText(content, face, s, x, y, encoded);
          } else {
            String value = "\t".equals(span.text) ? display : span.rawDisplay;
            RichText source = RichText.of(value, span.style);
            LayoutEngine.Metrics metrics =
                fonts == null ? new PdfMetrics() : new FontMetrics(fonts);
            float advance = 0;
            for (int at = 0; at < value.length(); ) {
              int end = Graphemes.next(source, at);
              String raw = value.substring(at, end), glyph = span.style.displayText(raw);
              drawText(
                  content,
                  face,
                  span.style.glyphStyle(raw),
                  x + advance,
                  y,
                  fonts == null ? "(" + literal(glyph) + ")" : fonts.face(s).encode(glyph));
              advance += CharacterMetrics.width(metrics, raw, span.style);
              at = end;
            }
          }
          if (s.hasUnderline()) {
            int ink = s.characters.underlineColor < 0 ? s.color : s.characters.underlineColor;
            LayoutEngine.Metrics metrics =
                fonts == null ? new PdfMetrics() : new FontMetrics(fonts);
            TextDecorations.underline(
                span,
                metrics,
                (x1, y1, x2, y2) ->
                    content
                        .append(color(ink))
                        .append(" RG ")
                        .append(n(TextDecorations.weight(s)))
                        .append(" w ")
                        .append(n(x + x1))
                        .append(' ')
                        .append(n(y - y1))
                        .append(" m ")
                        .append(n(x + x2))
                        .append(' ')
                        .append(n(y - y2))
                        .append(" l S\n"));
          }
          if (s.strike || s.characters.doubleStrike) {
            rule(content, s.color, x, y + s.renderedSize() * .35f, x + span.width);
            if (s.characters.doubleStrike)
              rule(content, s.color, x, y + s.renderedSize() * .35f + 1.5f, x + span.width);
          }
        }
        if (line.clipped) content.append("Q\n");
      }
      // Draw bars after text backgrounds, using the same geometry as the editor canvas.
      for (LayoutEngine.Line line : page.allLines())
        for (float bar : line.barTabs)
          content
              .append("0 G 0.5 w ")
              .append(n(line.x + bar))
              .append(' ')
              .append(n(spec.height - line.y))
              .append(" m ")
              .append(n(line.x + bar))
              .append(' ')
              .append(n(spec.height - line.y - line.height))
              .append(" l S\n");
      int pageId = 7 + pageIndex++ * 2;
      objects.set(
          pageId - 1,
          ascii(
              "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                  + n(spec.width)
                  + " "
                  + n(spec.height)
                  + "] /Resources << /Font << /F0 3 0 R /F1 4 0 R /F2 5 0 R /F3 6 0 R "
                  + embeddedResources
                  + " >> /XObject"
                  + " << "
                  + imageResources
                  + ">> >>"
                  + " /Contents "
                  + (pageId + 1)
                  + " 0 R >>"));
      byte[] stream = ascii(content.toString());
      ByteArrayOutputStream obj = new ByteArrayOutputStream();
      obj.write(ascii("<< /Length " + stream.length + " >>\nstream\n"));
      obj.write(stream);
      obj.write(ascii("endstream"));
      objects.set(pageId, obj.toByteArray());
    }
    ByteArrayOutputStream pdf = new ByteArrayOutputStream();
    pdf.write(ascii("%PDF-1.4\n% CN1 Word Processor\n"));
    List<Integer> offsets = new ArrayList<Integer>();
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(pdf.size());
      pdf.write(ascii((i + 1) + " 0 obj\n"));
      pdf.write(objects.get(i));
      pdf.write(ascii("\nendobj\n"));
    }
    int xref = pdf.size();
    pdf.write(ascii("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n"));
    for (Integer offset : offsets)
      pdf.write(ascii(TextUtil.padded(offset, 10, 10) + " 00000 n \n"));
    pdf.write(
        ascii(
            "trailer\n<< /Size "
                + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n"
                + xref
                + "\n%%EOF\n"));
    return pdf.toByteArray();
  }

  private static void curve(
      StringBuilder b, float x1, float y1, float x2, float y2, float x3, float y3) {
    b.append(n(x1))
        .append(' ')
        .append(n(y1))
        .append(' ')
        .append(n(x2))
        .append(' ')
        .append(n(y2))
        .append(' ')
        .append(n(x3))
        .append(' ')
        .append(n(y3))
        .append(" c ");
  }

  private static String n(float f) {
    long scaled = Math.round(Math.abs((double) f) * 1000);
    return (f < 0 ? "-" : "") + scaled / 1000 + "." + TextUtil.padded(scaled % 1000, 10, 3);
  }

  private static String color(int c) {
    return n(((c >> 16) & 255) / 255f)
        + " "
        + n(((c >> 8) & 255) / 255f)
        + " "
        + n((c & 255) / 255f);
  }

  private static byte[] ascii(String s) throws IOException {
    return s.getBytes("ISO-8859-1");
  }

  static int winAnsi(char character) throws IOException {
    String special = "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ";
    int c = character;
    if (c == '\t') c = ' ';
    if (c > 255) {
      int index = special.indexOf((char) c);
      if (index < 0)
        throw new IOException(
            "PDF font does not cover U+"
                + Integer.toHexString(c)
                + ". Export DOCX or HTML to preserve this text.");
      c = 128 + index;
    }
    if (c < 32 || c == 127 || c >= 128 && c < 160 && special.charAt(c - 128) == 0)
      throw new IOException("Unsupported PDF control character");
    return c;
  }

  private static String literal(String s) throws IOException {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      int c = winAnsi(s.charAt(i));
      if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
      else if (c > 126) b.append('\\').append(TextUtil.padded(c, 8, 3));
      else b.append((char) c);
    }
    return b.toString();
  }
}
