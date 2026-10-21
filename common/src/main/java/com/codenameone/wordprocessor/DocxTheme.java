package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.IOException;

/**
 * The DOCX theme part's fonts and colour scheme as Wordcraft reads them: {@code w:asciiTheme} /
 * {@code w:hAnsiTheme} name the major or minor Latin font, and {@code w:themeColor} /
 * {@code w:themeFill} a scheme colour (through {@code w:clrSchemeMapping}) with its tint and
 * shade applied to the HSL luminance. A theme value supersedes the fixed one beside it.
 */
final class DocxTheme {
  private static final String[] SCHEME = {"dk1", "lt1", "dk2", "lt2", "accent1", "accent2",
      "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"};
  private static final String[] NAMES = {"dark1", "light1", "dark2", "light2", "accent1",
      "accent2", "accent3", "accent4", "accent5", "accent6", "hyperlink", "followedHyperlink"};
  private static final String[] MAPPING = {"t1", "bg1", "t2", "bg2", "accent1", "accent2",
      "accent3", "accent4", "accent5", "accent6", "hyperlink", "followedHyperlink"};
  private static final String[] LOGICAL = {"text1", "background1", "text2", "background2"};

  /** Wordcraft's default theme accents 1-6 (wordcraft_doc::THEME_COLORS, the Office 2023 theme). */
  static final String[] DEFAULT_ACCENTS = {"156082", "E97132", "196B24", "0F9ED5", "A02B93", "4EA72E"};

  /** The document's theme accents 1-6 from its "theme.accents" setting, else Wordcraft's defaults. */
  static int[] accents(DocumentProperties p) {
    String[] got = p == null ? new String[0] : p.setting("theme.accents", "").split(",");
    int[] out = new int[6];
    for (int i = 0; i < 6; i++) {
      String v = i < got.length && got[i].length() == 6 ? got[i] : DEFAULT_ACCENTS[i];
      try {
        out[i] = Integer.parseInt(v, 16);
      } catch (NumberFormatException e) {
        out[i] = Integer.parseInt(DEFAULT_ACCENTS[i], 16);
      }
    }
    return out;
  }

  String majorFont = "", minorFont = "";
  final String[] colors = new String[12];
  final int[] map = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

  DocxTheme(byte[] theme, byte[] settings) throws IOException {
    if (theme != null) {
      Element root = Xml.parse(theme);
      Element elements = Xml.child(root, "themeElements");
      Element scheme = elements == null ? null : Xml.child(elements, "clrScheme");
      for (int i = 0; scheme != null && i < SCHEME.length; i++) {
        Element slot = Xml.child(scheme, SCHEME[i]);
        if (slot == null) continue;
        Element rgb = Xml.child(slot, "srgbClr"), sys = Xml.child(slot, "sysClr");
        String v = rgb != null ? Xml.attr(rgb, "val", "") : sys != null ? Xml.attr(sys, "lastClr", "") : "";
        if (v.length() == 6) colors[i] = v.toUpperCase();
      }
      Element fonts = elements == null ? null : Xml.child(elements, "fontScheme");
      if (fonts != null) {
        majorFont = latin(Xml.child(fonts, "majorFont"));
        minorFont = latin(Xml.child(fonts, "minorFont"));
      }
    }
    if (settings != null) {
      Element m = Xml.child(Xml.parse(settings), "clrSchemeMapping");
      for (int i = 0; m != null && i < MAPPING.length; i++) {
        String v = Xml.attr(m, MAPPING[i], "");
        for (int k = 0; k < NAMES.length; k++) if (NAMES[k].equals(v)) map[i] = k;
      }
    }
  }

  private static String latin(Element font) {
    Element latin = font == null ? null : Xml.child(font, "latin");
    return latin == null ? "" : Xml.attr(latin, "typeface", "");
  }

  /** The font a theme font name (majorAscii, minorHAnsi, ...) stands for, or null. */
  String font(String themed) {
    if (themed == null || themed.isEmpty()) return null;
    String f = themed.startsWith("major") ? majorFont : minorFont;
    return f.isEmpty() ? null : f;
  }

  /** The hex colour {@code e}'s {@code attr} theme colour gives with tint and shade, or null. */
  String color(Element e, String attr, String tint, String shade) {
    String name = Xml.attr(e, attr, "");
    if (name.isEmpty()) return null;
    int slot = -1;
    for (int k = 0; k < 4; k++) if (NAMES[k].equals(name)) slot = k;
    for (int k = 0; k < 4; k++) if (LOGICAL[k].equals(name)) slot = map[k];
    for (int k = 4; k < 12; k++) if (NAMES[k].equals(name)) slot = map[k];
    if (slot < 0 || colors[slot] == null) return null;
    int rgb = Integer.parseInt(colors[slot], 16);
    double r = (rgb >> 16 & 255) / 255.0, g = (rgb >> 8 & 255) / 255.0, b = (rgb & 255) / 255.0;
    double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
    double l = (max + min) / 2, h = 0, s = 0;
    if (max != min) {
      double d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      h = (max == r ? (g - b) / d + (g < b ? 6 : 0) : max == g ? (b - r) / d + 2 : (r - g) / d + 4) / 6;
    }
    double t = hexByte(Xml.attr(e, tint, "")), sh = hexByte(Xml.attr(e, shade, ""));
    if (t >= 0) l = Math.min(1, Math.max(0, l * t + (1 - t)));
    if (sh >= 0) l = Math.min(1, Math.max(0, l * sh));
    if (t < 0 && sh < 0) return colors[slot];
    int[] out = new int[3];
    if (s == 0) out[0] = out[1] = out[2] = channel(l);
    else {
      double q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
      double[] hs = {h + 1.0 / 3, h, h - 1.0 / 3};
      for (int k = 0; k < 3; k++) {
        double x = hs[k] - Math.floor(hs[k]);
        double v = x < 1.0 / 6 ? p + (q - p) * 6 * x : x < 0.5 ? q
            : x < 2.0 / 3 ? p + (q - p) * (2.0 / 3 - x) * 6 : p;
        out[k] = channel(v);
      }
    }
    return hex2(out[0]) + hex2(out[1]) + hex2(out[2]);
  }

  private static int channel(double v) {
    return (int) Math.max(0, Math.min(255, Math.round(v * 255)));
  }

  private static String hex2(int v) {
    String s = Integer.toHexString(v).toUpperCase();
    return s.length() < 2 ? "0" + s : s;
  }

  private static double hexByte(String v) {
    try {
      return v.trim().isEmpty() ? -1 : Integer.parseInt(v.trim(), 16) / 255.0;
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
