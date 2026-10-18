package com.codenameone.wordprocessor;

import java.util.Arrays;
import java.util.List;

/** Summary properties (File > Info > Properties) beyond the title, plus the protection mode. */
public final class DocumentProperties {
  public static final List<String> KEYS =
      Arrays.asList("title", "author", "subject", "keywords", "description", "category", "company", "manager");

  /** No restriction, open read-only, or restrict editing to comments or tracked changes. */
  public static final List<String> PROTECTION =
      Arrays.asList("none", "readOnly", "comments", "trackedChanges");

  public static final DocumentProperties EMPTY =
      new DocumentProperties(new String[KEYS.size()], "none");

  private final String[] values;
  public final String protection;

  /**
   * Application settings saved with the document (theme, hyphenation, grid, autocorrect, macros
   * and similar); keys are ASCII identifiers with dots, values are bounded strings.
   */
  private final java.util.TreeMap<String, String> settings;

  private DocumentProperties(String[] values, String protection) {
    this(values, protection, new java.util.TreeMap<>());
  }

  private DocumentProperties(
      String[] values, String protection, java.util.TreeMap<String, String> settings) {
    this.settings = settings;
    if (!PROTECTION.contains(protection)) throw new IllegalArgumentException("Invalid protection");
    this.values = values.clone();
    for (int i = 0; i < this.values.length; i++) {
      String v = this.values[i] == null ? "" : this.values[i];
      if (v.length() > 4096) throw new IllegalArgumentException("Property too long");
      this.values[i] = v;
    }
    this.protection = protection;
  }

  public String get(String key) {
    int i = KEYS.indexOf(key);
    if (i < 0) throw new IllegalArgumentException("Unknown property: " + key);
    return values[i];
  }

  public DocumentProperties with(String key, String value) {
    int i = KEYS.indexOf(key);
    if (i < 0) throw new IllegalArgumentException("Unknown property: " + key);
    var next = values.clone();
    next[i] = value;
    return new DocumentProperties(next, protection, settings);
  }

  public DocumentProperties protection(String value) {
    return new DocumentProperties(values, value, settings);
  }

  public String setting(String key, String fallback) {
    var v = settings.get(key);
    return v == null ? fallback : v;
  }

  public java.util.Map<String, String> settings() {
    return java.util.Collections.unmodifiableMap(settings);
  }

  /** Sets or (with null/empty) removes a saved setting. */
  public DocumentProperties withSetting(String key, String value) {
    if (key == null || key.isEmpty() || key.length() > 64)
      throw new IllegalArgumentException("Invalid setting");
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c > 127 || !(Unicode.letterOrDigit(c) || c == '.' || c == '_'))
        throw new IllegalArgumentException("Invalid setting: " + key);
    }
    if (value != null && value.length() > 65536) throw new IllegalArgumentException("Setting too long");
    var next = new java.util.TreeMap<String, String>(settings);
    if (value == null || value.isEmpty()) next.remove(key);
    else next.put(key, value);
    return new DocumentProperties(values, protection, next);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof DocumentProperties dp
        && Arrays.equals(values, dp.values)
        && protection.equals(dp.protection)
        && settings.equals(dp.settings);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values) * 31 + protection.hashCode() + settings.hashCode() * 7;
  }
}
