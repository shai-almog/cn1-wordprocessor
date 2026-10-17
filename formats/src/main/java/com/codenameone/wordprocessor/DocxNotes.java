package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Standard footnote/endnote package parts. Note stories own their scoped relationships. */
final class DocxNotes {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private final Map<String, Note> imported = new HashMap<String, Note>();
  private final Map<String, Integer> ids = new LinkedHashMap<String, Integer>();
  private final List<Note> notes;

  DocxNotes(Document document) {
    notes = Notes.all(document);
    for (Note note : notes) if (!ids.containsKey(note.id)) ids.put(note.id, ids.size() + 1);
  }

  DocxNotes(Map<String, byte[]> parts, long[] next, Set<String> warnings, DocxStyles styles)
      throws IOException {
    notes = Collections.emptyList();
    for (String kind : new String[] {"footnote", "endnote"}) {
      byte[] bytes = parts.get("word/" + kind + "s.xml");
      if (bytes == null) continue;
      DocxLinks links = new DocxLinks(parts, "word/_rels/" + kind + "s.xml.rels");
      DocxLists.Read lists = DocxLists.read(parts.get("word/numbering.xml"), warnings);
      for (Element entry : Xml.children(Xml.parse(bytes), kind)) {
        String type = Xml.attr(entry, "type", "normal"), key = Xml.attr(entry, "id", "");
        if (!"normal".equals(type)) continue;
        Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
        for (Element child : Xml.children(entry, null)) {
          if (!"p".equals(Xml.local(child))) { warnings.add("Note content: " + Xml.local(child)); continue; }
          Paragraph p = Docx.paragraph(child, next, warnings, lists, styles, links,
              new DocxComments(Collections.<String, byte[]>emptyMap()));
          // The automatic note mark is a separate object in the package, not part of the story text.
          if (paragraphs.size() == 0 && hasMarker(child, kind + "Ref") && p.text.length() > 0
              && (p.text.charAt(0) == ' ' || p.text.charAt(0) == '\t'))
            p = p.text(p.text.slice(1, p.text.length()));
          paragraphs = paragraphs.append(p);
        }
        if (paragraphs.size() == 0) paragraphs = paragraphs.append(new Paragraph("p" + next[0]++, new RichText()));
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
    Note note = imported.get(kind + id);
    if (note == null) throw new IllegalArgumentException("Missing " + kind + " " + id);
    if (mark.isEmpty()) return note;
    Sequence<Paragraph> story = note.paragraphs;
    Paragraph first = story.get(0);
    String prefix = first.text.toString();
    if (prefix.startsWith(mark)) {
      int skip = mark.length();
      if (skip < prefix.length() && (prefix.charAt(skip) == ' ' || prefix.charAt(skip) == '\t')) skip++;
      story = story.set(0, first.text(first.text.slice(skip, first.text.length())));
    }
    return new Note(note.id, kind, mark, mark, story);
  }

  void reference(StringBuilder out, Note note, TextStyle style, DocxLinks links) {
    Integer id = ids.get(note.id);
    if (id == null) throw new IllegalArgumentException("Note outside document body");
    String content = "<w:" + note.kind + "Reference w:id=\"" + id + "\""
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
      StringBuilder out = new StringBuilder("<w:" + name + " xmlns:w=\"" + W
          + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
      out.append("<w:").append(kind).append(" w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:").append(kind).append(">");
      DocxLinks links = new DocxLinks(parts, "word/_rels/" + name + ".xml.rels");
      for (Note note : notes) {
        if (!kind.equals(note.kind)) continue;
        out.append("<w:").append(kind).append(" w:id=\"").append(ids.get(note.id)).append("\">");
        boolean first = true;
        for (Paragraph original : note.paragraphs) {
          Paragraph p = original;
          if (first) {
            // Write the paragraph normally, then inject the reference mark after its properties.
            StringBuilder paragraph = new StringBuilder();
            Docx.writeParagraph(paragraph, p, lists, tabs, links, new DocxComments(Document.empty()));
            String xml = paragraph.toString();
            int at = xml.indexOf("</w:pPr>"); at = at < 0 ? xml.indexOf('>') + 1 : at + 8;
            String marker = note.mark.isEmpty() ? "<w:" + kind + "Ref/>" : "<w:t>" + Xml.escape(note.mark) + "</w:t>";
            out.append(xml.substring(0, at)).append("<w:r>");
            Docx.writeRunProperties(out, p.text.styleAt(0).clean().with("link", "").with("script", "superscript"));
            out.append(marker).append("</w:r><w:r>");
            Docx.writeRunProperties(out, p.text.styleAt(0).clean().with("link", "").with("script", "baseline"));
            out.append("<w:t xml:space=\"preserve\"> </w:t></w:r>").append(xml.substring(at));
          } else Docx.writeParagraph(out, p, lists, tabs, links, new DocxComments(Document.empty()));
          first = false;
        }
        out.append("</w:").append(kind).append(">");
      }
      out.append("</w:").append(name).append(">");
      // Also rewrite an emptied old part, so removed note definitions cannot reappear on export.
      boolean present = parts.containsKey(path);
      for (Note note : notes) present |= kind.equals(note.kind);
      if (!present) continue;
      parts.put(path, out.toString().getBytes("UTF-8"));
      links.packageParts(parts);
      DocxComments.part(parts, name, "application/vnd.openxmlformats-officedocument.wordprocessingml." + name + "+xml");
    }
  }
}
