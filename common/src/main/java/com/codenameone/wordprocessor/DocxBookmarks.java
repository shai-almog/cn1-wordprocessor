package com.codenameone.wordprocessor;

import java.util.*;

/** OOXML bookmark ranges share the paragraph offset walk with comment ranges. */
final class DocxBookmarks {
  private final Map<String, String> names = new LinkedHashMap<>();
  private final Map<String, String> startIds = new HashMap<String, String>(),
      endIds = new HashMap<String, String>();
  private final Map<String, Integer> starts = new HashMap<String, Integer>(),
      ends = new HashMap<String, Integer>();
  private final List<Bookmark> marks;
  private final Set<String> problems = new LinkedHashSet<>();
  boolean supported = true;

  DocxBookmarks() {
    marks = Collections.emptyList();
  }

  DocxBookmarks(Document document) {
    marks = new ArrayList<>(document.bookmarks.values());
  }

  void record(
      String kind, String id, String name, String paragraph, int offset, boolean insideField) {
    if (!"bookmarkStart".equals(kind) && !"bookmarkEnd".equals(kind)) return;
    if (!supported || insideField) {
      problems.add("Bookmarks inside field results are not supported");
      return;
    }
    if ("bookmarkStart".equals(kind)) {
      if (names.containsKey(id)) problems.add("Duplicate bookmark id");
      names.put(id, name);
      startIds.put(id, paragraph);
      starts.put(id, offset);
    } else {
      if (endIds.containsKey(id)) problems.add("Duplicate bookmark end id");
      endIds.put(id, paragraph);
      ends.put(id, offset);
    }
  }

  /** Offsets past a dropped note reference mark move with the text (as comment marks do). */
  void shift(String paragraph, int delta) {
    for (Map.Entry<String, String> e : startIds.entrySet())
      if (paragraph.equals(e.getValue())) starts.put(e.getKey(), Math.max(0, starts.get(e.getKey()) + delta));
    for (Map.Entry<String, String> e : endIds.entrySet())
      if (paragraph.equals(e.getValue())) ends.put(e.getKey(), Math.max(0, ends.get(e.getKey()) + delta));
  }

  void warnings(Set<String> warnings) {
    warnings.addAll(problems);
  }

  Map<String, Bookmark> read(Document d, Set<String> warnings) {
    warnings(warnings);
    var result = new LinkedHashMap<String, Bookmark>();
    for (Map.Entry<String, String> entry : names.entrySet()) {
      String id = entry.getKey(), name = entry.getValue();
      try {
        if (!ends.containsKey(id) || result.containsKey(name)) throw new IllegalArgumentException();
        var mark =
            new Bookmark(name, startIds.get(id), starts.get(id), endIds.get(id), ends.get(id));
        mark.validate(d);
        result.put(name, mark);
      } catch (IllegalArgumentException ex) {
        warnings.add("Invalid or unsupported bookmark range: " + name);
      }
    }
    for (String id : ends.keySet())
      if (!names.containsKey(id)) warnings.add("Bookmark end without a matching start");
    return result;
  }

  boolean anchored(String paragraph) {
    for (Bookmark mark : marks)
      if (mark.startId.equals(paragraph) || mark.endId.equals(paragraph)) return true;
    return false;
  }

  void boundaries(String paragraph, Set<Integer> out) {
    for (Bookmark mark : marks) {
      if (mark.startId.equals(paragraph)) out.add(mark.start);
      if (mark.endId.equals(paragraph)) out.add(mark.end);
    }
  }

  void markers(StringBuilder out, String paragraph, int offset) {
    for (int i = 0; i < marks.size(); i++) {
      var mark = marks.get(i);
      if (mark.startId.equals(paragraph) && mark.start == offset)
        out.append("<w:bookmarkStart w:id=\"")
            .append(i)
            .append("\" w:name=\"")
            .append(Xml.escape(mark.name))
            .append("\"/>");
    }
    for (int i = 0; i < marks.size(); i++) {
      var mark = marks.get(i);
      if (mark.endId.equals(paragraph) && mark.end == offset)
        out.append("<w:bookmarkEnd w:id=\"").append(i).append("\"/>");
    }
  }
}
