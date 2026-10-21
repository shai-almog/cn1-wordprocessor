package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Text box stories, as Wordcraft's text box parts: a drawing picture anywhere in the document
 * (body, table cells, notes, headers and footers) may hold a block story of its own. These walks
 * find and replace those pictures; text box stories do not nest.
 */
public final class TextBoxes {
  private TextBoxes() {}

  /** Pictures whose drawing holds a block story, in document order (body, notes, furniture). */
  public static List<Picture> all(Document d) {
    var out = new ArrayList<Picture>();
    map(d, p -> {
      if (p.drawing != null && p.drawing.story != null) out.add(p);
      return p;
    });
    return out;
  }

  /**
   * A group member's text box id: the group picture's id, a slash and the member's index (its
   * place in the group's items), as Wordcraft's group members hold text box parts of their own.
   */
  public static String memberId(String picture, int member) {
    return picture + "/" + member;
  }

  /** The member index of a group member's id, or -1 for a picture's own id. */
  static int member(String id) {
    int slash = id.lastIndexOf('/');
    if (slash < 0) return -1;
    try {
      return Integer.parseInt(id.substring(slash + 1));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String picture(String id) {
    return member(id) < 0 ? id : id.substring(0, id.lastIndexOf('/'));
  }

  /** Whether {@code id} names a text box or shape (or group member) that can hold a story. */
  public static boolean isBox(Document d, String id) {
    var p = find(d, id);
    if (p == null || p.drawing == null) return false;
    int k = member(id);
    if (k < 0) return p.drawing.storyKind();
    var items = p.drawing.items();
    if (k >= items.size() || items.get(k) == null) return false;
    try {
      return Drawing.decode(items.get(k)[4]).storyKind();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The drawing picture with {@code id} (for a group member: its group) anywhere, or null. */
  public static Picture find(Document d, String id) {
    final String wanted = picture(id);
    final Picture[] found = {null};
    map(d, p -> {
      if (found[0] == null && p.id().equals(wanted)) found[0] = p;
      return p;
    });
    return found[0];
  }

  /** The most boxes one story flows through (Wordcraft's MAX_LINKED_BOXES guard). */
  static final int MAX_LINKED = 64;

  /**
   * Linked text box chains, as Wordcraft's text_box_chains: each starts at a box no box links
   * to and follows the "linkNext" ids (the first box in document order to link to a box wins;
   * cycles and missing targets end a chain). Only chains of two or more boxes are listed.
   */
  public static List<List<String>> chains(Document d) {
    var boxes = new LinkedHashMap<String, Picture>();
    map(d, p -> {
      if (p.drawing != null && "textBox".equals(p.drawing.kind)) boxes.putIfAbsent(p.id(), p);
      return p;
    });
    var prev = new LinkedHashMap<String, String>();
    for (Picture p : boxes.values()) {
      String n = p.drawing.get("linkNext", "");
      if (!n.isEmpty() && !n.equals(p.id()) && boxes.containsKey(n)) prev.putIfAbsent(n, p.id());
    }
    var out = new ArrayList<List<String>>();
    for (Picture p : boxes.values()) {
      if (prev.containsKey(p.id())) continue;
      var chain = new ArrayList<String>();
      chain.add(p.id());
      String at = p.id();
      while (chain.size() < MAX_LINKED) {
        String n = boxes.get(at).drawing.get("linkNext", "");
        if (n.isEmpty() || !at.equals(prev.get(n)) || chain.contains(n)) break;
        chain.add(n);
        at = n;
      }
      if (chain.size() > 1) out.add(chain);
    }
    return out;
  }

  /**
   * Tags each chain for DOCX output as Wordcraft's writer numbers them: chain n (from 1) gets
   * "txbxId" n, its first box "txbxSeq" 0 (written as wps:txbx id) and each linked box its place
   * (written as wps:linkedTxbx). The first box takes the whole story (older per-box text joined).
   */
  static Document tagLinks(Document d) {
    var chains = chains(d);
    if (chains.isEmpty()) return d;
    var tags = new java.util.HashMap<String, String[]>();
    for (int n = 0; n < chains.size(); n++) {
      var c = chains.get(n);
      var whole = story(d, c.get(0));
      if (whole != null) d = setStory(d, c.get(0), whole);
      for (int seq = 0; seq < c.size(); seq++) tags.put(c.get(seq), new String[] {String.valueOf(n + 1), String.valueOf(seq)});
    }
    return map(d, p -> {
      var t = p.drawing == null ? null : tags.get(p.id());
      return t == null ? p : p.drawing(p.drawing.with("txbxId", t[0]).with("txbxSeq", t[1]));
    });
  }

  /**
   * Links the boxes a DOCX file chains (as Wordcraft's link_text_boxes): each "txbxId"'s boxes
   * in "txbxSeq" order after the box holding the text (seq 0). A repeated seq, a chain without
   * its first box or more than {@link #MAX_LINKED} boxes: the extra boxes stay apart. The tags
   * are dropped.
   */
  static Document resolveLinks(Document d) {
    var found = new ArrayList<String[]>();
    map(d, p -> {
      if (p.drawing != null && !p.drawing.get("txbxId", "").isEmpty())
        found.add(new String[] {p.drawing.get("txbxId", ""), p.drawing.get("txbxSeq", "0"), p.id()});
      return p;
    });
    if (found.isEmpty()) return d;
    found.sort((a, b) -> {
      long x = Long.parseLong(a[0]), y = Long.parseLong(b[0]);
      return x != y ? Long.compare(x, y) : Long.compare(Long.parseLong(a[1]), Long.parseLong(b[1]));
    });
    var next = new java.util.HashMap<String, String>();
    for (int i = 0; i < found.size();) {
      int j = i;
      while (j < found.size() && found.get(j)[0].equals(found.get(i)[0])) j++;
      if ("0".equals(found.get(i)[1])) {
        String prev = found.get(i)[2], seq = "0";
        int count = 1;
        for (int k = i + 1; k < j && count < MAX_LINKED; k++) {
          if (found.get(k)[1].equals(seq)) continue;
          seq = found.get(k)[1];
          next.put(prev, found.get(k)[2]);
          prev = found.get(k)[2];
          count++;
        }
      }
      i = j;
    }
    return map(d, p -> {
      if (p.drawing == null || p.drawing.get("txbxId", "").isEmpty()) return p;
      return p.drawing(p.drawing.with("txbxId", "").with("txbxSeq", "").with("linkNext", next.get(p.id())));
    });
  }

  /** The box whose story box {@code id} shows: its chain's first box, or itself. */
  public static String head(Document d, String id) {
    for (List<String> c : chains(d)) if (c.contains(id)) return c.get(0);
    return id;
  }

  /**
   * The story box {@code id} holds for editing and layout: its own, followed for a chain's first
   * box by any text its linked boxes still hold themselves (files from before stories flowed
   * through chains kept a single string per box). Null without text.
   */
  public static Sequence<Block> story(Document d, String id) {
    var box = find(d, id);
    if (box == null || box.drawing == null) return null;
    int k = member(id);
    if (k >= 0) {
      var own = box.drawing.memberStory(k);
      if (own != null) return own;
      var items = box.drawing.items();
      return k < items.size() && items.get(k) != null ? Drawing.decode(items.get(k)[4]).storyOrText(id + "-t") : null;
    }
    var out = box.drawing.storyOrText(id + "-t");
    for (List<String> c : chains(d)) {
      if (!c.get(0).equals(id)) continue;
      for (int i = 1; i < c.size(); i++) {
        var tail = find(d, c.get(i));
        var more = tail.drawing.storyOrText(c.get(i) + "-t");
        if (more == null) continue;
        if (out == null) out = more;
        else for (Block b : more) out = out.append(b);
      }
    }
    return out;
  }

  /**
   * Replaces the story of picture {@code id}; the boxes it links to hold no text of their own
   * (the story flows through them).
   */
  public static Document setStory(Document d, String id, Sequence<Block> story) {
    int k = member(id);
    if (k >= 0) {
      String group = picture(id);
      return map(d, p -> p.id().equals(group) && p.drawing != null ? p.drawing(p.drawing.memberStory(k, story)) : p);
    }
    var tails = new java.util.HashSet<String>();
    for (List<String> c : chains(d)) if (c.get(0).equals(id)) tails.addAll(c.subList(1, c.size()));
    return map(d, p -> {
      if (p.drawing == null) return p;
      if (p.id().equals(id)) return p.drawing(p.drawing.story(story));
      if (tails.contains(p.id()) && (p.drawing.story != null || !p.drawing.get("text", "").isEmpty()))
        return p.drawing(p.drawing.story(null).with("text", ""));
      return p;
    });
  }

  /** Maps every picture of the body, notes and headers/footers; returns d when none change. */
  public static Document map(Document d, UnaryOperator<Picture> f) {
    var out = d;
    var body = map(d.blocks, f);
    if (body != d.blocks) out = out.blocks(body, out.nextId);
    out = Notes.map(out, n -> {
      var mapped = map(n.blocks, f);
      return mapped == n.blocks ? n : n.blocks(mapped);
    });
    var furniture = furniture(out.furniture, f);
    if (furniture != out.furniture) out = out.furniture(furniture);
    if (!out.sections.isEmpty()) {
      boolean changed = false;
      var next = new LinkedHashMap<String, Section>();
      for (Map.Entry<String, Section> e : out.sections.entrySet()) {
        var s = e.getValue();
        var mf = furniture(s.furniture, f);
        if (mf != s.furniture) {
          s = s.furniture(mf);
          changed = true;
        }
        next.put(e.getKey(), s);
      }
      if (changed) out = out.sections(next, out.lastSection);
    }
    return out;
  }

  private static PageFurniture furniture(PageFurniture pf, UnaryOperator<Picture> f) {
    if (pf == null) return null;
    var out = pf;
    for (String key : new ArrayList<String>(pf.stories.keySet())) {
      var blocks = pf.stories.get(key);
      var mapped = map(blocks, f);
      if (mapped != blocks) out = out.blocks(key, mapped);
    }
    return out;
  }

  /** Maps the pictures of {@code in} at any table depth; returns {@code in} when none change. */
  public static Sequence<Block> map(Sequence<Block> in, UnaryOperator<Picture> f) {
    var out = in;
    for (int i = 0; i < in.size(); i++) {
      Block b = in.get(i), value = b;
      if (b instanceof Picture p) value = f.apply(p);
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) {
              var cell = t.blocks(r, c);
              var mapped = map(cell, f);
              if (mapped != cell) t = t.setBlocks(r, c, mapped);
            }
        value = t;
      }
      if (value != b) out = out.set(i, value);
    }
    return out;
  }

  /**
   * {@code story} showing {@code text} (paragraphs on lines, table cell paragraphs in their
   * place, as {@link Drawing#plainText} lists them) without flattening it: each line edits its
   * paragraph in place (the changed middle only, so the runs around it keep their formatting),
   * extra lines become paragraphs at the end formatted as the last, and missing lines drop
   * trailing paragraphs (a cell keeps its first, emptied). Tables and pictures stay where they
   * are, as Wordcraft only edits a box's text in its story and never flattens it.
   */
  public static Sequence<Block> retext(Sequence<Block> story, String text, long[] next) {
    if (Drawing.plainText(story).equals(text)) return story;
    var lines = DrawingOps.split(text, '\n');
    int[] at = {0};
    Paragraph[] last = {null};
    var out = retext(story, lines, at, last, true);
    for (Block b : out) if (b instanceof Paragraph p) last[0] = p;
    while (at[0] < lines.size()) {
      String line = lines.get(at[0]++);
      Paragraph p = last[0] == null ? new Paragraph("p" + next[0]++, RichText.of(line))
          : last[0].copy("p" + next[0]++, RichText.of(line, last[0].text.styleAt(Math.max(0, last[0].text.length() - 1))));
      out = out.append(p);
      last[0] = p;
    }
    return out;
  }

  private static Sequence<Block> retext(Sequence<Block> in, List<String> lines, int[] at, Paragraph[] last, boolean top) {
    var out = new Sequence<Block>();
    boolean any = false;
    for (Block b : in) {
      if (b instanceof Paragraph p) {
        last[0] = p;
        if (at[0] < lines.size()) out = out.append(edit(p, lines.get(at[0]++)));
        else if (!top && !any) out = out.append(edit(p, ""));
        else continue;
        any = true;
      } else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) t = t.setBlocks(r, c, retext(t.blocks(r, c), lines, at, last, false));
        out = out.append(t);
      } else out = out.append(b);
    }
    return out;
  }

  /** {@code p} showing {@code line}: only the changed middle is replaced. */
  private static Paragraph edit(Paragraph p, String line) {
    String was = p.text.toString();
    if (was.equals(line)) return p;
    int a = 0, n = Math.min(was.length(), line.length());
    while (a < n && was.charAt(a) == line.charAt(a)) a++;
    int z = 0;
    while (z < n - a && was.charAt(was.length() - 1 - z) == line.charAt(line.length() - 1 - z)) z++;
    var style = p.text.styleAt(a > 0 ? a - 1 : 0);
    return p.text(p.text.replace(a, was.length() - z, RichText.of(line.substring(a, line.length() - z), style)));
  }

  /**
   * A copy of {@code story} whose paragraphs (cells included) take fresh ids from {@code next},
   * for a pasted or duplicated text box (ids are unique document-wide, as comments anchor by them).
   */
  public static Sequence<Block> renumber(Sequence<Block> story, long[] next) {
    var out = new Sequence<Block>();
    for (Block b : story) {
      if (b instanceof Paragraph p) out = out.append(p.copy("p" + next[0]++, p.text));
      else if (b instanceof Table t) {
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++)
            if (!t.covered(r, c)) t = t.setBlocks(r, c, renumber(t.blocks(r, c), next));
        out = out.append(t);
      } else out = out.append(b);
    }
    return out;
  }
}
