package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Word's catalog ids for features that previously existed only as unrelated UI labels or under
 * other command names: Go To, Sort, Distributed, Word Count, Editor, Info, comment actions,
 * links, bookmarks, fields, margins, orientation, object position, select and equation
 * structures.
 */
final class WordAliasCommands implements Commands.Extension {
  private Commands c;

  public void register(final Commands c) {
    this.c = c;
    c.register(
        "edit.goto",
        "Go To: what is page, line, section, bookmark, comment, heading, table, picture or"
            + " equation; target is a 1-based number, +n/-n relative, or a bookmark name",
        "what:string,target?:any",
        false,
        (s, a) -> goTo(s, Commands.str(a, "what"), a.get("target")));
    c.register(
        "para.sort",
        "Sort the selected paragraphs (or all body paragraphs) as text or numbers",
        "descending?:boolean,numeric?:boolean",
        true,
        (s, a) -> sort(s, Boolean.TRUE.equals(a.get("descending")), Boolean.TRUE.equals(a.get("numeric"))));
    c.register(
        "para.distribute",
        "Distributed alignment: spread characters evenly between the margins",
        "",
        true,
        (s, a) -> {
          s.paragraph("align", "distribute");
          return null;
        });
    c.register(
        "review.wordCount",
        "Word Count: pages, words, characters (with and without spaces), paragraphs and lines",
        "",
        false,
        (s, a) -> wordCount(s));
    c.register(
        "review.editor",
        "Editor pane: spelling and grammar issue counts with the first suggestion",
        "",
        false,
        (s, a) -> c.execute(s, "review.spelling", Json.object("action", "check")));
    c.register(
        "file.info",
        "Document information: title, properties, statistics, protection and tracking",
        "",
        false,
        (s, a) -> {
          Document d = s.document();
          Map<String, Object> out = wordCount(s);
          out.put("title", d.title);
          out.put("trackChanges", d.trackChanges);
          out.put("comments", d.comments.size());
          out.put("properties", c.execute(s, "file.properties", Collections.<String, Object>emptyMap()));
          return out;
        });
    c.register(
        "review.deleteComment",
        "Delete the comment at the selection (or by id)",
        "id?:string",
        true,
        (s, a) -> c.execute(s, "comment.delete", Json.object("id", commentId(s, a))));
    c.register(
        "review.resolveComment",
        "Resolve (or reopen) the comment at the selection (or by id)",
        "id?:string,resolved?:boolean",
        true,
        (s, a) ->
            c.execute(
                s,
                "comment.resolve",
                Json.object("id", commentId(s, a), "resolved", !Boolean.FALSE.equals(a.get("resolved")))));
    c.register(
        "review.reply",
        "Reply to the comment at the selection (or by id)",
        "id?:string,author?:string,text:string",
        true,
        (s, a) ->
            c.execute(
                s,
                "comment.reply",
                Json.object(
                    "id", commentId(s, a),
                    "author", a.containsKey("author") ? a.get("author") : s.reviewAuthor(),
                    "text", a.get("text"))));
    c.register(
        "insert.link",
        "Link the selection to a URL, or insert the URL as linked text",
        "url:string,text?:string",
        true,
        (s, a) -> {
          String url = Commands.str(a, "url");
          if (url.trim().isEmpty()) throw new IllegalArgumentException("Enter a link address");
          Selection x = s.selection();
          if (x.start() == x.end() || a.containsKey("text")) {
            String text = a.containsKey("text") ? Commands.str(a, "text") : url;
            int from = x.start();
            s.replace(text);
            s.select(s.selection().block, from, from + text.length());
          }
          s.format("link", url);
          return url;
        });
    c.register(
        "insert.bookmark",
        "Add a bookmark at the selection",
        "name:string",
        true,
        (s, a) -> c.execute(s, "bookmark.add", a));
    c.register(
        "insert.field",
        "Insert a field by instruction, such as PAGE, DATE or NUMPAGES",
        "instruction:string,result?:string",
        true,
        (s, a) -> c.execute(s, "field.insert", a));
    c.register(
        "layout.margins",
        "Margins: a preset (normal, narrow, moderate, wide, mirrored) or explicit point values",
        "preset?:string,top?:number,right?:number,bottom?:number,left?:number",
        true,
        (s, a) -> {
          Map<String, Object> m = Json.object();
          String preset = a.containsKey("preset") ? Commands.str(a, "preset") : null;
          float[] v =
              preset == null ? null
                  : "normal".equals(preset) ? new float[] {72, 72, 72, 72}
                  : "narrow".equals(preset) ? new float[] {36, 36, 36, 36}
                  : "moderate".equals(preset) ? new float[] {72, 54, 72, 54}
                  : "wide".equals(preset) ? new float[] {72, 144, 72, 144}
                  : "mirrored".equals(preset) ? new float[] {72, 72, 72, 90}
                  : null;
          if (preset != null && v == null) throw new IllegalArgumentException("Unknown margins " + preset);
          String[] keys = {"top", "right", "bottom", "left"};
          for (int i = 0; i < 4; i++) {
            if (v != null) m.put(keys[i], (double) v[i]);
            if (a.containsKey(keys[i])) m.put(keys[i], a.get(keys[i]));
          }
          if (m.isEmpty()) throw new IllegalArgumentException("Choose a preset or margin values");
          return c.execute(s, "layout.pageSetup", m);
        });
    c.register(
        "layout.orientation",
        "Page orientation: portrait or landscape",
        "value:string",
        true,
        (s, a) -> {
          String v = Commands.str(a, "value");
          if (!"portrait".equals(v) && !"landscape".equals(v))
            throw new IllegalArgumentException("Orientation is portrait or landscape");
          return c.execute(s, "layout.pageSetup", Json.object("orientation", v));
        });
    c.register(
        "arrange.position",
        "Position an object: inline, or with square wrapping at left, center or right (a"
            + " top/middle/bottom prefix such as topLeft is accepted; objects stay anchored to"
            + " their paragraph vertically)",
        "block?:integer,position:string",
        true,
        (s, a) -> {
          int block = a.containsKey("block") ? Commands.integer(a, "block") : s.selection().block;
          Block b = s.document().blocks.get(block);
          if (!(b instanceof Picture)) throw new IllegalArgumentException("Select a picture or shape");
          Picture p = (Picture) b;
          String pos = Commands.str(a, "position").toLowerCase();
          String align =
              pos.endsWith("left") ? "left" : pos.endsWith("right") ? "right" : "center";
          boolean inline = "inline".equals(pos);
          if (!inline && !pos.endsWith("left") && !pos.endsWith("right") && !pos.endsWith("center"))
            throw new IllegalArgumentException("Unknown position " + pos);
          Picture next = p.resize(p.width, p.height, p.alt, inline ? p.align : align);
          if (p.drawing != null) next = next.drawing(p.drawing.with("wrap", inline ? "inline" : "square"));
          s.replacePicture(block, next);
          return inline ? "inline" : align;
        });
    c.register(
        "draw.select",
        "Select an object (ink, shape or picture) by block, as the Draw tab's Select tool does",
        "block:integer",
        false,
        (s, a) -> {
          int block = Commands.integer(a, "block");
          if (!(s.document().blocks.get(block) instanceof Picture))
            throw new IllegalArgumentException("Select a drawing or picture");
          // Object commands act on the object just before the caret (see DrawingCommands.block).
          Document d = s.document();
          int at = block + 1;
          if (at >= d.blocks.size() || !(d.blocks.get(at) instanceof Paragraph))
            throw new IllegalArgumentException("The object needs a following paragraph");
          s.leaveCell();
          s.select(at, 0, 0);
          return block;
        });
    c.register(
        "equation.insertStructure",
        "Insert an equation structure (fraction, script, radical, integral, sum, bracket,"
            + " function, accent, limit, operator, matrix), creating an equation when needed",
        "kind:string",
        true,
        (s, a) -> {
          String kind = Commands.str(a, "kind");
          if ("largeOperator".equals(kind) || "operator".equals(kind)) kind = "sum";
          int block = s.selection().block;
          Block b = s.document().blocks.get(block);
          if (!equation(b)) {
            int from = block;
            c.execute(s, "insert.equation", Json.object("text", ""));
            block = -1;
            for (int i = from; i < s.document().blocks.size() && block < 0; i++)
              if (equation(s.document().blocks.get(i))) block = i;
          }
          return c.execute(s, "equation.structure", Json.object("block", block, "kind", kind));
        });
  }

  private static boolean equation(Block b) {
    return b instanceof Picture && ((Picture) b).drawing != null && "equation".equals(((Picture) b).drawing.kind);
  }

  private Object goTo(Session s, String what, Object target) {
    Document d = s.document();
    List<Integer> blocks = new ArrayList<Integer>();
    if ("bookmark".equals(what)) {
      if (!(target instanceof String)) throw new IllegalArgumentException("Enter a bookmark name");
      c.execute(s, "bookmark.goto", Json.object("name", target));
      return target;
    }
    if ("page".equals(what) || "line".equals(what)) {
      LayoutEngine.Result r = c.layoutEngine().layout(d, s.revision());
      List<LayoutEngine.Line> lines = new ArrayList<LayoutEngine.Line>();
      if ("page".equals(what)) {
        for (LayoutEngine.Page p : r.pages) if (!p.lines.isEmpty()) lines.add(p.lines.get(0));
      } else for (LayoutEngine.Page p : r.pages) for (LayoutEngine.Line l : p.lines) if (l.row < 0) lines.add(l);
      int current = 0;
      for (int i = 0; i < lines.size(); i++)
        if (lines.get(i).block < s.selection().block
            || lines.get(i).block == s.selection().block && lines.get(i).start <= s.selection().caret) current = i;
      int i = index(target, current, lines.size());
      LayoutEngine.Line l = lines.get(i);
      s.leaveCell();
      s.select(l.block, l.start, l.start);
      return i + 1;
    }
    for (int i = 0; i < d.blocks.size(); i++) {
      Block b = d.blocks.get(i);
      boolean match =
          "section".equals(what) ? sectionStarts(d).contains(i)
              : "heading".equals(what) ? b instanceof Paragraph && ((Paragraph) b).style.startsWith("Heading")
              : "table".equals(what) ? b instanceof Table
              : "picture".equals(what) ? b instanceof Picture && (((Picture) b).drawing == null || !"equation".equals(((Picture) b).drawing.kind))
              : "equation".equals(what) ? b instanceof Picture && ((Picture) b).drawing != null && "equation".equals(((Picture) b).drawing.kind)
              : "comment".equals(what) && commentBlocks(d).contains(i);
      if (match) blocks.add(i);
    }
    if (!"section".equals(what) && !"heading".equals(what) && !"table".equals(what)
        && !"picture".equals(what) && !"equation".equals(what) && !"comment".equals(what))
      throw new IllegalArgumentException("Unknown Go To target " + what);
    if (blocks.isEmpty()) throw new IllegalArgumentException("The document has no " + what);
    int current = 0;
    for (int i = 0; i < blocks.size(); i++) if (blocks.get(i) <= s.selection().block) current = i;
    int i = index(target, current, blocks.size());
    s.leaveCell();
    s.select(blocks.get(i), 0, 0);
    return i + 1;
  }

  private static int index(Object target, int current, int size) {
    int i;
    if (target == null) i = current + 1;
    else if (target instanceof Number) i = ((Number) target).intValue() - 1;
    else {
      String t = ((String) target).trim();
      if (t.startsWith("+")) i = current + Integer.parseInt(t.substring(1));
      else if (t.startsWith("-")) i = current - Integer.parseInt(t.substring(1));
      else i = Integer.parseInt(t) - 1;
    }
    if (i < 0 || i >= size) throw new IllegalArgumentException("Go To target out of range");
    return i;
  }

  private static List<Integer> sectionStarts(Document d) {
    List<Integer> out = new ArrayList<Integer>();
    for (Section.Range r : d.sectionRanges()) out.add(r.first);
    return out;
  }

  private static List<Integer> commentBlocks(Document d) {
    List<Integer> out = new ArrayList<Integer>();
    for (ReviewComment rc : d.comments)
      for (int i = 0; i < d.blocks.size(); i++)
        if (d.blocks.get(i).id().equals(rc.startId) && !out.contains(i)) out.add(i);
    Collections.sort(out);
    return out;
  }

  private static String commentId(Session s, Map<String, Object> a) {
    if (a.get("id") instanceof String) return (String) a.get("id");
    Document d = s.document();
    Selection x = s.selection();
    String para = d.blocks.get(x.block).id();
    ReviewComment best = null;
    for (ReviewComment rc : d.comments) {
      if (!rc.startId.equals(para) && !rc.endId.equals(para)) continue;
      if (best == null || rc.start <= x.caret && x.caret <= rc.end) best = rc;
    }
    if (best == null) throw new IllegalArgumentException("Select text that has a comment");
    return best.id;
  }

  private Object sort(Session s, boolean descending, final boolean numeric) {
    Selection x = s.selection();
    int from = Math.min(x.anchorBlock, x.block), to = Math.max(x.anchorBlock, x.block);
    Document d = s.document();
    if (from == to) {
      from = 0;
      to = d.blocks.size() - 1;
    }
    List<Paragraph> paras = new ArrayList<Paragraph>();
    for (int i = from; i <= to; i++) {
      if (!(d.blocks.get(i) instanceof Paragraph))
        throw new IllegalArgumentException("Sort applies to paragraphs; use Table Sort for tables");
      paras.add((Paragraph) d.blocks.get(i));
    }
    List<Paragraph> sorted = new ArrayList<Paragraph>(paras);
    Collections.sort(
        sorted,
        (p, q) -> {
          String a = p.text.plainText().trim(), b = q.text.plainText().trim();
          if (numeric) {
            double va = leadingNumber(a), vb = leadingNumber(b);
            if (va != vb) return va < vb ? -1 : 1;
          }
          return a.compareToIgnoreCase(b);
        });
    if (descending) Collections.reverse(sorted);
    Sequence<Block> blocks = d.blocks;
    for (int i = 0; i < sorted.size(); i++) blocks = blocks.set(from + i, sorted.get(i));
    s.updateBlocks(blocks);
    return sorted.size();
  }

  private static double leadingNumber(String t) {
    int i = 0;
    while (i < t.length() && (Character.isDigit(t.charAt(i)) || t.charAt(i) == '.' || i == 0 && t.charAt(i) == '-')) i++;
    try {
      return i == 0 ? Double.MAX_VALUE : Double.parseDouble(t.substring(0, i));
    } catch (NumberFormatException ex) {
      return Double.MAX_VALUE;
    }
  }

  private Map<String, Object> wordCount(Session s) {
    Document d = s.document();
    String text = d.plainText();
    int words = new DocumentStats().words(d), chars = 0, noSpaces = 0, paragraphs = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '\n') continue;
      chars++;
      if (!Character.isWhitespace(ch)) noSpaces++;
    }
    for (int i = 0; i < d.blocks.size(); i++)
      if (d.blocks.get(i) instanceof Paragraph && !((Paragraph) d.blocks.get(i)).text.plainText().trim().isEmpty())
        paragraphs++;
    LayoutEngine.Result r = c.layoutEngine().layout(d, s.revision());
    int lines = 0;
    for (LayoutEngine.Page p : r.pages) lines += p.lines.size();
    return Json.object(
        "pages", r.pages.size(),
        "words", words,
        "characters", chars,
        "charactersNoSpaces", noSpaces,
        "paragraphs", paragraphs,
        "lines", lines);
  }
}
