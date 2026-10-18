package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A vector object carried by a {@link Picture}: shapes, text boxes, WordArt, charts, SmartArt,
 * equations, ink, canvases, icons and signature lines. The picture supplies size, alignment, alt
 * text, selection and undo; the drawing is an immutable kind plus string properties that {@link
 * DrawingOps} turns into polygons and text for every renderer.
 */
public final class Drawing {
  public static final List<String> KINDS =
      Collections.unmodifiableList(
          Arrays.asList(
              "shape", "textBox", "wordArt", "chart", "smartArt", "equation", "ink", "canvas",
              "icon", "signature", "object"));

  /** 1x1 transparent PNG used as the picture payload of every drawing. */
  static final byte[] PLACEHOLDER =
      hex(
          "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b49444154789c6360000200000500017a5eab3f0000000049454e44ae426082");

  private static byte[] hex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return b;
  }

  public final String kind;
  private final TreeMap<String, String> props;

  private Drawing(String kind, TreeMap<String, String> props) {
    if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown drawing kind: " + kind);
    this.kind = kind;
    this.props = props;
  }

  public static Drawing of(String kind) {
    return new Drawing(kind, new TreeMap<String, String>());
  }

  public String get(String key, String fallback) {
    var v = props.get(key);
    return v == null ? fallback : v;
  }

  public float num(String key, float fallback) {
    var v = props.get(key);
    if (v == null || v.isEmpty()) return fallback;
    try {
      return Float.parseFloat(v);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public boolean flag(String key) {
    return "true".equals(props.get(key));
  }

  /** A hex RGB property, or the fallback; "none" gives -1. */
  public int color(String key, int fallback) {
    var v = props.get(key);
    if (v == null || v.isEmpty()) return fallback;
    if ("none".equals(v)) return -1;
    if (v.startsWith("#")) v = v.substring(1);
    if (v.length() != 6) return fallback;
    try {
      return Integer.parseInt(v, 16);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public Drawing with(String key, String value) {
    if (key == null || key.isEmpty() || key.length() > 40)
      throw new IllegalArgumentException("Invalid drawing property");
    for (int i = 0; i < key.length(); i++)
      if (!Character.isLetterOrDigit(key.charAt(i)) || key.charAt(i) > 127)
        throw new IllegalArgumentException("Invalid drawing property: " + key);
    if (value != null && value.length() > 200000)
      throw new IllegalArgumentException("Drawing property too long");
    var next = new TreeMap<String, String>(props);
    if (value == null || value.isEmpty()) next.remove(key);
    else next.put(key, value);
    return new Drawing(kind, next);
  }

  public Map<String, String> properties() {
    return Collections.unmodifiableMap(props);
  }

  /** {@code kind;key=value;...} with backslash escapes for {@code ; = \}. */
  public String encode() {
    var b = new StringBuilder(kind);
    for (Map.Entry<String, String> e : props.entrySet())
      b.append(';').append(e.getKey()).append('=').append(escape(e.getValue()));
    return b.toString();
  }

  public static Drawing decode(String text) {
    var parts = new ArrayList<String>();
    var cur = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' && i + 1 < text.length()) cur.append(c).append(text.charAt(++i));
      else if (c == ';') {
        parts.add(cur.toString());
        cur.setLength(0);
      } else cur.append(c);
    }
    parts.add(cur.toString());
    Drawing d = of(parts.get(0));
    for (int i = 1; i < parts.size(); i++) {
      var p = parts.get(i);
      int eq = p.indexOf('=');
      if (eq <= 0) throw new IllegalArgumentException("Invalid drawing property");
      d = d.with(p.substring(0, eq), unescape(p.substring(eq + 1)));
    }
    return d;
  }

  private static String escape(String v) {
    var b = new StringBuilder();
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == ';' || c == '=' || c == '\\') b.append('\\');
      b.append(c);
    }
    return b.toString();
  }

  private static String unescape(String v) {
    var b = new StringBuilder();
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '\\' && i + 1 < v.length()) c = v.charAt(++i);
      b.append(c);
    }
    return b.toString();
  }

  public boolean equals(Object o) {
    return o instanceof Drawing d && d.kind.equals(kind) && d.props.equals(props);
  }

  public int hashCode() {
    return kind.hashCode() * 31 + props.hashCode();
  }
}
