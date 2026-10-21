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
              "icon", "signature", "object", "picture"));

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

  /**
   * A text box's (or shape's) own block story, as Wordcraft's text boxes hold a part of blocks:
   * paragraphs with ids, tables and pictures; null for a drawing whose text is the single
   * "text" property (older files, WordArt, equations and linked boxes).
   */
  public final Sequence<Block> story;

  /**
   * A group's members' block stories by member index (the order of its "items"), as Wordcraft's
   * group members hold text box parts; null when no member has one.
   */
  private final TreeMap<Integer, Sequence<Block>> members;

  private Drawing(String kind, TreeMap<String, String> props) {
    this(kind, props, null, null);
  }

  private Drawing(String kind, TreeMap<String, String> props, Sequence<Block> story) {
    this(kind, props, story, null);
  }

  private Drawing(String kind, TreeMap<String, String> props, Sequence<Block> story,
      TreeMap<Integer, Sequence<Block>> members) {
    if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unknown drawing kind: " + kind);
    this.kind = kind;
    this.props = props;
    this.story = story;
    this.members = members == null || members.isEmpty() ? null : members;
  }

  /** The members of a group (canvas "items"): {x, y, w, h, encoding} each. */
  public List<String[]> items() {
    var out = new ArrayList<String[]>();
    String all = get("items", "");
    if (all.isEmpty()) return out;
    for (String item : DrawingOps.split(all, '\u001e')) {
      var f = DrawingOps.split(item, '\u001f');
      out.add(f.size() == 5 ? f.toArray(new String[5]) : null);
    }
    return out;
  }

  /** Member {@code k}'s block story, or null. */
  public Sequence<Block> memberStory(int k) {
    return members == null ? null : members.get(k);
  }

  /** The member indexes holding a story, ascending. */
  public java.util.Set<Integer> storyMembers() {
    return members == null ? Collections.<Integer>emptySet() : Collections.unmodifiableSet(members.keySet());
  }

  /**
   * This group with member {@code k}'s story replaced (null or empty removes it); the member's
   * own "text" property mirrors its plain text as a text box's does.
   */
  public Drawing memberStory(int k, Sequence<Block> blocks) {
    var items = items();
    if (k < 0 || k >= items.size() || items.get(k) == null) throw new IllegalArgumentException("No group member " + k);
    var next = members == null ? new TreeMap<Integer, Sequence<Block>>() : new TreeMap<Integer, Sequence<Block>>(members);
    String[] item = items.get(k);
    var member = decode(item[4]);
    if (blocks == null || blocks.size() == 0) next.remove(k);
    else {
      next.put(k, blocks);
      member = member.story(blocks).story(null);
    }
    var b = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      String[] f = items.get(i);
      if (f == null) continue;
      if (b.length() > 0) b.append('\u001e');
      for (int j = 0; j < 4; j++) b.append(f[j]).append('\u001f');
      b.append(i == k ? member.encode() : f[4]);
    }
    var props = new TreeMap<String, String>(this.props);
    props.put("items", b.toString());
    return new Drawing(kind, props, story, next);
  }

  /** Whether this kind of drawing can hold a block story (Wordcraft's wps:txbx shapes). */
  public boolean storyKind() {
    return "textBox".equals(kind) || "shape".equals(kind);
  }

  /**
   * This drawing with {@code blocks} as its text story; the "text" property mirrors its plain
   * text (paragraphs on lines) for search, counts and the legacy text paths. Null or empty
   * removes the story.
   */
  public Drawing story(Sequence<Block> blocks) {
    if (blocks == null || blocks.size() == 0) return new Drawing(kind, props, null, members);
    boolean any = false;
    for (Block b : blocks) any |= b instanceof Paragraph;
    if (!any) throw new IllegalArgumentException("A text box story needs a paragraph");
    var next = new TreeMap<String, String>(props);
    String plain = plainText(blocks);
    if (plain.isEmpty()) next.remove("text");
    else next.put("text", plain.length() > 200000 ? plain.substring(0, 200000) : plain);
    return new Drawing(kind, next, blocks, members);
  }

  /** The story's text: paragraphs (and table cells) on their own lines. */
  public static String plainText(Sequence<Block> blocks) {
    var b = new StringBuilder();
    appendText(b, blocks);
    return b.toString();
  }

  private static void appendText(StringBuilder b, Sequence<Block> blocks) {
    for (Block block : blocks)
      if (block instanceof Paragraph p) {
        if (b.length() > 0) b.append('\n');
        b.append(p.text.plainText());
      } else if (block instanceof Table t)
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++) if (!t.covered(r, c)) appendText(b, t.blocks(r, c));
  }

  /**
   * The story shown for this drawing: its own, or for a legacy single-string text box the text
   * as paragraphs formatted by the drawing's text properties (font size, bold, italic, color
   * and alignment), with ids {@code prefix}0, {@code prefix}1 and so on. Null without text.
   */
  public Sequence<Block> storyOrText(String prefix) {
    if (story != null) return story;
    var text = get("text", "");
    if (text.isEmpty() || !storyKind()) return null;
    boolean box = "textBox".equals(kind);
    var style = TextStyle.DEFAULT.with("size", FloatPosition.num(num("fontSize", 11)));
    if (flag("bold")) style = style.with("bold", "true");
    if (flag("italic")) style = style.with("italic", "true");
    int color = color("textColor", box ? 0 : 0xffffff);
    if (color >= 0) style = style.with("color", hex6(color));
    var align = get("align", box ? "left" : "center");
    if (!"center".equals(align) && !"right".equals(align)) align = "left";
    var out = new Sequence<Block>();
    int i = 0;
    for (String line : DrawingOps.split(text, '\n')) {
      var p = new Paragraph(prefix + i++, RichText.of(line, style));
      if (!"left".equals(align)) p = p.with("align", align);
      out = out.append(p.with("after", "0"));
    }
    return out;
  }

  private static String hex6(int rgb) {
    var s = Integer.toHexString(rgb & 0xffffff);
    while (s.length() < 6) s = "0" + s;
    return s;
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
      if (!Unicode.letterOrDigit(key.charAt(i)) || key.charAt(i) > 127)
        throw new IllegalArgumentException("Invalid drawing property: " + key);
    if (value != null && value.length() > 200000)
      throw new IllegalArgumentException("Drawing property too long");
    var next = new TreeMap<String, String>(props);
    if (value == null || value.isEmpty()) next.remove(key);
    else next.put(key, value);
    // Setting the plain text replaces a story whose text it does not match (the single-string
    // editing paths: linked boxes, the text dialog); other properties keep the story.
    var keep = story;
    if (keep != null && "text".equals(key) && !plainText(keep).equals(value == null ? "" : value)) keep = null;
    // Replacing a group's members drops their stories.
    return new Drawing(kind, next, keep, "items".equals(key) ? null : members);
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
    return o instanceof Drawing d && d.kind.equals(kind) && d.props.equals(props)
        && java.util.Objects.equals(d.story, story) && java.util.Objects.equals(d.members, members);
  }

  public int hashCode() {
    return kind.hashCode() * 31 + props.hashCode();
  }
}
