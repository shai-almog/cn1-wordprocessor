package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Standard OOXML comment ranges and comments part. */
final class DocxComments {
  final DocxBookmarks bookmarks;
  private final Map<String, String[]> definitions = new LinkedHashMap<>();
  private final Map<String, String> startIds = new HashMap<String, String>(),
      endIds = new HashMap<String, String>();
  private final Map<String, Integer> starts = new HashMap<String, Integer>(),
      ends = new HashMap<String, Integer>();
  private final List<ReviewComment> comments;
  private final Set<String> resolved = new HashSet<>();
  private final Map<String, String> parents = new LinkedHashMap<>();

  DocxComments(Map<String, byte[]> parts) throws IOException {
    bookmarks = new DocxBookmarks();
    comments = Collections.emptyList();
    var bytes = parts.get("word/comments.xml");
    if (bytes == null) return;
    var byParagraph = new HashMap<String, String>();
    for (Element c : Xml.children(Xml.parse(bytes), "comment")) {
      var text = new StringBuilder();
      boolean firstParagraph = true;
      for (Element p : Xml.children(c, "p")) {
        if (!firstParagraph) text.append('\n');
        firstParagraph = false;
        appendText(p, text);
        byParagraph.put(Xml.attr(p, "paraId", ""), Xml.attr(c, "id", ""));
      }
      definitions.put(
          Xml.attr(c, "id", ""), new String[] {Xml.attr(c, "author", "Author"), text.toString(), Xml.attr(c, "date", "")});
    }
    bytes = parts.get("word/commentsExtended.xml");
    if (bytes != null)
      for (Element e : Xml.children(Xml.parse(bytes), "commentEx")) {
        var id = byParagraph.get(Xml.attr(e, "paraId", ""));
        String done = Xml.attr(e, "done", "0");
        if (id != null && ("1".equals(done) || "true".equals(done))) resolved.add(id);
        var parent = byParagraph.get(Xml.attr(e, "paraIdParent", ""));
        if (id != null && parent != null && !Xml.attr(e, "paraIdParent", "").isEmpty())
          parents.put(id, parent);
      }
  }

  DocxComments(Document d) {
    bookmarks = new DocxBookmarks(d);
    comments = d.comments;
  }

  private static void appendText(Element e, StringBuilder b) {
    String kind = Xml.local(e);
    if ("t".equals(kind)) b.append(Xml.text(e));
    else if ("tab".equals(kind)) b.append('\t');
    else if ("br".equals(kind) || "cr".equals(kind)) b.append('\n');
    else if (!"rPr".equals(kind) && !"pPr".equals(kind))
      for (Element c : Xml.children(e, null)) appendText(c, b);
  }

  /** False when the part has no bookmark or comment marker, so paragraphs need no marker scan. */
  boolean markers = true;

  void scan(Element p, String paragraph, Set<String> warnings) {
    if (markers) scan(p, paragraph, new int[] {0, 0});
    bookmarks.warnings(warnings);
  }

  private void scan(Element e, String paragraph, int[] offset) {
    String kind = Xml.local(e), id = Xml.attr(e, "id", "");
    bookmarks.record(kind, id, Xml.attr(e, "name", ""), paragraph, offset[0], offset[1] > 0);
    if ("fldSimple".equals(kind)) {
      // Its result is atomic in this model; report embedded range markers rather than silently
      // moving them.
      int[] inside = {offset[0], offset[1] + 1};
      for (Element child : Xml.children(e, null)) scan(child, paragraph, inside);
      if (offset[1] == 0) offset[0]++;
      return;
    }
    if ("fldChar".equals(kind)) {
      String action = Xml.attr(e, "fldCharType", "");
      if ("begin".equals(action)) offset[1]++;
      else if ("end".equals(action) && offset[1] > 0) {
        offset[1]--;
        if (offset[1] == 0) offset[0]++;
      }
      return;
    }
    if ("commentRangeStart".equals(kind)) {
      startIds.put(id, paragraph);
      starts.put(id, offset[0]);
    } else if ("commentRangeEnd".equals(kind)) {
      endIds.put(id, paragraph);
      ends.put(id, offset[0]);
    } else if ("commentReference".equals(kind) && !starts.containsKey(id)) {
      startIds.put(id, paragraph);
      endIds.put(id, paragraph);
      starts.put(id, offset[0]);
      ends.put(id, offset[0]);
    } else if ("t".equals(kind) || "delText".equals(kind)) {
      if (offset[1] == 0) offset[0] += Xml.text(e).length();
    } else if ("tab".equals(kind) || "br".equals(kind) || "cr".equals(kind)) {
      if (offset[1] == 0) offset[0]++;
    } else if (!"pPr".equals(kind) && !"rPr".equals(kind))
      for (Element c : Xml.children(e, null)) scan(c, paragraph, offset);
  }

  /** Moves the offsets recorded in a paragraph after text before them was dropped. */
  void shift(String paragraph, int delta) {
    for (Map.Entry<String, String> e : startIds.entrySet())
      if (paragraph.equals(e.getValue())) starts.put(e.getKey(), Math.max(0, starts.get(e.getKey()) + delta));
    for (Map.Entry<String, String> e : endIds.entrySet())
      if (paragraph.equals(e.getValue())) ends.put(e.getKey(), Math.max(0, ends.get(e.getKey()) + delta));
  }

  List<ReviewComment> read(Document d, Set<String> warnings) {
    var result = new ArrayList<ReviewComment>();

    for (Map.Entry<String, String[]> e : definitions.entrySet()) {
      var id = e.getKey();
      if (parents.containsKey(id)) continue;
      Integer startValue = starts.get(id), endValue = ends.get(id);
      if (startValue == null || endValue == null
          || !ReviewComment.validRange(d, startIds.get(id), startValue, endIds.get(id), endValue)) {
        warnings.add("Comment anchor outside a supported body or table-cell range");
        continue;
      }
      int start = startValue, end = endValue;
      result.add(
          new ReviewComment(
              "comment" + id,
              e.getValue()[0],
              e.getValue()[1],
              startIds.get(id),
              start,
              endIds.get(id),
              end,
              resolved.contains(id),
              java.util.Collections.<ReviewComment.Reply>emptyList(),
              e.getValue()[2]));
    }
    for (Map.Entry<String, String> reply : parents.entrySet()) {
      var parent = reply.getValue();
      var seen = new HashSet<String>();
      seen.add(reply.getKey());
      while (parents.containsKey(parent) && seen.add(parent)) parent = parents.get(parent);
      if (!seen.add(parent)) {
        warnings.add("Cyclic comment thread");
        continue;
      }
      var definition = definitions.get(reply.getKey());
      if (definition == null || definition[0].trim().isEmpty() || definition[1].trim().isEmpty()) {
        warnings.add("Empty or unsupported comment reply");
        continue;
      }
      boolean found = false;
      for (int i = 0; i < result.size(); i++)
        if (result.get(i).id.equals("comment" + parent)) {
          var root = result.get(i);
          result.set(i, root.reply(definition[0], definition[1], definition[2]).resolved(root.resolved));
          found = true;
          break;
        }
      if (!found) warnings.add("Reply without a supported comment anchor");
    }
    return result;
  }

  /** Whether a comment or bookmark marks a position inside the paragraph. */
  boolean anchored(Paragraph p) {
    if (bookmarks.anchored(p.id)) return true;
    for (ReviewComment c : comments) if (c.startId.equals(p.id) || c.endId.equals(p.id)) return true;
    return false;
  }

  void content(final StringBuilder b, final Paragraph p, final DocxLinks links) {
    if (!anchored(p)) {
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {
              Docx.writeRun(b, text, style, links);
            }

            public void field(int offset, InlineField field, TextStyle style) {
              DocxFields.write(b, field, style, links);
            }
            public void note(int offset, Note note, TextStyle style) {
              if (links.notes == null) throw new IllegalArgumentException("Notes outside document body");
              links.notes.reference(b, note, style, links);
            }
          });
      return;
    }
    final var boundaries = new TreeSet<Integer>();
    boundaries.add(0);
    boundaries.add(p.text.length());
    for (ReviewComment c : comments) {
      if (c.startId.equals(p.id)) boundaries.add(c.start);
      if (c.endId.equals(p.id)) boundaries.add(c.end);
    }
    bookmarks.boundaries(p.id, boundaries);
    final var emitted = new HashSet<Integer>();
    p.text.visit(
        new RichText.Visitor() {
          public void note(int offset, Note note, TextStyle style) {
            if (emitted.add(offset)) markers(b, p.id, offset);
            if (links.notes == null) throw new IllegalArgumentException("Notes outside document body");
            links.notes.reference(b, note, style, links);
            if (emitted.add(offset + 1)) markers(b, p.id, offset + 1);
          }
          public void field(int offset, InlineField field, TextStyle style) {
            if (emitted.add(offset)) markers(b, p.id, offset);
            DocxFields.write(b, field, style, links);
            if (emitted.add(offset + 1)) markers(b, p.id, offset + 1);
          }

          public void run(int offset, String text, TextStyle style) {
            int from = offset, to = offset + text.length();
            for (Integer cut : boundaries.subSet(offset, true, to, true)) {
              if (cut > from) {
                Docx.writeRun(b, text.substring(from - offset, cut - offset), style, links);
                from = cut;
              }
              if (emitted.add(cut)) markers(b, p.id, cut);
            }
            if (from < to) Docx.writeRun(b, text.substring(from - offset), style, links);
            if (text.isEmpty()) Docx.writeRun(b, text, style, links);
          }
        });
  }

  private void markers(StringBuilder b, String paragraph, int at) {
    bookmarks.markers(b, paragraph, at);
    for (int i = 0; i < comments.size(); i++) {
      var c = comments.get(i);
      if (c.startId.equals(paragraph) && c.start == at)
        b.append("<w:commentRangeStart w:id=\"").append(i).append("\"/>");
    }
    for (int i = 0; i < comments.size(); i++) {
      var c = comments.get(i);
      if (c.endId.equals(paragraph) && c.end == at)
        b.append("<w:commentRangeEnd w:id=\"")
            .append(i)
            .append("\"/><w:r><w:commentReference w:id=\"")
            .append(i)
            .append("\"/></w:r>");
    }
  }

  void packageParts(Map<String, byte[]> parts) throws IOException {
    if (comments.isEmpty() && !parts.containsKey("word/comments.xml")) return;
    var b =
        new StringBuilder(
            """
            <w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"\
             xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"\
             xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"\
             mc:Ignorable="w14">""");
    int replyId = comments.size();
    for (int i = 0; i < comments.size(); i++) {
      var c = comments.get(i);
      writeComment(b, i, c.author, c.text, c.date);
      for (ReviewComment.Reply reply : c.replies)
        writeComment(b, replyId++, reply.author, reply.text, reply.date);
    }
    parts.put("word/comments.xml", b.append("</w:comments>").toString().getBytes("UTF-8"));
    part(
        parts,
        "comments",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml");
    b =
        new StringBuilder(
            "<w15:commentsEx xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\">");
    replyId = comments.size();
    for (int i = 0; i < comments.size(); i++) {
      var c = comments.get(i);
      b.append("<w15:commentEx w15:paraId=\"")
          .append(commentParagraph(i))
          .append("\" w15:done=\"")
          .append(c.resolved ? "1" : "0")
          .append("\"/>");
      for (ReviewComment.Reply reply : c.replies)
        b.append("<w15:commentEx w15:paraId=\"")
            .append(commentParagraph(replyId++))
            .append("\" w15:paraIdParent=\"")
            .append(commentParagraph(i))
            .append("\" w15:done=\"0\"/>");
    }
    parts.put(
        "word/commentsExtended.xml", b.append("</w15:commentsEx>").toString().getBytes("UTF-8"));
    part(
        parts,
        "commentsExtended",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml");
  }

  private static void writeComment(StringBuilder b, int id, String author, String text, String date) {
    b.append("<w:comment w:id=\"")
        .append(id)
        .append("\" w:author=\"")
        .append(Xml.escape(author));
    if (!date.isEmpty()) b.append("\" w:date=\"").append(Xml.escape(date));
    b.append("\">");
    String[] paragraphs = TextUtil.split(text, '\n');
    for (int line = 0; line < paragraphs.length; line++) {
      b.append("<w:p");
      if (line == paragraphs.length - 1)
        b.append(" w14:paraId=\"").append(commentParagraph(id)).append("\"");
      b.append("><w:r><w:t xml:space=\"preserve\">")
          .append(Xml.escape(paragraphs[line]))
          .append("</w:t></w:r></w:p>");
    }
    b.append("</w:comment>");
  }

  private static String commentParagraph(int i) {
    return TextUtil.padded(i + 1, 16, 8).toUpperCase();
  }

  static void part(Map<String, byte[]> parts, String name, String contentType) throws IOException {
    String path = "word/_rels/document.xml.rels",
        ns = "http://schemas.openxmlformats.org/package/2006/relationships";
    String xml =
        parts.containsKey(path)
            ? new String(parts.get(path), "UTF-8")
            : "<Relationships xmlns=\"" + ns + "\"></Relationships>";
    boolean found = false;
    var ids = new HashSet<String>();
    for (Element e : Xml.children(Xml.parse(xml), null)) {
      ids.add(Xml.attr(e, "Id", ""));
      if (Xml.attr(e, "Type", "").endsWith("/" + name)
          && Xml.attr(e, "Target", "").equals(name + ".xml")) found = true;
    }
    if (!found) {
      var id = "cn1" + name;
      while (ids.contains(id)) id += "1";
      xml =
          DocxLists.insert(
              xml,
              "<Relationship xmlns=\""
                  + ns
                  + "\" Id=\""
                  + id
                  + "\" Type=\""
                  + ("commentsExtended".equals(name)
                      ? "http://schemas.microsoft.com/office/2011/relationships/"
                      : "http://schemas.openxmlformats.org/officeDocument/2006/relationships/")
                  + name
                  + "\" Target=\""
                  + name
                  + ".xml\"/>");
      parts.put(path, xml.getBytes("UTF-8"));
    }
    path = "[Content_Types].xml";
    if (!parts.containsKey(path)) return;
    xml = new String(parts.get(path), "UTF-8");
    found = false;
    for (Element e : Xml.children(Xml.parse(xml), null))
      if (("/word/" + name + ".xml").equals(Xml.attr(e, "PartName", ""))) found = true;
    if (!found)
      parts.put(
          path,
          DocxLists.insert(
                  xml,
                  "<Override xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\""
                      + " PartName=\"/word/"
                      + name
                      + ".xml\" ContentType=\""
                      + contentType
                      + "\"/>")
              .getBytes("UTF-8"));
  }
}
