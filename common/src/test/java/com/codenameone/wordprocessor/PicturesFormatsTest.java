package com.codenameone.wordprocessor;

import static org.junit.Assert.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

/** GIF, BMP, TIFF and WebP decoding, DOCX/native round trip and PDF export. */
public class PicturesFormatsTest {
  static final int W = 37, H = 29;

  /** Test pattern; alpha varies when requested. */
  static int px(int x, int y, boolean alpha) {
    int a = alpha ? ((x + y) * 7) & 255 : 255;
    return a << 24 | ((x * 12) & 255) << 16 | ((y * 9) & 255) << 8 | ((x * y * 3) & 255);
  }

  static int[] pattern(int w, int h, boolean alpha) {
    int[] p = new int[w * h];
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) p[y * w + x] = px(x, y, alpha);
    return p;
  }

  static final int[] PALETTE = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff, 0xff000000, 0xff808080, 0xffffff00, 0xff00ffff,
    0xff123456, 0xff654321, 0xffabcdef, 0xfffedcba, 0xff111111, 0xff222222, 0xff333333, 0xff444444};

  static int index(int x, int y, int colors) {
    return (x / 2 + y * 3 + (x * y) % 5) % colors;
  }

  // ---- GIF ----

  static byte[] lzw(byte[] indices, int min) {
    var out = new ByteArrayOutputStream();
    int clear = 1 << min, size = min + 1, next = clear + 2;
    Map<String, Integer> dict = new HashMap<>();
    var bits = new int[] {0};
    var buf = new long[] {0};
    java.util.function.BiConsumer<Integer, Integer> put = (code, n) -> {
      buf[0] |= (long) code << bits[0];
      bits[0] += n;
      while (bits[0] >= 8) {
        out.write((int) (buf[0] & 255));
        buf[0] >>>= 8;
        bits[0] -= 8;
      }
    };
    put.accept(clear, size);
    String w = "" + (char) (indices[0] & 255);
    for (int i = 1; i < indices.length; i++) {
      char c = (char) (indices[i] & 255);
      String wc = w + c;
      if (wc.length() == 1 || dict.containsKey(wc)) {
        w = wc;
        continue;
      }
      put.accept(w.length() == 1 ? w.charAt(0) : dict.get(w), size);
      if (next < 4096) {
        dict.put(wc, next++);
        if (next > (1 << size) && size < 12) size++;
      } else {
        put.accept(clear, size);
        dict.clear();
        size = min + 1;
        next = clear + 2;
      }
      w = "" + c;
    }
    put.accept(w.length() == 1 ? w.charAt(0) : dict.get(w), size);
    if (next == 1 << size && size < 12) size++;
    put.accept(clear + 1, size);
    if (bits[0] > 0) out.write((int) (buf[0] & 255));
    return out.toByteArray();
  }

  static byte[] gif(int w, int h, boolean interlaced, int transparent, boolean localTable) {
    var o = new ByteArrayOutputStream();
    o.writeBytes("GIF89a".getBytes());
    le16(o, w);
    le16(o, h);
    o.write(localTable ? 0 : 0x80 | 3);
    o.write(0);
    o.write(0);
    if (!localTable) table(o);
    o.writeBytes(new byte[] {0x21, (byte) 0xff, 11});
    o.writeBytes("NETSCAPE2.0".getBytes());
    o.writeBytes(new byte[] {3, 1, 0, 0, 0});
    if (transparent >= 0) o.writeBytes(new byte[] {0x21, (byte) 0xf9, 4, 1, 0, 0, (byte) transparent, 0});
    o.write(0x2c);
    le16(o, 0);
    le16(o, 0);
    le16(o, w);
    le16(o, h);
    o.write((interlaced ? 0x40 : 0) | (localTable ? 0x80 | 3 : 0));
    if (localTable) table(o);
    byte[] idx = new byte[w * h];
    int k = 0;
    int[][] passes = interlaced ? new int[][] {{0, 8}, {4, 8}, {2, 4}, {1, 2}} : new int[][] {{0, 1}};
    for (int[] p : passes)
      for (int y = p[0]; y < h; y += p[1]) for (int x = 0; x < w; x++) idx[k++] = (byte) index(x, y, 16);
    o.write(4);
    byte[] data = lzw(idx, 4);
    for (int i = 0; i < data.length; i += 255) {
      int n = Math.min(255, data.length - i);
      o.write(n);
      o.write(data, i, n);
    }
    o.write(0);
    // A second frame that must be ignored.
    o.writeBytes(new byte[] {0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0, 0x3b});
    return o.toByteArray();
  }

  static void table(ByteArrayOutputStream o) {
    for (int c : PALETTE) {
      o.write(c >> 16 & 255);
      o.write(c >> 8 & 255);
      o.write(c & 255);
    }
  }

  static void le16(ByteArrayOutputStream o, int v) {
    o.write(v & 255);
    o.write(v >> 8 & 255);
  }

  static void le32(ByteArrayOutputStream o, int v) {
    le16(o, v & 0xffff);
    le16(o, v >>> 16);
  }

  static int[] indexed(int w, int h, int colors, int transparent) {
    int[] p = new int[w * h];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int i = index(x, y, colors);
        p[y * w + x] = i == transparent ? 0 : PALETTE[i];
      }
    return p;
  }

  @Test(timeout = 20000)
  public void decodesGifFirstFrame() throws Exception {
    assertArrayEquals(indexed(W, H, 16, -1), PictureCodecs.decode(gif(W, H, false, -1, false)).argb);
    assertArrayEquals(indexed(W, H, 16, 5), PictureCodecs.decode(gif(W, H, true, 5, true)).argb);
    var big = PictureCodecs.decode(gif(300, 200, false, -1, false));
    assertArrayEquals(indexed(300, 200, 16, -1), big.argb);
  }

  // ---- BMP ----

  static byte[] bmp(int bpp, int compression, boolean topDown, int w, int h, int header) {
    var pixels = new ByteArrayOutputStream();
    int stride = (w * bpp + 31) / 32 * 4;
    if (compression == 1 || compression == 2) {
      boolean eight = compression == 1;
      for (int y = h - 1; y >= 0; y--) {
        int x = 0;
        while (x < w) {
          int v = index(x, y, 16), run = 1;
          while (x + run < w && run < 255 && index(x + run, y, 16) == v) run++;
          if (run == 1 && w - x >= 3) {
            pixels.write(0);
            pixels.write(3);
            if (eight) {
              for (int i = 0; i < 3; i++) pixels.write(index(x + i, y, 16));
              pixels.write(0);
            } else {
              pixels.write(index(x, y, 16) << 4 | index(x + 1, y, 16));
              pixels.write(index(x + 2, y, 16) << 4);
            }
            x += 3;
          } else {
            pixels.write(run);
            pixels.write(eight ? v : v << 4 | v);
            x += run;
          }
        }
        pixels.write(0);
        pixels.write(0);
      }
      pixels.write(0);
      pixels.write(1);
    } else {
      for (int r = 0; r < h; r++) {
        int y = topDown ? r : h - 1 - r;
        byte[] row = new byte[stride];
        for (int x = 0; x < w; x++) {
          int c = px(x, y, bpp == 32);
          if (bpp <= 8) {
            int v = index(x, y, 1 << Math.min(bpp, 4)), bit = x * bpp;
            row[bit >> 3] |= v << (8 - bpp - (bit & 7));
          } else if (bpp == 16) {
            int v = (c >> 19 & 31) << 10 | (c >> 11 & 31) << 5 | (c >> 3 & 31);
            row[x * 2] = (byte) v;
            row[x * 2 + 1] = (byte) (v >> 8);
          } else {
            int at = x * (bpp / 8);
            row[at] = (byte) c;
            row[at + 1] = (byte) (c >> 8);
            row[at + 2] = (byte) (c >> 16);
            if (bpp == 32) row[at + 3] = (byte) (c >>> 24);
          }
        }
        pixels.writeBytes(row);
      }
    }
    int colors = bpp <= 8 ? 1 << Math.min(bpp, 4) : 0;
    int masks = compression == 3 && header == 40 ? 12 : 0;
    var o = new ByteArrayOutputStream();
    o.writeBytes("BM".getBytes());
    int offset = 14 + header + masks + colors * 4;
    le32(o, offset + pixels.size());
    le32(o, 0);
    le32(o, offset);
    le32(o, header);
    le32(o, w);
    le32(o, topDown ? -h : h);
    le16(o, 1);
    le16(o, bpp);
    le32(o, compression);
    le32(o, pixels.size());
    le32(o, 2835);
    le32(o, 2835);
    le32(o, colors);
    le32(o, 0);
    if (header > 40) {
      le32(o, 0xff0000);
      le32(o, 0xff00);
      le32(o, 0xff);
      le32(o, 0xff000000);
      o.writeBytes(new byte[header - 56]);
    } else if (masks > 0) {
      le32(o, 0xff0000);
      le32(o, 0xff00);
      le32(o, 0xff);
    }
    for (int i = 0; i < colors; i++) le32(o, PALETTE[i] & 0xffffff);
    o.writeBytes(pixels.toByteArray());
    return o.toByteArray();
  }

  static int[] rgb555(int w, int h) {
    int[] p = pattern(w, h, false);
    for (int i = 0; i < p.length; i++) {
      int c = p[i], r = (c >> 19 & 31) * 255 / 31, g = (c >> 11 & 31) * 255 / 31, b = (c >> 3 & 31) * 255 / 31;
      p[i] = 0xff000000 | r << 16 | g << 8 | b;
    }
    return p;
  }

  @Test(timeout = 20000)
  public void decodesBmpVariants() throws Exception {
    assertArrayEquals(indexed(W, H, 2, -1), PictureCodecs.decode(bmp(1, 0, false, W, H, 40)).argb);
    assertArrayEquals(indexed(W, H, 16, -1), PictureCodecs.decode(bmp(4, 0, false, W, H, 40)).argb);
    assertArrayEquals(indexed(W, H, 16, -1), PictureCodecs.decode(bmp(8, 0, true, W, H, 40)).argb);
    assertArrayEquals(rgb555(W, H), PictureCodecs.decode(bmp(16, 0, false, W, H, 40)).argb);
    assertArrayEquals(pattern(W, H, false), PictureCodecs.decode(bmp(24, 0, false, W, H, 40)).argb);
    assertArrayEquals(pattern(W, H, false), PictureCodecs.decode(bmp(24, 0, true, W, H, 40)).argb);
    int[] opaque = pattern(W, H, false);
    var plain32 = PictureCodecs.decode(bmp(32, 0, false, W, H, 40)).argb;
    assertArrayEquals(opaque, plain32);
    assertArrayEquals(pattern(W, H, true), PictureCodecs.decode(bmp(32, 3, false, W, H, 108)).argb);
    assertArrayEquals(opaque, PictureCodecs.decode(bmp(32, 3, false, W, H, 40)).argb);
    assertArrayEquals(indexed(W, H, 16, -1), PictureCodecs.decode(bmp(8, 1, false, W, H, 40)).argb);
    assertArrayEquals(indexed(W, H, 16, -1), PictureCodecs.decode(bmp(4, 2, false, W, H, 40)).argb);
  }

  // ---- TIFF ----

  static byte[] packBits(byte[] in) {
    var o = new ByteArrayOutputStream();
    int i = 0;
    while (i < in.length) {
      int run = 1;
      while (i + run < in.length && run < 128 && in[i + run] == in[i]) run++;
      if (run >= 2) {
        o.write(1 - run);
        o.write(in[i]);
        i += run;
      } else {
        int n = 1;
        while (i + n < in.length && n < 128 && (i + n + 1 >= in.length || in[i + n] != in[i + n + 1])) n++;
        o.write(n - 1);
        o.write(in, i, n);
        i += n;
      }
    }
    o.write(-128 & 255);
    return o.toByteArray();
  }

  static byte[] tiffLzw(byte[] in) {
    var o = new ByteArrayOutputStream();
    long[] st = {0, 0};
    java.util.function.BiConsumer<Integer, Integer> put = (code, n) -> {
      st[0] = st[0] << n | code;
      st[1] += n;
      while (st[1] >= 8) {
        o.write((int) (st[0] >> (st[1] - 8)) & 255);
        st[1] -= 8;
      }
    };
    Map<String, Integer> dict = new HashMap<>();
    int size = 9, next = 258;
    put.accept(256, size);
    String w = "";
    for (byte b : in) {
      String wc = w + (char) (b & 255);
      if (wc.length() == 1 || dict.containsKey(wc)) {
        w = wc;
        continue;
      }
      put.accept(w.length() == 1 ? w.charAt(0) : dict.get(w), size);
      dict.put(wc, next++);
      if (next >= 1 << size) {
        if (size == 12) {
          put.accept(256, size);
          dict.clear();
          next = 258;
          size = 9;
        } else size++;
      }
      w = "" + (char) (b & 255);
    }
    if (!w.isEmpty()) put.accept(w.length() == 1 ? w.charAt(0) : dict.get(w), size);
    if (next + 2 >= 1 << size && size < 12) size++;
    put.accept(257, size);
    if (st[1] > 0) o.write((int) (st[0] << (8 - st[1])) & 255);
    return o.toByteArray();
  }

  /** Kinds: rgb, rgba, gray8, gray4, gray1w (WhiteIsZero), palette8, rgb16. */
  static byte[] samples(String kind, int x0, int y0, int w, int h, int width, int height, boolean predictor, boolean big) {
    var o = new ByteArrayOutputStream();
    for (int y = y0; y < y0 + h; y++) {
      int bitBuf = 0, bitN = 0;
      byte[] row;
      var r = new ByteArrayOutputStream();
      for (int x = x0; x < x0 + w; x++) {
        boolean in = x < width && y < height;
        int c = in ? px(x, y, kind.equals("rgba")) : 0;
        switch (kind) {
          case "rgb": r.write(c >> 16 & 255); r.write(c >> 8 & 255); r.write(c & 255); break;
          case "rgba": r.write(c >> 16 & 255); r.write(c >> 8 & 255); r.write(c & 255); r.write(c >>> 24); break;
          case "rgb16":
            for (int s = 16; s >= 0; s -= 8) {
              r.write(big ? c >> s & 255 : 0x5a);
              r.write(big ? 0x5a : c >> s & 255);
            }
            break;
          case "gray8": r.write(c >> 16 & 255); break;
          case "palette8": r.write(in ? index(x, y, 16) : 0); break;
          default: {
            int bps = kind.equals("gray4") ? 4 : 1, v = in ? index(x, y, 1 << bps) : 0;
            bitBuf = bitBuf << bps | v;
            bitN += bps;
            if (bitN == 8) { r.write(bitBuf); bitBuf = bitN = 0; }
          }
        }
      }
      if (bitN > 0) r.write(bitBuf << (8 - bitN));
      row = r.toByteArray();
      if (predictor) {
        int step = kind.equals("rgba") ? 4 : kind.equals("rgb") ? 3 : 1;
        for (int i = row.length - 1; i >= step; i--) row[i] -= row[i - step];
      }
      o.writeBytes(row);
    }
    return o.toByteArray();
  }

  static int[] expectedTiff(String kind) {
    int[] p = new int[W * H];
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        int c = px(x, y, kind.equals("rgba")), v;
        switch (kind) {
          case "gray8": v = c >> 16 & 255; p[y * W + x] = 0xff000000 | v * 0x10101; break;
          case "gray4": v = index(x, y, 16) * 17; p[y * W + x] = 0xff000000 | v * 0x10101; break;
          case "gray1w": v = index(x, y, 2) == 1 ? 0 : 255; p[y * W + x] = 0xff000000 | v * 0x10101; break;
          case "palette8": p[y * W + x] = PALETTE[index(x, y, 16)] & 0xfff0f0f0 | 0xff000000; break;
          default: p[y * W + x] = c;
        }
      }
    return p;
  }

  static byte[] compress(byte[] raw, int compression) {
    switch (compression) {
      case 32773: return packBits(raw);
      case 5: return tiffLzw(raw);
      case 8: {
        var d = new Deflater();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length * 2 + 64];
        int n = d.deflate(buf);
        return Arrays.copyOf(buf, n);
      }
      default: return raw;
    }
  }

  static byte[] tiff(String kind, int compression, boolean big, int tile, boolean predictor) throws Exception {
    int spp = kind.equals("rgba") ? 4 : kind.startsWith("rgb") ? 3 : 1;
    int bps = kind.equals("rgb16") ? 16 : kind.equals("gray4") ? 4 : kind.equals("gray1w") ? 1 : 8;
    int photometric = kind.startsWith("rgb") ? 2 : kind.equals("palette8") ? 3 : kind.equals("gray1w") ? 0 : 1;
    List<byte[]> blocks = new ArrayList<>();
    int tw = tile > 0 ? tile : W, th = tile > 0 ? tile : 5;
    for (int y = 0; y < H; y += th)
      for (int x = 0; x < W; x += tw)
        blocks.add(compress(samples(kind, x, y, tw, tile > 0 ? th : Math.min(th, H - y), W, H, predictor, big), compression));
    var body = new ByteArrayOutputStream();
    body.writeBytes(new byte[8]);
    long[] offsets = new long[blocks.size()], counts = new long[blocks.size()];
    for (int i = 0; i < blocks.size(); i++) {
      offsets[i] = body.size();
      counts[i] = blocks.get(i).length;
      body.writeBytes(blocks.get(i));
      if ((body.size() & 1) != 0) body.write(0);
    }
    TreeMap<Integer, long[]> tags = new TreeMap<>();
    TreeMap<Integer, Integer> types = new TreeMap<>();
    tags.put(256, new long[] {W});
    tags.put(257, new long[] {H});
    long[] bits = new long[spp];
    Arrays.fill(bits, bps);
    tags.put(258, bits);
    tags.put(259, new long[] {compression});
    tags.put(262, new long[] {photometric});
    tags.put(277, new long[] {spp});
    if (spp == 4) tags.put(338, new long[] {2});
    if (predictor) tags.put(317, new long[] {2});
    if (photometric == 3) {
      long[] map = new long[3 * 256];
      for (int i = 0; i < 16; i++) {
        map[i] = (PALETTE[i] >> 16 & 255) * 257L;
        map[256 + i] = (PALETTE[i] >> 8 & 255) * 257L;
        map[512 + i] = (PALETTE[i] & 255) * 257L;
      }
      // Low byte differs from the high byte; the decoder keeps the high byte.
      for (int i = 0; i < 768; i++) map[i] = map[i] & 0xf000 | map[i] >> 8 & 0xf0 | 0x0a;
      tags.put(320, map);
    }
    if (tile > 0) {
      tags.put(322, new long[] {tw});
      tags.put(323, new long[] {th});
      tags.put(324, offsets);
      tags.put(325, counts);
      types.put(324, 4);
      types.put(325, 4);
    } else {
      tags.put(273, offsets);
      tags.put(278, new long[] {th});
      tags.put(279, counts);
      types.put(273, 4);
      types.put(279, 4);
    }
    // Out-of-line tag values.
    Map<Integer, Long> where = new HashMap<>();
    for (var e : tags.entrySet()) {
      int type = types.getOrDefault(e.getKey(), 3), size = type == 4 ? 4 : 2;
      if (e.getValue().length * size > 4) {
        where.put(e.getKey(), (long) body.size());
        for (long v : e.getValue()) write(body, v, size, big);
      }
    }
    if ((body.size() & 1) != 0) body.write(0);
    long ifd = body.size();
    write(body, tags.size(), 2, big);
    for (var e : tags.entrySet()) {
      int type = types.getOrDefault(e.getKey(), 3), size = type == 4 ? 4 : 2;
      write(body, e.getKey(), 2, big);
      write(body, type, 2, big);
      write(body, e.getValue().length, 4, big);
      if (where.containsKey(e.getKey())) write(body, where.get(e.getKey()), 4, big);
      else {
        var inline = new ByteArrayOutputStream();
        for (long v : e.getValue()) write(inline, v, size, big);
        while (inline.size() < 4) inline.write(0);
        body.writeBytes(inline.toByteArray());
      }
    }
    write(body, 0, 4, big);
    byte[] out = body.toByteArray();
    out[0] = out[1] = (byte) (big ? 'M' : 'I');
    out[big ? 3 : 2] = 42;
    var h = new ByteArrayOutputStream();
    write(h, ifd, 4, big);
    System.arraycopy(h.toByteArray(), 0, out, 4, 4);
    return out;
  }

  static void write(ByteArrayOutputStream o, long v, int size, boolean big) {
    for (int i = 0; i < size; i++) o.write((int) (v >> (8 * (big ? size - 1 - i : i))) & 255);
  }

  @Test(timeout = 20000)
  public void decodesTiffVariants() throws Exception {
    for (String kind : new String[] {"rgb", "rgba", "gray8", "gray4", "gray1w", "palette8", "rgb16"})
      for (int compression : new int[] {1, 32773, 5, 8})
        for (boolean big : new boolean[] {false, true}) {
          String label = kind + "/" + compression + "/" + big;
          assertArrayEquals(label, expectedTiff(kind), PictureCodecs.decode(tiff(kind, compression, big, 0, false)).argb);
          assertArrayEquals(label + "/tiled", expectedTiff(kind), PictureCodecs.decode(tiff(kind, compression, big, 16, false)).argb);
        }
    for (String kind : new String[] {"rgb", "rgba", "gray8"})
      for (int compression : new int[] {5, 8})
        assertArrayEquals(kind, expectedTiff(kind), PictureCodecs.decode(tiff(kind, compression, false, 0, true)).argb);
  }

  // ---- WebP ----

  static int[] webp(String base64) throws Exception {
    var r = PictureCodecs.decode(Base64.getDecoder().decode(base64));
    assertEquals(W, r.width);
    assertEquals(H, r.height);
    return r.argb;
  }

  static long rgbaCrc(int[] argb) {
    var crc = new CRC32();
    for (int p : argb) crc.update(new byte[] {(byte) (p >> 16), (byte) (p >> 8), (byte) p, (byte) (p >>> 24)});
    return crc.getValue();
  }

  static void close(String label, int[] expected, int[] actual, double meanLimit) {
    long sum = 0;
    for (int i = 0; i < expected.length; i++)
      for (int s = 0; s < 32; s += 8) sum += Math.abs((expected[i] >>> s & 255) - (actual[i] >>> s & 255));
    double mean = sum / (expected.length * 4.0);
    assertTrue(label + " mean error " + mean, mean < meanLimit);
  }

  @Test(timeout = 20000)
  public void decodesWebpLosslessAndLossy() throws Exception {
    assertArrayEquals(pattern(W, H, false), webp(LL0));
    assertArrayEquals(pattern(W, H, true), webp(LL1));
    int[] pal = webp(LLP);
    int[] colors = {0xffff0000, 0xff0080ff, 0x800ac81e};
    for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) assertEquals(colors[(x / 3 + y) % 3], pal[y * W + x]);
    // Lossy output matches libwebp (via Pillow) bit for bit: CRC32 of its RGBA bytes.
    assertEquals(0xfb3ff1d4L, rgbaCrc(webp(LY0)));
    int[] alpha = webp(LY1), expected = pattern(W, H, true);
    for (int i = 0; i < alpha.length; i++) assertEquals(expected[i] >>> 24, alpha[i] >>> 24);
    assertEquals(0x6d1671eeL, rgbaCrc(alpha));
    assertEquals(0x11b83ad4L, rgbaCrc(webp(LYF)));
    assertEquals(0xee861e27L, rgbaCrc(webp(LYS)));
    close("ly0", pattern(W, H, false), webp(LY0), 16);
  }

  // ---- Document integration ----

  @Test(timeout = 60000)
  public void picturesRoundTripThroughDocxNativeAndPdf() throws Exception {
    Map<String, byte[]> inputs = new LinkedHashMap<>();
    inputs.put("image/gif", gif(W, H, false, 3, false));
    inputs.put("image/bmp", bmp(24, 0, false, W, H, 40));
    inputs.put("image/tiff", tiff("rgba", 5, false, 0, false));
    inputs.put("image/webp", Base64.getDecoder().decode(LY1));
    var s = new Session(Document.empty());
    s.replace("Pictures");
    for (var e : inputs.entrySet()) {
      int[] shape = Picture.shape(e.getValue());
      assertEquals(W, shape[0]);
      assertEquals(H, shape[1]);
      s.insertPicture(e.getValue(), 74, 58, e.getKey());
    }
    List<Picture> pictures = new ArrayList<>();
    for (Block b : s.document().blocks) if (b instanceof Picture) pictures.add((Picture) b);
    assertEquals(4, pictures.size());
    int i = 0;
    for (var e : inputs.entrySet()) {
      var p = pictures.get(i++);
      assertEquals("image/png", p.mime);
      assertEquals(e.getKey(), p.originalMime());
      assertArrayEquals(e.getValue(), p.originalBytes());
      assertEquals(W, p.pixelWidth);
      assertArrayEquals(PictureCodecs.decode(e.getValue()).argb, decodePng(p.bytes()));
      var resized = p.resize(30, 20, "x", "center");
      assertArrayEquals(e.getValue(), resized.originalBytes());
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
    assertEquals(new TreeSet<>(Arrays.asList("bmp", "gif", "tiff", "webp")), media);
    var opened = Docx.read(new ByteArrayInputStream(out.toByteArray()));
    assertTrue(opened.warnings.toString(), opened.warnings.isEmpty());
    i = 0;
    for (Block b : opened.document.blocks)
      if (b instanceof Picture) {
        var p = (Picture) b;
        assertArrayEquals(pictures.get(i).originalBytes(), p.originalBytes());
        assertArrayEquals(pictures.get(i).bytes(), p.bytes());
        i++;
      }
    assertEquals(4, i);
    var pdf = new String(PdfExporter.write(s.document()), "ISO-8859-1");
    assertTrue(pdf.contains("/Subtype /Image"));
    assertTrue(pdf.contains("/SMask"));
  }

  /** Minimal PNG reader for the encoder's own output (8-bit RGB/RGBA, filter 0). */
  static int[] decodePng(byte[] png) throws Exception {
    var in = new DataInputStream(new ByteArrayInputStream(png));
    in.readLong();
    int w = 0, h = 0, type = 0;
    var idat = new ByteArrayOutputStream();
    while (true) {
      int n = in.readInt();
      byte[] t = new byte[4];
      in.readFully(t);
      byte[] data = new byte[n];
      in.readFully(data);
      in.readInt();
      String tag = new String(t, "ISO-8859-1");
      if (tag.equals("IHDR")) {
        w = new DataInputStream(new ByteArrayInputStream(data)).readInt();
        h = new DataInputStream(new ByteArrayInputStream(data, 4, 4)).readInt();
        type = data[9];
      } else if (tag.equals("IDAT")) idat.writeBytes(data);
      else if (tag.equals("IEND")) break;
    }
    byte[] raw = new InflaterInputStream(new ByteArrayInputStream(idat.toByteArray())).readAllBytes();
    int bpp = type == 6 ? 4 : 3, at = 0;
    int[] out = new int[w * h];
    for (int y = 0; y < h; y++) {
      assertEquals(0, raw[at++]);
      for (int x = 0; x < w; x++, at += bpp)
        out[y * w + x] = (bpp == 4 ? (raw[at + 3] & 255) << 24 : 0xff000000)
            | (raw[at] & 255) << 16 | (raw[at + 1] & 255) << 8 | raw[at + 2] & 255;
    }
    return out;
  }

  // ---- Hostile input ----

  static List<byte[]> fixtures() throws Exception {
    List<byte[]> all = new ArrayList<>();
    all.add(gif(W, H, true, 2, false));
    all.add(bmp(8, 1, false, W, H, 40));
    all.add(bmp(4, 2, false, W, H, 40));
    all.add(bmp(32, 3, false, W, H, 108));
    all.add(tiff("rgb", 5, false, 0, true));
    all.add(tiff("rgba", 8, true, 16, false));
    all.add(tiff("palette8", 32773, false, 0, false));
    for (String s : new String[] {LL0, LL1, LLP, LY0, LY1, LYF})
      all.add(Base64.getDecoder().decode(s));
    return all;
  }

  @Test(timeout = 120000)
  public void truncatedAndCorruptInputFailsWithIOException() throws Exception {
    for (byte[] f : fixtures()) {
      String label = PictureCodecs.mime(f);
      try {
        PictureCodecs.decode(Arrays.copyOf(f, f.length / 2));
        fail(label + " truncated to half decoded");
      } catch (IOException expected) {
        assertNotNull(expected.getMessage());
      }
      for (int n = 12; n < f.length; n += Math.max(1, f.length / 60)) decodeOrIOException(Arrays.copyOf(f, n));
      var random = new Random(f.length);
      for (int k = 0; k < 150; k++) {
        byte[] g = f.clone();
        for (int j = random.nextInt(4); j >= 0; j--) g[12 + random.nextInt(g.length - 12)] = (byte) random.nextInt();
        decodeOrIOException(g);
      }
      byte[] garbage = new byte[400];
      random.nextBytes(garbage);
      System.arraycopy(f, 0, garbage, 0, 12);
      decodeOrIOException(garbage);
    }
    try {
      new Picture("p", Arrays.copyOf(fixtures().get(0), 40), 10, 10, "", "left");
      fail("truncated GIF accepted");
    } catch (IllegalArgumentException expected) {
      assertNotNull(expected.getMessage());
    }
  }

  static void decodeOrIOException(byte[] b) {
    try {
      PictureCodecs.decode(b);
    } catch (IOException expected) {
      // Clear failure is the contract for malformed input.
    }
  }

  @Test(timeout = 20000)
  public void rejectsOversizedPictures() throws Exception {
    byte[] huge = bmp(24, 0, false, 1, 1, 40);
    huge[18] = 0;
    huge[19] = 0x10;
    huge[22] = 0;
    huge[23] = 0x10;
    try {
      PictureCodecs.decode(huge);
      fail("4096x4096 accepted");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("Truncated") || expected.getMessage().contains("16 megapixels"));
    }
    huge[20] = 1;
    try {
      PictureCodecs.decode(huge);
      fail("over 16 megapixels accepted");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("16 megapixels"));
    }
  }

  private static final String LL0 =
      "UklGRsABAABXRUJQVlA4TLMBAAAvJAAHALkyRPQ/FgKBJH/iDVYofvQ/grXIjiRJkaz4Pf1F7p4+FIDRH+PCwTBA" +
      "EQCAgBPOtm3btm2bs22bych7ZUzAdQEX8gQggAt5FQR8ToHN0jyHYJD4bB706XYkaDGISTZPE7w4bP9BehNrN2K1" +
      "GRypWl3ZRWYxedNgSfcUuyjNw4rdNCgyNoaxENjB9NpfY2hSN4ZHzhY2v8Zr/PAKIZFjXG8EH+skgsQgKKlD2DKH" +
      "QMn+xvw+SQgnUXiQRYQis5jt4hi+JgZRpIIgZYTQZQ2Rbkyv4zW+4i1ZXMuyMc9rq9Gmj2jSQ9SviGqySxBhJY8o" +
      "XocsEpw/hCO3RCVvk+kRf5/xgXC/EKBs8CavAlpBUImaHzIktBS5kWNiejzFGO0nUf7gf8ZEmD47+TYEljjXyfOR" +
      "Om+/OGSW6LJKbNklvpwKZLIcZLLsMiTGbCP3/smf2yytfaRrvE6V6B7cfCOyLCPPwtsI+osxjIyMkWbVTQo850jI" +
      "r8T/YwM/UzdGk30fGZMsceRaRFkNIBj5t4Kws+g6PQGmaPzInahz6C0rQjzNYmw/POdadDn3kmOHPwAA";
  private static final String LL1 =
      "UklGRgoCAABXRUJQVlA4TP4BAAAvJAAHELkyRPQ/FgKBJH/iDVYofvQ/grXIjmTbtZr5Jf+Qr3noRYCH81renSv3" +
      "NAyQJck2bd2IdW3btm3bfrZt28bIGL+vVNi2bZtigE5NQBBTuGd5A27AOQbWWYxA7yhoizHdVnDW0g9jupVQIHlr" +
      "GIGE7LSDJNkZAoE1yaZTQicBtr8jI8a03HUvXjZDQ7owppOO6NiZOssRamTYCUeMrEckflZDMmYmWUZgR3B+W73z" +
      "wzI80rsmRIac2bEZkURZXl3GJCzkGO42QvdtJQrJgZBIL2OKmMxBsMi+jfWnFEEkksWYz7II4ZBZWDsSsIhIciAG" +
      "6WCEmExA+GQNorWxepcoywsjslKFO1lmmWf7do5YyyjEIiMQswtisnIkCRFJHWJwIbACZRGh8Z1IyC1iIm9WVv/g" +
      "RzoIrAQKIFLvCZJs4K+8EpQ9CEmyGBO/M47wpIngyDHi8nICRqz9J0afiVYmEaWM4pBvFsFJHnep45peRmR95Xdm" +
      "EZusIj7ZRWJySjBW9o4QSxVHxhGnbEPu+Sl/PGTJvmuGjUC4SxexeXb/kehkGfJMZDZC9h5jRiATLKOVVe7TQFxy" +
      "RNguJO7zBr6kdwWCWGSfayaRKhKQa6KSVQGhkH/2jCliWeQuIwQpTZbX3BGzHPI3K4Rpz5j2xuezt1wTm5wTkxzz" +
      "+zs=";
  private static final String LLP =
      "UklGRloAAABXRUJQVlA4TE4AAAAvJAAHEBcgEEjyJxphRLFgAjB/1d4EAkkk+2ttIiBJpkvLC3tVg6K2jaS5FkKp" +
      "7QVhUAyUPgfoAthnRP9/gFBXczQ4JUeDs64aON/UPwA=";
  private static final String LY0 =
      "UklGRvQCAABXRUJQVlA4IOgCAAAQEQCdASolAB0APjEWiUKiISEVVQAgAwS2AE6ZQjgbxD8Vf2Z+ASgPzz7cZJfz" +
      "bfp/496Xf7d7APEU/UTrQeY79gP2A93T/D/2DVGPQA/VX0qv95/gPg6/XL9RPZn/894BfcPAvwL+ity5omVfDOfF" +
      "89VVm7meS7CJDii2mdorjGW/Cll2JBV+uDrtOeIF3pkgAP7//rKQ3/Wqs0sN6xpWok5eXfwspS2Wp5JUGLxsGt+G" +
      "nad20eaEMJeYoCO2YA39wET8thoFfX+7N77/71y4F0NLLD4UbOe8pQP9uKY2jzmfB1K4ztcA1PewAlw+IJnZ2DTB" +
      "Kjz0wfi9P/mp/tVaukrbQrpTp2udXGMig5Dt7DXwlFrbe68kxPfUinO7KZ5oFkuMZCJ226CXIwMJzNuczbre1KYN" +
      "CmwOOid6bR4/P94/o4Ga0YU//G6OUAdXrLUgqiv3y5Cyln/jMjz1379Twgs1QWsSUPrR3n5JWwdNcKhatccfEtCj" +
      "mm2lUE2NrVvy3ypCB0ylVnFBy0nw2ot7Bx+ffeHtArhTQixRjwMCdE8NZ4yB+suH2AaWVHa7zY38NdS4aR35sDx/" +
      "kMKBN7bue2mppbmJwf2A3gTl9Geyi8uaDMViw06e5K6oc01P54YgD29qkF1YsE/WRUwqk788rzlxetfzlLui4n56" +
      "9aMKIhYxZc5A49H9AGXWHqP65aQgBzFb5H6Nd+CUzAs0hJCHgIaklz0LE1Rv+4y7xw2NYmzudMYpFnJ/mFNo2tnB" +
      "HwM40WfPniVwQtiTrxNwbeyh9mDC2nvxRXZzQqmp60BkJvWo60I0pj4uIGON0fBm9tvzlxl+VtkUiZt/vdYXo1fc" +
      "kp7D39Q86IPoTBauPaBp4ROmA0LOAlxH6G8+/lmtn4R5wHdpBfSWftK/MINeSED2EXW6DGMPZ+nRfeZ/8AXWaVzV" +
      "xOe6XI8AEijUllcXT3Pd157P0OizFnv2nAmqL5oBr2vYvKCUAAA=";
  private static final String LY1 =
      "UklGRigDAABXRUJQVlA4WAoAAAAQAAAAJAAAHAAAQUxQSBIAAAABuYzofxiEBAnN/99NEROQZhdWUDgg8AIAABAR" +
      "AJ0BKiUAHQA+MRKIQqIhIRgNVAAgAwS2AE6ZQjgbzD8O/2Z+ASgPyT7pZIHzhfqv5h6IvUB4hm4U/Yz9Sfd7/TP2" +
      "AfpL/gPVV6xb0AP1G9Jj9nPg5/Zb9iPZu/8N4BfcPAvwL+ity5omVfDOfF89VVq7meS7CJDai2mdorka1hxRd2JB" +
      "V+uDrobvTc+kyEAA/v/+spDf9aqzSw3rGlaiTl5d/CylLZanklQYvGwa34adp3bR5oQwl5igI7ZgDf3ARPy2GgV9" +
      "0NAP/f/VtFglsBvuFbZMXgsrPCrNNLVE8n/Srjtb9PzVnnvH8QYay89ddcZjVXZ+L0/+an+1Vq6SttCulOna51cY" +
      "yKDkO3sNfCUWtt7ryTE99SKc7spnmgWS4xkInbboJcjAwnM25zNut7Upg0KbA46J7e23x755B2OrvVXIv/IDz6Cw" +
      "SivZj/0+TbFqKHt6NWs45IhWvK2Vm9ml/qE6DMzrplfx3wBg6Sh6SziZ1OZfj6015FcIw3lXWK4GKdJYiRbhWSDW" +
      "PDai3nG1SSmvy9oYQw9JCvp6+w5y3+oBi73HjDMD9C3Krcd3XoESGLRJoiplYivHNPE7oTi1QXR0iRNWCBmYnB/Y" +
      "DeBOX0Z7KLy5oMxWLDTp7krqhzTU/nhiAPb2qQXViwT9ZFTCqTvzyvOXF61/OUwLyogrp+/hLQLMtPATe6Vbm85C" +
      "Pqzu+GGN/CM76ROFLvyNvvGlmxusG7lWfa70lQwgV5zL6AkucOAksEH2VzxGhoptG1s4I+BnGiz588SuCFsSdeJu" +
      "Db2UPswYW09+KK7OaFU1PWgMhN61HWhGlMfFxAxxuj4M0H3C5rMJvawkLesOx42+H42D26jEzoV0qXIHCyxotvUK" +
      "TDW2lHrEo727HwvMMYadDHoLAcjnlCor4+wdAV8gWJFzRw+tN/Dd1Prk3X6r4tlNsfu03r5fvkpx8PBzwCqNNqq8" +
      "VfbLTz2mVub+aL3SMiMt9Y8w4/3wAAAA";
  private static final String LYF =
      "UklGRloBAABXRUJQVlA4IE4BAADQCQCdASolAB0APu1qr08ppiSiKqoBNh2JbACdMoR7X7Xxy3FDxVwIMdt4Od00" +
      "xr0APLA9ksJoS8Nw2nJUPh5Fh9Rw2PAOC033bfEkWVbwmAxuJBk66wAA/v0feodCcg4eC5GgqvAI2ifAtMXrUz9z" +
      "wM9Mhvlevi+axKKiJd8zsdj/o1gNB5vMv35g0ldir5fQRHH21NkUlnxYGHQFbHqGcKXEnSaA45IHQGZ/MW+7Gyfm" +
      "S4KXtkANvQvW/KI+Qi4Mhrir+A+HfDiBE3k5KQpfpIVOwQ1ffeFtkbwx9a6ZyRaT4DfSANFpzABNGOw5yUznxENu" +
      "RJ2efSd37qU2y5jqeoFE+IVNQkvdgJS9LTB0o9RVgQxnhRBl+cA2UQ+QU0xCBEBzziu0oqvdxsh2aD5Aumk9PhIz" +
      "Juzf+pR4NXX/N5UC6RbSpn22/QLDvoAA";
  private static final String LYS =
      "UklGRuABAABXRUJQVlA4INQBAADwCwCdASolAB0APnE4nE4kkpEVVQBIBwABOmWEdB9J4zhwOqh6gPEi6THid/sz" +
      "7uv87yQD9QPSj/bv4Qf2G9ICsAsU7v+ZXdtElTgAzDVo/tZr1YehPXKoqt/cTDp19ZclxVJyF99gAAD+/e9SuB84" +
      "Oer3r2x1foajxQCrChjb/hEXXDJqPkukXsQrOVAGNWixS/POdYf0L8GlQJ6fOkJYZYvbPHkLvPhsWJC1TzVUoDIo" +
      "0V4T7q8B6ti6oTgQyTouVxbZeom2rQeoFV0B/CtUqbnxOGWJc7hF1d3Gsw/iQb1QSxzfRRnqItUEv4AxHxfKFqJU" +
      "CM48aB1NMnR9m3A/gdOalLkD9PbwuwobgJtw5XxcRMi3IQU9nNZzF3IpAhuhRS8w9P+UQkMdeyDxBBCtTJguBoBG" +
      "m/71eaxHZlKomXjimg8yNNsvVESfEHP3nnGyA6qNxihajf8RF8NqIBM/2CyrJjhm/TP1okwYmIeybGOX5Nj8BKYa" +
      "VDXOM+VUS6oJ/BTbTbiyWoczucCVBDPu+peUoY1Z+2P8cLPiIPr+/lzMQPV00aKIsvCMCo7+N5Dvl4qgilGgxPPD" +
      "7k4EcjEHKej04MVXVFtiLzk3MMJE/lgAAAA=";
}
