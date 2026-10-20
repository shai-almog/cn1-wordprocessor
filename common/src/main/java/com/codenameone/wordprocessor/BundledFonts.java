package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/** Shared OFL font names and bytes for native rendering and headless export. */
public final class BundledFonts {
  private BundledFonts() {}
  public interface Resources {
    InputStream open(String path) throws IOException;
  }

  public static String family(String name) {
    var n = name.toLowerCase();
    if (n.contains("sans")) return "Source Sans 3";
    if (n.contains("mono") || n.contains("courier")) return "JetBrains Mono";
    if (n.contains("serif")
        || n.contains("times")
        || n.contains("georgia")
        || n.contains("cambria")) return "Source Serif 4";
    return "Source Sans 3";
  }

  public static String resource(TextStyle style) {
    String family = family(style.font), name;
    if ("Source Serif 4".equals(family))
      name =
          "SourceSerif4-"
              + (style.bold
                  ? (style.italic ? "BoldIt" : "Bold")
                  : (style.italic ? "It" : "Regular"));
    else if ("JetBrains Mono".equals(family))
      name =
          "JetBrainsMono-"
              + (style.bold
                  ? (style.italic ? "BoldItalic" : "Bold")
                  : (style.italic ? "Italic" : "Regular"));
    else
      name =
          "SourceSans3-"
              + (style.bold
                  ? (style.italic ? "BoldIt" : "Bold")
                  : (style.italic ? "It" : "Regular"));
    return name;
  }


  public static PdfExporter.FontProvider provider(final Resources resources) {
    return new Provider(resources);
  }

  /** Noto faces for scripts the document families lack, tried in order per character. */
  public static List<String> fallbacks(TextStyle style) {
    String weight = style.bold ? "Bold" : "Regular";
    return Arrays.asList("NotoSansHebrew-" + weight, "NotoNaskhArabic-" + weight);
  }

  private static final class Provider implements PdfExporter.FontProvider, PdfExporter.FallbackFonts {
    private final Resources resources;

    Provider(Resources resources) {
      this.resources = resources;
    }

    public String name(TextStyle style) { return resource(style); }
    public List<String> fallbacks(TextStyle style) { return BundledFonts.fallbacks(style); }
    public byte[] bytes(String name) throws IOException {
      try (var in = resources.open("/" + name + ".ttf")) {
        if (in == null) throw new IOException("Missing bundled font " + name);
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) {
          if (out.size() + n > 20 * 1024 * 1024) throw new IOException("Font exceeds size limit");
          out.write(buffer, 0, n);
        }
        return out.toByteArray();
      }
    }
  }
}
