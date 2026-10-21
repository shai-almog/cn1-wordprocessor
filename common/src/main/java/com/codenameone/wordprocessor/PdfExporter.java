package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * Selectable-text PDF export from the shared pagination result. Native callers embed their document
 * fonts with per-character fallback; the no-provider overload uses standard Helvetica (WinAnsi only).
 */
public final class PdfExporter {
  private PdfExporter() {}

  public interface FontProvider {
    String name(TextStyle style);

    byte[] bytes(String name) throws IOException;
  }

  /** Optional on a {@link FontProvider}: faces tried, in order, for characters the chosen face lacks. */
  public interface FallbackFonts {
    List<String> fallbacks(TextStyle style);
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
      var b = d.borders;
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
    var b = f.borders;
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
    var k = pic.picture().look;
    float w = pic.width(), h = pic.height();
    double a = Math.toRadians(k.rotation);
    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
    float fx = k.flipH ? -1 : 1, fy = k.flipV ? -1 : 1;
    content
        .append("q 1 0 0 1 ")
        .append(n(pic.x() + w / 2))
        .append(' ')
        .append(n(pageHeight - pic.y() - h / 2))
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

  // Reused so repeated exports of an unchanged (or slightly edited) document keep the
  // per-paragraph measurement cache instead of re-measuring every line.
  private static LayoutEngine headless;

  public static byte[] write(Document document) throws IOException {
    LayoutEngine.Result layout;
    synchronized (PdfExporter.class) {
      if (headless == null) headless = new LayoutEngine(new PdfMetrics());
      layout = headless.layout(document, 0);
    }
    return writeWithFonts(document.page, layout, null, document);
  }

  /** Paginate and embed with the same font instances, loading each face at most once per export. */
  public static byte[] write(Document document, FontProvider provider) throws IOException {
    if (provider == null) return write(document);
    final var fonts = new PdfFonts(provider);
    LayoutEngine.Result layout;
    try {
      layout = new LayoutEngine(new FontMetrics(fonts)).layout(document, 0);
    } catch (FontFailure failure) {
      throw failure.error;
    }
    return writeWithFonts(document.page, layout, fonts, document);
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

  private static void drawText(StringBuilder b, TextStyle s, float x, float y, String shown) {
    var c = s.characters;
    if (c.shadow) textPass(b, s, x + 1, y - 1, shown, 0x808080, false);
    if (c.emboss || c.engrave) {
      textPass(b, s, x - 1, y + 1, shown, c.engrave ? 0x707070 : 0xffffff, false);
      textPass(b, s, x + 1, y - 1, shown, c.engrave ? 0xffffff : 0x707070, false);
    }
    textPass(b, s, x, y, shown, s.color, c.outline);
  }

  /** Font selection and showing operators: kerned, fallback-aware runs when fonts are embedded. */
  private static String shown(PdfFonts fonts, TextStyle s, String text, boolean rtl)
      throws IOException {
    return shown(fonts, s, text, rtl, true);
  }

  private static String shown(
      PdfFonts fonts, TextStyle s, String text, boolean rtl, boolean kerning) throws IOException {
    if (fonts != null) return fonts.show(s, text, rtl, n(s.renderedSize()), kerning);
    return "/F" + ((s.bold ? 1 : 0) + (s.italic ? 2 : 0)) + ' ' + n(s.renderedSize()) + " Tf ("
        + literal(rtl ? reversed(text) : text) + ") Tj";
  }

  private static void textPass(
      StringBuilder b,
      TextStyle s,
      float x,
      float y,
      String shown,
      int ink,
      boolean outline) {
    b.append("q ")
        .append(color(ink))
        .append(" RG 0.5 w ")
        .append(color(ink))
        .append(" rg BT ")
        .append(n(s.characters.scale))
        .append(" Tz ")
        .append(outline ? "1" : "0")
        .append(" Tr 1 0 0 1 ")
        .append(n(x))
        .append(' ')
        .append(n(y))
        .append(" Tm ")
        .append(shown)
        .append(" ET Q\n");
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
        return fonts.width(style, text, style.renderedSize());
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
    return writeWithFonts(
        fallback, layout, provider == null ? null : new PdfFonts(provider), null);
  }

  private static byte[] writeWithFonts(
      PageSpec fallback, LayoutEngine.Result layout, PdfFonts fonts, Document document)
      throws IOException {
    var structure = new PdfStructure(document);
    int[] pageIds = new int[layout.pages.size()];
    for (int i = 0; i < pageIds.length; i++) pageIds[i] = 7 + i * 2;
    if (!layout.warnings.isEmpty()) throw new IOException(layout.warnings.get(0));
    var objects = new ArrayList<byte[]>();
    for (int i = 0; i < 6; i++) objects.add(null);
    objects.set(0, ascii("<< /Type /Catalog /Pages 2 0 R >>"));
    var kids = new StringBuilder();
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
    // One font dictionary shared by all pages, completed once every page's text is shown.
    objects.add(null);
    int fontDictionary = objects.size();
    var imageIds = new HashMap<Picture, Integer>();
    for (LayoutEngine.Page page : layout.pages)
      for (LayoutEngine.PlacedPicture pic : page.pictures)
        if (pic.picture().drawing == null && !imageIds.containsKey(pic.picture()))
          imageIds.put(pic.picture(), PdfPictures.add(objects, pic.picture()));
    int pageIndex = 0;
    for (LayoutEngine.Page page : layout.pages) {
      var spec = page.spec;
      StringBuilder content = new StringBuilder(), imageResources = new StringBuilder(),
          annotations = new StringBuilder();
      int pageNumber = pageIndex;
      var furniture = Collections.newSetFromMap(new IdentityHashMap<LayoutEngine.Line, Boolean>());
      furniture.addAll(page.header);
      furniture.addAll(page.footer);
      var notes = Collections.newSetFromMap(new IdentityHashMap<LayoutEngine.Line, Boolean>());
      notes.addAll(page.notes);
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
      for (float[] n : page.lineNumbers) {
        String label = String.valueOf((int) n[1]);
        content.append("0.42 g BT /F0 9 Tf 1 0 0 1 ")
            .append(n(spec.left - 14 - label.length() * 5f)).append(' ')
            .append(n(spec.height - n[0] - 9)).append(" Tm (").append(label).append(") Tj ET\n");
      }
      for (LayoutEngine.Frame frame : page.frames) frame(content, frame, spec.height);
      for (LayoutEngine.PlacedPicture pic : LayoutEngine.byDepth(page.pictures, false)) {
        int figure = structure.mcid(pageNumber, structure.figure(pic.picture().alt));
        content.append("/Figure << /MCID ").append(figure).append(" >> BDC\n");
        if (pic.picture().drawing != null) {
          PdfDrawings.paint(content, pic, spec.height);
          content.append("EMC\n");
          continue;
        }
        var name = "Im" + (imageIndex++);
        imageResources
            .append('/')
            .append(name)
            .append(' ')
            .append(imageIds.get(pic.picture()))
            .append(" 0 R ");
        picture(content, pic, spec.height, name);
        content.append("EMC\n");
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
        if (furniture.contains(line)) content.append("/Artifact BMC\n");
        else {
          var element = structure.element(line, notes.contains(line));
          if (!notes.contains(line)) structure.heading(line, pageNumber, spec.height - line.y);
          content.append('/').append(element.type).append(" << /MCID ")
              .append(structure.mcid(pageNumber, element)).append(" >> BDC\n");
        }
        for (LayoutEngine.Span span : line.spans) {
          var link = span.style.link;
          if (link == null || link.isEmpty() || span.width <= 0) continue;
          var action = structure.action(link, layout, pageIds);
          if (action == null) continue;
          objects.add(ascii("<< /Type /Annot /Subtype /Link /Rect [" + n(line.x + span.x) + ' '
              + n(spec.height - line.y - line.height) + ' ' + n(line.x + span.x + span.width) + ' '
              + n(spec.height - line.y) + "] /Border [0 0 0] " + action + " >>"));
          annotations.append(objects.size()).append(" 0 R ");
        }
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
          if (span.displayText.isEmpty() && InlineField.OBJECT.equals(span.text)) {
            var math = inlineMath(layout.document, line, span.start);
            if (math != null)
              PdfDrawings.paint(content, Equation.placed(math, span.glyphStyle, line.block, 0, line.x + span.x,
                  line.y + span.top + span.glyphStyle.renderedSize(), span.width), spec.height);
          }
          if (span.displayText.isEmpty()) continue;
          var s = span.glyphStyle;
          float x = line.x + span.x, y = spec.height - line.y - span.top - s.renderedSize();
          var display = span.displayText;
          if ("\t".equals(span.text)) {
            var glyph = display.substring(0, 1);
            float advance;
            if (fonts == null) advance = new PdfMetrics().width(glyph, s.renderedStyle());
            else advance = fonts.width(s, glyph, s.renderedSize());
            advance = Math.max(0, advance * s.characters.scale / 100 + s.characters.spacing);
            int count = advance <= 0 ? 0 : Math.min(4096, (int) (span.width / advance));
            char[] repeated = new char[count];
            java.util.Arrays.fill(repeated, glyph.charAt(0));
            display = new String(repeated);
          }
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
            drawText(content, s, x, y, shown(fonts, s, display, span.rtl, !"\t".equals(span.text)));
          } else {
            String value = "\t".equals(span.text) ? display : span.rawDisplay;
            RichText source = RichText.of(value, span.style);
            LayoutEngine.Metrics metrics =
                fonts == null ? new PdfMetrics() : new FontMetrics(fonts);
            float advance = 0;
            var bounds = new ArrayList<Integer>();
            for (int at = 0; at < value.length(); at = Graphemes.next(source, at)) bounds.add(at);
            bounds.add(value.length());
            // Right-to-left spans place their last grapheme at the left edge.
            for (int k = 0; k + 1 < bounds.size(); k++) {
              int g = span.rtl ? bounds.size() - 2 - k : k;
              String raw = value.substring(bounds.get(g), bounds.get(g + 1));
              var glyphStyle = span.style.glyphStyle(raw);
              drawText(
                  content,
                  glyphStyle,
                  x + advance,
                  y,
                  shown(fonts, glyphStyle, span.style.displayText(raw), span.rtl));
              advance += CharacterMetrics.width(metrics, raw, span.style);
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
        content.append("EMC\n");
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
      for (LayoutEngine.PlacedPicture pic : LayoutEngine.byDepth(page.pictures, true)) {
        int figure = structure.mcid(pageNumber, structure.figure(pic.picture().alt));
        content.append("/Figure << /MCID ").append(figure).append(" >> BDC\n");
        PdfDrawings.paint(content, pic, spec.height);
        content.append("EMC\n");
      }
      int pageId = 7 + pageIndex++ * 2;
      objects.set(
          pageId - 1,
          ascii(
              "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                  + n(spec.width)
                  + " "
                  + n(spec.height)
                  + "] /Resources << /Font "
                  + fontDictionary
                  + " 0 R /XObject"
                  + " << "
                  + imageResources
                  + ">> >>"
                  + " /Contents "
                  + (pageId + 1)
                  + " 0 R /StructParents "
                  + pageNumber
                  + (annotations.length() == 0 ? "" : " /Annots [" + annotations + "]")
                  + " >>"));
      byte[] stream = ascii(content.toString());
      var obj = new ByteArrayOutputStream();
      obj.write(ascii("<< /Length " + stream.length + " >>\nstream\n"));
      obj.write(stream);
      obj.write(ascii("endstream"));
      objects.set(pageId, obj.toByteArray());
    }
    objects.set(
        fontDictionary - 1,
        ascii(
            "<< /F0 3 0 R /F1 4 0 R /F2 5 0 R /F3 6 0 R"
                + (fonts == null ? "" : fonts.install(objects))
                + " >>"));
    int tree = structure.writeTree(objects, pageIds),
        outline = structure.writeOutline(objects, pageIds);
    objects.add(ascii(structure.info(layout)));
    int info = objects.size();
    var lang = PdfStructure.language(layout);
    objects.set(
        0,
        ascii(
            "<< /Type /Catalog /Pages 2 0 R /MarkInfo << /Marked true >> /StructTreeRoot "
                + tree
                + " 0 R"
                + (outline == 0 ? "" : " /Outlines " + outline + " 0 R /PageMode /UseOutlines")
                + (lang.isEmpty() ? "" : " /Lang (" + lang + ")")
                + " >>"));
    var pdf = new ByteArrayOutputStream();
    pdf.write(ascii("%PDF-1.4\n% CN1 Word Processor\n"));
    var offsets = new ArrayList<Integer>();
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
                + " /Root 1 0 R /Info "
                + info
                + " 0 R >>\nstartxref\n"
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

  static String n(float f) {
    long scaled = Math.round(Math.abs((double) f) * 1000);
    return (f < 0 ? "-" : "") + scaled / 1000 + "." + TextUtil.padded(scaled % 1000, 10, 3);
  }

  private static String reversed(String text) {
    char[] c = new char[text.length()];
    for (int i = 0; i < c.length; i++) c[i] = text.charAt(c.length - 1 - i);
    return new String(c);
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
    var special = "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ";
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

  /** The inline equation field a line's object character at {@code at} holds, or null. */
  static InlineField inlineMath(Document d, LayoutEngine.Line line, int at) {
    if (d == null || line.block < 0 || line.block >= d.blocks.size()) return null;
    Block b = d.blocks.get(line.block);
    Paragraph p = null;
    if (line.row < 0 && b instanceof Paragraph q) p = q;
    else if (line.row >= 0 && b instanceof Table t)
      for (Paragraph q : t.cell(line.row, line.column)) if (q.id.equals(line.paragraphId)) p = q;
    if (p == null || at < 0 || at >= p.text.length()) return null;
    var f = p.text.fieldAt(at);
    return Equation.inline(f) ? f : null;
  }

  private static String literal(String s) throws IOException {
    var b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      int c = winAnsi(s.charAt(i));
      if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
      else if (c > 126) b.append('\\').append(TextUtil.padded(c, 8, 3));
      else b.append((char) c);
    }
    return b.toString();
  }
}
