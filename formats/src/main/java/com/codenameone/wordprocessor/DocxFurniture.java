package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Single-section paragraph header/footer parts, with scoped relationships. */
final class DocxFurniture {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
      R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
      P = "http://schemas.openxmlformats.org/package/2006/relationships",
      REL = "word/_rels/document.xml.rels";
  private final Document document;
  private final Map<String, String> ids = new LinkedHashMap<String, String>(),
      paths = new LinkedHashMap<String, String>();

  DocxFurniture(Document doc, Map<String, byte[]> parts) throws IOException {
    this(doc, parts, "");
  }

  DocxFurniture(Document doc, Map<String, byte[]> parts, String prefix) throws IOException {
    document = doc;
    Set<String> existing = new HashSet<String>();
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
        String id = "cn1Furniture" + prefix + key;
        while (existing.contains(id)) id += "x";
        existing.add(id);
        ids.put(key, id);
        String path = "word/cn1-" + key + ".xml";
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
    if (section == null) return PageFurniture.EMPTY;
    Map<String, String> targets = new HashMap<String, String>();
    if (parts.containsKey(REL))
      for (Element rel : Xml.children(Xml.parse(parts.get(REL)), null)) {
        String type = Xml.attr(rel, "Type", "");
        if ((type.equals(R + "/header") || type.equals(R + "/footer"))
            && !"External".equals(Xml.attr(rel, "TargetMode", "")))
          targets.put(Xml.attr(rel, "Id", ""), Xml.attr(rel, "Target", ""));
      }
    Map<String, Sequence<Paragraph>> stories = new LinkedHashMap<String, Sequence<Paragraph>>();
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
      String target = targets.get(Xml.attr(ref, "id", ""));
      if (target == null || target.contains("..") || target.contains("\\")) {
        warnings.add("Missing or invalid header/footer relationship");
        continue;
      }
      String path = target.startsWith("/") ? target.substring(1) : "word/" + target;
      byte[] data = parts.get(path);
      if (data == null) {
        warnings.add("Missing header/footer part");
        continue;
      }
      Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
      String relPath =
          path.substring(0, path.lastIndexOf('/') + 1)
              + "_rels/"
              + path.substring(path.lastIndexOf('/') + 1)
              + ".rels";
      DocxLinks links = new DocxLinks(parts, relPath);
      DocxComments comments = new DocxComments(Collections.<String, byte[]>emptyMap());
      comments.bookmarks.supported = false;
      for (Element child : Xml.children(Xml.parse(data), null)) {
        if ("p".equals(Xml.local(child)))
          paragraphs =
              paragraphs.append(
                  Docx.paragraph(child, id, warnings, lists, styles, links, comments));
        else warnings.add("Header/footer content: " + Xml.local(child));
      }
      stories.put(key, paragraphs);
    }
    Element margin = Xml.child(section, "pgMar");
    float header = 18, footer = 18;
    try {
      if (margin != null) {
        header = Float.parseFloat(Xml.attr(margin, "header", "360")) / 20;
        footer = Float.parseFloat(Xml.attr(margin, "footer", "360")) / 20;
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
      String relPath = "word/_rels/" + path.substring(5) + ".rels";
      DocxLinks links = new DocxLinks(parts, relPath);
      StringBuilder b =
          new StringBuilder("<w:")
              .append(tag)
              .append(" xmlns:w=\"")
              .append(W)
              .append("\" xmlns:r=\"")
              .append(R)
              .append("\">");
      for (Paragraph para : document.furniture.get(key))
        Docx.writeParagraph(b, para, lists, tabs, links, new DocxComments(Document.empty()));
      if (document.furniture.get(key).size() == 0) b.append("<w:p/>");
      parts.put(path, b.append("</w:").append(tag).append(">").toString().getBytes("UTF-8"));
      links.packageParts(parts);
      relationship(parts, ids.get(key), kind, path.substring(5));
      contentType(parts, path, kind);
    }
    String settingsPath = "word/settings.xml";
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
          && "trackRevisions".equals(Xml.local(settings.getChildAt(i)))) settings.removeChildAt(i);
    if (document.trackChanges)
      settings.addChild(Xml.parse("<w:trackRevisions xmlns:w=\"" + W + "\"/>"));
    NoteFormats.settings(settings, document.notes);
    DocxProperties.settings(settings, document.properties, W);
    tabs.settings(settings);
    parts.put(settingsPath, Xml.write(settings).getBytes("UTF-8"));
    String rels = new String(parts.get(REL), "UTF-8");
    boolean found = false;
    Set<String> existing = new HashSet<String>();
    for (Element rel : Xml.children(Xml.parse(rels), null)) {
      existing.add(Xml.attr(rel, "Id", ""));
      if ((R + "/settings").equals(Xml.attr(rel, "Type", ""))) found = true;
    }
    if (!found) {
      String id = "cn1Settings";
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
