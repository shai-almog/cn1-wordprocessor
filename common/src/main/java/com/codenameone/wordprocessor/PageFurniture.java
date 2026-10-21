package com.codenameone.wordprocessor;

import java.util.*;

/**
 * Immutable header/footer stories placed in page margins. Like Word and Wordcraft, a story holds
 * any body block: paragraphs, tables (nested too) and pictures, inline or floating. Variants are
 * independent, including empty ones; an absent variant links to the previous section.
 */
public final class PageFurniture {
  public static final String[] KEYS = {
    "header", "footer", "firstHeader", "firstFooter", "evenHeader", "evenFooter"
  };
  public static final PageFurniture EMPTY =
      new PageFurniture(Collections.<String, Sequence<Block>>emptyMap(), false, false, 18, 18);
  public final Map<String, Sequence<Block>> stories;
  private final Map<String, Sequence<Paragraph>> paragraphViews = new HashMap<>();
  public final boolean differentFirst, differentEven;
  public final float headerDistance, footerDistance;

  public PageFurniture(
      Map<String, ? extends Sequence<? extends Block>> stories,
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
    var copy = new LinkedHashMap<String, Sequence<Block>>();
    for (Map.Entry<String, ? extends Sequence<? extends Block>> entry : stories.entrySet()) {
      check(entry.getKey());
      if (entry.getValue() == null || entry.getValue().size() > 1000)
        throw new IllegalArgumentException("Invalid header/footer story");
      copy.put(entry.getKey(), widen(entry.getValue()));
    }
    this.stories = Collections.unmodifiableMap(copy);
    differentFirst = first;
    differentEven = even;
    headerDistance = header;
    footerDistance = footer;
  }

  public static void check(String key) {
    for (String k : KEYS) if (k.equals(key)) return;
    throw new IllegalArgumentException("Unknown header/footer variant");
  }

  @SuppressWarnings("unchecked")
  private static Sequence<Block> widen(Sequence<? extends Block> blocks) {
    // Sequence is immutable, so a paragraph sequence is safely a block sequence.
    return (Sequence<Block>) blocks;
  }

  /** The story's top-level paragraphs, skipping tables and pictures (see {@link #blocks}). */
  public Sequence<Paragraph> get(String key) {
    check(key);
    synchronized (paragraphViews) {
      var view = paragraphViews.get(key);
      if (view == null) {
        view = new Sequence<Paragraph>();
        for (Block b : blocks(key)) if (b instanceof Paragraph p) view = view.append(p);
        paragraphViews.put(key, view);
      }
      return view;
    }
  }

  /** The story's content: paragraphs, tables and pictures in order. */
  public Sequence<Block> blocks(String key) {
    check(key);
    var result = stories.get(key);
    return result == null ? new Sequence<Block>() : result;
  }

  /**
   * The story shown for {@code key} once sections are resolved. As in Wordcraft, an even-page
   * variant that no section defines falls back to the default one.
   */
  public Sequence<Block> shown(String key) {
    if (key.startsWith("even") && !stories.containsKey(key))
      return blocks(key.endsWith("Footer") ? "footer" : "header");
    return blocks(key);
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

  /** Replaces the whole story, tables and pictures included, with {@code paragraphs}. */
  public PageFurniture story(String key, Sequence<Paragraph> paragraphs) {
    return blocks(key, widen(paragraphs));
  }

  public PageFurniture blocks(String key, Sequence<Block> blocks) {
    check(key);
    var copy = new LinkedHashMap<String, Sequence<Block>>(stories);
    copy.put(key, blocks);
    return new PageFurniture(copy, differentFirst, differentEven, headerDistance, footerDistance);
  }

  /** Every paragraph of the story in reading order, inside tables (nested too) as well. */
  public List<Paragraph> allParagraphs(String key) {
    var out = new ArrayList<Paragraph>();
    mapBlocks(
        blocks(key),
        p -> {
          out.add(p);
          return p;
        });
    return out;
  }

  /** Maps every paragraph of every story, inside tables (nested too) as well. */
  public PageFurniture map(java.util.function.UnaryOperator<Paragraph> f) {
    var out = this;
    for (Map.Entry<String, Sequence<Block>> e : stories.entrySet()) {
      var mapped = mapBlocks(e.getValue(), f);
      if (mapped != e.getValue()) out = out.blocks(e.getKey(), mapped);
    }
    return out;
  }

  /** Maps the paragraphs of {@code in} at any table depth; returns {@code in} when none change. */
  public static Sequence<Block> mapBlocks(
      Sequence<Block> in, java.util.function.UnaryOperator<Paragraph> f) {
    var out = in;
    for (int i = 0; i < in.size(); i++) {
      Block b = in.get(i), value = b;
      if (b instanceof Paragraph p) value = f.apply(p);
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) {
              var cell = t.blocks(r, c);
              var mapped = mapBlocks(cell, f);
              if (mapped != cell) t = t.setBlocks(r, c, mapped);
            }
        value = t;
      }
      if (value != b) out = out.set(i, value);
    }
    return out;
  }

  public PageFurniture inherit(String key) {
    check(key);
    var copy = new LinkedHashMap<String, Sequence<Block>>(stories);
    copy.remove(key);
    return new PageFurniture(copy, differentFirst, differentEven, headerDistance, footerDistance);
  }

  public PageFurniture options(boolean first, boolean even, float header, float footer) {
    return new PageFurniture(stories, first, even, header, footer);
  }
}
