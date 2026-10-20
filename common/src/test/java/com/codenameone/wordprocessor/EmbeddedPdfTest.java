package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import org.junit.Test;

public class EmbeddedPdfTest {
  private static final PdfExporter.FontProvider FONTS =
      new PdfExporter.FontProvider() {
        public String name(TextStyle style) {
          return DocumentFonts.resource(style);
        }

        public byte[] bytes(String name) throws IOException {
          try (var in = EmbeddedPdfTest.class.getResourceAsStream("/" + name + ".ttf")) {
            assertNotNull(name, in);
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
          }
        }
      };

  @Test
  public void allTwelveFacesEmbedWithUnicodeAndCorrectReferences() throws Exception {
    var blocks = new Sequence<Block>();
    int index = 0;
    var faces = new HashSet<String>();
    for (String family : new String[] {"SansSerif", "Serif", "Monospaced"})
      for (int variant = 0; variant < 4; variant++) {
        var style =
            TextStyle.DEFAULT
                .with("font", family)
                .with("size", "18")
                .with("bold", String.valueOf((variant & 1) != 0))
                .with("italic", String.valueOf((variant & 2) != 0));
        var name = FONTS.name(style);
        faces.add(name);
        blocks =
            blocks.append(
                new Paragraph(
                    "p" + ++index, RichText.of(name + " Résumé € Ελληνικά Кириллица", style)));
      }
    assertEquals(12, faces.size());
    var doc = new Document(blocks, PageSpec.A4, "Font acceptance", index + 1);
    final var loaded = new HashMap<String, PdfFonts.TrueType>();
    var layout =
        new LayoutEngine(
                new LayoutEngine.Metrics() {
                  public float width(String text, TextStyle style) {
                    try {
                      var name = FONTS.name(style);
                      var f = loaded.get(name);
                      if (f == null) loaded.put(name, f = new PdfFonts.TrueType(FONTS.bytes(name)));
                      float width = 0;
                      for (int i = 0; i < text.length(); i++)
                        width += f.scaled(f.advance(f.glyph(text.charAt(i)))) * style.size / 1000;
                      return width;
                    } catch (IOException ex) {
                      throw new RuntimeException(ex);
                    }
                  }

                  public float height(TextStyle style) {
                    return style.size * 1.2f;
                  }
                })
            .layout(doc, 0);
    byte[] bytes = PdfExporter.write(doc.page, layout, FONTS);
    var pdf = new String(bytes, "ISO-8859-1");
    for (String face : faces) assertTrue(face, pdf.contains("/BaseFont /" + face));
    assertTrue(pdf.contains("/FontFile2"));
    assertTrue(pdf.contains("/ToUnicode"));
    assertTrue(pdf.contains("<20ac>"));
    assertTrue(pdf.contains("<03bb>"));
    int start = pdf.lastIndexOf("startxref\n") + 10;
    int offset = Integer.parseInt(pdf.substring(start, pdf.indexOf('\n', start)));
    var lines = pdf.substring(offset).split("\n");
    int count = Integer.parseInt(lines[1].split(" ")[1]);
    for (int i = 1; i < count; i++) {
      int at = Integer.parseInt(lines[i + 2].substring(0, 10));
      assertTrue("Object " + i, pdf.substring(at).startsWith(i + " 0 obj"));
    }
    String artifact = System.getProperty("font.pdf.artifact");
    if (artifact != null)
      try (var out = new FileOutputStream(artifact)) {
        out.write(bytes);
      }
  }

  @Test
  public void tabLeadersFitPdfAdvancesEvenWhenScreenMeasurementsRound() throws Exception {
    var session = new Session(Document.empty());
    session.replace("A\tB");
    session.paragraph("tabs", "120,left,dot");
    var layout =
        new LayoutEngine(
                new LayoutEngine.Metrics() {
                  public float width(String text, TextStyle style) {
                    return text.length();
                  }

                  public float height(TextStyle style) {
                    return 12;
                  }
                })
            .layout(session.document(), 0);
    var pdf =
        new String(PdfExporter.write(session.document().page, layout, FONTS), "ISO-8859-1");
    var leader =
        java.util.regex.Pattern.compile("\\[<((?:0002)+)>\\] TJ").matcher(pdf);
    assertTrue(leader.find());
    int count = leader.group(1).length() / 4;
    var font = new PdfFonts.TrueType(FONTS.bytes(FONTS.name(TextStyle.DEFAULT)));
    float advance = font.scaled(font.advance(font.glyph('.'))) * 12 / 1000f;
    float gap = layout.pages.get(0).lines.get(0).spans.get(1).width;
    assertTrue(count > 0);
    assertTrue(count * advance <= gap + .001f);
    assertTrue((count + 1) * advance > gap);
  }

  @Test
  public void missingGlyphShowsNotdefAndMalformedFontsFailExplicitly() throws Exception {
    var face = new PdfFonts(FONTS).face(TextStyle.DEFAULT);
    assertEquals("<0001>", face.encode("\uD83D\uDE80"));
    assertEquals(0, face.font.glyph(face.glyphCodes.get(0)));
    for (byte[] bytes :
        new byte[][] {
          new byte[0],
          new byte[] {0, 1, 0, 0},
          Arrays.copyOf(FONTS.bytes("SourceSerif4-Regular"), 64)
        }) {
      try {
        new PdfFonts.TrueType(bytes);
        fail();
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("font"));
      }
    }
  }

  @Test
  public void supplementaryCharactersHaveUtf16UnicodeMapping() throws Exception {
    var face = new PdfFonts(FONTS).face(TextStyle.DEFAULT.with("font", "Serif"));
    int supplementary = 0;
    for (int cp = 0x10000; cp <= 0x1ffff; cp++)
      if (face.font.glyph(cp) != 0) {
        supplementary = cp;
        break;
      }
    assertTrue("Fixture covers supplementary Unicode", supplementary > 0);
    int value = supplementary - 0x10000;
    var text = "" + (char) (0xd800 + (value >> 10)) + (char) (0xdc00 + (value & 1023));
    assertEquals("<0001>", face.encode(text));
    var objects = new ArrayList<byte[]>();
    face.install(objects);
    var map = new String(objects.get(3), "ISO-8859-1");
    assertTrue(
        map.contains(Integer.toHexString(text.charAt(0)) + Integer.toHexString(text.charAt(1))));
  }
}
