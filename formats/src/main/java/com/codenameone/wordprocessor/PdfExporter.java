package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * Selectable-text PDF export using standard Helvetica faces and the shared pagination result. The
 * initial exporter supports WinAnsi text; unsupported glyphs are rejected, never replaced.
 */
public final class PdfExporter {
  private PdfExporter() {}

  public static byte[] write(PageSpec spec, LayoutEngine.Result layout) throws IOException {
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
    for (LayoutEngine.Page page : layout.pages) {
      StringBuilder content = new StringBuilder();
      for (LayoutEngine.Box b : page.boxes)
        content
            .append("0.65 G 0.5 w ")
            .append(n(b.x))
            .append(' ')
            .append(n(spec.height - b.y - b.height))
            .append(' ')
            .append(n(b.width))
            .append(' ')
            .append(n(b.height))
            .append(" re S\n");
      for (LayoutEngine.Line line : page.lines)
        for (LayoutEngine.Span span : line.spans) {
          TextStyle s = span.style;
          float x = line.x + span.x, y = spec.height - line.y - s.size;
          int face = (s.bold ? 1 : 0) + (s.italic ? 2 : 0);
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
          content
              .append(color(s.color))
              .append(" rg BT /F")
              .append(face)
              .append(' ')
              .append(n(s.size))
              .append(" Tf 1 0 0 1 ")
              .append(n(x))
              .append(' ')
              .append(n(y))
              .append(" Tm (")
              .append(literal(span.text))
              .append(") Tj ET\n");
          if (s.underline || s.strike) {
            float ly = s.strike ? y + s.size * .35f : y - 1;
            content
                .append(color(s.color))
                .append(" RG 0.5 w ")
                .append(n(x))
                .append(' ')
                .append(n(ly))
                .append(" m ")
                .append(n(x + span.width))
                .append(' ')
                .append(n(ly))
                .append(" l S\n");
          }
        }
      int pageId = objects.size() + 1;
      objects.add(
          ascii(
              "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                  + n(spec.width)
                  + " "
                  + n(spec.height)
                  + "] /Resources << /Font << /F0 3 0 R /F1 4 0 R /F2 5 0 R /F3 6 0 R >> >>"
                  + " /Contents "
                  + (pageId + 1)
                  + " 0 R >>"));
      byte[] stream = ascii(content.toString());
      ByteArrayOutputStream obj = new ByteArrayOutputStream();
      obj.write(ascii("<< /Length " + stream.length + " >>\nstream\n"));
      obj.write(stream);
      obj.write(ascii("endstream"));
      objects.add(obj.toByteArray());
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
      pdf.write(ascii(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset)));
    pdf.write(
        ascii(
            "trailer\n<< /Size "
                + (objects.size() + 1)
                + " /Root 1 0 R >>\nstartxref\n"
                + xref
                + "\n%%EOF\n"));
    return pdf.toByteArray();
  }

  private static String n(float f) {
    return String.format(java.util.Locale.ROOT, "%.3f", f);
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

  private static String literal(String s) throws IOException {
    String special = "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000\u0000‘’“”•–—˜™š›œ\u0000žŸ";
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      int c = s.charAt(i);
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
      if (c == '(' || c == ')' || c == '\\') b.append('\\').append((char) c);
      else if (c > 126) b.append('\\').append(String.format(java.util.Locale.ROOT, "%03o", c));
      else b.append((char) c);
    }
    return b.toString();
  }
}
