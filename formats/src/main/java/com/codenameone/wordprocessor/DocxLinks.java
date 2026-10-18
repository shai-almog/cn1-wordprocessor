package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** External hyperlink relationships with collision-free IDs and bookmark anchors. */
final class DocxLinks {
  DocxNotes notes;
  private static final String PATH = "word/_rels/document.xml.rels";
  private static final String NS = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String TYPE =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink";
  private final String path;
  private final Map<String, String> targets = new LinkedHashMap<>();
  private final Set<String> ids = new HashSet<>();

  DocxLinks(Map<String, byte[]> parts) throws IOException {
    this(parts, PATH);
  }

  DocxLinks(Map<String, byte[]> parts, String path) throws IOException {
    this.path = path;
    var bytes = parts.get(path);
    if (bytes != null)
      for (Element e : Xml.children(Xml.parse(bytes), null)) {
        String id = Xml.attr(e, "Id", "");
        ids.add(id);
        if (TYPE.equals(Xml.attr(e, "Type", ""))
            && "External".equals(Xml.attr(e, "TargetMode", "")))
          targets.put(id, Xml.attr(e, "Target", ""));
      }
  }

  String target(Element link) {
    String anchor = Xml.attr(link, "anchor", "");
    if (!anchor.isEmpty()) return "#" + anchor;
    var value = targets.get(Xml.attr(link, "id", ""));
    return value == null ? "" : value;
  }

  String id(String target) {
    for (Map.Entry<String, String> e : targets.entrySet())
      if (e.getValue().equals(target)) return e.getKey();
    var id = "cn1Link" + (targets.size() + 1);
    while (ids.contains(id)) id += "x";
    ids.add(id);
    targets.put(id, target);
    return id;
  }

  void packageParts(Map<String, byte[]> parts) throws IOException {
    String xml =
        parts.containsKey(path)
            ? new String(parts.get(path), "UTF-8")
            : "<Relationships xmlns=\"" + NS + "\"></Relationships>";
    var existing = new HashSet<String>();
    for (Element e : Xml.children(Xml.parse(xml), null)) existing.add(Xml.attr(e, "Id", ""));
    for (Map.Entry<String, String> e : targets.entrySet())
      if (!existing.contains(e.getKey()))
        xml =
            DocxLists.insert(
                xml,
                "<Relationship xmlns=\""
                    + NS
                    + "\" Id=\""
                    + Xml.escape(e.getKey())
                    + "\" Type=\""
                    + TYPE
                    + "\" TargetMode=\"External\" Target=\""
                    + Xml.escape(e.getValue())
                    + "\"/>");
    parts.put(path, xml.getBytes("UTF-8"));
  }
}
