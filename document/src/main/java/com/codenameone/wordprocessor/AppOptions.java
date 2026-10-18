package com.codenameone.wordprocessor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Application-level options (File > Options): kept per user, not per document. The editor installs
 * a persistent {@link Backend}; headless use keeps them in memory.
 */
public final class AppOptions {
  public interface Backend {
    void save(Map<String, String> values);
  }

  /** Known options and their defaults; unknown keys are rejected. */
  public static final Map<String, String> DEFAULTS;

  static {
    var d = new LinkedHashMap<String, String>();
    d.put("userName", "");
    d.put("userInitials", "");
    d.put("units", "inches");
    d.put("autosave", "true");
    d.put("autosaveMinutes", "10");
    d.put("numeral", "arabic");
    d.put("checkSpelling", "true");
    d.put("checkGrammar", "true");
    d.put("autocorrect.replaceText", "true");
    d.put("autocorrect.capitalizeSentences", "true");
    d.put("autocorrect.capitalizeDays", "true");
    d.put("autocorrect.twoInitialCaps", "true");
    d.put("autocorrect.smartQuotes", "true");
    d.put("autocorrect.entries", "(c)=\u00a9|(r)=\u00ae|(tm)=\u2122|--=\u2014|->=\u2192|teh=the|adn=and|recieve=receive|seperate=separate|occured=occurred");
    d.put("ribbon.hidden", "");
    d.put("ribbon.custom", "");
    d.put("keys.custom", "");
    d.put("printMarkup", "true");
    d.put("showComments", "true");
    d.put("hideInk", "false");
    d.put("officeTheme", "system");
    DEFAULTS = Collections.unmodifiableMap(d);
  }

  private static final TreeMap<String, String> VALUES = new TreeMap<>();
  private static Backend backend;

  private AppOptions() {}

  /** Install persistence and the values it loaded. */
  public static synchronized void install(Backend b, Map<String, String> loaded) {
    VALUES.clear();
    if (loaded != null)
      for (Map.Entry<String, String> e : loaded.entrySet())
        if (DEFAULTS.containsKey(e.getKey()) && e.getValue() != null) VALUES.put(e.getKey(), e.getValue());
    backend = b;
  }

  public static synchronized String get(String key) {
    if (!DEFAULTS.containsKey(key)) throw new IllegalArgumentException("Unknown option: " + key);
    var v = VALUES.get(key);
    return v == null ? DEFAULTS.get(key) : v;
  }

  public static boolean flag(String key) {
    return "true".equals(get(key));
  }

  public static synchronized void set(String key, String value) {
    if (!DEFAULTS.containsKey(key)) throw new IllegalArgumentException("Unknown option: " + key);
    if (value == null || value.equals(DEFAULTS.get(key))) VALUES.remove(key);
    else VALUES.put(key, value);
    if (backend != null) backend.save(new TreeMap<>(VALUES));
  }

  public static synchronized Map<String, String> all() {
    var out = new LinkedHashMap<String, String>();
    for (String k : DEFAULTS.keySet()) out.put(k, get(k));
    return out;
  }

  /** Reset every option to its default (tests and "Reset" in the dialog). */
  public static synchronized void reset() {
    VALUES.clear();
    if (backend != null) backend.save(new TreeMap<>());
  }

  /** AutoCorrect replacement list as from-to pairs. */
  public static Map<String, String> autocorrect() {
    var out = new LinkedHashMap<String, String>();
    String raw = get("autocorrect.entries");
    if (raw.isEmpty()) return out;
    for (String pair : TextUtil.split(raw, '|')) {
      int eq = pair.indexOf('=', 1);
      if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return out;
  }

  public static void autocorrect(Map<String, String> entries) {
    var b = new StringBuilder();
    for (Map.Entry<String, String> e : entries.entrySet()) {
      if (e.getKey().isEmpty() || e.getKey().indexOf('|') >= 0 || e.getValue().indexOf('|') >= 0)
        throw new IllegalArgumentException("AutoCorrect entries cannot contain '|'");
      if (b.length() > 0) b.append('|');
      b.append(e.getKey()).append('=').append(e.getValue());
    }
    set("autocorrect.entries", b.toString());
  }

  /**
   * Word's AutoCorrect as you type: after a word-ending character, the word before it may be
   * replaced. Returns the corrected text of {@code before} (text up to and excluding the typed
   * delimiter), or null when nothing changes.
   */
  public static String correct(String before) {
    int start = before.length();
    while (start > 0 && !Character.isWhitespace(before.charAt(start - 1))) start--;
    var word = before.substring(start);
    if (word.isEmpty()) return null;
    var out = word;
    if (flag("autocorrect.replaceText")) {
      var r = autocorrect().get(word);
      if (r == null) r = autocorrect().get(word.toLowerCase());
      if (r != null) out = r;
    }
    if (flag("autocorrect.twoInitialCaps") && out.length() > 2 && Character.isUpperCase(out.charAt(0))
        && Character.isUpperCase(out.charAt(1)) && Character.isLowerCase(out.charAt(2)))
      out = out.charAt(0) + out.substring(1).toLowerCase();
    if (flag("autocorrect.capitalizeDays")) {
      String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
      for (String d : days) if (d.equals(out)) out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }
    if (flag("autocorrect.capitalizeSentences") && Character.isLowerCase(out.charAt(0))) {
      int i = start - 1;
      while (i >= 0 && Character.isWhitespace(before.charAt(i))) i--;
      if (i < 0 || (before.charAt(i) == '.' || before.charAt(i) == '!' || before.charAt(i) == '?') && i < start - 1)
        out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }
    return out.equals(word) ? null : before.substring(0, start) + out;
  }

  /** Numeral shaping for display: arabic (0-9), hindi (Arabic-Indic), context (Hindi in RTL paragraphs). */
  public static String numerals(String text, boolean rtl) {
    String mode = get("numeral");
    if (!"hindi".equals(mode) && !("context".equals(mode) && rtl)) return text;
    var c = text.toCharArray();
    for (int i = 0; i < c.length; i++) if (c[i] >= '0' && c[i] <= '9') c[i] = (char) ('\u0660' + c[i] - '0');
    return new String(c);
  }
}
