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

  private DocumentProperties(String[] values, String protection) {
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
    String[] next = values.clone();
    next[i] = value;
    return new DocumentProperties(next, protection);
  }

  public DocumentProperties protection(String value) {
    return new DocumentProperties(values, value);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof DocumentProperties
        && Arrays.equals(values, ((DocumentProperties) o).values)
        && protection.equals(((DocumentProperties) o).protection);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(values) * 31 + protection.hashCode();
  }
}
