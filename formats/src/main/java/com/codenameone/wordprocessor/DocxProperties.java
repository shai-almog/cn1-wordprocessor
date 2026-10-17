package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Summary properties in docProps/core.xml and docProps/app.xml, protection in settings. */
final class DocxProperties {
  private static final String CORE = "docProps/core.xml", APP = "docProps/app.xml";
  private static final String CP =
          "http://schemas.openxmlformats.org/package/2006/metadata/core-properties",
      DC = "http://purl.org/dc/elements/1.1/",
      EP = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties",
      REL = "http://schemas.openxmlformats.org/package/2006/relationships",
      TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";

  /** Model key, part, element prefix and element name. */
  private static final String[][] MAP = {
    {"title", CORE, "dc", "title"},
    {"author", CORE, "dc", "creator"},
    {"subject", CORE, "dc", "subject"},
    {"keywords", CORE, "cp", "keywords"},
    {"description", CORE, "dc", "description"},
    {"category", CORE, "cp", "category"},
    {"company", APP, "", "Company"},
    {"manager", APP, "", "Manager"}
  };

  private DocxProperties() {}

  static DocumentProperties read(Map<String, byte[]> parts, Element settings, Set<String> warnings)
      throws IOException {
    DocumentProperties p = DocumentProperties.EMPTY;
    for (String part : new String[] {CORE, APP}) {
      if (!parts.containsKey(part)) continue;
      Element root;
      try {
        root = Xml.parse(parts.get(part));
      } catch (IOException ex) {
        warnings.add("Unreadable document properties");
        continue;
      }
      for (String[] m : MAP) {
        if (!m[1].equals(part)) continue;
        Element e = Xml.child(root, m[3]);
        if (e != null) {
          String v = Xml.text(e);
          p = p.with(m[0], v.length() > 4096 ? v.substring(0, 4096) : v);
        }
      }
    }
    Element protect = settings == null ? null : Xml.child(settings, "documentProtection");
    if (protect != null && !"0".equals(Xml.attr(protect, "enforcement", "0"))
        && !"false".equals(Xml.attr(protect, "enforcement", "0"))) {
      String edit = Xml.attr(protect, "edit", "none");
      if (DocumentProperties.PROTECTION.contains(edit)) p = p.protection(edit);
      else warnings.add("Editing restriction " + edit + " is not enforced");
      if (!Xml.attr(protect, "hash", "").isEmpty() || !Xml.attr(protect, "hashValue", "").isEmpty())
        warnings.add("Protection password is not kept");
    }
    return p;
  }

  static void write(Map<String, byte[]> parts, DocumentProperties p) throws IOException {
    for (String part : new String[] {CORE, APP}) {
      boolean core = CORE.equals(part);
      boolean any = false;
      for (String[] m : MAP) if (m[1].equals(part) && !p.get(m[0]).isEmpty()) any = true;
      if (!any && !parts.containsKey(part)) continue;
      Element root =
          parts.containsKey(part)
              ? Xml.parse(parts.get(part))
              : Xml.parse(
                  core
                      ? "<cp:coreProperties xmlns:cp=\"" + CP + "\" xmlns:dc=\"" + DC + "\"/>"
                      : "<Properties xmlns=\"" + EP + "\"/>");
      for (String[] m : MAP) {
        if (!m[1].equals(part)) continue;
        for (int i = root.getNumChildren() - 1; i >= 0; i--) {
          Element c = root.getChildAt(i);
          if (!c.isTextElement() && m[3].equals(Xml.local(c))) root.removeChildAt(i);
        }
        String v = p.get(m[0]);
        if (v.isEmpty()) continue;
        String tag = m[2].isEmpty() ? m[3] : m[2] + ":" + m[3];
        String ns = "dc".equals(m[2]) ? DC : "cp".equals(m[2]) ? CP : EP;
        root.addChild(
            Xml.parse(
                "<" + tag + " xmlns" + (m[2].isEmpty() ? "" : ":" + m[2]) + "=\"" + ns + "\">"
                    + Xml.escape(v) + "</" + tag + ">"));
      }
      parts.put(part, Xml.write(root).getBytes("UTF-8"));
      String rels = new String(parts.get("_rels/.rels"), "UTF-8");
      if (!rels.contains("/" + part + "\"") && !rels.contains("\"" + part + "\"")) {
        String type =
            core
                ? "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
                : "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties";
        String id = core ? "cn1Core" : "cn1App";
        while (rels.contains("\"" + id + "\"")) id += "x";
        parts.put(
            "_rels/.rels",
            DocxLists.insert(
                    rels,
                    "<Relationship xmlns=\"" + REL + "\" Id=\"" + id + "\" Type=\"" + type
                        + "\" Target=\"" + part + "\"/>")
                .getBytes("UTF-8"));
      }
      String types = new String(parts.get("[Content_Types].xml"), "UTF-8");
      if (!types.contains("\"/" + part + "\""))
        parts.put(
            "[Content_Types].xml",
            DocxLists.insert(
                    types,
                    "<Override xmlns=\"" + TYPES + "\" PartName=\"/" + part + "\" ContentType=\""
                        + (core
                            ? "application/vnd.openxmlformats-package.core-properties+xml"
                            : "application/vnd.openxmlformats-officedocument.extended-properties+xml")
                        + "\"/>")
                .getBytes("UTF-8"));
    }
  }

  /** Replaces the settings documentProtection element; the password hash is never written. */
  static void settings(Element settings, DocumentProperties p, String w) throws IOException {
    for (int i = settings.getNumChildren() - 1; i >= 0; i--)
      if (!settings.getChildAt(i).isTextElement()
          && "documentProtection".equals(Xml.local(settings.getChildAt(i))))
        settings.removeChildAt(i);
    if (!"none".equals(p.protection))
      settings.addChild(
          Xml.parse(
              "<w:documentProtection xmlns:w=\"" + w + "\" w:edit=\"" + p.protection
                  + "\" w:enforcement=\"1\"/>"));
  }
}
