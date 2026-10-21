package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

/** WMF and EMF rasterization, original-byte preservation and hostile input. */
public class MetafilePicturesTest {
  static final int RED = 0xffff0000, GREEN = 0xff00ff00, BLUE = 0xff0000ff;

  static final class Out {
    final ByteArrayOutputStream o = new ByteArrayOutputStream();

    Out u16(int... vs) {
      for (int v : vs) {
        o.write(v);
        o.write(v >> 8);
      }
      return this;
    }

    Out u32(int... vs) {
      for (int v : vs) {
        o.write(v);
        o.write(v >> 8);
        o.write(v >> 16);
        o.write(v >> 24);
      }
      return this;
    }

    Out bytes(byte[] b) {
      o.write(b, 0, b.length);
      return this;
    }

    byte[] get() {
      return o.toByteArray();
    }
  }

  /** WMF record from 16-bit words after the function. */
  static void wmf(Out o, int fn, int... words) {
    o.u32(3 + words.length).u16(fn).u16(words);
  }

  /** 2x2 24-bit bottom-up DIB: bottom row red, green; top row blue, white. */
  static byte[] dib() {
    Out o = new Out();
    o.u32(40, 2, 2).u16(1, 24).u32(0, 16, 0, 0, 0, 0);
    o.bytes(new byte[] {0, 0, (byte) 255, 0, (byte) 255, 0, 0, 0});
    o.bytes(new byte[] {(byte) 255, 0, 0, (byte) 255, (byte) 255, (byte) 255, 0, 0});
    return o.get();
  }

  static byte[] wmfSample() {
    Out o = new Out();
    // Placeable header: bounds 0,0,100,100 at 72 units per inch (100 x 100 points).
    o.u32(0x9AC6CDD7).u16(0, 0, 0, 100, 100, 72).u32(0).u16(0);
    o.u16(1, 9, 0x0300).u32(0).u16(0).u32(0).u16(0);
    wmf(o, 0x020B, 0, 0);
    wmf(o, 0x020C, 100, 100);
    wmf(o, 0x02FC, 0, 0x00ff, 0, 0); // red solid brush -> handle 0
    wmf(o, 0x012D, 0);
    wmf(o, 0x02FA, 5, 0, 0, 0, 0); // null pen -> handle 1
    wmf(o, 0x012D, 1);
    wmf(o, 0x041B, 50, 50, 10, 10); // rectangle 10,10 - 50,50
    wmf(o, 0x02FC, 0, 0, 0x00ff, 0); // blue -> handle 2
    wmf(o, 0x012D, 2);
    wmf(o, 0x0418, 95, 95, 60, 60); // ellipse
    wmf(o, 0x01F0, 0);
    wmf(o, 0x02FC, 0, 0xff00, 0, 0); // green reuses handle 0
    wmf(o, 0x012D, 0);
    wmf(o, 0x0324, 3, 10, 60, 40, 60, 10, 95); // triangle
    byte[] d = dib();
    int[] words = new int[11 + d.length / 2];
    // DIBStretchBlt: rop(2 words), srcH, srcW, ySrc, xSrc, destH, destW, yDest, xDest.
    int[] fields = {0x0020, 0x00CC, 2, 2, 0, 0, 30, 30, 10, 60};
    System.arraycopy(fields, 0, words, 0, fields.length);
    for (int i = 0; i < d.length / 2; i++) words[10 + i] = (d[2 * i] & 255) | (d[2 * i + 1] & 255) << 8;
    wmf(o, 0x0B41, Arrays.copyOf(words, 10 + d.length / 2));
    wmf(o, 0x0000);
    return o.get();
  }

  static void emf(Out o, int type, int... vals) {
    o.u32(type, 8 + 4 * vals.length).u32(vals);
  }

  static byte[] emfSample() {
    return emfSample(0x00CC0020);
  }

  static byte[] emfSample(int rop) {
    Out body = new Out();
    emf(body, 39, 1, 0, 0x0000ff); // red brush ih 1
    emf(body, 37, 1);
    emf(body, 37, 0x80000008); // NULL_PEN
    emf(body, 43, 10, 10, 50, 50);
    // Polygon16 triangle in blue.
    emf(body, 39, 2, 0, 0xff0000);
    emf(body, 37, 2);
    body.u32(86, 8 + 16 + 4 + 12).u32(0, 0, 0, 0, 3).u16(60, 10, 95, 10, 60, 45);
    // Path bracket: green square 60..90 x 60..90.
    emf(body, 39, 3, 0, 0x00ff00);
    emf(body, 37, 3);
    emf(body, 59);
    emf(body, 27, 60, 60);
    emf(body, 54, 90, 60);
    emf(body, 54, 90, 90);
    emf(body, 54, 60, 90);
    emf(body, 61);
    emf(body, 60);
    emf(body, 62, 0, 0, 0, 0);
    // StretchDIBits into 10..40 x 60..90.
    byte[] d = dib();
    int off = 8 + 72;
    body.u32(81, off + d.length).u32(0, 0, 0, 0, 10, 60, 0, 0, 2, 2, off, 40, off + 40, 16, 0, rop, 30, 30);
    body.bytes(d);
    emf(body, 14, 0, 16, 20);
    byte[] records = body.get();
    Out o = new Out();
    // Header: frame 0..2540 hundredths of a mm (one inch), device 1000 px over 254 mm.
    o.u32(1, 88, 0, 0, 99, 99, 0, 0, 2540, 2540, 0x464D4520, 0x10000, 88 + records.length, 20);
    o.u16(0, 0).u32(0, 0, 0, 1000, 1000, 254, 254);
    o.bytes(records);
    return o.get();
  }

  static int at(PictureCodecs.Raster r, double fx, double fy) {
    return r.argb[(int) (fy * r.height) * r.width + (int) (fx * r.width)];
  }

  @Test(timeout = 20000)
  public void rendersWmfShapesAndBitmaps() throws Exception {
    byte[] w = wmfSample();
    assertEquals("image/x-wmf", PictureCodecs.mime(w));
    var r = PictureCodecs.decode(w);
    assertEquals(200, r.width);
    assertEquals(200, r.height);
    assertEquals(RED, at(r, 0.3, 0.3));
    assertEquals(BLUE, at(r, 0.775, 0.775));
    assertEquals(GREEN, at(r, 0.15, 0.75));
    assertEquals(0, at(r, 0.05, 0.05) >>> 24);
    // Bitmap 60..90 x 10..40: top row blue, white; bottom row red, green.
    assertEquals(BLUE, at(r, 0.67, 0.17));
    assertEquals(0xffffffff, at(r, 0.83, 0.17));
    assertEquals(RED, at(r, 0.67, 0.33));
    assertEquals(GREEN, at(r, 0.83, 0.33));
  }

  @Test(timeout = 20000)
  public void rendersEmfShapesPathsAndBitmaps() throws Exception {
    byte[] e = emfSample();
    assertEquals("image/x-emf", PictureCodecs.mime(e));
    var r = PictureCodecs.decode(e);
    assertEquals(144, r.width);
    assertEquals(144, r.height);
    assertEquals(RED, at(r, 0.3, 0.3));
    assertEquals(BLUE, at(r, 0.7, 0.15));
    assertEquals(GREEN, at(r, 0.75, 0.75));
    assertEquals(BLUE, at(r, 0.17, 0.67));
    assertEquals(GREEN, at(r, 0.33, 0.83));
    assertEquals(0, at(r, 0.05, 0.05) >>> 24);
  }

  @Test(timeout = 20000)
  public void emfRasterOperationsDrawAsOverWhitePaper() throws Exception {
    // As Wordcraft's dib::prepare: SRCAND leaves white source pixels out (they keep the paper),
    // SRCPAINT/SRCINVERT leave black ones out; plain copy keeps every pixel.
    var copy = PictureCodecs.decode(emfSample(0x00CC0020));
    assertEquals(0xffffffff, at(copy, 0.33, 0.67));
    var and = PictureCodecs.decode(emfSample(0x008800C6));
    assertEquals(0, at(and, 0.33, 0.67) >>> 24);
    assertEquals(BLUE, at(and, 0.17, 0.67));
    var paint = PictureCodecs.decode(emfSample(0x00EE0086));
    assertEquals(0xffffffff, at(paint, 0.33, 0.67));
    assertEquals(GREEN, at(paint, 0.33, 0.83));
  }

  @Test(timeout = 60000)
  public void metafilesKeepOriginalBytesThroughDocxNativeAndPdf() throws Exception {
    Map<String, byte[]> inputs = new LinkedHashMap<>();
    inputs.put("image/x-wmf", wmfSample());
    inputs.put("image/x-emf", emfSample());
    var s = new Session(Document.empty());
    s.replace("Metafiles");
    for (var e : inputs.entrySet()) s.insertPicture(e.getValue(), 72, 72, e.getKey());
    List<Picture> pictures = new ArrayList<>();
    for (Block b : s.document().blocks) if (b instanceof Picture) pictures.add((Picture) b);
    assertEquals(2, pictures.size());
    int i = 0;
    for (var e : inputs.entrySet()) {
      var p = pictures.get(i++);
      assertEquals("image/png", p.mime);
      assertEquals(e.getKey(), p.originalMime());
      assertArrayEquals(e.getValue(), p.originalBytes());
    }
    var nativeCopy = DocumentXml.read(DocumentXml.write(s.document()));
    assertEquals(DocumentXml.write(s.document()), DocumentXml.write(nativeCopy));
    var out = new ByteArrayOutputStream();
    Docx.write(s.document(), out);
    Set<String> media = new TreeSet<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      for (ZipEntry z; (z = zip.getNextEntry()) != null; )
        if (z.getName().startsWith("word/media/")) media.add(z.getName().substring(z.getName().lastIndexOf('.') + 1));
    }
    assertEquals(new TreeSet<>(Arrays.asList("emf", "wmf")), media);
    var opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    i = 0;
    for (Block b : opened.document.blocks)
      if (b instanceof Picture) {
        assertArrayEquals(pictures.get(i).originalBytes(), ((Picture) b).originalBytes());
        i++;
      }
    assertEquals(2, i);
    var pdf = new String(PdfExporter.write(s.document()), "ISO-8859-1");
    assertTrue(pdf.contains("/Subtype /Image"));
  }

  static void decodeOrIOException(byte[] b) {
    try {
      PictureCodecs.decode(b);
    } catch (IOException expected) {
      // Clear failure is the contract for malformed input.
    }
  }

  @Test(timeout = 120000)
  public void hostileMetafilesNeverHangOrThrowUnchecked() throws Exception {
    for (byte[] f : new byte[][] {wmfSample(), emfSample()}) {
      for (int n = 12; n < f.length; n++) decodeOrIOException(Arrays.copyOf(f, n));
      var random = new Random(f.length);
      for (int k = 0; k < 300; k++) {
        byte[] g = f.clone();
        for (int j = random.nextInt(6); j >= 0; j--) g[4 + random.nextInt(g.length - 4)] = (byte) random.nextInt();
        decodeOrIOException(g);
      }
    }
    // Huge point counts, huge record sizes and a flood of full-canvas fills stay bounded.
    Out body = new Out();
    body.u32(3, 8 + 20).u32(0, 0, 0, 0, 0x7fffffff);
    body.u32(8, 8 + 28).u32(0, 0, 0, 0, 0x7fffffff, 0x7fffffff, 0x7fffffff);
    for (int i = 0; i < 100000; i++) emf(body, 43, -1000000, -1000000, 1000000, 1000000);
    body.u32(81, 0x7ffffff0);
    byte[] records = body.get();
    Out o = new Out();
    o.u32(1, 88, 0, 0, 99, 99, 0, 0, 2540, 2540, 0x464D4520, 0x10000, 88 + records.length, 20);
    o.u16(0, 0).u32(0, 0, 0, 1000, 1000, 254, 254);
    o.bytes(records);
    long start = System.currentTimeMillis();
    var r = PictureCodecs.decode(o.get());
    assertEquals(144, r.width);
    assertTrue(System.currentTimeMillis() - start < 30000);
    // A placeable WMF with no size fails clearly.
    Out z = new Out();
    z.u32(0x9AC6CDD7).u16(0, 0, 0, 0, 0, 72).u32(0).u16(0);
    z.u16(1, 9, 0x0300).u32(0).u16(0).u32(0).u16(0);
    try {
      PictureCodecs.decode(z.get());
      fail("zero-size WMF decoded");
    } catch (IOException expected) {
      assertNotNull(expected.getMessage());
    }
  }
}
