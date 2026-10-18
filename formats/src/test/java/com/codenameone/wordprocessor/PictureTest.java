package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class PictureTest {
  static byte[] png(
      int bits, int type, int width, int height, byte[] rows, byte[] palette, byte[] alpha)
      throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeLong(0x89504e470d0a1a0aL);
    var header = new ByteArrayOutputStream();
    var h = new DataOutputStream(header);
    h.writeInt(width);
    h.writeInt(height);
    h.writeByte(bits);
    h.writeByte(type);
    h.write(new byte[3]);
    chunk(out, "IHDR", header.toByteArray());
    if (palette != null) chunk(out, "PLTE", palette);
    if (alpha != null) chunk(out, "tRNS", alpha);
    var compressed = new ByteArrayOutputStream();
    var z = new DeflaterOutputStream(compressed);
    z.write(rows);
    z.close();
    chunk(out, "IDAT", compressed.toByteArray());
    chunk(out, "IEND", new byte[0]);
    return bytes.toByteArray();
  }

  static void chunk(DataOutputStream out, String name, byte[] data) throws Exception {
    var tag = name.getBytes("ISO-8859-1");
    out.writeInt(data.length);
    out.write(tag);
    out.write(data);
    var crc = new CRC32();
    crc.update(tag);
    crc.update(data);
    out.writeInt((int) crc.getValue());
  }

  static byte[] rgba() throws Exception {
    return png(
        8, 6, 2, 1, new byte[] {0, (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 64}, null, null);
  }

  @Test
  public void insertionSplitsSelectionUndoAndFormatsRoundTrip() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Before replace After");
    s.select(0, 7, 14);
    var before = s.document();
    byte[] original = rgba();
    s.insertPicture(original, 120, 60, "Red & blue");
    var inserted = s.document();
    assertEquals("Before ", inserted.paragraph(0).text.toString());
    assertEquals(" After", inserted.paragraph(2).text.toString());
    assertEquals(2, s.selection().block);
    s.undo();
    assertSame(before, s.document());
    s.redo();
    assertSame(inserted, s.document());
    s.updatePicture(1, 180, 90, "Red & blue", "center", false);
    var picture = (Picture) s.document().blocks.get(1);
    assertArrayEquals(original, picture.bytes());
    original[0] = 0;
    assertEquals(137, picture.bytes()[0] & 255);
    Document nativeCopy = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals(DocumentXml.write(s.document()), DocumentXml.write(nativeCopy));
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Docx.Opened opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    var copy = (Picture) opened.document.blocks.get(1);
    assertArrayEquals(picture.bytes(), copy.bytes());
    assertEquals(180, copy.width, .001);
    assertEquals(90, copy.height, .001);
    assertEquals("center", copy.align);
    assertEquals(picture.alt, copy.alt);
    var edited = new Session(opened.document);
    edited.replace("Edit ");
    out.reset();
    Docx.write(edited.document(), opened, out, false);
    assertArrayEquals(
        picture.bytes(),
        ((Picture) Docx.read(new ByteArrayInputStream(out.toByteArray())).document.blocks.get(1))
            .bytes());
    assertTrue(TextFormats.html(s.document()).contains("data:image/png;base64,"));
    assertTrue(s.document().plainText().contains("[Picture: Red & blue]"));
    s.updatePicture(1, 180, 90, "", "left", true);
    assertEquals(2, s.document().blocks.size());
    s.undo();
    assertEquals(3, s.document().blocks.size());
  }

  @Test
  public void layoutMovesPictureToNextPageAndFitsWithoutDistortion() throws Exception {
    var s = new Session(Document.empty());
    s.replace("Text");
    s.insertPicture(rgba(), 800, 400, "test");
    var d = s.document().page(new PageSpec(220, 120, 10, 10, 10, 10));
    var layout = new LayoutEngine(new PdfMetrics()).layout(d, 0);
    assertTrue(layout.pages.size() >= 2);
    var placed = layout.pages.get(1).pictures.get(0);
    assertEquals(200, placed.width(), .001);
    assertEquals(100, placed.height(), .001);
    assertEquals(10, placed.y(), .001);
    var pdf = new String(PdfExporter.write(d), "ISO-8859-1");
    assertTrue(pdf.contains("/Subtype /Image"));
    assertTrue(pdf.contains("/SMask"));
    assertTrue(pdf.contains("/Im0 Do"));
    int start = pdf.lastIndexOf("startxref\n") + 10;
    int xref = Integer.parseInt(pdf.substring(start, pdf.indexOf('\n', start)));
    assertTrue(pdf.substring(xref).startsWith("xref"));
  }

  @Test
  public void pngPixelsAndAlphaDecodeExactlyForAllFilters() throws Exception {
    // Two identical RGBA pixels repeated over five rows, one row per PNG filter.
    byte[] raw = {(byte) 250, 10, 30, (byte) 255, 20, 40, 60, 80};
    var rows = new ByteArrayOutputStream();
    for (int filter = 0; filter < 5; filter++) {
      rows.write(filter);
      for (int x = 0; x < raw.length; x++) {
        int a = x < 4 ? 0 : raw[x - 4] & 255,
            b = filter == 0 ? 0 : raw[x] & 255,
            c = x < 4 ? 0 : raw[x - 4] & 255,
            p = a + b - c,
            pa = Math.abs(p - a),
            pb = Math.abs(p - b),
            pc = Math.abs(p - c);
        int predict =
            filter == 0
                ? 0
                : filter == 1
                    ? a
                    : filter == 2
                        ? b
                        : filter == 3 ? (a + b) / 2 : pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
        rows.write((raw[x] & 255) - predict);
      }
    }
    byte[] data = png(8, 6, 2, 5, rows.toByteArray(), null, null);
    var result = new PdfPictures.Png(data, new Picture("i1", data, 20, 50, "", "left"));
    for (int row = 0; row < 5; row++) {
      assertArrayEquals(
          new byte[] {(byte) 250, 10, 30, 20, 40, 60},
          Arrays.copyOfRange(result.rgb, row * 6, row * 6 + 6));
      assertEquals(255, result.alpha[row * 2] & 255);
      assertEquals(80, result.alpha[row * 2 + 1] & 255);
    }
  }

  @Test
  public void indexedAndGrayTransparencyArePreserved() throws Exception {
    byte[] data =
        png(
            1,
            3,
            2,
            1,
            new byte[] {0, 64},
            new byte[] {(byte) 255, 0, 0, 0, (byte) 255, 0},
            new byte[] {0, (byte) 255});
    var result = new PdfPictures.Png(data, new Picture("i1", data, 2, 1, "", "left"));
    assertArrayEquals(new byte[] {(byte) 255, 0, 0, 0, (byte) 255, 0}, result.rgb);
    assertArrayEquals(new byte[] {0, (byte) 255}, result.alpha);
    data = png(16, 0, 2, 1, new byte[] {0, 0, 0, (byte) 255, (byte) 255}, null, new byte[] {0, 0});
    result = new PdfPictures.Png(data, new Picture("i1", data, 2, 1, "", "left"));
    assertArrayEquals(new byte[] {0, 0, 0, (byte) 255, (byte) 255, (byte) 255}, result.rgb);
    assertArrayEquals(new byte[] {0, (byte) 255}, result.alpha);
  }

  @Test
  public void jpegIsEmbeddedWithoutRecompression() throws Exception {
    var bitmap =
        new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    bitmap.setRGB(0, 0, 0xff0000);
    var out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(bitmap, "jpeg", out);
    var data = out.toByteArray();
    var p = new Picture("i1", data, 30, 20, "", "left");
    assertEquals(3, p.pixelWidth);
    assertEquals(2, p.pixelHeight);
    assertEquals("image/jpeg", p.mime);
    var objects = new ArrayList<byte[]>();
    PdfPictures.add(objects, p);
    var object = new String(objects.get(0), "ISO-8859-1");
    assertTrue(object.contains("/DCTDecode"));
    assertTrue(object.contains(new String(data, "ISO-8859-1")));
  }

  @Test
  public void malformedPicturesFailWithoutMutatingDocument() throws Exception {
    var s = new Session(Document.empty());
    var d = s.document();
    try {
      s.insertPicture(new byte[40], 10, 10, "");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    assertSame(d, s.document());
    byte[] data = rgba();
    data[data.length - 5] ^= 1;
    try {
      new PdfPictures.Png(data, new Picture("i1", data, 10, 10, "", "left"));
      fail();
    } catch (IOException expected) {
    }
  }
}
