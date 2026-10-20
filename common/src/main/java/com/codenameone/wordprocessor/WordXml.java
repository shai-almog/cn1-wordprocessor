package com.codenameone.wordprocessor;

import com.codename1.xml.Element;
import java.io.IOException;
import java.util.HashMap;
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
 *       Lists (w:listDef/w:list) become a numbering part, w:hlink targets relationships,
 *       w:binData + v:imagedata pictures media parts, v:textbox text boxes DrawingML text boxes,
 *       w:hdr/w:ftr header and footer parts, inline w:footnote/w:endnote notes parts and
 *       aml:annotation comments a comments part. Other VML shapes are reported and dropped.
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
    var c = new Converter(warnings);
    Element lists = Xml.child(root, "lists");
    if (lists != null) c.numbering(lists);
    var doc = new StringBuilder("<w:document").append(NS).append('>');
    c.convert(body, doc);
    doc.append("</w:document>");
    c.parts.put("word/document.xml", utf8(doc));
    c.rels("word/_rels/document.xml.rels", c.story);
    Element styles = Xml.child(root, "styles");
    if (styles != null) {
      var s = new StringBuilder("<w:styles").append(NS).append('>');
      for (Element e : Xml.children(styles, null)) c.convert(e, s);
      s.append("</w:styles>");
      c.parts.put("word/styles.xml", utf8(s));
    }
    c.notes("footnote", c.footnotes);
    c.notes("endnote", c.endnotes);
    if (c.comments.length() > 0)
      c.parts.put(
          "word/comments.xml",
          utf8(new StringBuilder("<w:comments").append(NS).append('>').append(c.comments).append("</w:comments>")));
    return c.parts;
  }

  private static byte[] utf8(CharSequence s) throws IOException {
    return s.toString().getBytes("UTF-8");
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

  private static final String R =
      "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String NS =
      " xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\""
          + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
          + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
          + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
          + " xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"";

  /** Relationships of one converted part (document, a header or footer, the notes). */
  private static final class Story {
    final StringBuilder rels = new StringBuilder();
    int next = 1;
  }

  /** The 2003 vocabulary rewritten as WordprocessingML, collecting the side parts on the way. */
  private static final class Converter {
    final Map<String, byte[]> parts = new LinkedHashMap<>();
    final Set<String> warnings;
    final Map<String, String> media = new HashMap<>();
    final StringBuilder footnotes = new StringBuilder(), endnotes = new StringBuilder(),
        comments = new StringBuilder();
    final Story footnoteRels = new Story(), endnoteRels = new Story();
    Story story = new Story();
    int notes = 1, headers, footers, images, shapes;
    long mediaBytes;
    boolean inRun;

    Converter(Set<String> warnings) {
      this.warnings = warnings;
    }

    String rel(String type, String target, boolean external) {
      String id = "rId" + story.next++;
      story.rels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(R).append('/')
          .append(type).append("\" Target=\"").append(Xml.attribute(target)).append('"');
      if (external) story.rels.append(" TargetMode=\"External\"");
      story.rels.append("/>");
      return id;
    }

    void rels(String path, Story s) throws IOException {
      if (s.rels.length() == 0) return;
      parts.put(path, utf8(new StringBuilder(
          "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
          .append(s.rels).append("</Relationships>")));
    }

    void notes(String kind, StringBuilder content) throws IOException {
      if (content.length() == 0) return;
      parts.put("word/" + kind + "s.xml", utf8(new StringBuilder("<w:").append(kind).append('s')
          .append(NS).append('>').append(content).append("</w:").append(kind).append("s>")));
      rels("word/_rels/" + kind + "s.xml.rels", "footnote".equals(kind) ? footnoteRels : endnoteRels);
    }

    /** w:listDef becomes w:abstractNum and w:list (an instance) becomes w:num. */
    void numbering(Element lists) throws IOException {
      var b = new StringBuilder("<w:numbering").append(NS).append('>');
      for (Element d : Xml.children(lists, "listDef")) {
        b.append("<w:abstractNum w:abstractNumId=\"")
            .append(Xml.attribute(Xml.attr(d, "w:listDefId", "0"))).append("\">");
        children(d, b);
        b.append("</w:abstractNum>");
      }
      for (Element l : Xml.children(lists, "list")) {
        Element ilst = Xml.child(l, "ilst");
        b.append("<w:num w:numId=\"").append(Xml.attribute(Xml.attr(l, "w:ilfo", "0")))
            .append("\"><w:abstractNumId w:val=\"")
            .append(Xml.attribute(ilst == null ? "0" : Xml.attr(ilst, "w:val", "0")))
            .append("\"/>");
        for (Element o : Xml.children(l, "lvlOverride")) convert(o, b);
        b.append("</w:num>");
      }
      parts.put("word/numbering.xml", utf8(b.append("</w:numbering>")));
      rel("numbering", "numbering.xml", false);
    }

    void children(Element e, StringBuilder out) {
      for (int i = 0; i < e.getNumChildren(); i++) {
        Element c = e.getChildAt(i);
        if (c.isTextElement()) out.append(Xml.escape(c.getText()));
        else convert(c, out);
      }
    }

    void convert(Element e, StringBuilder out) {
      String local = Xml.local(e);
      String prefix = prefix(e);
      if ("aml".equals(prefix) && "annotation".equals(local)) {
        annotation(e, out);
        return;
      }
      if (!"w".equals(prefix)) {
        // wx: section wrappers and hints, smart tags and other markup: keep what they contain.
        children(e, out);
        return;
      }
      switch (local) {
        case "pict":
          pict(e, out);
          return;
        case "binData":
          binData(e);
          return;
        case "footnote":
        case "endnote":
          note(local, e, out);
          return;
        case "hdr":
        case "ftr":
          furniture(local, e, out);
          return;
        case "listPr":
          out.append("<w:numPr>");
          children(e, out);
          out.append("</w:numPr>");
          return;
        case "ilfo":
          out.append("<w:numId w:val=\"").append(Xml.attribute(Xml.attr(e, "w:val", "0"))).append("\"/>");
          return;
        case "nfc":
          out.append("<w:numFmt w:val=\"").append(numberFormat(Xml.attr(e, "w:val", "0"))).append("\"/>");
          return;
        case "hlink":
          hyperlink(e, out);
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
      boolean run = inRun;
      if ("r".equals(local)) inRun = true;
      else if ("p".equals(local)) inRun = false;
      children(e, out);
      inRun = run;
      out.append("</").append(name).append('>');
    }

    void hyperlink(Element e, StringBuilder out) {
      String dest = Xml.attr(e, "w:dest", ""), anchor = Xml.attr(e, "w:bookmark", "");
      out.append("<w:hyperlink");
      if (!dest.isEmpty())
        out.append(" r:id=\"").append(rel("hyperlink", anchor.isEmpty() ? dest : dest + "#" + anchor, true)).append('"');
      else if (!anchor.isEmpty()) out.append(" w:anchor=\"").append(Xml.attribute(anchor)).append('"');
      out.append('>');
      children(e, out);
      out.append("</w:hyperlink>");
    }

    /** An inline w:footnote/w:endnote becomes a reference here and an entry in the notes part. */
    void note(String kind, Element e, StringBuilder out) {
      int id = notes++;
      String ref = "<w:" + kind + "Reference w:id=\"" + id + "\"/>";
      out.append(inRun ? ref : "<w:r>" + ref + "</w:r>");
      StringBuilder target = "footnote".equals(kind) ? footnotes : endnotes;
      Story saved = story;
      boolean run = inRun;
      story = "footnote".equals(kind) ? footnoteRels : endnoteRels;
      inRun = false;
      var entry = new StringBuilder();
      entry.append("<w:").append(kind).append(" w:id=\"").append(id).append("\">");
      children(e, entry);
      entry.append("</w:").append(kind).append('>');
      target.append(entry);
      story = saved;
      inRun = run;
    }

    /** A w:hdr/w:ftr inside w:sectPr becomes its own part and a header/footer reference. */
    void furniture(String kind, Element e, StringBuilder out) {
      boolean header = "hdr".equals(kind);
      String name = (header ? "header" : "footer") + (header ? ++headers : ++footers);
      String type = Xml.attr(e, "w:type", "odd");
      type = "odd".equals(type) ? "default" : type;
      Story saved = story;
      story = new Story();
      var b = new StringBuilder("<w:").append(kind).append(NS).append('>');
      boolean run = inRun;
      inRun = false;
      children(e, b);
      inRun = run;
      b.append("</w:").append(kind).append('>');
      try {
        parts.put("word/" + name + ".xml", utf8(b));
        rels("word/_rels/" + name + ".xml.rels", story);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
      story = saved;
      out.append("<w:").append(header ? "headerReference" : "footerReference").append(" w:type=\"")
          .append(Xml.attribute(type)).append("\" r:id=\"")
          .append(rel(header ? "header" : "footer", name + ".xml", false)).append("\"/>");
    }

    /** Base64 picture data named wordml://..., referenced later by v:imagedata. */
    void binData(Element e) {
      String name = Xml.attr(e, "w:name", "");
      if (name.isEmpty() || media.containsKey(name)) return;
      var clean = new StringBuilder();
      for (int i = 0; i < e.getNumChildren(); i++) {
        Element c = e.getChildAt(i);
        if (!c.isTextElement()) continue;
        String text = c.getText();
        for (int j = 0; j < text.length(); j++) if (text.charAt(j) > ' ') clean.append(text.charAt(j));
      }
      byte[] data;
      try {
        data = com.codename1.util.Base64.decode(clean.toString().getBytes("UTF-8"));
      } catch (IOException | RuntimeException ex) {
        data = null;
      }
      if (data == null || data.length == 0 || (mediaBytes += data.length) > MAX_TOTAL) {
        warnings.add("Invalid Word 2003 XML picture data is skipped");
        return;
      }
      int dot = name.lastIndexOf('.');
      String ext = dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase();
      var safe = new StringBuilder();
      for (int i = 0; i < ext.length() && i < 8; i++) {
        char c = ext.charAt(i);
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) safe.append(c);
      }
      String path = "media/image" + ++images + "." + (safe.length() == 0 ? "bin" : safe.toString());
      parts.put("word/" + path, data);
      media.put(name, path);
    }

    /** VML pictures and text boxes become DrawingML inline drawings. */
    void pict(Element e, StringBuilder out) {
      collectBinData(e);
      shapes(e, out);
    }

    void collectBinData(Element e) {
      for (Element c : Xml.children(e, null)) {
        if ("binData".equals(Xml.local(c))) binData(c);
        else collectBinData(c);
      }
    }

    void shapes(Element e, StringBuilder out) {
      for (Element c : Xml.children(e, null)) {
        String local = Xml.local(c);
        if ("binData".equals(local) || "shapetype".equals(local)) continue;
        if (!"v".equals(prefix(c))) {
          shapes(c, out);
          continue;
        }
        Element image = Xml.child(c, "imagedata"), box = Xml.child(c, "textbox");
        if (image != null) picture(c, image, out);
        else if (box != null) textBox(c, box, out);
        else if ("group".equals(local)) shapes(c, out);
        else warnings.add("Word 2003 XML drawing shapes other than pictures and text boxes are not imported");
      }
    }

    void open(Element shape, StringBuilder out, String what, String uri) {
      if (!inRun) out.append("<w:r>");
      long[] size = size(Xml.attr(shape, "style", ""));
      out.append("<w:drawing><wp:inline><wp:extent cx=\"").append(size[0]).append("\" cy=\"")
          .append(size[1]).append("\"/><wp:docPr id=\"").append(++shapes).append("\" name=\"")
          .append(what).append(' ').append(shapes).append("\" descr=\"")
          .append(Xml.attribute(Xml.attr(shape, "alt", ""))).append("\"/><a:graphic><a:graphicData uri=\"")
          .append(uri).append("\">");
    }

    void close(StringBuilder out) {
      out.append("</a:graphicData></a:graphic></wp:inline></w:drawing>");
      if (!inRun) out.append("</w:r>");
    }

    void picture(Element shape, Element image, StringBuilder out) {
      String path = media.get(Xml.attr(image, "src", ""));
      if (path == null) {
        warnings.add("Linked or missing Word 2003 XML pictures are not imported");
        return;
      }
      open(shape, out, "Picture", "http://schemas.openxmlformats.org/drawingml/2006/picture");
      out.append("<pic:pic><pic:blipFill><a:blip r:embed=\"").append(rel("image", path, false))
          .append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill></pic:pic>");
      close(out);
    }

    void textBox(Element shape, Element box, StringBuilder out) {
      Element content = Xml.child(box, "txbxContent");
      open(shape, out, "Text Box", "http://schemas.microsoft.com/office/word/2010/wordprocessingShape");
      out.append("<wps:wsp><wps:cNvSpPr txBox=\"1\"/><wps:spPr><a:prstGeom prst=\"rect\"/></wps:spPr><wps:txbx><w:txbxContent>");
      boolean run = inRun;
      inRun = false;
      if (content != null) children(content, out);
      inRun = run;
      out.append("</w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp>");
      close(out);
    }

    void annotation(Element e, StringBuilder out) {
      String type = Xml.attr(e, "w:type", "");
      String id = Xml.attribute(Xml.attr(e, "aml:id", "0"));
      if ("Word.Bookmark.Start".equals(type)) {
        out.append("<w:bookmarkStart w:id=\"").append(id).append("\" w:name=\"")
            .append(Xml.attribute(Xml.attr(e, "w:name", ""))).append("\"/>");
      } else if ("Word.Bookmark.End".equals(type)) {
        out.append("<w:bookmarkEnd w:id=\"").append(id).append("\"/>");
      } else if ("Word.Insertion".equals(type)) {
        warnings.add("Word 2003 XML tracked changes are accepted");
        Element content = Xml.child(e, "content");
        if (content != null) children(content, out);
      } else if ("Word.Deletion".equals(type)) {
        warnings.add("Word 2003 XML tracked changes are accepted");
      } else if ("Word.Comment.Start".equals(type)) {
        out.append("<w:commentRangeStart w:id=\"").append(id).append("\"/>");
      } else if ("Word.Comment.End".equals(type)) {
        out.append("<w:commentRangeEnd w:id=\"").append(id).append("\"/>");
      } else if ("Word.Comment".equals(type)) {
        String ref = "<w:commentReference w:id=\"" + id + "\"/>";
        out.append(inRun ? ref : "<w:r>" + ref + "</w:r>");
        var entry = new StringBuilder();
        entry.append("<w:comment w:id=\"").append(id).append("\" w:author=\"")
            .append(Xml.attribute(Xml.attr(e, "aml:author", "Author"))).append("\" w:initials=\"")
            .append(Xml.attribute(Xml.attr(e, "w:initials", ""))).append("\">");
        Element content = Xml.child(e, "content");
        boolean run = inRun;
        inRun = false;
        if (content != null) children(content, entry);
        inRun = run;
        comments.append(entry).append("</w:comment>");
      } else {
        Element content = Xml.child(e, "content");
        if (content != null) children(content, out);
      }
    }
  }

  /** 2003 w:nfc numbers to ECMA-376 w:numFmt names. */
  static String numberFormat(String nfc) {
    switch (nfc) {
      case "1": return "upperRoman";
      case "2": return "lowerRoman";
      case "3": return "upperLetter";
      case "4": return "lowerLetter";
      case "5": return "ordinal";
      case "6": return "cardinalText";
      case "7": return "ordinalText";
      case "22": return "decimalZero";
      case "23": return "bullet";
      case "255": return "none";
      default: return "decimal";
    }
  }

  /** VML width/height from a CSS-like style, in EMU; 100 by 50 points when absent. */
  static long[] size(String style) {
    float w = 100, h = 50;
    int i = 0;
    while (i < style.length()) {
      int semi = style.indexOf(';', i);
      if (semi < 0) semi = style.length();
      String item = style.substring(i, semi).trim();
      i = semi + 1;
      int colon = item.indexOf(':');
      if (colon < 0) continue;
      String key = item.substring(0, colon).trim();
      float v = length(item.substring(colon + 1).trim());
      if (!(v > 0)) continue;
      if ("width".equals(key)) w = v;
      else if ("height".equals(key)) h = v;
    }
    return new long[] {(long) (Math.min(w, 20000) * 12700), (long) (Math.min(h, 20000) * 12700)};
  }

  private static float length(String v) {
    float scale = 0.75f; // VML lengths without units are pixels
    String n = v;
    if (v.endsWith("pt")) { scale = 1; n = v.substring(0, v.length() - 2); }
    else if (v.endsWith("in")) { scale = 72; n = v.substring(0, v.length() - 2); }
    else if (v.endsWith("cm")) { scale = 72 / 2.54f; n = v.substring(0, v.length() - 2); }
    else if (v.endsWith("mm")) { scale = 72 / 25.4f; n = v.substring(0, v.length() - 2); }
    else if (v.endsWith("px")) n = v.substring(0, v.length() - 2);
    float f = Xml.number(n.trim());
    return Float.isNaN(f) || Float.isInfinite(f) ? 0 : f * scale;
  }
}
