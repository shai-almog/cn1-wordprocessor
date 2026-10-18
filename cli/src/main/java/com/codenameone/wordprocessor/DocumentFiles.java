package com.codenameone.wordprocessor;

import com.codename1.ui.RichTextFormat;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Desktop file boundary. Shared codecs live in formats. */
public final class DocumentFiles {
  public static final class Opened {
    public final Document document;
    public final Docx.Opened source;
    public final List<String> warnings;

    Opened(Document d, Docx.Opened s, List<String> w) {
      document = d;
      source = s;
      warnings = w;
    }
  }

  private DocumentFiles() {}

  private static final PdfExporter.FontProvider FONTS =
      BundledFonts.provider(DocumentFiles.class::getResourceAsStream);

  static Commands commands() {
    return new Commands(PdfExporter.layoutEngine(FONTS));
  }

  public static Opened read(Path path) throws IOException {
    if (Files.size(path) > 128 * 1024 * 1024) throw new IOException("File exceeds 128 MiB");
    byte[] bytes = Files.readAllBytes(path);
    String ext = extension(path);
    if ("docx".equals(ext)) {
      Docx.Opened d = Docx.read(new ByteArrayInputStream(bytes));
      return new Opened(d.document, d, d.warnings);
    }
    if ("cn1doc".equals(ext)) {
      ProjectFile.Opened d = ProjectFile.read(bytes);
      return new Opened(
          d.document,
          d.source,
          d.source == null ? Collections.<String>emptyList() : d.source.warnings);
    }
    RichTextFormat format = format(ext);
    if (format == null) throw new IOException("Unsupported format: " + ext);
    Document d = TextFormats.read(new String(bytes, "UTF-8"), format);
    return new Opened(
        d.title(path.getFileName().toString()),
        null,
        Collections.singletonList(
            "Text format import supports a documented rich-text subset; use native project files for lossless editing"));
  }

  public static byte[] write(
      Document d, Docx.Opened source, String extension, boolean allowConversion)
      throws IOException {
    if ("pdf".equals(extension)) return PdfExporter.write(d,
        FONTS);
    if ("cn1doc".equals(extension)) return ProjectFile.write(d, source);
    if ("docx".equals(extension)) {
      var out = new ByteArrayOutputStream();
      Docx.write(d, source, out, allowConversion);
      return out.toByteArray();
    }
    RichTextFormat format = format(extension);
    if (format == null) throw new IOException("Unsupported output format: " + extension);
    if (format != RichTextFormat.HTML && format != RichTextFormat.PLAIN_TEXT)
      for (Block b : d.blocks)
        if (b instanceof Table)
          throw new IOException("Table export requires DOCX, HTML or native project format");
    return TextFormats.write(d, format).getBytes("UTF-8");
  }

  public static String extension(Path p) {
    var n = p.getFileName().toString();
    int i = n.lastIndexOf('.');
    return i < 0 ? "" : n.substring(i + 1).toLowerCase(java.util.Locale.ROOT);
  }

  public static RichTextFormat format(String ext) {
    if ("txt".equals(ext)) return RichTextFormat.PLAIN_TEXT;
    if ("html".equals(ext) || "htm".equals(ext)) return RichTextFormat.HTML;
    if ("md".equals(ext)) return RichTextFormat.MARKDOWN;
    if ("rtf".equals(ext)) return RichTextFormat.RTF;
    if ("adoc".equals(ext)) return RichTextFormat.ASCIIDOC;
    return null;
  }
}
