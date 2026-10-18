package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable, bounded bibliography record; author names use Last, First; Last, First. */
public final class CitationSource {
  public static final String[] KEYS = {"tag", "kind", "author", "editor", "title", "year", "month", "day",
      "city", "publisher", "journal", "edition", "volume", "issue", "pages", "url", "accessed", "doi"};
  public final Map<String, String> values;
  public CitationSource(Map<String, String> input) {
    var copy = new LinkedHashMap<String, String>();
    for (String key : input.keySet()) if (!Arrays.asList(KEYS).contains(key))
      throw new IllegalArgumentException("Unknown source field: " + key);
    for (String key : KEYS) {
      String value = input.containsKey(key) ? input.get(key) : "";
      if (value == null || value.length() > 2000) throw new IllegalArgumentException("Source field exceeds 2000 characters: " + key);
      for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127)
        throw new IllegalArgumentException("Source fields cannot contain control characters");
      copy.put(key, value.trim());
    }
    if (copy.get("kind").isEmpty()) copy.put("kind", "book");
    if (copy.get("tag").isEmpty()) {
      var authors = names(copy.get("author"));
      String base = authors.isEmpty() ? "Source" : authors.get(0)[0];
      var tag = new StringBuilder();
      for (char c : (base + copy.get("year")).toCharArray()) if (Character.isLetterOrDigit(c)) tag.append(c);
      copy.put("tag", tag.length() == 0 ? "Source" : tag.toString().substring(0, Math.min(64, tag.length())));
    }
    var tag = copy.get("tag");
    if (tag.length() > 64) throw new IllegalArgumentException("Source tag exceeds 64 characters");
    for (char c : tag.toCharArray()) if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.')
      throw new IllegalArgumentException("Source tags use letters, digits, dots, hyphens or underscores");
    values = Collections.unmodifiableMap(copy);
  }
  public String get(String key) { var value = values.get(key); return value == null ? "" : value; }
  public String tag() { return get("tag"); }
  public static List<String[]> names(String authors) {
    var result = new ArrayList<String[]>();
    for (String author : TextUtil.split(authors, ';')) {
      author = author.trim(); if (author.isEmpty()) continue;
      int comma = author.indexOf(',');
      if (comma >= 0) result.add(new String[]{author.substring(0, comma).trim(), author.substring(comma + 1).trim()});
      else { int space = author.lastIndexOf(' '); result.add(space < 0 ? new String[]{author, ""}
          : new String[]{author.substring(space + 1), author.substring(0, space).trim()}); }
    }
    return result;
  }
}
