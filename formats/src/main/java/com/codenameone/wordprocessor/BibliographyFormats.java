package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Native source records and standard OOXML bibliography custom-XML parts. */
final class BibliographyFormats {
  private static final String NS = "http://schemas.openxmlformats.org/officeDocument/2006/bibliography";
  private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
  private static final String PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String[] TAGS = {"Tag", "SourceType", "Author", "Editor", "Title", "Year", "Month", "Day", "City", "Publisher", "JournalName", "Edition", "Volume", "Issue", "Pages", "URL", "Accessed", "DOI"};
  static void nativeWrite(StringBuilder b, Bibliography value) {
    b.append("<bibliography style=\"").append(Xml.escape(value.style)).append("\">");
    for (CitationSource source : value.sources.values()) {
      b.append("<source");
      for (String key : CitationSource.KEYS) b.append(' ').append(key).append("=\"").append(Xml.escape(source.get(key))).append('"');
      b.append("/>");
    }
    b.append("</bibliography>");
  }
  static Bibliography nativeRead(Element root) throws IOException {
    if (root == null) return Bibliography.EMPTY;
    var sources = new LinkedHashMap<String, CitationSource>();
    for (Element e : Xml.children(root, "source")) {
      var data = new LinkedHashMap<String, String>();
      for (String key : CitationSource.KEYS) data.put(key, Xml.attr(e, key, ""));
      add(sources, new CitationSource(data));
    }
    return new Bibliography(Xml.attr(root, "style", "APA"), sources);
  }
  private static void add(Map<String, CitationSource> sources, CitationSource source) throws IOException {
    if (sources.size() >= 2000 || sources.containsKey(source.tag())) throw new IOException("Duplicate or excessive bibliography sources");
    sources.put(source.tag(), source);
  }
  private static boolean sourceRoot(Element e) {
    if (!"Sources".equals(Xml.local(e))) return false;
    String tag = e.getTagName(), prefix = tag.indexOf(':') < 0 ? "xmlns" : "xmlns:" + tag.substring(0, tag.indexOf(':'));
    return NS.equals(e.getAttribute(prefix));
  }
  private static String text(Element parent, String name) { Element e = Xml.child(parent, name); return e == null ? "" : Xml.text(e); }
  private static String persons(Element parent) {
    if (parent == null) return "";
    if (Xml.child(parent, "Corporate") != null) return text(parent, "Corporate");
    Element list = Xml.child(parent, "NameList"); if (list == null) list = parent;
    var out = new StringBuilder();
    for (Element person : Xml.children(list, "Person")) {
      if (out.length() > 0) out.append("; ");
      var first = (text(person, "First") + " " + text(person, "Middle")).trim();
      out.append(text(person, "Last")); if (!first.isEmpty()) out.append(", ").append(first);
    }
    return out.toString();
  }
  static Bibliography read(Map<String, byte[]> parts, Set<String> warnings) throws IOException {
    var sources = new LinkedHashMap<String, CitationSource>(); var style = "APA";
    for (String path : parts.keySet()) if (path.startsWith("customXml/") && path.endsWith(".xml") && !path.contains("/_rels/")) {
      Element root = Xml.parse(parts.get(path)); if (!sourceRoot(root)) continue;
      style = Xml.attr(root, "StyleName", style);
      for (Element e : Xml.children(root, "Source")) {
        var data = new LinkedHashMap<String, String>();
        for (int i = 0; i < TAGS.length; i++) if (i != 2 && i != 3) data.put(CitationSource.KEYS[i], text(e, TAGS[i]));
        Element authors = Xml.child(e, "Author");
        data.put("author", authors == null ? "" : persons(Xml.child(authors, "Author") == null ? authors : Xml.child(authors, "Author")));
        data.put("editor", authors == null ? persons(Xml.child(e, "Editor")) : persons(Xml.child(authors, "Editor")));
        data.put("kind", kind(data.get("kind"), false));
        if (data.get("doi").isEmpty()) data.put("doi", text(e, "StandardNumber"));
        if (data.get("accessed").isEmpty()) {
          String date = text(e, "YearAccessed");
          if (!text(e, "MonthAccessed").isEmpty()) date += "-" + text(e, "MonthAccessed");
          if (!text(e, "DayAccessed").isEmpty()) date += "-" + text(e, "DayAccessed");
          data.put("accessed", date);
        }
        if (data.get("tag").isEmpty()) { warnings.add("Bibliography source without a tag cannot be linked to citations"); continue; }
        add(sources, new CitationSource(data));
        for (Element child : Xml.children(e, null))
          if (!Arrays.asList(TAGS).contains(Xml.local(child)) && !Arrays.asList("Guid", "LCID", "StandardNumber", "YearAccessed", "MonthAccessed", "DayAccessed").contains(Xml.local(child)))
            warnings.add("Bibliography source property: " + Xml.local(child));
      }
    }
    try { style = CitationStyles.NAMES[CitationStyles.index(style)]; }
    catch (IllegalArgumentException unsupported) { warnings.add("Unsupported bibliography style: " + style); }
    return new Bibliography(style, sources);
  }
  private static String kind(String value, boolean write) {
    String[] ours = {"book", "article", "website", "report", "bookSection", "conference", "misc"};
    String[] word = {"Book", "JournalArticle", "InternetSite", "Report", "BookSection", "ConferenceProceedings", "Misc"};
    for (int i = 0; i < ours.length; i++) if ((write ? ours[i] : word[i]).equalsIgnoreCase(value)) return write ? word[i] : ours[i];
    return value;
  }
  private static void element(StringBuilder b, String tag, String value) {
    if (!value.isEmpty()) b.append("<b:").append(tag).append('>').append(Xml.escape(value)).append("</b:").append(tag).append('>');
  }
  private static void names(StringBuilder b, String role, String value) {
    if (value.isEmpty()) return;
    b.append("<b:").append(role).append("><b:NameList>");
    for (String[] person : CitationSource.names(value)) {
      b.append("<b:Person>"); element(b, "Last", person[0]); element(b, "First", person[1]); b.append("</b:Person>");
    }
    b.append("</b:NameList></b:").append(role).append('>');
  }
  static void write(Map<String, byte[]> parts, Bibliography bibliography) throws IOException {
    // Reuse the bibliography part, leaving unrelated custom XML and its relationships untouched.
    var old = new ArrayList<String>();
    for (String path : parts.keySet()) if (path.startsWith("customXml/") && path.endsWith(".xml") && !path.contains("/_rels/"))
      if (sourceRoot(Xml.parse(parts.get(path)))) old.add(path);
    if (old.isEmpty() && bibliography.sources.isEmpty() && "APA".equals(bibliography.style)) return;
    String path = old.isEmpty() ? null : old.get(0);
    if (path == null) {
      int index = 1;
      while (parts.containsKey("customXml/cn1Bibliography" + index + ".xml")) index++;
      path = "customXml/cn1Bibliography" + index + ".xml";
    }
    var b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><b:Sources xmlns:b=\"").append(NS)
        .append("\" StyleName=\"").append(Xml.escape(bibliography.style)).append("\">");
    for (CitationSource source : bibliography.sources.values()) {
      b.append("<b:Source>");
      for (int i = 0; i < TAGS.length; i++) if (i != 2 && i != 3) element(b, TAGS[i], i == 1 ? kind(source.get("kind"), true) : source.get(CitationSource.KEYS[i]));
      b.append("<b:Author>"); names(b, "Author", source.get("author")); names(b, "Editor", source.get("editor")); b.append("</b:Author></b:Source>");
    }
    b.append("</b:Sources>"); parts.put(path, b.toString().getBytes("UTF-8"));
    // Empty duplicate source parts avoid stale sources reappearing on reopen, without removing stores referenced elsewhere.
    for (String duplicate : old) if (!duplicate.equals(path)) parts.put(duplicate, ("<b:Sources xmlns:b=\"" + NS + "\"/>").getBytes("UTF-8"));
    relationship(parts, "word/_rels/document.xml.rels", REL + "customXml", "../" + path);
    String file = path.substring(path.lastIndexOf('/') + 1), propertiesRels = "customXml/_rels/" + file + ".rels";
    if (parts.containsKey(propertiesRels)) for (Element e : Xml.children(Xml.parse(parts.get(propertiesRels)), null))
      if ((REL + "customXmlProps").equals(Xml.attr(e, "Type", "")) && !"External".equals(Xml.attr(e, "TargetMode", ""))
          && parts.containsKey("customXml/" + Xml.attr(e, "Target", ""))) return;
    String stem = file.substring(0, file.length() - 4) + "Props", props = stem + ".xml";
    int suffix = 1; while (parts.containsKey("customXml/" + props)) props = stem + suffix++ + ".xml";
    var propsPath = "customXml/" + props;
    var id = "00000000" + Integer.toHexString(propsPath.hashCode()); id = id.substring(id.length() - 8);
    parts.put(propsPath, ("<ds:datastoreItem xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\" ds:itemID=\"{" + id + "-C1B1-4000-8000-000000000001}\"><ds:schemaRefs><ds:schemaRef ds:uri=\"" + NS + "\"/></ds:schemaRefs></ds:datastoreItem>").getBytes("UTF-8"));
    relationship(parts, propertiesRels, REL + "customXmlProps", props);
    var types = new String(parts.get("[Content_Types].xml"), "UTF-8");
    parts.put("[Content_Types].xml", DocxLists.insert(types, "<Override xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\" PartName=\"/" + Xml.escape(propsPath) + "\" ContentType=\"application/vnd.openxmlformats-officedocument.customXmlProperties+xml\"/>").getBytes("UTF-8"));
  }

  private static void relationship(Map<String, byte[]> parts, String path, String type, String target) throws IOException {
    String xml = parts.containsKey(path) ? new String(parts.get(path), "UTF-8") : "<Relationships xmlns=\"" + PACKAGE + "\"></Relationships>";
    var ids = new HashSet<String>();
    for (Element e : Xml.children(Xml.parse(xml), null)) {
      if (type.equals(Xml.attr(e, "Type", "")) && target.equals(Xml.attr(e, "Target", ""))) return;
      ids.add(Xml.attr(e, "Id", ""));
    }
    var id = "cn1Bibliography"; while (ids.contains(id)) id += "x";
    parts.put(path, DocxLists.insert(xml, "<Relationship xmlns=\"" + PACKAGE + "\" Id=\"" + id + "\" Type=\"" + type + "\" Target=\"" + Xml.escape(target) + "\"/>").getBytes("UTF-8"));
  }
}
