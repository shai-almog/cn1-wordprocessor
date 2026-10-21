package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Standard footnote/endnote package parts. Note stories own their scoped relationships. */
final class DocxNotes {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private final Map<String, Note> imported = new HashMap<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();
  private final List<Note> notes;
  private float width = 468;

  private DocxComments commentsOrEmpty() {
    return comments != null ? comments : new DocxComments(Document.empty());
  }

  DocxNotes(Document document) {
    notes = Notes.all(document);
    width = document.page.contentWidth();
    for (Note note : notes) if (!ids.containsKey(note.id)) ids.put(note.id, ids.size() + 1);
  }

  /** The package's comments: ranges inside note stories are read and written with them. */
  DocxComments comments;

  DocxNotes(Map<String, byte[]> parts, long[] next, Set<String> warnings, DocxStyles styles)
      throws IOException {
    this(parts, next, warnings, styles, null);
  }

  DocxNotes(Map<String, byte[]> parts, long[] next, Set<String> warnings, DocxStyles styles,
      DocxComments comments) throws IOException {
    this.comments = comments;
    notes = Collections.emptyList();
    for (String kind : new String[] {"footnote", "endnote"}) {
      var bytes = parts.get("word/" + kind + "s.xml");
      if (bytes == null) continue;
      var links = new DocxLinks(parts, "word/_rels/" + kind + "s.xml.rels");
      DocxLists.Read lists = DocxLists.read(parts.get("word/numbering.xml"), warnings);
      var pictures = new DocxPictures(parts, "word/_rels/" + kind + "s.xml.rels");
      Docx.stories(pictures, next, warnings, lists, styles, links,
          comments != null ? comments : new DocxComments(Collections.<String, byte[]>emptyMap()));
      for (Element entry : Xml.children(Xml.parse(bytes), kind)) {
        String type = Xml.attr(entry, "type", "normal"), key = Xml.attr(entry, "id", "");
        if (!"normal".equals(type)) continue;
        // A note story holds any body block, as Wordcraft's note parts: tables and pictures too.
        var paragraphs = new Sequence<Block>();
        boolean opened = false;
        for (Element child : Xml.children(entry, null)) {
          if ("tbl".equals(Xml.local(child))) {
            var table = Docx.readTable(child, next, warnings, lists, styles, links,
                comments != null ? comments : new DocxComments(Collections.<String, byte[]>emptyMap()), pictures);
            if (table != null) paragraphs = paragraphs.append(table);
            continue;
          }
          if (!"p".equals(Xml.local(child))) { warnings.add("Note content: " + Xml.local(child)); continue; }
          var paragraphWarnings = new LinkedHashSet<String>();
          DocxComments marks = comments;
          boolean scanned = marks != null && marks.markers;
          if (marks == null) marks = new DocxComments(Collections.<String, byte[]>emptyMap());
          else marks.markers = true; // the main part's flag does not cover note parts
          Paragraph p = Docx.paragraph(child, next, paragraphWarnings, lists, styles, links, marks);
          var images = pictures.read(child, next, warnings);
          if (!images.isEmpty()) paragraphWarnings.remove("Run feature: drawing");
          warnings.addAll(paragraphWarnings);
          if (comments != null) comments.markers = scanned;
          // The automatic note mark is a separate object in the package, not part of the story text.
          if (!opened && hasMarker(child, kind + "Ref") && p.text.length() > 0
              && (p.text.charAt(0) == ' ' || p.text.charAt(0) == '\t')) {
            p = p.text(p.text.slice(1, p.text.length()));
            if (comments != null) comments.shift(p.id, -1);
          }
          opened = true;
          // As in the body and headers: pictures follow their paragraph as blocks.
          if (images.isEmpty() || p.text.length() > 0) paragraphs = paragraphs.append(p);
          for (Picture image : images) paragraphs = paragraphs.append(image);
        }
        boolean any = false;
        for (Block b : paragraphs) any |= b instanceof Paragraph;
        if (!any) paragraphs = paragraphs.append(new Paragraph("p" + next[0]++, new RichText()));
        if (imported.put(kind + key, new Note("n" + next[0]++, kind, "", "1", paragraphs)) != null)
          throw new IOException("Duplicate note definition");
      }
    }
  }

  private static boolean hasMarker(Element node, String name) {
    if (name.equals(Xml.local(node))) return true;
    for (Element child : Xml.children(node, null)) if (hasMarker(child, name)) return true;
    return false;
  }

  Note read(String kind, String id, String mark, Set<String> warnings) {
    var note = imported.get(kind + id);
    if (note == null) throw new IllegalArgumentException("Missing " + kind + " " + id);
    if (mark.isEmpty()) return note;
    Sequence<Block> story = note.blocks;
    int index = 0;
    while (!(story.get(index) instanceof Paragraph)) index++;
    var first = (Paragraph) story.get(index);
    var prefix = first.text.toString();
    if (prefix.startsWith(mark)) {
      int skip = mark.length();
      if (skip < prefix.length() && (prefix.charAt(skip) == ' ' || prefix.charAt(skip) == '\t')) skip++;
      story = story.set(index, first.text(first.text.slice(skip, first.text.length())));
    }
    return new Note(note.id, kind, mark, mark, story);
  }

  void reference(StringBuilder out, Note note, TextStyle style, DocxLinks links) {
    var id = ids.get(note.id);
    if (id == null) throw new IllegalArgumentException("Note outside document body");
    var content = "<w:" + note.kind + "Reference w:id=\"" + id + "\""
        + (note.mark.isEmpty() ? "" : " w:customMarkFollows=\"1\"") + "/>"
        + (note.mark.isEmpty() ? "" : "<w:t>" + Xml.escape(note.mark) + "</w:t>");
    RevisionFormats.start(out, style.revision);
    out.append("<w:r>");
    Docx.writeRunProperties(out, style);
    out.append(content).append("</w:r>");
    RevisionFormats.end(out, style.revision);
  }

  void packageParts(Map<String, byte[]> parts, DocxLists lists, DocxTabs tabs) throws IOException {
    for (String kind : new String[] {"footnote", "endnote"}) {
      String name = kind + "s", path = "word/" + name + ".xml";
      var out = new StringBuilder("<w:" + name + " xmlns:w=\"" + W
          + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
      out.append("<w:").append(kind).append(" w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:").append(kind).append(">");
      var links = new DocxLinks(parts, "word/_rels/" + name + ".xml.rels");
      var pictures = new DocxPictures(parts, "word/_rels/" + name + ".xml.rels");
      Docx.stories(pictures, width, lists, tabs, links, commentsOrEmpty());
      for (Note note : notes) {
        if (!kind.equals(note.kind)) continue;
        out.append("<w:").append(kind).append(" w:id=\"").append(ids.get(note.id)).append("\">");
        boolean first = true;
        for (Block block : note.blocks) {
          if (block instanceof Table t) {
            Docx.writeTable(out, t, width, lists, tabs, links, commentsOrEmpty(), pictures);
            continue;
          }
          if (block instanceof Picture picture) {
            pictures.write(out, picture);
            continue;
          }
          var p = (Paragraph) block;
          if (first) {
            // Write the paragraph normally, then inject the reference mark after its properties.
            var paragraph = new StringBuilder();
            Docx.writeParagraph(paragraph, p, lists, tabs, links, commentsOrEmpty());
            var xml = paragraph.toString();
            int at = xml.indexOf("</w:pPr>"); at = at < 0 ? xml.indexOf('>') + 1 : at + 8;
            String marker = note.mark.isEmpty() ? "<w:" + kind + "Ref/>" : "<w:t>" + Xml.escape(note.mark) + "</w:t>";
            out.append(xml.substring(0, at)).append("<w:r>");
            Docx.writeRunProperties(out, p.text.styleAt(0).clean().with("link", "").with("script", "superscript"));
            out.append(marker).append("</w:r><w:r>");
            Docx.writeRunProperties(out, p.text.styleAt(0).clean().with("link", "").with("script", "baseline"));
            out.append("<w:t xml:space=\"preserve\"> </w:t></w:r>").append(xml.substring(at));
          } else Docx.writeParagraph(out, p, lists, tabs, links, commentsOrEmpty());
          first = false;
        }
        // A story must end with a paragraph (ECMA-376 17.11.10), as table cells must.
        var last = note.blocks.get(note.blocks.size() - 1);
        if (!(last instanceof Paragraph)) out.append("<w:p/>");
        out.append("</w:").append(kind).append(">");
      }
      out.append("</w:").append(name).append(">");
      // Also rewrite an emptied old part, so removed note definitions cannot reappear on export.
      boolean present = parts.containsKey(path);
      for (Note note : notes) present |= kind.equals(note.kind);
      if (!present) continue;
      parts.put(path, out.toString().getBytes("UTF-8"));
      links.packageParts(parts);
      pictures.packageParts();
      DocxComments.part(parts, name, "application/vnd.openxmlformats-officedocument.wordprocessingml." + name + "+xml");
    }
  }
}
