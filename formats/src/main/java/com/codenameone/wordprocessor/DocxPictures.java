package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.*;
import java.util.*;

/** Embedded, paragraph-level DrawingML pictures. External images are never fetched. */
final class DocxPictures {
  private static final String RELS = "word/_rels/document.xml.rels";
  private static final String NS = "http://schemas.openxmlformats.org/package/2006/relationships";
  private final Map<String, byte[]> parts;
  private final Map<String, String> targets = new HashMap<String, String>();
  private final Map<Picture, String> output = new LinkedHashMap<Picture, String>();
  private final Set<String> ids = new HashSet<String>();

  DocxPictures(Map<String, byte[]> parts) throws IOException {
    this.parts = parts;
    if (parts.containsKey(RELS))
      for (Element e : Xml.children(Xml.parse(parts.get(RELS)), null)) {
        String id = Xml.attr(e, "Id", "");
        ids.add(id);
        if (Xml.attr(e, "Type", "").endsWith("/image")
            && !"External".equals(Xml.attr(e, "TargetMode", ""))) {
          String target = Xml.attr(e, "Target", "");
          if (!target.contains("..") && !target.contains("\\"))
            targets.put(id, target.startsWith("/") ? target.substring(1) : "word/" + target);
        }
      }
  }

  static Element descendant(Element e, String kind) {
    if (kind.equals(Xml.local(e))) return e;
    for (Element child : Xml.children(e, null)) {
      Element found = descendant(child, kind);
      if (found != null) return found;
    }
    return null;
  }

  boolean hasPicture(Element paragraph) {
    return descendant(paragraph, "drawing") != null;
  }

  List<Picture> read(Element paragraph, long[] id, Set<String> warnings) {
    List<Picture> result = new ArrayList<Picture>();
    collect(paragraph, result, id, warnings);
    return result;
  }

  private void collect(Element e, List<Picture> result, long[] id, Set<String> warnings) {
    if ("drawing".equals(Xml.local(e))) {
      try {
        Element inline = descendant(e, "inline"), anchor = descendant(e, "anchor");
        Element container = inline != null ? inline : anchor;
        if (container == null) throw new IllegalArgumentException("Unknown drawing container");
        if (anchor != null)
          warnings.add("Floating picture placement converted to a separate paragraph");
        Element blip = descendant(container, "blip"),
            extent = Xml.child(container, "extent"),
            props = Xml.child(container, "docPr");
        if (blip == null || extent == null)
          throw new IllegalArgumentException("Drawing is not an embedded picture");
        byte[] data = parts.get(targets.get(Xml.attr(blip, "embed", "")));
        if (data == null) throw new IllegalArgumentException("Picture data missing or external");
        if (descendant(container, "srcRect") != null) warnings.add("Picture crop needs conversion");
        Element transform = descendant(container, "xfrm");
        if (transform != null
            && (!"0".equals(Xml.attr(transform, "rot", "0"))
                || "1".equals(Xml.attr(transform, "flipH", "0"))
                || "1".equals(Xml.attr(transform, "flipV", "0"))))
          warnings.add("Picture rotation or reflection needs conversion");
        result.add(
            new Picture(
                "i" + id[0]++,
                data,
                Float.parseFloat(Xml.attr(extent, "cx", "0")) / 12700f,
                Float.parseFloat(Xml.attr(extent, "cy", "0")) / 12700f,
                props == null ? "" : Xml.attr(props, "descr", Xml.attr(props, "name", "")),
                "left"));
      } catch (RuntimeException ex) {
        warnings.add("Picture requires conversion: " + ex.getMessage());
      }
      return;
    }
    for (Element child : Xml.children(e, null)) collect(child, result, id, warnings);
  }

  void write(StringBuilder b, Picture p) {
    String id = output.get(p);
    if (id == null) {
      id = "cn1Picture" + (output.size() + 1);
      while (ids.contains(id)) id += "x";
      ids.add(id);
      output.put(p, id);
    }
    long cx = Math.round(p.width * 12700), cy = Math.round(p.height * 12700);
    b.append("<w:p><w:pPr><w:jc w:val=\"")
        .append(p.align)
        .append(
            "\"/></w:pPr><w:r><w:drawing><wp:inline"
                + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                + " distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"")
        .append(cx)
        .append("\" cy=\"")
        .append(cy)
        .append("\"/><wp:docPr id=\"")
        .append(output.size())
        .append("\" name=\"Picture\" descr=\"")
        .append(Xml.escape(p.alt))
        .append(
            "\"/><a:graphic"
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic"
                + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr"
                + " id=\"0\" name=\"Picture\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip"
                + " r:embed=\"")
        .append(id)
        .append(
            "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off"
                + " x=\"0\" y=\"0\"/><a:ext cx=\"")
        .append(cx)
        .append("\" cy=\"")
        .append(cy)
        .append(
            "\"/></a:xfrm><a:prstGeom"
                + " prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
  }

  void packageParts() throws IOException {
    if (output.isEmpty()) return;
    String rels =
        parts.containsKey(RELS)
            ? new String(parts.get(RELS), "UTF-8")
            : "<Relationships xmlns=\"" + NS + "\"></Relationships>";
    String types = new String(parts.get("[Content_Types].xml"), "UTF-8");
    for (Map.Entry<Picture, String> entry : output.entrySet()) {
      Picture p = entry.getKey();
      String name =
          "word/media/" + entry.getValue() + ("image/png".equals(p.mime) ? ".png" : ".jpg");
      while (parts.containsKey(name))
        name =
            name.substring(0, name.lastIndexOf('.')) + "x" + name.substring(name.lastIndexOf('.'));
      parts.put(name, p.bytes());
      rels =
          DocxLists.insert(
              rels,
              "<Relationship xmlns=\""
                  + NS
                  + "\" Id=\""
                  + entry.getValue()
                  + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
                  + " Target=\""
                  + name.substring(5)
                  + "\"/>");
      types =
          DocxLists.insert(
              types,
              "<Override xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\""
                  + " PartName=\"/"
                  + name
                  + "\" ContentType=\""
                  + p.mime
                  + "\"/>");
    }
    parts.put(RELS, rels.getBytes("UTF-8"));
    parts.put("[Content_Types].xml", types.getBytes("UTF-8"));
  }
}
