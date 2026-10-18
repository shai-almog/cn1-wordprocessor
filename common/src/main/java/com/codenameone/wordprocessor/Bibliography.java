package com.codenameone.wordprocessor;

import java.util.*;

/** Document-owned source library and citation style, included in shared undo and recovery. */
public final class Bibliography {
  public static final Bibliography EMPTY = new Bibliography("APA", Collections.<String, CitationSource>emptyMap());
  public final String style;
  public final Map<String, CitationSource> sources;
  public Bibliography(String style, Map<String, CitationSource> sources) {
    if (style == null || style.isEmpty() || style.length() > 200 || sources == null || sources.size() > 2000)
      throw new IllegalArgumentException("Invalid bibliography");
    var copy = new LinkedHashMap<String, CitationSource>();
    for (Map.Entry<String, CitationSource> e : sources.entrySet()) {
      if (e.getValue() == null || !e.getKey().equals(e.getValue().tag())) throw new IllegalArgumentException("Invalid source key");
      copy.put(e.getKey(), e.getValue());
    }
    this.style = style; this.sources = Collections.unmodifiableMap(copy);
  }
  public Bibliography put(CitationSource source) {
    var copy = new LinkedHashMap<String, CitationSource>(sources); copy.put(source.tag(), source);
    return new Bibliography(style, copy);
  }
  public Bibliography remove(String tag) {
    var copy = new LinkedHashMap<String, CitationSource>(sources); copy.remove(tag);
    return new Bibliography(style, copy);
  }
  public Bibliography style(String value) { return new Bibliography(value, sources); }
}
