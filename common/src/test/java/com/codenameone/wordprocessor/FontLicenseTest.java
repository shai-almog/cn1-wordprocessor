package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * OS/2 fsType restricted-license (0x0002) faces are never embedded: a restricted fallback face is
 * skipped for the next one, and a restricted chosen face fails the export with a message naming
 * it. (Beyond Wordcraft, whose PDF writer embeds regardless.)
 */
public class FontLicenseTest {
  private static byte[] resource(String name) throws IOException {
    try (var in = FontLicenseTest.class.getResourceAsStream("/" + name + ".ttf")) {
      return in.readAllBytes();
    }
  }

  /** {@code name}'s bytes with the OS/2 fsType restricted-license bit set. */
  private static byte[] restricted(String name) throws IOException {
    byte[] data = resource(name);
    int os = new PdfFonts.TrueType(data).tables.get("OS/2")[0];
    data[os + 9] |= 2;
    return data;
  }

  private static final class Provider
      implements PdfExporter.FontProvider, PdfExporter.FallbackFonts {
    final String restrictedName;

    Provider(String restrictedName) {
      this.restrictedName = restrictedName;
    }

    public String name(TextStyle style) {
      return "SourceSans3-Regular";
    }

    public List<String> fallbacks(TextStyle style) {
      return Arrays.asList("NotoSansHebrew-Regular", "NotoSansHebrew-Bold");
    }

    public byte[] bytes(String name) throws IOException {
      return name.equals(restrictedName) ? restricted(name) : resource(name);
    }
  }

  @Test
  public void restrictedFaceIsRejected() throws Exception {
    try {
      new PdfFonts.TrueType(restricted("SourceSans3-Regular"));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("embedding"));
    }
  }

  @Test
  public void restrictedFallbackIsSkipped() throws Exception {
    var s = new Session(Document.empty());
    s.replace("abc שלום");
    var pdf = new String(PdfExporter.write(s.document(), new Provider("NotoSansHebrew-Regular")),
        "ISO-8859-1");
    assertFalse(pdf.contains("NotoSansHebrew-Regular"));
    assertTrue(pdf.contains("NotoSansHebrew-Bold"));
  }

  @Test
  public void restrictedChosenFaceIsReportedByName() throws Exception {
    var s = new Session(Document.empty());
    s.replace("abc");
    try {
      PdfExporter.write(s.document(), new Provider("SourceSans3-Regular"));
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("SourceSans3-Regular"));
      assertTrue(expected.getMessage(), expected.getMessage().contains("license"));
    }
  }
}
