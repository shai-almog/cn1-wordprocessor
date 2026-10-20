package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Single-file XML forms of a Word document, opened by rewriting them as the package parts the
 * DOCX reader already understands (one reader, one set of property rules):
 *
 * <ul>
 *   <li>Flat OPC (root {@code pkg:package}, what Word saves as "Word XML Document"): each
 *       {@code pkg:part} becomes a part, from its {@code pkg:xmlData} element or base64
 *       {@code pkg:binaryData}.
 *   <li>Word 2003 XML (root {@code w:wordDocument}): the body and styles are carried over with the
 *       renamed 2003 vocabulary ({@code b-cs}, {@code vmerge}...) mapped to ECMA-376 names.
 *       Lists, headers/footers, notes, comments, pictures and hyperlink targets are not
 *       converted; each one dropped is reported as a warning and its text is kept where it is
 *       body text.
 * </ul>
 *
 * Import only. Bounded: the XML parser caps depth and size, parts are capped in number and size.
 */
final class WordXml {
  static final int MAX_PARTS = 10000;
  static final long MAX_TOTAL = 512L * 1024 * 1024;
  private static final String W =
      "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  private WordXml() {}

  /** Whether the bytes start like an XML document (after an optional byte order mark). */
  static boolean looksLikeXml(byte[] b) {
    int i = 0;
    if (b.length >= 3 && (b[0] & 255) == 0xEF && (b[1] & 255) == 0xBB && (b[2] & 255) == 0xBF) i = 3;
    else if (b.length >= 2 && ((b[0] & 255) == 0xFF || (b[0] & 255) == 0xFE)) return true;
    while (i < b.length && (b[i] == ' ' || b[i] == '\n' || b[i] == '\r' || b[i] == '\t')) i++;
    return i < b.length && b[i] == '<';
  }

  /** Package parts for a Flat OPC or Word 2003 XML document. */
  static Map<String, byte[]> parts(byte[] bytes, Set<String> warnings) throws IOException {
    Element root = Xml.parse(bytes);
    String kind = Xml.local(root);
    if ("package".equals(kind)) return flat(root);
    if ("wordDocument".equals(kind)) return word2003(root, warnings);
    throw new IOException("Not a Word XML document (root " + kind + ")");
  }

  private static Map<String, byte[]> flat(Element root) throws IOException {
    var parts = new LinkedHashMap<String, byte[]>();
    long total = 0;
    for (Element part : Xml.children(root, "part")) {
      if (parts.size() >= MAX_PARTS) throw new IOException("Too many parts in Flat OPC document");
      String name = Xml.attr(part, "pkg:name", Xml.attr(part, "name", ""));
      while (name.startsWith("/")) name = name.substring(1);
      if (name.isEmpty() || name.indexOf("..") >= 0 || name.indexOf('\\') >= 0)
        throw new IOException("Invalid part name in Flat OPC document");
      if (parts.containsKey(name)) throw new IOException("Duplicate part: " + name);
      byte[] data;
      Element xml = Xml.child(part, "xmlData");
      Element binary = Xml.child(part, "binaryData");
      if (xml != null) {
        Element content = Xml.children(xml, null).isEmpty() ? null : Xml.children(xml, null).get(0);
        if (content == null) continue;
        data = Xml.write(content).getBytes("UTF-8");
      } else if (binary != null) {
        var text = new StringBuilder();
        for (int i = 0; i < binary.getNumChildren(); i++)
          if (binary.getChildAt(i).isTextElement()) text.append(binary.getChildAt(i).getText());
        var clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
          char c = text.charAt(i);
          if (c > ' ') clean.append(c);
        }
        try {
          data = com.codename1.util.Base64.decode(clean.toString().getBytes("UTF-8"));
        } catch (RuntimeException e) {
          throw new IOException("Invalid binary part: " + name);
        }
        if (data == null) throw new IOException("Invalid binary part: " + name);
      } else continue;
      total += data.length;
      if (total > MAX_TOTAL) throw new IOException("Flat OPC document is too large");
      parts.put(name, data);
    }
    if (!parts.containsKey("word/document.xml"))
      throw new IOException("Missing word/document.xml");
    return parts;
  }

  private static Map<String, byte[]> word2003(Element root, Set<String> warnings)
      throws IOException {
    Element body = Xml.child(root, "body");
    if (body == null) throw new IOException("Missing document body");
    var parts = new LinkedHashMap<String, byte[]>();
    var doc = new StringBuilder("<w:document xmlns:w=\"").append(W).append("\">");
    convert(body, doc, warnings);
    doc.append("</w:document>");
    parts.put("word/document.xml", doc.toString().getBytes("UTF-8"));
    Element styles = Xml.child(root, "styles");
    if (styles != null) {
      var s = new StringBuilder("<w:styles xmlns:w=\"").append(W).append("\">");
      for (Element c : Xml.children(styles, null)) convert(c, s, warnings);
      s.append("</w:styles>");
      parts.put("word/styles.xml", s.toString().getBytes("UTF-8"));
    }
    if (Xml.child(root, "lists") != null)
      warnings.add("Word 2003 XML lists are imported as plain paragraphs");
    return parts;
  }

  private static String prefix(Element e) {
    String tag = e.getTagName();
    int colon = tag.indexOf(':');
    return colon < 0 ? "" : tag.substring(0, colon);
  }

  /** ECMA-376 name of a 2003 element: hyphenated names become camel case (b-cs to bCs). */
  static String rename(String local) {
    if ("vmerge".equals(local)) return "vMerge";
    if ("hmerge".equals(local)) return "hMerge";
    if (local.indexOf('-') < 0) return local;
    var b = new StringBuilder(local.length());
    boolean upper = false;
    for (int i = 0; i < local.length(); i++) {
      char c = local.charAt(i);
      if (c == '-') upper = true;
      else {
        b.append(upper && c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        upper = false;
      }
    }
    return b.toString();
  }

  private static void children(Element e, StringBuilder out, Set<String> warnings) {
    for (int i = 0; i < e.getNumChildren(); i++) {
      Element c = e.getChildAt(i);
      if (c.isTextElement()) out.append(Xml.escape(c.getText()));
      else convert(c, out, warnings);
    }
  }

  private static void convert(Element e, StringBuilder out, Set<String> warnings) {
    String local = Xml.local(e);
    String prefix = prefix(e);
    if ("aml".equals(prefix) && "annotation".equals(local)) {
      annotation(e, out, warnings);
      return;
    }
    if (!"w".equals(prefix)) {
      // wx: section wrappers and hints, smart tags and other markup: keep what they contain.
      children(e, out, warnings);
      return;
    }
    switch (local) {
      case "pict":
      case "binData":
        warnings.add("Pictures and text boxes in Word 2003 XML are not imported");
        return;
      case "footnote":
      case "endnote":
        warnings.add("Footnotes and endnotes in Word 2003 XML are not imported");
        return;
      case "hdr":
      case "ftr":
        warnings.add("Headers and footers in Word 2003 XML are not imported");
        return;
      case "listPr":
        return; // reported once from w:lists
      case "hlink":
        String anchor = Xml.attr(e, "w:bookmark", "");
        if (anchor.isEmpty()) {
          warnings.add("Word 2003 XML hyperlink targets are dropped; their text is kept");
          children(e, out, warnings);
        } else {
          out.append("<w:hyperlink w:anchor=\"").append(Xml.attribute(anchor)).append("\">");
          children(e, out, warnings);
          out.append("</w:hyperlink>");
        }
        return;
      default:
        break;
    }
    String name = "w:" + rename(local);
    out.append('<').append(name);
    var attributes = attributes(e);
    for (int i = 0; i < attributes.length; i += 2)
      if (!attributes[i].startsWith("xmlns"))
        out.append(' ').append(attributes[i]).append("=\"").append(Xml.attribute(attributes[i + 1])).append('"');
    out.append('>');
    children(e, out, warnings);
    out.append("</").append(name).append('>');
  }

  private static String[] attributes(Element e) {
    if (e instanceof Xml.Node n)
      return n.attrs == null ? new String[0] : java.util.Arrays.copyOf(n.attrs, n.attrCount);
    var map = e.getAttributes();
    if (map == null) return new String[0];
    var a = new String[map.size() * 2];
    int i = 0;
    for (Object k : map.keySet()) {
      a[i++] = (String) k;
      a[i++] = (String) map.get(k);
    }
    return a;
  }

  private static void annotation(Element e, StringBuilder out, Set<String> warnings) {
    String type = Xml.attr(e, "w:type", "");
    String id = Xml.attr(e, "aml:id", "0");
    if ("Word.Bookmark.Start".equals(type)) {
      out.append("<w:bookmarkStart w:id=\"").append(Xml.attribute(id)).append("\" w:name=\"")
          .append(Xml.attribute(Xml.attr(e, "w:name", ""))).append("\"/>");
    } else if ("Word.Bookmark.End".equals(type)) {
      out.append("<w:bookmarkEnd w:id=\"").append(Xml.attribute(id)).append("\"/>");
    } else if ("Word.Insertion".equals(type)) {
      warnings.add("Word 2003 XML tracked changes are accepted");
      Element content = Xml.child(e, "content");
      if (content != null) children(content, out, warnings);
    } else if ("Word.Deletion".equals(type)) {
      warnings.add("Word 2003 XML tracked changes are accepted");
    } else if (type.startsWith("Word.Comment")) {
      warnings.add("Word 2003 XML comments are not imported");
    } else {
      Element content = Xml.child(e, "content");
      if (content != null) children(content, out, warnings);
    }
  }
}
