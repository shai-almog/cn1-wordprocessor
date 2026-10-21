package com.codenameone.wordprocessor;

import java.util.*;

/** Bookmark-backed references to body headings, captions and explicitly named ranges. */
public final class CrossReferences {
  private CrossReferences() {}

  public static Map<String, String> targets(Document d, String kind) {
    var result = new LinkedHashMap<String, String>();
    if ("bookmark".equals(kind)) {
      for (Bookmark mark : d.bookmarks.values()) result.put(mark.name, mark.startId);
      return result;
    }
    if (!Arrays.asList("heading", "figure", "table", "equation").contains(kind))
      throw new IllegalArgumentException("Unknown reference type");
    for (Block block : d.blocks)
      if (block instanceof Paragraph p) {
        var text = p.text.plainText().trim();
        boolean match =
            "heading".equals(kind)
                ? SequenceFields.headingLevel(p.style) > 0
                : "Caption".equals(p.style)
                    && text.startsWith(Character.toUpperCase(kind.charAt(0)) + kind.substring(1));
        if (match && !text.isEmpty()) {
          var label = text;
          for (int count = 2; result.containsKey(label); count++) label = text + " [" + count + "]";
          result.put(label, p.id);
        }
      }
    return result;
  }

  public static void insert(
      Session session, String kind, String target, String show, LayoutEngine engine) {
    if (session.inCell())
      throw new IllegalArgumentException("Insert cross-references in body text");
    if (!Arrays.asList("text", "page", "number", "aboveBelow").contains(show))
      throw new IllegalArgumentException("Unknown reference display");
    var paragraphId = targets(session.document(), kind).get(target);
    if (paragraphId == null)
      throw new IllegalArgumentException("Unknown reference target: " + target);
    session.transact(
        s -> {
          var name = target;
          if (!"bookmark".equals(kind)) {
            var d = s.document();
            int block = ReviewComment.block(d, paragraphId);
            var p = d.paragraph(block);
            int end = p.text.length();
            if ("number".equals(show)) {
              int colon = p.text.toString().indexOf(':');
              if (colon >= 0) end = colon;
            }
            name = null;
            for (Bookmark mark : d.bookmarks.values())
              if (mark.name.startsWith("_Ref")
                  && mark.startId.equals(p.id)
                  && mark.endId.equals(p.id)
                  && mark.start == 0
                  && mark.end == end) {
                name = mark.name;
                break;
              }
            if (name == null) {
              int next = 1;
              do {
                name = "_Ref" + next++;
              } while (d.bookmarks.containsKey(name));
              var at = s.selection();
              s.select(block, 0, end);
              s.addBookmark(name);
              s.selectRange(at.anchorBlock, at.anchor, at.block, at.caret);
            }
          }
          var instruction =
              ("page".equals(show) ? "PAGEREF " : "REF ")
                  + name
                  + " \\h"
                  + ("aboveBelow".equals(show) ? " \\p" : "");
          s.insertField(new InlineField(instruction, "", false, false));
          if (!s.document().bookmarks.containsKey(name))
            throw new IllegalArgumentException("The selection removed the reference target");
          for (int pass = 0; pass < 4; pass++) {
            var before = s.document();
            s.mapFields(
                DocumentFields.resolver(
                    before,
                    engine.layout(before, s.revision()),
                    Collections.<String, String>emptyMap()));
            if (before == s.document()) break;
          }
        });
  }

  static InlineField resolve(
      Document d, LayoutEngine.Result layout, Map<String, List<LayoutEngine.Line>> lines,
      String paragraph, int offset, InlineField field) {
    try {
      return resolve(d, layout, lines, paragraph, offset, field, new HashSet<>());
    } catch (ReferenceError error) {
      return field.result(error.getMessage());
    }
  }

  private static final class ReferenceError extends RuntimeException {
    ReferenceError(String message) {
      super(message);
    }
  }

  private static InlineField resolve(
      Document d,
      LayoutEngine.Result layout,
      Map<String, List<LayoutEngine.Line>> lines,
      String paragraph,
      int offset,
      InlineField field,
      Set<String> visiting) {
    if (field.locked) return field;
    var code = new FieldCode(field.instruction);
    if (!("REF".equals(code.name) || "PAGEREF".equals(code.name)) || code.arguments.isEmpty())
      return field;
    var mark = d.bookmarks.get(code.arguments.get(0));
    if (mark == null) return field.result("Error! Reference source not found.");
    String result;
    if ("PAGEREF".equals(code.name)) {
      int page = page(layout, lines.get(mark.startId), mark.start);
      if (page < 1) return field;
      result = SequenceFields.number(page, code.switches.get("*"));
    } else if (code.switches.containsKey("p")) {
      int target = flowBlock(d, mark.startId), here = flowBlock(d, paragraph);
      result = target < here || target == here && mark.start < offset ? "above" : "below";
    } else {
      int here = ReviewComment.block(d, paragraph),
          first = ReviewComment.block(d, mark.startId),
          last = ReviewComment.block(d, mark.endId);
      if (here >= first
          && here <= last
          && (here > first || offset >= mark.start)
          && (here < last || offset < mark.end)) throw new ReferenceError("Error! Self reference.");
      result = "";
      if (code.switches.containsKey("n")
          || code.switches.containsKey("r")
          || code.switches.containsKey("w")) {
        var numbers = new ListNumbers();
        for (Block block : d.blocks)
          if (block instanceof Paragraph p) {
            var label = numbers.next(p);
            if (p.id.equals(mark.startId)) {
              result = label.isEmpty() ? "0" : label.substring(0, label.length() - 1);
              break;
            }
          }
      } else {
        if (visiting.size() >= 64 || !visiting.add(mark.name))
          throw new ReferenceError("Error! Circular reference.");
        try {
          result = text(d, layout, lines, mark, visiting);
        } finally {
          visiting.remove(mark.name);
        }
      }
      var format = code.switches.get("*");
      if ("Upper".equalsIgnoreCase(format)) result = result.toUpperCase();
      if ("Lower".equalsIgnoreCase(format)) result = result.toLowerCase();
    }
    return field.result(result);
  }

  /** Body block holding a paragraph, also for one in a cell or note (Wordcraft's body order). */
  private static int flowBlock(Document d, String id) {
    int block = ReviewComment.block(d, id);
    if (block >= 0) return block;
    var at = ReviewComment.locate(d, id);
    return at == null || at.furniture != null ? -1 : at.block;
  }

  private static String text(
      Document d, LayoutEngine.Result layout, Map<String, List<LayoutEngine.Line>> lines,
      Bookmark mark, Set<String> visiting) {
    int first = ReviewComment.block(d, mark.startId), last = ReviewComment.block(d, mark.endId);
    // A bookmark in a table cell, header/footer or note story: its plain range text.
    if (first < 0 || last < 0) return mark.text(d);
    final var out = new StringBuilder();
    for (int i = first; i <= last; i++) {
      if (i > first) out.append('\n');
      if (!(d.blocks.get(i) instanceof Paragraph)) continue;
      final var p = d.paragraph(i);
      final int start = i == first ? mark.start : 0;
      int end = i == last ? mark.end : p.text.length();
      p.text
          .slice(start, end)
          .visit(
              new RichText.Visitor() {
                public void run(int offset, String text, TextStyle style) {
                  if (style.revision != null && !style.revision.inserted()) return;
                  if (out.length() + text.length() > 1000000)
                    throw new ReferenceError("Error! Reference result too large.");
                  out.append(text);
                }

                public void field(int offset, InlineField field, TextStyle style) {
                  if (style.revision != null && !style.revision.inserted()) return;
                  run(
                      offset,
                      resolve(d, layout, lines, p.id, start + offset, field, visiting).display(),
                      style);
                }
              });
    }
    return out.toString();
  }

  private static int page(LayoutEngine.Result layout, List<LayoutEngine.Line> lines, int offset) {
    if (lines == null || lines.isEmpty()) return 0;
    for (LayoutEngine.Line line : lines)
      if (offset >= line.start && (offset < line.end || line.start == line.end))
        return layout.pages.get(line.page).number;
    return layout.pages.get(lines.get(lines.size() - 1).page).number;
  }
}
