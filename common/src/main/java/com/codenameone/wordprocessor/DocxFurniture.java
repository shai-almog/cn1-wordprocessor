package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/**
 * Header/footer parts holding any body block (paragraphs, tables, pictures), with scoped
 * relationships.
 */
final class DocxFurniture {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
      R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
      P = "http://schemas.openxmlformats.org/package/2006/relationships",
      REL = "word/_rels/document.xml.rels";
  private final Document document;
  private final Map<String, String> ids = new LinkedHashMap<String, String>(),
      paths = new LinkedHashMap<String, String>();

  /** The main part's comment writer: header/footer comment ranges are written with it. */
  DocxComments comments;

  private DocxComments marks() {
    return comments != null ? comments : new DocxComments(Document.empty());
  }

  DocxFurniture(Document doc, Map<String, byte[]> parts) throws IOException {
    this(doc, parts, "");
  }

  DocxFurniture(Document doc, Map<String, byte[]> parts, String prefix) throws IOException {
    document = doc;
    var existing = new HashSet<String>();
    if (parts.containsKey(REL))
      for (Element rel : Xml.children(Xml.parse(parts.get(REL)), null))
        existing.add(Xml.attr(rel, "Id", ""));
    for (String key : PageFurniture.KEYS)
      if (doc.furniture.stories.containsKey(key)) {
        String reused = null;
        if (parts.containsKey(REL))
          for (Element rel : Xml.children(Xml.parse(parts.get(REL)), null)) {
            String candidate = Xml.attr(rel, "Id", ""), target = Xml.attr(rel, "Target", "");
            if (candidate.startsWith("cn1Furniture" + prefix + key)
                && target.startsWith("cn1-" + prefix + key)
                && parts.containsKey("word/" + target)) {
              reused = candidate;
              ids.put(key, candidate);
              paths.put(key, "word/" + target);
              break;
            }
          }
        if (reused != null) continue;
        var id = "cn1Furniture" + prefix + key;
        while (existing.contains(id)) id += "x";
        existing.add(id);
        ids.put(key, id);
        var path = "word/cn1-" + key + ".xml";
        int suffix = 1;
        while (parts.containsKey(path)) path = "word/cn1-" + key + (suffix++) + ".xml";
        paths.put(key, path);
      }
  }

  private static boolean enabled(Element parent, String name) {
    Element e = parent == null ? null : Xml.child(parent, name);
    if (e == null) return false;
    String val = Xml.attr(e, "val", "1");
    return !("0".equals(val) || "false".equals(val) || "off".equals(val));
  }

  static PageFurniture read(
      Map<String, byte[]> parts,
      Element section,
      long[] id,
      Set<String> warnings,
      DocxLists.Read lists,
      DocxStyles styles)
      throws IOException {
    return read(parts, section, id, warnings, lists, styles, null);
  }

  /**
   * As {@link #read}, recording comment ranges in header/footer stories in {@code body}, the
   * main part's comment reader (Wordcraft reads w:commentRangeStart/End in every story).
   */
  static PageFurniture read(
      Map<String, byte[]> parts,
      Element section,
      long[] id,
      Set<String> warnings,
      DocxLists.Read lists,
      DocxStyles styles,
      DocxComments body)
      throws IOException {
    if (section == null) return PageFurniture.EMPTY;
    var targets = new HashMap<String, String>();
    if (parts.containsKey(REL))
      for (Element rel : Xml.children(Xml.parse(parts.get(REL)), null)) {
        String type = Xml.attr(rel, "Type", "");
        if ((type.equals(R + "/header") || type.equals(R + "/footer"))
            && !"External".equals(Xml.attr(rel, "TargetMode", "")))
          targets.put(Xml.attr(rel, "Id", ""), Xml.attr(rel, "Target", ""));
      }
    var stories = new LinkedHashMap<String, Sequence<Block>>();
    for (Element ref : Xml.children(section, null)) {
      String kind = Xml.local(ref);
      if (!"headerReference".equals(kind) && !"footerReference".equals(kind)) continue;
      String type = Xml.attr(ref, "type", "default"),
          base = kind.startsWith("header") ? "header" : "footer";
      if (!"default".equals(type) && !"first".equals(type) && !"even".equals(type)) {
        warnings.add("Unsupported header/footer variant");
        continue;
      }
      String key =
          "default".equals(type)
              ? base
              : type + Character.toUpperCase(base.charAt(0)) + base.substring(1);
      var target = targets.get(Xml.attr(ref, "id", ""));
      if (target == null || target.contains("..") || target.contains("\\")) {
        warnings.add("Missing or invalid header/footer relationship");
        continue;
      }
      String path = target.startsWith("/") ? target.substring(1) : "word/" + target;
      var data = parts.get(path);
      if (data == null) {
        warnings.add("Missing header/footer part");
        continue;
      }
      var blocks = new ArrayList<Block>();
      var relPath =
          path.substring(0, path.lastIndexOf('/') + 1)
              + "_rels/"
              + path.substring(path.lastIndexOf('/') + 1)
              + ".rels";
      var links = new DocxLinks(parts, relPath);
      var pictures = new DocxPictures(parts, relPath);
      var comments = body != null ? body : new DocxComments(Collections.<String, byte[]>emptyMap());
      // The main part's marker flag does not cover header parts (as for note parts).
      boolean wasMarkers = comments.markers, wasSupported = comments.bookmarks.supported;
      comments.markers = true;
      comments.bookmarks.supported = false;
      for (Element child : Xml.children(Xml.parse(data), null)) {
        String name = Xml.local(child);
        if ("p".equals(name)) {
          // As in the body: pictures (inline or floating) follow their paragraph as blocks.
          var paragraphWarnings = new LinkedHashSet<String>();
          var p = Docx.paragraph(child, id, paragraphWarnings, lists, styles, links, comments);
          paragraphWarnings.remove("Run feature: drawing");
          warnings.addAll(paragraphWarnings);
          var images = pictures.read(child, id, warnings);
          if (images.isEmpty() || p.text.length() > 0) blocks.add(p);
          Element props = Xml.child(child, "pPr"), jc = props == null ? null : Xml.child(props, "jc");
          String align = jc == null ? "left" : Xml.attr(jc, "val", "left");
          if (!"center".equals(align) && !"right".equals(align)) align = "left";
          for (Picture image : images)
            blocks.add(image.resize(image.width, image.height, image.alt, align));
        } else if ("tbl".equals(name)) {
          Table table = Docx.readTable(child, id, warnings, lists, styles, links, comments, pictures);
          if (table != null) blocks.add(table);
        } else if (!"sectPr".equals(name)) warnings.add("Header/footer content: " + name);
      }
      comments.markers = wasMarkers;
      comments.bookmarks.supported = wasSupported;
      var paragraphs = Sequence.<Block>of(blocks);
      stories.put(key, paragraphs);
    }
    Element margin = Xml.child(section, "pgMar");
    float header = 18, footer = 18;
    try {
      if (margin != null) {
        header = Xml.number(Xml.attr(margin, "header", "360")) / 20;
        footer = Xml.number(Xml.attr(margin, "footer", "360")) / 20;
      }
    } catch (RuntimeException ex) {
      warnings.add("Invalid header/footer distance");
      header = footer = 18;
    }
    Element settings =
        parts.containsKey("word/settings.xml") ? Xml.parse(parts.get("word/settings.xml")) : null;
    return new PageFurniture(
        stories,
        enabled(section, "titlePg"),
        enabled(settings, "evenAndOddHeaders"),
        header,
        footer);
  }

  void references(StringBuilder b) {
    for (String key : ids.keySet()) {
      String kind = key.toLowerCase().endsWith("header") ? "header" : "footer",
          type = key.startsWith("first") ? "first" : key.startsWith("even") ? "even" : "default";
      b.append("<w:")
          .append(kind)
          .append("Reference w:type=\"")
          .append(type)
          .append("\" r:id=\"")
          .append(ids.get(key))
          .append("\"/>");
    }
    if (document.furniture.differentFirst) b.append("<w:titlePg/>");
  }

  void packageParts(Map<String, byte[]> parts, DocxLists lists, DocxTabs tabs) throws IOException {
    for (String key : ids.keySet()) {
      String kind = key.toLowerCase().endsWith("header") ? "header" : "footer",
          tag = kind.equals("header") ? "hdr" : "ftr",
          path = paths.get(key);
      var relPath = "word/_rels/" + path.substring(5) + ".rels";
      var links = new DocxLinks(parts, relPath);
      var b =
          new StringBuilder("<w:")
              .append(tag)
              .append(" xmlns:w=\"")
              .append(W)
              .append("\" xmlns:r=\"")
              .append(R)
              .append("\">");
      var pictures = new DocxPictures(parts, relPath);
      var story = document.furniture.blocks(key);
      for (Block block : story)
        if (block instanceof Paragraph para)
          Docx.writeParagraph(b, para, lists, tabs, links, marks());
        else if (block instanceof Table t)
          Docx.writeTable(
              b, t, document.page.contentWidth(), lists, tabs, links, marks(), pictures);
        else if (block instanceof Picture picture) pictures.write(b, picture);
      // A story must end with a paragraph (ECMA-376 17.10.1), as table cells must.
      if (story.size() == 0 || !(story.get(story.size() - 1) instanceof Paragraph))
        b.append("<w:p/>");
      parts.put(path, b.append("</w:").append(tag).append(">").toString().getBytes("UTF-8"));
      links.packageParts(parts);
      pictures.packageParts();
      relationship(parts, ids.get(key), kind, path.substring(5));
      contentType(parts, path, kind);
    }
    var settingsPath = "word/settings.xml";
    Element settings =
        parts.containsKey(settingsPath)
            ? Xml.parse(parts.get(settingsPath))
            : Xml.parse("<w:settings xmlns:w=\"" + W + "\"/>");
    // Newly created documents use border-at-margin table placement. Without an
    // explicit mode, other readers can apply pre-2013 cell-text-at-margin rules.
    if (!parts.containsKey(settingsPath))
      settings.addChild(
          Xml.parse(
              "<w:compat xmlns:w=\""
                  + W
                  + "\"><w:compatSetting w:name=\"compatibilityMode\""
                  + " w:uri=\"http://schemas.microsoft.com/office/word\""
                  + " w:val=\"15\"/></w:compat>"));
    for (int i = settings.getNumChildren() - 1; i >= 0; i--)
      if (!settings.getChildAt(i).isTextElement()
          && "evenAndOddHeaders".equals(Xml.local(settings.getChildAt(i))))
        settings.removeChildAt(i);
    for (int i = settings.getNumChildren() - 1; i >= 0; i--)
      if (!settings.getChildAt(i).isTextElement()
          && "displayBackgroundShape".equals(Xml.local(settings.getChildAt(i))))
        settings.removeChildAt(i);
    if (document.lastSection.decor.pageColor >= 0)
      settings.addChild(Xml.parse("<w:displayBackgroundShape xmlns:w=\"" + W + "\"/>"));
    if (document.furniture.differentEven)
      settings.addChild(Xml.parse("<w:evenAndOddHeaders xmlns:w=\"" + W + "\"/>"));
    for (int i = settings.getNumChildren() - 1; i >= 0; i--)
      if (!settings.getChildAt(i).isTextElement()
          && "mirrorMargins".equals(Xml.local(settings.getChildAt(i)))) settings.removeChildAt(i);
    if (document.mirrorMargins())
      settings.addChild(Xml.parse("<w:mirrorMargins xmlns:w=\"" + W + "\"/>"));
    for (int i = settings.getNumChildren() - 1; i >= 0; i--)
      if (!settings.getChildAt(i).isTextElement()
          && "trackRevisions".equals(Xml.local(settings.getChildAt(i)))) settings.removeChildAt(i);
    if (document.trackChanges)
      settings.addChild(Xml.parse("<w:trackRevisions xmlns:w=\"" + W + "\"/>"));
    NoteFormats.settings(settings, document.notes);
    DocxProperties.settings(settings, document.properties, W);
    tabs.settings(settings);
    parts.put(settingsPath, Xml.write(settings).getBytes("UTF-8"));
    var rels = new String(parts.get(REL), "UTF-8");
    boolean found = false;
    var existing = new HashSet<String>();
    for (Element rel : Xml.children(Xml.parse(rels), null)) {
      existing.add(Xml.attr(rel, "Id", ""));
      if ((R + "/settings").equals(Xml.attr(rel, "Type", ""))) found = true;
    }
    if (!found) {
      var id = "cn1Settings";
      while (existing.contains(id)) id += "x";
      relationship(parts, id, "settings", "settings.xml");
    }
    contentType(parts, settingsPath, "settings");
  }

  private static void relationship(Map<String, byte[]> parts, String id, String kind, String target)
      throws IOException {
    String xml =
        parts.containsKey(REL)
            ? new String(parts.get(REL), "UTF-8")
            : "<Relationships xmlns=\"" + P + "\"/>";
    for (Element rel : Xml.children(Xml.parse(xml), null))
      if (id.equals(Xml.attr(rel, "Id", ""))) return;
    parts.put(
        REL,
        DocxLists.insert(
                xml,
                "<Relationship xmlns=\""
                    + P
                    + "\" Id=\""
                    + Xml.escape(id)
                    + "\" Type=\""
                    + R
                    + "/"
                    + kind
                    + "\" Target=\""
                    + Xml.escape(target)
                    + "\"/>")
            .getBytes("UTF-8"));
  }

  private static void contentType(Map<String, byte[]> parts, String path, String kind)
      throws IOException {
    String xml =
        parts.containsKey("[Content_Types].xml")
            ? new String(parts.get("[Content_Types].xml"), "UTF-8")
            : "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>";
    for (Element e : Xml.children(Xml.parse(xml), null))
      if (("/" + path).equals(Xml.attr(e, "PartName", ""))) return;
    parts.put(
        "[Content_Types].xml",
        DocxLists.insert(
                xml,
                "<Override xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\""
                    + " PartName=\"/"
                    + path
                    + "\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml."
                    + kind
                    + "+xml\"/>")
            .getBytes("UTF-8"));
  }
}
