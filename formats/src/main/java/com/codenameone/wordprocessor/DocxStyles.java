package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Resolves document defaults and bounded basedOn chains before applying direct formatting. */
final class DocxStyles {
  private final Map<String, Element> definitions = new HashMap<String, Element>();
  private final Set<String> warnings;
  private Element runDefaults, paragraphDefaults;
  private String defaultParagraph = "Normal";
  final float tabInterval;

  DocxStyles(byte[] bytes, Set<String> warnings) throws IOException {
    this(bytes, null, warnings);
  }

  DocxStyles(byte[] bytes, byte[] settings, Set<String> warnings) throws IOException {
    this.warnings = warnings;
    float interval = 36;
    if (settings != null) {
      Element stop = Xml.child(Xml.parse(settings), "defaultTabStop");
      if (stop != null) {
        try {
          interval = Float.parseFloat(Xml.attr(stop, "val", "720")) / 20f;
          ParagraphFormat.DEFAULT.with("tabInterval", String.valueOf(interval));
        } catch (IllegalArgumentException invalid) {
          warnings.add("Invalid document default tab interval");
          interval = 36;
        }
      }
    }
    tabInterval = interval;
    if (bytes == null) return;
    Element root = Xml.parse(bytes), defaults = Xml.child(root, "docDefaults");
    if (defaults != null) {
      Element r = Xml.child(defaults, "rPrDefault"), p = Xml.child(defaults, "pPrDefault");
      if (r != null) runDefaults = Xml.child(r, "rPr");
      if (p != null) paragraphDefaults = Xml.child(p, "pPr");
    }
    for (Element s : Xml.children(root, "style")) {
      String id = Xml.attr(s, "styleId", "");
      if (!id.isEmpty()) definitions.put(id, s);
      if ("paragraph".equals(Xml.attr(s, "type", "")) && "1".equals(Xml.attr(s, "default", "0")))
        defaultParagraph = id;
    }
  }

  Map<String, String> paragraphNames() {
    Map<String, String> names = new LinkedHashMap<String, String>();
    for (Map.Entry<String, Element> entry : definitions.entrySet())
      if ("paragraph".equals(Xml.attr(entry.getValue(), "type", ""))) {
        Element name = Xml.child(entry.getValue(), "name");
        names.put(
            entry.getKey(), name == null ? entry.getKey() : Xml.attr(name, "val", entry.getKey()));
      }
    return names;
  }

  String defaultParagraph() {
    return defaultParagraph;
  }

  private List<Element> chain(String id) {
    List<Element> result = new ArrayList<Element>();
    Set<String> seen = new HashSet<String>();
    while (!id.isEmpty()) {
      if (!seen.add(id) || result.size() >= 64) {
        warnings.add("Cyclic or excessively deep style inheritance: " + id);
        break;
      }
      Element style = definitions.get(id);
      if (style == null) break;
      result.add(0, style);
      Element parent = Xml.child(style, "basedOn");
      id = parent == null ? "" : Xml.attr(parent, "val", "");
    }
    return result;
  }

  List<Element> paragraphProperties(String id) {
    List<Element> result = new ArrayList<Element>();
    if (paragraphDefaults != null) result.addAll(Xml.children(paragraphDefaults, null));
    for (Element s : chain(id)) {
      Element p = Xml.child(s, "pPr");
      if (p != null) result.addAll(Xml.children(p, null));
    }
    return result;
  }

  // Style-chain resolution is the same for every run sharing a paragraph and character
  // style; resolving it per run dominated opening large documents.
  private final java.util.Map<String, TextStyle> resolved = new java.util.HashMap<String, TextStyle>();

  private ParagraphFormat baseFormat;

  /** The default paragraph format with this document's tab interval, built once. */
  ParagraphFormat baseFormat() {
    if (baseFormat == null)
      baseFormat = ParagraphFormat.DEFAULT.with("tabInterval", String.valueOf(tabInterval));
    return baseFormat;
  }

  private final Map<String, TextStyle> runs = new HashMap<String, TextStyle>();

  TextStyle text(String paragraphStyle, Element direct) {
    Element characterStyle = direct == null ? null : Xml.child(direct, "rStyle");
    String character = characterStyle == null ? null : Xml.attr(characterStyle, "val", "");
    String key = paragraphStyle + "\u0000" + character;
    TextStyle result = resolved.get(key);
    if (result == null) {
      result = apply(TextStyle.DEFAULT, runDefaults, false);
      for (Element s : chain(paragraphStyle)) result = apply(result, Xml.child(s, "rPr"), true);
      if (character != null)
        for (Element s : chain(character)) result = apply(result, Xml.child(s, "rPr"), true);
      resolved.put(key, result);
    }
    Element change = direct == null ? null : Xml.child(direct, "rPrChange");
    TextStyle current;
    if (direct == null) current = result;
    else if (change != null) current = apply(result, direct, false);
    else {
      // Runs repeat a handful of direct formats; reuse one style object per distinct rPr.
      String runKey = key + "\u0001" + Xml.write(direct);
      current = runs.get(runKey);
      if (current == null) {
        current = apply(result, direct, false);
        if (runs.size() > 4096) runs.clear();
        runs.put(runKey, current);
      }
    }
    if (change != null) {
      TextStyle before = apply(result, Xml.child(change, "rPr"), false);
      current =
          current.formatRevision(
              new Revision(
                  Xml.attr(change, "id", "0"),
                  "format",
                  Xml.attr(change, "author", ""),
                  Xml.attr(change, "date", "")),
              before);
    }
    return current;
  }

  private TextStyle apply(TextStyle s, Element props, boolean toggle) {
    if (props == null) return s;
    for (Element p : Xml.children(props, null)) {
      String k = Xml.local(p), value = Xml.attr(p, "val", "");
      if ("b".equals(k) || "i".equals(k) || "strike".equals(k)) {
        String key = "b".equals(k) ? "bold" : "i".equals(k) ? "italic" : "strike";
        boolean current = "b".equals(k) ? s.bold : "i".equals(k) ? s.italic : s.strike;
        boolean on = !("0".equals(value) || "false".equals(value) || "off".equals(value));
        s = s.with(key, String.valueOf(toggle ? (on ? !current : current) : on));
      } else if ("u".equals(k)) {
        String type =
            value.isEmpty()
                ? "single"
                : ("0".equals(value) || "false".equals(value) || "off".equals(value))
                    ? "none"
                    : value;
        if (!java.util.Arrays.asList(CharacterFormat.UNDERLINES).contains(type)) type = "single";
        s = s.with("underlineStyle", type).with("underlineColor", Xml.attr(p, "color", "auto"));
      } else if (java.util.Arrays.asList(
              "dstrike", "smallCaps", "caps", "vanish", "outline", "shadow", "emboss", "imprint")
          .contains(k)) {
        String key =
            "dstrike".equals(k)
                ? "doubleStrike"
                : "caps".equals(k)
                    ? "allCaps"
                    : "vanish".equals(k) ? "hidden" : "imprint".equals(k) ? "engrave" : k;
        boolean on = !("0".equals(value) || "false".equals(value) || "off".equals(value));
        boolean current = Boolean.parseBoolean(s.characters.value(key));
        s = s.with(key, String.valueOf(toggle ? (on ? !current : current) : on));
      } else if ("spacing".equals(k))
        s = s.with("characterSpacing", String.valueOf(Float.parseFloat(value) / 20));
      else if ("w".equals(k)) s = s.with("characterScale", value);
      else if ("position".equals(k))
        s = s.with("characterPosition", String.valueOf(Float.parseFloat(value) / 2));
      else if ("vertAlign".equals(k)) s = s.with("script", value);
      else if ("bdr".equals(k)) {
        String color = Xml.attr(p, "color", "000000");
        if (!"none".equals(value) && !"nil".equals(value))
          s = s.with("border", "auto".equals(color) ? "000000" : color);
      } else if ("lang".equals(k) && !value.isEmpty()) s = s.with("lang", value);
      else if ("sz".equals(k)) s = s.with("size", String.valueOf(Float.parseFloat(value) / 2));
      else if ("color".equals(k)) {
        if (!value.isEmpty() && !"auto".equals(value)) s = s.with("color", value);
      } else if ("rFonts".equals(k))
        s = s.with("font", Xml.attr(p, "ascii", Xml.attr(p, "hAnsi", s.font)));
      else if ("shd".equals(k)) {
        String fill = Xml.attr(p, "fill", "auto");
        if (!"auto".equals(fill)) s = s.with("background", fill);
      } else if ("highlight".equals(k)) {
        String[] names = {
          "black",
          "blue",
          "cyan",
          "green",
          "magenta",
          "red",
          "yellow",
          "white",
          "darkBlue",
          "darkCyan",
          "darkGreen",
          "darkMagenta",
          "darkRed",
          "darkYellow",
          "darkGray",
          "lightGray",
          "none"
        };
        String[] colors = {
          "000000", "0000ff", "00ffff", "00ff00", "ff00ff", "ff0000", "ffff00", "ffffff", "000080",
          "008080", "008000", "800080", "800000", "808000", "808080", "c0c0c0", "ffffff"
        };
        for (int i = 0; i < names.length; i++)
          if (names[i].equals(value)) s = s.with("background", colors[i]);
      } else if (!Arrays.asList("rStyle", "lang", "noProof", "bCs", "iCs", "szCs", "rPrChange")
          .contains(k)) warnings.add("Character feature: " + k);
    }
    return s;
  }
}
