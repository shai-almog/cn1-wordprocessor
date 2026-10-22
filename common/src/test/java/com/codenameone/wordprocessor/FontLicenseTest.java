package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * OS/2 fsType embedding permissions, least restrictive bit winning: a face is unembeddable only
 * when 0x0002 is set without 0x0004 or 0x0008, or when 0x0200 (bitmap only) is set. A restricted
 * fallback face is skipped for the next one, and a restricted chosen face is substituted by the
 * next covering face with an export warning naming it. 0x0100 embeds the whole font, unsubset.
 * (Beyond Wordcraft, whose PDF writer embeds regardless.)
 */
public class FontLicenseTest {
  private static byte[] resource(String name) throws IOException {
    try (var in = FontLicenseTest.class.getResourceAsStream("/" + name + ".ttf")) {
      return in.readAllBytes();
    }
  }

  /** {@code name}'s bytes with the OS/2 fsType restricted-license bit set. */
  private static byte[] restricted(String name) throws IOException {
    return withFsType(name, 2);
  }

  /** {@code name}'s bytes with OS/2 fsType replaced by {@code fsType}. */
  private static byte[] withFsType(String name, int fsType) throws IOException {
    byte[] data = resource(name);
    int os = new PdfFonts.TrueType(data).tables.get("OS/2")[0];
    data[os + 8] = (byte) (fsType >> 8);
    data[os + 9] = (byte) fsType;
    return data;
  }

  private static boolean embeddable(int fsType) throws IOException {
    try {
      new PdfFonts.TrueType(withFsType("SourceSans3-Regular", fsType));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  public void leastRestrictivePermissionWins() throws Exception {
    assertTrue(embeddable(0));
    assertFalse(embeddable(0x0002));
    assertTrue(embeddable(0x0004));
    assertTrue(embeddable(0x0006)); // Futura, Gill Sans, Lucida Grande
    assertTrue(embeddable(0x0008));
    assertTrue(embeddable(0x000a));
    assertTrue(embeddable(0x000e)); // ZapfDingbats
    assertTrue(embeddable(0x0100));
    assertFalse(embeddable(0x0102));
    assertFalse(embeddable(0x0200));
    assertFalse(embeddable(0x0204));
  }

  @Test
  public void previewAndPrintFaceIsEmbedded() throws Exception {
    var s = new Session(Document.empty());
    s.replace("abc");
    var warnings = new java.util.ArrayList<String>();
    var pdf = new String(PdfExporter.write(s.document(), new Provider("SourceSans3-Regular", 6),
        warnings), "ISO-8859-1");
    assertTrue(pdf.contains("+SourceSans3-Regular"));
    assertTrue(warnings.toString(), warnings.isEmpty());
  }

  @Test
  public void noSubsettingEmbedsTheWholeFont() throws Exception {
    var s = new Session(Document.empty());
    s.replace("abc");
    var pdf = new String(PdfExporter.write(s.document(), new Provider("SourceSans3-Regular", 0x100)),
        "ISO-8859-1");
    assertTrue(pdf.contains("/SourceSans3-Regular"));
    assertFalse(pdf.contains("+SourceSans3-Regular"));
    int length = resource("SourceSans3-Regular").length;
    assertTrue(pdf.contains("/Length1 " + length));
  }

  private static final class Provider
      implements PdfExporter.FontProvider, PdfExporter.FallbackFonts {
    final String restrictedName;
    final int fsType;

    Provider(String restrictedName) {
      this(restrictedName, 2);
    }

    Provider(String restrictedName, int fsType) {
      this.restrictedName = restrictedName;
      this.fsType = fsType;
    }

    public String name(TextStyle style) {
      return "SourceSans3-Regular";
    }

    public List<String> fallbacks(TextStyle style) {
      return Arrays.asList("NotoSansHebrew-Regular", "NotoSansHebrew-Bold");
    }

    public byte[] bytes(String name) throws IOException {
      return name.equals(restrictedName) ? withFsType(name, fsType) : resource(name);
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
  public void restrictedChosenFaceIsSubstitutedWithAWarning() throws Exception {
    var s = new Session(Document.empty());
    s.replace("abc");
    var warnings = new java.util.ArrayList<String>();
    var pdf = new String(
        PdfExporter.write(s.document(), new Provider("SourceSans3-Regular"), warnings),
        "ISO-8859-1");
    assertFalse(pdf.contains("SourceSans3-Regular"));
    assertEquals(warnings.toString(), 1, warnings.size());
    assertTrue(warnings.get(0), warnings.get(0).contains("SourceSans3-Regular"));
    assertTrue(warnings.get(0), warnings.get(0).contains("license"));
  }

  /** The command export paths (file.exportPdf, file.share) report the substitution too. */
  @Test
  public void commandExportsReportTheWarning() throws Exception {
    var s = new Session(Document.empty());
    s.replace("abc");
    var commands = new Commands();
    var saved = OfficeCommands.pdfFonts;
    OfficeCommands.pdfFonts = new Provider("SourceSans3-Regular");
    try {
      for (String[] c : new String[][] {{"file.exportPdf"}, {"file.share", "format", "pdf"}}) {
        var a = new java.util.LinkedHashMap<String, Object>();
        if (c.length > 1) a.put(c[1], c[2]);
        var result = (java.util.Map<?, ?>) commands.execute(s, c[0], a);
        assertTrue(c[0] + " " + result.keySet(),
            String.valueOf(result.get("warnings")).contains("SourceSans3-Regular"));
      }
    } finally {
      OfficeCommands.pdfFonts = saved;
    }
  }
}
