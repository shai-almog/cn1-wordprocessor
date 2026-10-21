package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.util.*;

final class RevisionFormats {
  private RevisionFormats() {}

  static void attributes(StringBuilder b, Revision r, String prefix) {
    if (r == null) return;
    attributes(b, r.previous, prefix + "prior-");
    b.append(" ")
        .append(prefix)
        .append("id=\"")
        .append(Xml.escape(r.id))
        .append("\" ")
        .append(prefix)
        .append("kind=\"")
        .append(r.kind)
        .append("\" ")
        .append(prefix)
        .append("author=\"")
        .append(Xml.escape(r.author))
        .append("\" ")
        .append(prefix)
        .append("date=\"")
        .append(Xml.escape(r.date))
        .append("\"");
    if (r.move != null)
      b.append(" ").append(prefix).append("move=\"").append(Xml.escape(r.move)).append("\"");
  }

  static Revision nativeRead(Element e, String prefix) {
    String id = Xml.attr(e, prefix + "id", "");
    return id.isEmpty()
        ? null
        : new Revision(
                id,
                Xml.attr(e, prefix + "kind", ""),
                Xml.attr(e, prefix + "author", ""),
                Xml.attr(e, prefix + "date", ""))
            .previous(nativeRead(e, prefix + "prior-"))
            .move(Xml.attr(e, prefix + "move", null));
  }

  static Revision docxRead(Element e) {
    String kind = Xml.local(e);
    return new Revision(
            Xml.attr(e, "id", "0"),
            "ins".equals(kind) || "moveTo".equals(kind) ? "insert" : "delete",
            Xml.attr(e, "author", ""),
            Xml.attr(e, "date", ""))
        .move(Xml.attr(e, MOVE_NAME, null));
  }

  /** Whether a run container or mark element is a tracked insertion/deletion or move end. */
  static boolean tracked(String kind) {
    return "ins".equals(kind) || "del".equals(kind) || "moveFrom".equals(kind) || "moveTo".equals(kind);
  }

  /** Attribute the move pre-pass stamps on each w:moveFrom / w:moveTo with its range's name. */
  static final String MOVE_NAME = "wp-move-name";

  /**
   * Names each w:moveFrom / w:moveTo (run containers and paragraph-mark elements alike) after
   * the w:moveFromRangeStart / w:moveToRangeStart range around it, as Wordcraft's reader: a
   * range that never ends, or a name with only one end, leaves its revisions plain deletions
   * and insertions.
   */
  static void markMoves(Element root) {
    if (!Xml.mayContain(root, "moveFrom") && !Xml.mayContain(root, "moveTo")) return;
    var open = new LinkedHashMap<String, String>();
    var pending = new HashMap<String, List<Element>>();
    var named = new ArrayList<Object[]>();
    walkMoves(root, open, pending, named, Collections.emptyMap());
    var from = new HashSet<String>();
    var to = new HashSet<String>();
    for (Object[] n : named) ("moveFrom".equals(Xml.local((Element) n[0])) ? from : to).add((String) n[1]);
    for (Object[] n : named)
      if (from.contains(n[1]) && to.contains(n[1])) ((Element) n[0]).setAttribute(MOVE_NAME, (String) n[1]);
  }

  private static void walkMoves(
      Element e,
      Map<String, String> open,
      Map<String, List<Element>> pending,
      List<Object[]> named,
      Map<String, String> paragraph) {
    if ("p".equals(Xml.local(e))) {
      // A paragraph mark's move outside any range is named by the range starting in its paragraph.
      paragraph = new HashMap<>();
      for (int i = 0; i < e.getNumChildren(); i++) {
        Element c = e.getChildAt(i);
        String k = c.isTextElement() ? "" : Xml.local(c);
        if (k.endsWith("RangeStart") && k.startsWith("move") && !paragraph.containsKey(k))
          paragraph.put(k.substring(0, k.indexOf('R')), Xml.attr(c, "name", ""));
      }
    }
    for (int i = 0; i < e.getNumChildren(); i++) {
      Element c = e.getChildAt(i);
      if (c.isTextElement()) continue;
      String k = Xml.local(c);
      if ("moveFromRangeStart".equals(k) || "moveToRangeStart".equals(k)) {
        String key = k.substring(0, k.indexOf('R')) + ":" + Xml.attr(c, "id", "");
        open.put(key, Xml.attr(c, "name", ""));
        pending.put(key, new ArrayList<>());
      } else if ("moveFromRangeEnd".equals(k) || "moveToRangeEnd".equals(k)) {
        String key = k.substring(0, k.indexOf('R')) + ":" + Xml.attr(c, "id", "");
        String name = open.remove(key);
        List<Element> got = pending.remove(key);
        if (name != null && !name.isEmpty())
          for (Element m : got) named.add(new Object[] {m, name});
      } else if ("moveFrom".equals(k) || "moveTo".equals(k)) {
        String last = null;
        for (String key : open.keySet()) if (key.startsWith(k + ":")) last = key;
        if (last != null) pending.get(last).add(c);
        else if (paragraph.get(k) != null && !paragraph.get(k).isEmpty())
          named.add(new Object[] {c, paragraph.get(k)});
        walkMoves(c, open, pending, named, paragraph);
      } else walkMoves(c, open, pending, named, paragraph);
    }
  }

  private static int rangeId(Revision r) {
    return 700000000 + (("move:" + r.id).hashCode() & 0xffffff);
  }

  static void start(StringBuilder b, Revision r) {
    if (r != null && r.previous != null) start(b, r.previous);
    if (r == null) return;
    if (r.move != null) {
      String tag = r.inserted() ? "w:moveTo" : "w:moveFrom";
      b.append('<').append(tag).append("RangeStart w:id=\"").append(rangeId(r))
          .append("\" w:name=\"").append(Xml.escape(r.move))
          .append("\" w:author=\"").append(Xml.escape(r.author))
          .append("\" w:date=\"").append(Xml.escape(r.date)).append("\"/>");
      b.append('<').append(tag).append(" w:id=\"");
    } else b.append(r.inserted() ? "<w:ins w:id=\"" : "<w:del w:id=\"");
    b.append(Xml.escape(r.id))
        .append("\" w:author=\"")
        .append(Xml.escape(r.author))
        .append("\" w:date=\"")
        .append(Xml.escape(r.date))
        .append("\">");
  }

  static void end(StringBuilder b, Revision r) {
    if (r != null) {
      if (r.move != null) {
        String tag = r.inserted() ? "w:moveTo" : "w:moveFrom";
        b.append("</").append(tag).append("><").append(tag).append("RangeEnd w:id=\"")
            .append(rangeId(r)).append("\"/>");
      } else b.append(r.inserted() ? "</w:ins>" : "</w:del>");
      if (r.previous != null) end(b, r.previous);
    }
  }

  /** An empty named range after w:pPr naming a moved paragraph mark (read back by markMoves). */
  static void markRanges(StringBuilder b, Revision r) {
    for (; r != null; r = r.previous)
      if (r.move != null) {
        String tag = r.inserted() ? "w:moveTo" : "w:moveFrom";
        int id = 600000000 + (("mark:" + r.id).hashCode() & 0xffffff);
        b.append('<').append(tag).append("RangeStart w:id=\"").append(id)
            .append("\" w:name=\"").append(Xml.escape(r.move))
            .append("\" w:author=\"").append(Xml.escape(r.author))
            .append("\" w:date=\"").append(Xml.escape(r.date)).append("\"/><")
            .append(tag).append("RangeEnd w:id=\"").append(id).append("\"/>");
      }
  }

  /** A paragraph mark's revision as the empty w:rPr child (w:moveTo/w:moveFrom for a move). */
  static void mark(StringBuilder b, Revision r) {
    String tag = r.move != null ? (r.inserted() ? "w:moveTo" : "w:moveFrom") : r.inserted() ? "w:ins" : "w:del";
    b.append('<').append(tag).append(" w:id=\"").append(Xml.escape(r.id))
        .append("\" w:author=\"").append(Xml.escape(r.author))
        .append("\" w:date=\"").append(Xml.escape(r.date)).append("\"/>");
  }

  static void styleAttributes(StringBuilder b, TextStyle s, String prefix) {
    String[] names = {
      "font",
      "size",
      "color",
      "background",
      "bold",
      "italic",
      "underline",
      "strike",
      "link",
      "script"
    };
    String[] values = {
      s.font,
      String.valueOf(s.size),
      Integer.toHexString(s.color),
      Integer.toHexString(s.background),
      String.valueOf(s.bold),
      String.valueOf(s.italic),
      String.valueOf(s.underline),
      String.valueOf(s.strike),
      s.link,
      s.script
    };
    characterAttributes(b, s, prefix);
    for (int i = 0; i < names.length; i++)
      b.append(" ")
          .append(prefix)
          .append(names[i])
          .append("=\"")
          .append(Xml.escape(values[i]))
          .append("\"");
  }

  static TextStyle readStyle(Element e, String prefix) {
    return readCharacters(
        e,
        prefix,
        new TextStyle(
            Xml.attr(e, prefix + "font", "Serif"),
            Xml.number(Xml.attr(e, prefix + "size", "12")),
            Integer.parseInt(Xml.attr(e, prefix + "color", "202833"), 16),
            Boolean.parseBoolean(Xml.attr(e, prefix + "bold", "false")),
            Boolean.parseBoolean(Xml.attr(e, prefix + "italic", "false")),
            Boolean.parseBoolean(Xml.attr(e, prefix + "underline", "false")),
            Boolean.parseBoolean(Xml.attr(e, prefix + "strike", "false")),
            Integer.parseInt(Xml.attr(e, prefix + "background", "ffffff"), 16),
            Xml.attr(e, prefix + "link", ""),
            Xml.attr(e, prefix + "script", "baseline")));
  }

  static void characterAttributes(StringBuilder b, TextStyle s, String prefix) {
    for (String key : CharacterFormat.KEYS)
      b.append(" ")
          .append(prefix)
          .append(key)
          .append("=\"")
          .append(s.characters.value(key))
          .append("\"");
  }

  static TextStyle readCharacters(Element e, String prefix, TextStyle style) {
    for (String key : CharacterFormat.KEYS)
      style = style.with(key, Xml.attr(e, prefix + key, style.characters.value(key)));
    return style;
  }

  static void formatStart(StringBuilder b, Revision r) {
    b.append("<w:rPrChange w:id=\"")
        .append(Xml.escape(r.id))
        .append("\" w:author=\"")
        .append(Xml.escape(r.author))
        .append("\" w:date=\"")
        .append(Xml.escape(r.date))
        .append("\">");
  }

  static Document reserveIds(Document d) {
    return d.blocks(d.blocks, Revisions.nextId(d));
  }
}
