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
    var p = DocumentProperties.EMPTY;
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
    p = readCustom(parts, p, warnings);
    Element vars = settings == null ? null : Xml.child(settings, "docVars");
    if (vars != null)
      for (Element v : Xml.children(vars, "docVar")) {
        String name = Xml.attr(v, "name", "");
        if (name.startsWith(VAR) && name.length() > VAR.length())
          try {
            p = p.withSetting(name.substring(VAR.length()), Xml.attr(v, "val", ""));
          } catch (IllegalArgumentException ex) {
            warnings.add("Ignored invalid document setting");
          }
      }
    return p;
  }

  /** Prefix of the w:docVar names that carry {@link DocumentProperties#settings()}. */
  static final String VAR = "cn1.";

  static void write(Map<String, byte[]> parts, DocumentProperties p) throws IOException {
    writeCustom(parts, p);
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
          var c = root.getChildAt(i);
          if (!c.isTextElement() && m[3].equals(Xml.local(c))) root.removeChildAt(i);
        }
        var v = p.get(m[0]);
        if (v.isEmpty()) continue;
        String tag = m[2].isEmpty() ? m[3] : m[2] + ":" + m[3];
        String ns = "dc".equals(m[2]) ? DC : "cp".equals(m[2]) ? CP : EP;
        root.addChild(
            Xml.parse(
                "<" + tag + " xmlns" + (m[2].isEmpty() ? "" : ":" + m[2]) + "=\"" + ns + "\">"
                    + Xml.escape(v) + "</" + tag + ">"));
      }
      parts.put(part, Xml.write(root).getBytes("UTF-8"));
      var rels = new String(parts.get("_rels/.rels"), "UTF-8");
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
      var types = new String(parts.get("[Content_Types].xml"), "UTF-8");
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
    Element vars = Xml.child(settings, "docVars");
    if (vars != null)
      for (int i = vars.getNumChildren() - 1; i >= 0; i--)
        if (Xml.attr(vars.getChildAt(i), "name", "").startsWith(VAR)) vars.removeChildAt(i);
    if (!p.settings().isEmpty()) {
      var b = new StringBuilder("<w:docVars xmlns:w=\"" + w + "\">");
      for (Map.Entry<String, String> e : p.settings().entrySet())
        b.append("<w:docVar w:name=\"").append(VAR).append(Xml.escape(e.getKey()))
            .append("\" w:val=\"").append(Xml.escape(e.getValue())).append("\"/>");
      Element fresh = Xml.parse(b.append("</w:docVars>").toString());
      if (vars == null) {
        // CT_Settings is a sequence: docVars precedes rsids and every later element.
        var later =
            java.util.Arrays.asList(
                "rsids", "mathPr", "attachedSchema", "themeFontLang", "clrSchemeMapping",
                "doNotIncludeSubdocsInStats", "doNotAutoCompressPictures", "forceUpgrade",
                "captions", "readModeInkLockDown", "smartTagType", "schemaLibrary",
                "shapeDefaults", "doNotEmbedSmartTags", "decimalSymbol", "listSeparator");
        int at = settings.getNumChildren();
        for (int i = 0; i < settings.getNumChildren(); i++)
          if (!settings.getChildAt(i).isTextElement()
              && later.contains(Xml.local(settings.getChildAt(i)))) {
            at = i;
            break;
          }
        settings.insertChildAt(fresh, at);
      }
      else
        while (fresh.getNumChildren() > 0) {
          var c = fresh.getChildAt(0);
          fresh.removeChildAt(0);
          vars.addChild(c);
        }
    } else if (vars != null && vars.getNumChildren() == 0) settings.removeChildAt(settings.getChildIndex(vars));
    if (!"none".equals(p.protection))
      settings.addChild(
          Xml.parse(
              "<w:documentProtection xmlns:w=\"" + w + "\" w:edit=\"" + p.protection
                  + "\" w:enforcement=\"1\"/>"));
  }

  private static final String CUSTOM = "docProps/custom.xml",
      CUSTOM_NS = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties",
      VT = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes",
      FMTID = "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}";

  /** Joins Zotero's ZOTERO_PREF_1..n custom properties (Word plugin layout) into one setting. */
  private static DocumentProperties readCustom(
      Map<String, byte[]> parts, DocumentProperties p, Set<String> warnings) {
    if (!parts.containsKey(CUSTOM)) return p;
    Element root;
    try {
      root = Xml.parse(parts.get(CUSTOM));
    } catch (IOException ex) {
      warnings.add("Unreadable custom document properties");
      return p;
    }
    var chunks = new java.util.TreeMap<Integer, String>();
    for (Element prop : Xml.children(root, "property")) {
      int n = Zotero.prefIndex(Xml.attr(prop, "name", ""));
      if (n <= 0) continue;
      String v = "";
      for (int i = 0; i < prop.getNumChildren(); i++)
        if (!prop.getChildAt(i).isTextElement()) v = Xml.text((Element) prop.getChildAt(i));
      chunks.put(n, v);
    }
    var b = new StringBuilder();
    for (int i = 1; chunks.containsKey(i); i++) b.append(chunks.get(i));
    if (b.length() == 0) return p;
    try {
      return p.withSetting(Zotero.PREFS_SETTING, b.toString());
    } catch (IllegalArgumentException ex) {
      warnings.add("Zotero document preferences are too long and were not kept");
      return p;
    }
  }

  /** Rewrites ZOTERO_PREF_n custom properties from the setting, keeping other custom properties. */
  private static void writeCustom(Map<String, byte[]> parts, DocumentProperties p)
      throws IOException {
    String prefs = p.setting(Zotero.PREFS_SETTING, "");
    if (prefs.isEmpty() && !parts.containsKey(CUSTOM)) return;
    Element root =
        parts.containsKey(CUSTOM)
            ? Xml.parse(parts.get(CUSTOM))
            : Xml.parse("<Properties xmlns=\"" + CUSTOM_NS + "\" xmlns:vt=\"" + VT + "\"/>");
    int pid = 1;
    for (int i = root.getNumChildren() - 1; i >= 0; i--) {
      var c = root.getChildAt(i);
      if (c.isTextElement() || !"property".equals(Xml.local((Element) c))) continue;
      if (Zotero.prefIndex(Xml.attr((Element) c, "name", "")) > 0) root.removeChildAt(i);
      else pid = Math.max(pid, Xml.integer(Xml.attr((Element) c, "pid", "1"), 1));
    }
    for (int i = 0, n = 1; i < prefs.length(); i += Zotero.PREF_CHUNK, n++) {
      String chunk = prefs.substring(i, Math.min(prefs.length(), i + Zotero.PREF_CHUNK));
      root.addChild(
          Xml.parse(
              "<property xmlns=\"" + CUSTOM_NS + "\" xmlns:vt=\"" + VT + "\" fmtid=\"" + FMTID
                  + "\" pid=\"" + (++pid) + "\" name=\"" + Zotero.PREF_NAME + n + "\"><vt:lpwstr>"
                  + Xml.escape(chunk) + "</vt:lpwstr></property>"));
    }
    parts.put(CUSTOM, Xml.write(root).getBytes("UTF-8"));
    var rels = new String(parts.get("_rels/.rels"), "UTF-8");
    if (!rels.contains(CUSTOM + "\"")) {
      String id = "cn1Custom";
      while (rels.contains("\"" + id + "\"")) id += "x";
      parts.put(
          "_rels/.rels",
          DocxLists.insert(
                  rels,
                  "<Relationship xmlns=\"" + REL + "\" Id=\"" + id
                      + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties\" Target=\""
                      + CUSTOM + "\"/>")
              .getBytes("UTF-8"));
    }
    var types = new String(parts.get("[Content_Types].xml"), "UTF-8");
    if (!types.contains("\"/" + CUSTOM + "\""))
      parts.put(
          "[Content_Types].xml",
          DocxLists.insert(
                  types,
                  "<Override xmlns=\"" + TYPES + "\" PartName=\"/" + CUSTOM
                      + "\" ContentType=\"application/vnd.openxmlformats-officedocument.custom-properties+xml\"/>")
              .getBytes("UTF-8"));
  }
}
