package com.codenameone.wordprocessor;

import java.util.*;

/**
 * Immutable paragraph stories placed in page margins. Variants are independent, including empty
 * ones.
 */
public final class PageFurniture {
  public static final String[] KEYS = {
    "header", "footer", "firstHeader", "firstFooter", "evenHeader", "evenFooter"
  };
  public static final PageFurniture EMPTY =
      new PageFurniture(Collections.<String, Sequence<Paragraph>>emptyMap(), false, false, 18, 18);
  public final Map<String, Sequence<Paragraph>> stories;
  public final boolean differentFirst, differentEven;
  public final float headerDistance, footerDistance;

  public PageFurniture(
      Map<String, Sequence<Paragraph>> stories,
      boolean first,
      boolean even,
      float header,
      float footer) {
    if (stories == null
        || Float.isNaN(header)
        || Float.isNaN(footer)
        || header < 0
        || footer < 0
        || header > 1000
        || footer > 1000) throw new IllegalArgumentException("Invalid header/footer geometry");
    for (Map.Entry<String, Sequence<Paragraph>> entry : stories.entrySet()) {
      check(entry.getKey());
      if (entry.getValue() == null || entry.getValue().size() > 1000)
        throw new IllegalArgumentException("Invalid header/footer story");
    }
    this.stories =
        Collections.unmodifiableMap(new LinkedHashMap<String, Sequence<Paragraph>>(stories));
    differentFirst = first;
    differentEven = even;
    headerDistance = header;
    footerDistance = footer;
  }

  public static void check(String key) {
    for (String k : KEYS) if (k.equals(key)) return;
    throw new IllegalArgumentException("Unknown header/footer variant");
  }

  public Sequence<Paragraph> get(String key) {
    check(key);
    var result = stories.get(key);
    return result == null ? new Sequence<Paragraph>() : result;
  }

  public String key(boolean footer, int page) {
    return key(footer, page, page + 1);
  }

  public String key(boolean footer, int sectionPage, int number) {
    String prefix =
        differentFirst && sectionPage == 0
            ? "first"
            : differentEven && number % 2 == 0 ? "even" : "";
    return prefix.isEmpty()
        ? (footer ? "footer" : "header")
        : prefix + (footer ? "Footer" : "Header");
  }

  public PageFurniture story(String key, Sequence<Paragraph> paragraphs) {
    check(key);
    var copy = new LinkedHashMap<String, Sequence<Paragraph>>(stories);
    copy.put(key, paragraphs);
    return new PageFurniture(copy, differentFirst, differentEven, headerDistance, footerDistance);
  }

  public PageFurniture inherit(String key) {
    check(key);
    var copy = new LinkedHashMap<String, Sequence<Paragraph>>(stories);
    copy.remove(key);
    return new PageFurniture(copy, differentFirst, differentEven, headerDistance, footerDistance);
  }

  public PageFurniture options(boolean first, boolean even, float header, float footer) {
    return new PageFurniture(stories, first, even, header, footer);
  }
}
