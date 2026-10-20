package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class OdtTest {
  private static final String NS =
      "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
          + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
          + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
          + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
          + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
          + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
          + " xmlns:xlink=\"http://www.w3.org/1999/xlink\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
          + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
          + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\"";

  // A 1x1 PNG.
  static final byte[] PNG = {
    (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6,
    0, 0, 0, 0x1f, 0x15, (byte) 0xc4, (byte) 0x89, 0, 0, 0, 13, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9c, 99, (byte) 0xf8,
    (byte) 0xcf, (byte) 0xc0, (byte) 0xf0, 31, 0, 5, 0, 1, (byte) 0xff, (byte) 0x89, (byte) 0x99, 0x3d, 0x1d, 0, 0, 0,
    0, 'I', 'E', 'N', 'D', (byte) 0xae, 0x42, 0x60, (byte) 0x82
  };

  static final String STYLES =
      "<office:document-styles " + NS + "><office:styles>"
          + "<style:default-style style:family=\"paragraph\"><style:text-properties style:font-name=\"Liberation Serif\" fo:font-size=\"12pt\"/></style:default-style>"
          + "<style:style style:name=\"Standard\" style:family=\"paragraph\"/>"
          + "<style:style style:name=\"Heading_20_1\" style:display-name=\"Heading 1\" style:family=\"paragraph\" style:parent-style-name=\"Standard\" style:default-outline-level=\"1\"><style:text-properties fo:font-size=\"18pt\" fo:font-weight=\"bold\"/></style:style>"
          + "<style:style style:name=\"Quote\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:margin-left=\"36pt\"/><style:text-properties fo:font-style=\"italic\"/></style:style>"
          + "<style:style style:name=\"Strong\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\"/></style:style>"
          + "</office:styles><office:automatic-styles>"
          + "<style:page-layout style:name=\"pm1\"><style:page-layout-properties fo:page-width=\"27.94cm\" fo:page-height=\"21.59cm\" style:print-orientation=\"landscape\" fo:margin-top=\"0.5in\" fo:margin-bottom=\"1in\" fo:margin-left=\"1.5in\" fo:margin-right=\"72pt\"/>"
          + "<style:header-style><style:header-footer-properties fo:min-height=\"0.5in\" fo:margin-bottom=\"0in\"/></style:header-style></style:page-layout>"
          + "<style:style style:name=\"MP1\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:text-align=\"center\"/></style:style>"
          + "</office:automatic-styles><office:master-styles><style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\">"
          + "<style:header><text:p text:style-name=\"MP1\">Running head</text:p></style:header>"
          + "<style:footer><text:p>Page foot</text:p></style:footer></style:master-page></office:master-styles></office:document-styles>";

  static final String CONTENT =
      "<office:document-content " + NS + "><office:automatic-styles>"
          + "<style:style style:name=\"P1\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:text-align=\"justify\" fo:margin-left=\"0.5in\" fo:margin-right=\"18pt\" fo:text-indent=\"-12pt\" fo:margin-top=\"6pt\" fo:margin-bottom=\"12pt\" fo:line-height=\"150%\"/></style:style>"
          + "<style:style style:name=\"P2\" style:family=\"paragraph\" style:parent-style-name=\"Standard\"><style:paragraph-properties fo:break-before=\"page\" fo:text-align=\"end\"/></style:style>"
          + "<style:style style:name=\"T1\" style:family=\"text\"><style:text-properties fo:font-weight=\"bold\"/></style:style>"
          + "<style:style style:name=\"T2\" style:family=\"text\"><style:text-properties fo:font-style=\"italic\" style:text-underline-style=\"solid\" style:text-underline-width=\"auto\" style:text-underline-color=\"font-color\"/></style:style>"
          + "<style:style style:name=\"T3\" style:family=\"text\"><style:text-properties style:text-line-through-style=\"solid\" fo:font-size=\"20pt\" style:font-name=\"Courier New\" fo:color=\"#ff0000\" fo:background-color=\"#ffff00\"/></style:style>"
          + "<style:style style:name=\"T4\" style:family=\"text\"><style:text-properties style:text-position=\"super 58%\"/></style:style>"
          + "<style:style style:name=\"T5\" style:family=\"text\"><style:text-properties style:text-position=\"-33% 58%\"/></style:style>"
          + "<style:style style:name=\"Tc1\" style:family=\"table-column\"><style:table-column-properties style:column-width=\"2in\"/></style:style>"
          + "<style:style style:name=\"Tc2\" style:family=\"table-column\"><style:table-column-properties style:column-width=\"1in\"/></style:style>"
          + "<style:style style:name=\"Cell1\" style:family=\"table-cell\"><style:table-cell-properties fo:background-color=\"#00ff00\"/></style:style>"
          + "<text:list-style style:name=\"L1\"><text:list-level-style-bullet text:level=\"1\" text:bullet-char=\"\u2022\"/>"
          + "<text:list-level-style-number text:level=\"2\" style:num-format=\"a\" style:num-suffix=\")\"/></text:list-style>"
          + "<text:list-style style:name=\"L2\"><text:list-level-style-number text:level=\"1\" style:num-format=\"1\" style:num-suffix=\".\"/></text:list-style>"
          + "</office:automatic-styles><office:body><office:text>"
          + "<text:sequence-decls><text:sequence-decl text:display-outline-level=\"0\" text:name=\"Table\"/></text:sequence-decls>"
          + "<text:h text:style-name=\"Heading_20_1\" text:outline-level=\"1\">Title <text:bookmark text:name=\"top\"/>here</text:h>\n"
          + "<text:p text:style-name=\"P1\">Plain <text:span text:style-name=\"T1\">bold</text:span> <text:span text:style-name=\"T2\">ital</text:span>"
          + "<text:s text:c=\"2\"/><text:span text:style-name=\"T3\">big</text:span><text:tab/>x<text:span text:style-name=\"T4\">2</text:span>"
          + "H<text:span text:style-name=\"T5\">2</text:span>O<text:line-break/>next <text:span text:style-name=\"Strong\">strong</text:span></text:p>"
          + "<text:p>See <text:a xlink:type=\"simple\" xlink:href=\"https://example.com/?a=1&amp;b=2\">site</text:a> and <text:a xlink:href=\"#top\">top</text:a>"
          + "<text:note text:id=\"ftn1\" text:note-class=\"footnote\"><text:note-citation>1</text:note-citation><text:note-body><text:p>Foot text</text:p></text:note-body></text:note>"
          + " end<text:note text:id=\"edn1\" text:note-class=\"endnote\"><text:note-citation>i</text:note-citation><text:note-body><text:p>End text</text:p></text:note-body></text:note>"
          + "<office:annotation><dc:creator>Ada</dc:creator><text:p>ignored</text:p></office:annotation><text:soft-page-break/></text:p>"
          + "<text:p text:style-name=\"Quote\">Quoted</text:p>"
          + "<text:list text:style-name=\"L1\"><text:list-item><text:p>Bullet one</text:p><text:list><text:list-item><text:p>Sub a</text:p></text:list-item>"
          + "<text:list-item><text:p>Sub b</text:p></text:list-item></text:list></text:list-item><text:list-item><text:p>Bullet two</text:p></text:list-item></text:list>"
          + "<text:list text:style-name=\"L2\"><text:list-item><text:p>First</text:p></text:list-item><text:list-item><text:p>Second</text:p></text:list-item></text:list>"
          + "<table:table table:name=\"Table1\"><table:table-column table:style-name=\"Tc1\"/><table:table-column table:style-name=\"Tc2\" table:number-columns-repeated=\"2\"/>"
          + "<table:table-row><table:table-cell table:number-columns-spanned=\"2\" office:value-type=\"string\"><text:p>wide</text:p></table:table-cell><table:covered-table-cell/>"
          + "<table:table-cell table:number-rows-spanned=\"2\" table:style-name=\"Cell1\"><text:p>tall</text:p></table:table-cell></table:table-row>"
          + "<table:table-row><table:table-cell><text:p>a</text:p></table:table-cell><table:table-cell><text:p>b</text:p></table:table-cell><table:covered-table-cell/></table:table-row>"
          + "</table:table>"
          + "<text:p>Pic <draw:frame draw:name=\"Image1\" text:anchor-type=\"as-char\" svg:width=\"1in\" svg:height=\"0.5in\"><draw:image xlink:href=\"Pictures/dot.png\"/><svg:title>A dot</svg:title></draw:frame></text:p>"
          + "<text:p text:style-name=\"P2\">After break</text:p>"
          + "<text:tracked-changes><text:changed-region text:id=\"c1\"><text:deletion><text:p>gone</text:p></text:deletion></text:changed-region></text:tracked-changes>"
          + "</office:text></office:body></office:document-content>";

  static final String META =
      "<office:document-meta " + NS + "><office:meta><dc:title>My title</dc:title><dc:subject>Subj</dc:subject>"
          + "<meta:initial-creator>Ada</meta:initial-creator><meta:keyword>one</meta:keyword><meta:keyword>two</meta:keyword></office:meta></office:document-meta>";

  static byte[] odt(String content, String styles, String meta) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var zip = new ZipOutputStream(bytes);
    var mime = Odt.MIME.getBytes("UTF-8");
    var entry = new ZipEntry("mimetype");
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(mime.length);
    var crc = new CRC32();
    crc.update(mime);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(mime);
    zip.closeEntry();
    String[][] parts = {{"content.xml", content}, {"styles.xml", styles}, {"meta.xml", meta}};
    for (String[] p : parts) {
      if (p[1] == null) continue;
      zip.putNextEntry(new ZipEntry(p[0]));
      zip.write(p[1].getBytes("UTF-8"));
      zip.closeEntry();
    }
    zip.putNextEntry(new ZipEntry("Pictures/dot.png"));
    zip.write(PNG);
    zip.closeEntry();
    zip.finish();
    return bytes.toByteArray();
  }

  static Document read(byte[] b) throws IOException {
    return Odt.read(new ByteArrayInputStream(b)).document;
  }

  static byte[] write(Document d) throws IOException {
    var out = new ByteArrayOutputStream();
    Odt.write(d, out);
    return out.toByteArray();
  }

  private static Paragraph find(Document d, String text) {
    for (Block b : d.blocks)
      if (b instanceof Paragraph p && p.text.toString().contains(text)) return p;
    fail("No paragraph " + text + " in " + d.plainText());
    return null;
  }

  private static Table table(Document d) {
    for (Block b : d.blocks) if (b instanceof Table t) return t;
    fail("No table");
    return null;
  }

  private static void assertFeatures(Document d) {
    var h = find(d, "Title here");
    assertTrue(h.style, h.style.startsWith("Heading1"));
    var p = find(d, "Plain bold ital");
    assertEquals("both", p.align);
    assertEquals(36, p.indent, .1);
    assertEquals(18, p.format.rightIndent, .1);
    assertEquals(-12, p.format.firstIndent, .1);
    String s = p.text.toString();
    assertEquals("Plain bold ital  big\tx2H2O\nnext strong", s);
    assertTrue(p.text.styleAt(s.indexOf("bold")).bold);
    assertFalse(p.text.styleAt(0).bold);
    assertTrue(p.text.styleAt(s.indexOf("ital")).italic);
    var big = p.text.styleAt(s.indexOf("big"));
    assertTrue(big.strike);
    assertEquals(20, big.size, .01);
    assertEquals("Courier New", big.font);
    assertEquals(0xff0000, big.color);
    assertEquals("superscript", p.text.styleAt(s.indexOf("x2") + 1).script);
    assertEquals("subscript", p.text.styleAt(s.indexOf("H2O") + 1).script);
    assertTrue(p.text.styleAt(s.indexOf("strong")).bold);
    var link = find(d, "See site");
    assertEquals("https://example.com/?a=1&b=2", link.text.styleAt(5).link);
    assertEquals("#top", link.text.styleAt(link.text.toString().indexOf("top")).link);
    assertFalse(link.text.toString().contains("ignored"));
    assertEquals(2, Notes.all(d).size());
    assertEquals(36, find(d, "Quoted").indent, .1);
    assertTrue(find(d, "Quoted").text.styleAt(0).italic);
    assertEquals("bullet", find(d, "Bullet one").list);
    assertEquals(1, find(d, "Sub a").listLevel);
    assertEquals("number", find(d, "First").list);
    var t = table(d);
    assertEquals(2, t.rows());
    assertEquals(3, t.columns());
    assertEquals(2, t.span(0, 0).columns);
    assertEquals(2, t.span(0, 2).rows);
    assertEquals("tall", Table.cellText(t.cell(0, 2)));
    assertEquals("b", Table.cellText(t.cell(1, 1)));
    assertTrue(find(d, "After break").pageBreak);
    assertEquals("right", find(d, "After break").align);
    assertFalse(d.plainText().contains("gone"));
    Picture pic = null;
    for (Block b : d.blocks) if (b instanceof Picture x) pic = x;
    assertNotNull(pic);
    assertEquals(72, pic.width, .5);
    assertEquals(36, pic.height, .5);
    assertEquals("A dot", pic.alt);
    assertEquals("My title", d.properties.get("title"));
    assertEquals("Ada", d.properties.get("author"));
    var section = d.sectionRanges().get(0).resolved;
    assertEquals(792, section.page.width, .5);
    assertEquals(612, section.page.height, .5);
    assertEquals(108, section.page.left, .5);
    assertEquals(72, section.page.top, .5);
    assertEquals("Running head", section.furniture.get("header").get(0).text.plainText());
    assertEquals("center", section.furniture.get("header").get(0).align);
    assertEquals("Page foot", section.furniture.get("footer").get(0).text.plainText());
    var kinds = new ArrayList<String>();
    for (Note n : Notes.all(d)) kinds.add(n.kind + ":" + n.plainText());
    assertTrue(kinds.toString(), kinds.contains("footnote:Foot text") && kinds.contains("endnote:End text"));
  }

  @Test
  public void importsLibreOfficeStyleDocument() throws Exception {
    var o = Odt.read(new ByteArrayInputStream(odt(CONTENT, STYLES, META)));
    assertFeatures(o.document);
  }

  @Test
  public void roundTripsThroughExport() throws Exception {
    var first = read(odt(CONTENT, STYLES, META));
    var bytes = write(first);
    var second = read(bytes);
    assertFeatures(second);
    assertEquals(first.plainText(), second.plainText());
    // Export of the re-import is stable.
    assertEquals(second.plainText(), read(write(second)).plainText());
  }

  @Test
  public void exportIsAValidPackage() throws Exception {
    var bytes = write(read(odt(CONTENT, STYLES, META)));
    // mimetype is the first entry, stored, with no extra field.
    assertEquals(0x04034b50, (bytes[0] & 0xff) | (bytes[1] & 0xff) << 8 | (bytes[2] & 0xff) << 16 | (bytes[3] & 0xff) << 24);
    assertEquals(0, bytes[8] | bytes[9]);
    assertEquals(0, bytes[28] | bytes[29]);
    assertEquals("mimetype", new String(bytes, 30, 8, "UTF-8"));
    assertEquals(Odt.MIME, new String(bytes, 38, Odt.MIME.length(), "UTF-8"));
    var names = new ArrayList<String>();
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
        names.add(e.getName());
        byte[] data = zip.readAllBytes();
        if (e.getName().endsWith(".xml"))
          factory.newDocumentBuilder().parse(new ByteArrayInputStream(data)); // throws if malformed
      }
    }
    assertEquals("mimetype", names.get(0));
    assertTrue(names.toString(), names.containsAll(Arrays.asList(
        "content.xml", "styles.xml", "meta.xml", "META-INF/manifest.xml", "Pictures/image1.png")));
    var dir = System.getProperty("odt.out");
    if (dir != null) try (var f = new FileOutputStream(new File(dir, "export.odt"))) {
      f.write(bytes);
    }
  }

  @Test
  public void sessionDocumentRoundTrips() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Parent\nChild\nTail   spaced  text");
    s.selectRange(0, 0, 1, 5);
    s.paragraph("list", "number");
    s.select(1, 0, 0);
    s.paragraph("listLevel", "1");
    s.select(2, 0, 4);
    s.format("bold", "true");
    var d = read(write(s.document()));
    assertEquals(s.document().plainText(), d.plainText());
    assertEquals("number", d.paragraph(0).list);
    assertEquals(1, d.paragraph(1).listLevel);
    assertTrue(d.paragraph(2).text.styleAt(0).bold);
    assertFalse(d.paragraph(2).text.styleAt(5).bold);
  }

  @Test
  public void garbageIsAnIOException() throws Exception {
    byte[][] inputs = {
      new byte[0], "not a zip".getBytes("UTF-8"), Arrays.copyOf(odt(CONTENT, STYLES, META), 200),
      odt("<office:document-content " + NS + "><office:body><office:text><text:p>x", STYLES, null),
      odt("<x/>", null, null)
    };
    for (byte[] in : inputs) {
      try {
        Odt.read(new ByteArrayInputStream(in));
        fail("accepted garbage");
      } catch (IOException expected) {
      }
    }
    var rnd = new Random(7);
    byte[] good = odt(CONTENT, STYLES, META);
    for (int i = 0; i < 50; i++) {
      byte[] bad = good.clone();
      for (int j = 0; j < 8; j++) bad[rnd.nextInt(bad.length)] = (byte) rnd.nextInt();
      try {
        Odt.read(new ByteArrayInputStream(bad));
      } catch (IOException expected) {
      }
    }
  }
}
