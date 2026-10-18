package com.codenameone.wordprocessor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A content control stored as an atomic {@link InlineField} whose instruction is {@code CONTROL
 * type key=value;...}. The field result is the control's current content, so every exporter that
 * shows field results shows the control; DOCX maps it to {@code w:sdt}.
 */
public final class ContentControl {
  public static final String PREFIX = "CONTROL ";
  public static final List<String> TYPES =
      Collections.unmodifiableList(
          Arrays.asList(
              "richText", "plainText", "picture", "buildingBlock", "checkBox", "comboBox",
              "dropDown", "datePicker", "repeatingSection"));
  public static final List<String> LOCKS =
      Collections.unmodifiableList(Arrays.asList("none", "contentLocked", "sdtLocked", "sdtContentLocked"));
  public static final String CHECKED = "\u2612", UNCHECKED = "\u2610";

  public final String type;
  private final TreeMap<String, String> props;

  private ContentControl(String type, TreeMap<String, String> props) {
    if (!TYPES.contains(type)) throw new IllegalArgumentException("Unknown content control: " + type);
    this.type = type;
    this.props = props;
  }

  public static ContentControl of(String type) {
    return new ContentControl(type, new TreeMap<String, String>());
  }

  public static boolean is(InlineField f) {
    return f != null && f.instruction.startsWith(PREFIX);
  }

  public String get(String key, String fallback) {
    var v = props.get(key);
    return v == null ? fallback : v;
  }

  public Map<String, String> properties() {
    return Collections.unmodifiableMap(props);
  }

  public ContentControl with(String key, String value) {
    if (key == null || key.isEmpty() || key.length() > 40) throw new IllegalArgumentException("Invalid property");
    for (int i = 0; i < key.length(); i++)
      if (key.charAt(i) > 127 || !Character.isLetterOrDigit(key.charAt(i)))
        throw new IllegalArgumentException("Invalid property: " + key);
    if (value != null && value.length() > 4000) throw new IllegalArgumentException("Property too long");
    if ("lock".equals(key) && value != null && !LOCKS.contains(value))
      throw new IllegalArgumentException("Lock must be one of " + LOCKS);
    var next = new TreeMap<String, String>(props);
    if (value == null || value.isEmpty()) next.remove(key);
    else next.put(key, value);
    return new ContentControl(type, next);
  }

  /** List items for combo boxes and drop-downs, separated by {@code |}. */
  public List<String> items() {
    var v = get("items", "");
    if (v.isEmpty()) return Collections.emptyList();
    return Arrays.asList(TextUtil.split(v, '|'));
  }

  public boolean contentLocked() {
    var lock = get("lock", "none");
    return "contentLocked".equals(lock) || "sdtContentLocked".equals(lock);
  }

  public String instruction() {
    var b = new StringBuilder(PREFIX).append(type);
    for (Map.Entry<String, String> e : props.entrySet()) {
      b.append(b.length() == PREFIX.length() + type.length() ? " " : ";").append(e.getKey()).append('=');
      var v = e.getValue();
      for (int i = 0; i < v.length(); i++) {
        char c = v.charAt(i);
        if (c == ';' || c == '\\') b.append('\\');
        b.append(c);
      }
    }
    return b.toString();
  }

  public static ContentControl parse(String instruction) {
    if (!instruction.startsWith(PREFIX)) throw new IllegalArgumentException("Not a content control");
    var rest = instruction.substring(PREFIX.length());
    int space = rest.indexOf(' ');
    ContentControl c = of(space < 0 ? rest : rest.substring(0, space));
    if (space < 0) return c;
    var cur = new StringBuilder();
    var body = rest.substring(space + 1);
    for (int i = 0; i <= body.length(); i++) {
      char ch = i < body.length() ? body.charAt(i) : ';';
      if (ch == '\\' && i + 1 < body.length()) {
        cur.append(body.charAt(++i));
        continue;
      }
      if (ch == ';') {
        var p = cur.toString();
        cur.setLength(0);
        int eq = p.indexOf('=');
        if (eq > 0) c = c.with(p.substring(0, eq), p.substring(eq + 1));
        continue;
      }
      cur.append(ch);
    }
    return c;
  }

  /** The placeholder shown while a control has no content. */
  public String placeholder() {
    if ("checkBox".equals(type)) return UNCHECKED;
    if ("picture".equals(type)) return "[Picture]";
    if ("datePicker".equals(type)) return "Click or tap to enter a date.";
    if ("dropDown".equals(type) || "comboBox".equals(type)) return "Choose an item.";
    if ("buildingBlock".equals(type)) return "Choose a building block.";
    return "Click or tap here to enter text.";
  }

  public InlineField field(String result) {
    return new InlineField(instruction(), result == null || result.isEmpty() ? placeholder() : result, false, false);
  }
}
