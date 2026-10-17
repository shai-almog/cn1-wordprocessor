package com.codenameone.wordprocessor;

import com.codename1.ui.Display;
import com.codename1.ui.Font;
import java.io.*;

/** Bundled OFL fonts make family/weight selection work on every native target. */
final class DocumentFonts {
  static String family(String name) { return BundledFonts.family(name); }
  static String resource(TextStyle style) { return BundledFonts.resource(style); }

  static Font font(TextStyle style, float pixels) {
    String name = resource(style);
    return Font.createTrueTypeFont(name, name + ".ttf")
        .derive(
            pixels, (style.bold ? Font.STYLE_BOLD : 0) | (style.italic ? Font.STYLE_ITALIC : 0));
  }

  static final PdfExporter.FontProvider PDF = BundledFonts.provider(
      path -> Display.getInstance().getResourceAsStream(DocumentFonts.class, path));
}
