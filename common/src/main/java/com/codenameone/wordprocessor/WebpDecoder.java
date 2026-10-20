package com.codenameone.wordprocessor;

import java.io.IOException;

/** WebP container, lossless (VP8L) and alpha (ALPH) decoding; lossy frames go to Vp8Decoder. */
final class WebpDecoder {
  private static final int[] ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
  private static final int[] DISTANCE = {
    0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0, 1, 3,
    -1, 3, 3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1, 3, 3, -3, 3,
    2, 4, -2, 4, 4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5, 5, 1, -5, 1, 2, 5,
    -2, 5, 5, 2, -5, 2, 4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0, 1, 6, -1, 6, 6, 1, -6, 1,
    2, 6, -2, 6, 6, 2, -6, 2, 4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6, 6, 3, -6, 3, 0, 7, 7, 0, 1, 7,
    -1, 7, 5, 5, -5, 5, 7, 1, -7, 1, 4, 6, -4, 6, 6, 4, -6, 4, 2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7,
    7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5, 8, 0, 4, 7, -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6,
    8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7, -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7
  };

  private WebpDecoder() {}

  static PictureCodecs.Raster decode(byte[] b) throws IOException {
    long riff = PictureCodecs.s32le(b, 4) & 0xffffffffL;
    int end = (int) Math.min(b.length, riff + 8);
    int at = 12, alphaAt = -1, alphaLen = 0;
    while (at + 8 <= end) {
      String id = new String(b, at, 4, "ISO-8859-1");
      long len = PictureCodecs.s32le(b, at + 4) & 0xffffffffL;
      int data = at + 8;
      if (data + len > end) throw new IOException("Truncated WebP chunk");
      if ("VP8L".equals(id)) {
        var r = new WebpDecoder.Bits(b, data, (int) len);
        if (r.read(8) != 0x2f) throw new IOException("Invalid WebP lossless signature");
        int w = r.read(14) + 1, h = r.read(14) + 1;
        r.read(1);
        if (r.read(3) != 0) throw new IOException("Unsupported WebP lossless version");
        PictureCodecs.checkSize(w, h);
        return new PictureCodecs.Raster(w, h, lossless(r, w, h));
      }
      if ("VP8 ".equals(id)) {
        var r = Vp8Decoder.decode(b, data, (int) len);
        if (alphaAt >= 0) {
          byte[] alpha = alpha(b, alphaAt, alphaLen, r.width, r.height);
          for (int i = 0; i < r.argb.length; i++) r.argb[i] = (r.argb[i] & 0xffffff) | (alpha[i] & 255) << 24;
        }
        return r;
      }
      if ("ALPH".equals(id)) {
        alphaAt = data;
        alphaLen = (int) len;
      }
      if ("ANIM".equals(id) || "ANMF".equals(id)) throw new IOException("Animated WebP is not supported");
      at = data + (int) len + (int) (len & 1);
    }
    throw new IOException("WebP has no image data");
  }

  private static byte[] alpha(byte[] b, int at, int len, int w, int h) throws IOException {
    if (len < 1) throw new IOException("Invalid WebP alpha");
    int head = b[at] & 255, method = head & 3, filter = (head >> 2) & 3;
    byte[] a = new byte[w * h];
    if (method == 0) {
      if (len - 1 < a.length) throw new IOException("Truncated WebP alpha");
      System.arraycopy(b, at + 1, a, 0, a.length);
    } else if (method == 1) {
      int[] px = lossless(new Bits(b, at + 1, len - 1), w, h);
      for (int i = 0; i < a.length; i++) a[i] = (byte) (px[i] >> 8);
    } else throw new IOException("Unsupported WebP alpha compression");
    if (filter != 0)
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          int i = y * w + x, pred;
          if (x == 0 && y == 0) pred = 0;
          else if (y == 0) pred = a[i - 1] & 255;
          else if (x == 0) pred = a[i - w] & 255;
          else if (filter == 1) pred = a[i - 1] & 255;
          else if (filter == 2) pred = a[i - w] & 255;
          else pred = Math.max(0, Math.min(255, (a[i - 1] & 255) + (a[i - w] & 255) - (a[i - w - 1] & 255)));
          a[i] = (byte) (a[i] + pred);
        }
    return a;
  }

  static final class Bits {
    private final byte[] b;
    private int pos;
    private final int end;
    private long buf;
    private int count;

    Bits(byte[] b, int at, int len) {
      this.b = b;
      pos = at;
      end = at + len;
    }

    int read(int n) throws IOException {
      if (n == 0) return 0;
      while (count < n) {
        if (pos >= end) throw new IOException("Truncated WebP data");
        buf |= (long) (b[pos++] & 255) << count;
        count += 8;
      }
      int v = (int) (buf & ((1L << n) - 1));
      buf >>>= n;
      count -= n;
      return v;
    }
  }

  /** Canonical prefix code; decoded one bit at a time. */
  private static final class Code {
    int single = -1;
    int[] counts = new int[16], symbols;

    Code(int[] lengths) throws IOException {
      int used = 0, last = 0;
      for (int i = 0; i < lengths.length; i++)
        if (lengths[i] > 0) {
          counts[lengths[i]]++;
          used++;
          last = i;
        }
      if (used == 0) throw new IOException("Invalid WebP prefix code");
      if (used == 1) {
        single = last;
        return;
      }
      int left = 1;
      for (int l = 1; l < 16; l++) {
        left = (left << 1) - counts[l];
        if (left < 0) throw new IOException("Invalid WebP prefix code");
      }
      if (left != 0) throw new IOException("Incomplete WebP prefix code");
      int[] offs = new int[16];
      for (int l = 1; l < 15; l++) offs[l + 1] = offs[l] + counts[l];
      symbols = new int[used];
      for (int i = 0; i < lengths.length; i++) if (lengths[i] > 0) symbols[offs[lengths[i]]++] = i;
    }

    int read(Bits r) throws IOException {
      if (single >= 0) return single;
      int code = 0, first = 0, index = 0;
      for (int l = 1; l < 16; l++) {
        code |= r.read(1);
        int n = counts[l];
        if (code - first < n) return symbols[index + code - first];
        index += n;
        first = (first + n) << 1;
        code <<= 1;
      }
      throw new IOException("Invalid WebP prefix code");
    }
  }

  private static Code readCode(Bits r, int alphabet) throws IOException {
    int[] lengths = new int[alphabet];
    if (r.read(1) == 1) {
      int n = r.read(1) + 1, s0 = r.read(r.read(1) == 1 ? 8 : 1);
      if (s0 >= alphabet) throw new IOException("Invalid WebP prefix code");
      lengths[s0] = 1;
      if (n == 2) {
        int s1 = r.read(8);
        if (s1 >= alphabet) throw new IOException("Invalid WebP prefix code");
        lengths[s1] = 1;
      }
      var c = new Code(lengths);
      return c;
    }
    int[] clen = new int[19];
    int n = 4 + r.read(4);
    for (int i = 0; i < n; i++) clen[ORDER[i]] = r.read(3);
    var lenCode = new Code(clen);
    int max = alphabet;
    if (r.read(1) == 1) {
      max = 2 + r.read(2 + 2 * r.read(3));
      if (max > alphabet) throw new IOException("Invalid WebP prefix code");
    }
    int prev = 8, sym = 0;
    while (sym < alphabet && max-- > 0) {
      int c = lenCode.read(r);
      if (c < 16) {
        lengths[sym++] = c;
        if (c != 0) prev = c;
      } else {
        int repeat = c == 16 ? 3 + r.read(2) : c == 17 ? 3 + r.read(3) : 11 + r.read(7);
        if (sym + repeat > alphabet) throw new IOException("Invalid WebP prefix code");
        for (int i = 0; i < repeat; i++) lengths[sym++] = c == 16 ? prev : 0;
      }
    }
    return new Code(lengths);
  }

  private static int sub(int size, int bits) {
    return (size + (1 << bits) - 1) >> bits;
  }

  static int[] lossless(Bits r, int w, int h) throws IOException {
    int[] types = new int[4], bitsOf = new int[4], widths = new int[4];
    int[][] data = new int[4][];
    int count = 0, xsize = w, seen = 0;
    while (r.read(1) == 1) {
      int type = r.read(2);
      if ((seen & 1 << type) != 0) throw new IOException("Repeated WebP transform");
      seen |= 1 << type;
      types[count] = type;
      widths[count] = xsize;
      if (type == 0 || type == 1) {
        int bits = r.read(3) + 2;
        bitsOf[count] = bits;
        data[count] = image(r, sub(xsize, bits), sub(h, bits), false);
      } else if (type == 3) {
        int n = r.read(8) + 1;
        int[] table = image(r, n, 1, false);
        for (int i = 1; i < n; i++) table[i] = add(table[i], table[i - 1]);
        data[count] = table;
        int bits = n <= 2 ? 3 : n <= 4 ? 2 : n <= 16 ? 1 : 0;
        bitsOf[count] = bits;
        xsize = sub(xsize, bits);
      }
      count++;
    }
    int[] px = image(r, xsize, h, true);
    for (int t = count - 1; t >= 0; t--) {
      int tw = widths[t];
      switch (types[t]) {
        case 0: predict(px, tw, h, bitsOf[t], data[t]); break;
        case 1: crossColor(px, tw, h, bitsOf[t], data[t]); break;
        case 2:
          for (int i = 0; i < px.length; i++) {
            int p = px[i], g = (p >> 8) & 255;
            px[i] = p & 0xff00ff00 | ((((p >> 16) & 255) + g) & 255) << 16 | ((p & 255) + g) & 255;
          }
          break;
        default: px = colorIndex(px, tw, h, bitsOf[t], data[t]);
      }
    }
    return px;
  }

  private static int[] colorIndex(int[] px, int w, int h, int bits, int[] table) {
    int[] out = new int[w * h];
    int pw = sub(w, bits), per = 1 << bits, depth = 8 >> bits, mask = (1 << depth) - 1;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int g = (px[y * pw + (x >> bits)] >> 8) & 255;
        int idx = bits == 0 ? g : (g >> ((x & (per - 1)) * depth)) & mask;
        out[y * w + x] = idx < table.length ? table[idx] : 0;
      }
    return out;
  }

  private static int add(int a, int b) {
    return ((a & 0xff00ff00) + (b & 0xff00ff00)) & 0xff00ff00 | ((a & 0xff00ff) + (b & 0xff00ff)) & 0xff00ff;
  }

  private static int avg(int a, int b) {
    return (((a ^ b) & 0xfefefefe) >>> 1) + (a & b);
  }

  private static int clamp(int v) {
    return v < 0 ? 0 : v > 255 ? 255 : v;
  }

  private static void predict(int[] px, int w, int h, int bits, int[] modes) {
    int bw = sub(w, bits);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int i = y * w + x, pred;
        if (y == 0) pred = x == 0 ? 0xff000000 : px[i - 1];
        else if (x == 0) pred = px[i - w];
        else {
          int L = px[i - 1], T = px[i - w], TL = px[i - w - 1], TR = px[i - w + 1];
          switch ((modes[(y >> bits) * bw + (x >> bits)] >> 8) & 15) {
            case 0: pred = 0xff000000; break;
            case 1: pred = L; break;
            case 2: pred = T; break;
            case 3: pred = TR; break;
            case 4: pred = TL; break;
            case 5: pred = avg(avg(L, TR), T); break;
            case 6: pred = avg(L, TL); break;
            case 7: pred = avg(L, T); break;
            case 8: pred = avg(TL, T); break;
            case 9: pred = avg(T, TR); break;
            case 10: pred = avg(avg(L, TL), avg(T, TR)); break;
            case 11: {
              int pl = 0, pt = 0;
              for (int s = 0; s < 32; s += 8) {
                int t = (T >> s) & 255, tl = (TL >> s) & 255, l = (L >> s) & 255;
                pl += Math.abs(t - tl);
                pt += Math.abs(l - tl);
              }
              pred = pl < pt ? L : T;
              break;
            }
            case 12: {
              pred = 0;
              for (int s = 0; s < 32; s += 8)
                pred |= clamp(((L >> s) & 255) + ((T >> s) & 255) - ((TL >> s) & 255)) << s;
              break;
            }
            case 13: {
              int a = avg(L, T);
              pred = 0;
              for (int s = 0; s < 32; s += 8) {
                int c = (a >> s) & 255;
                pred |= clamp(c + (c - ((TL >> s) & 255)) / 2) << s;
              }
              break;
            }
            default: pred = 0xff000000;
          }
        }
        px[i] = add(px[i], pred);
      }
  }

  private static void crossColor(int[] px, int w, int h, int bits, int[] data) {
    int bw = sub(w, bits);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int m = data[(y >> bits) * bw + (x >> bits)], i = y * w + x, p = px[i];
        int g2r = (byte) m, g2b = (byte) (m >> 8), r2b = (byte) (m >> 16);
        int g = (byte) (p >> 8), r = (p >> 16) & 255, b = p & 255;
        r = (r + (g2r * g >> 5)) & 255;
        b = (b + (g2b * g >> 5)) & 255;
        b = (b + (r2b * (byte) r >> 5)) & 255;
        px[i] = p & 0xff00ff00 | r << 16 | b;
      }
  }

  private static int[] image(Bits r, int w, int h, boolean main) throws IOException {
    PictureCodecs.checkSize(w, h);
    int cacheBits = 0;
    if (r.read(1) == 1) {
      cacheBits = r.read(4);
      if (cacheBits < 1 || cacheBits > 11) throw new IOException("Invalid WebP color cache");
    }
    int metaBits = 0, metaW = 0, groups = 1;
    int[] meta = null;
    if (main && r.read(1) == 1) {
      metaBits = r.read(3) + 2;
      metaW = sub(w, metaBits);
      meta = image(r, metaW, sub(h, metaBits), false);
      for (int i = 0; i < meta.length; i++) {
        meta[i] = (meta[i] >> 8) & 0xffff;
        groups = Math.max(groups, meta[i] + 1);
      }
    }
    if (groups > 65536) throw new IOException("Invalid WebP prefix groups");
    Code[][] codes = new Code[groups][];
    for (int g = 0; g < groups; g++)
      codes[g] = new Code[] {
        readCode(r, 280 + (cacheBits > 0 ? 1 << cacheBits : 0)), readCode(r, 256), readCode(r, 256),
        readCode(r, 256), readCode(r, 40)
      };
    int[] cache = cacheBits > 0 ? new int[1 << cacheBits] : null, px = new int[w * h];
    int pos = 0, total = w * h, last = 0;
    while (pos < total) {
      int x = pos % w, y = pos / w;
      Code[] c = codes[meta == null ? 0 : meta[(y >> metaBits) * metaW + (x >> metaBits)]];
      int s = c[0].read(r);
      if (s < 256) {
        int red = c[1].read(r), blue = c[2].read(r), alpha = c[3].read(r);
        px[pos++] = alpha << 24 | red << 16 | s << 8 | blue;
      } else if (s < 280) {
        int len = prefix(r, s - 256), dcode = prefix(r, c[4].read(r)), dist;
        if (dcode > 120) dist = dcode - 120;
        else {
          dist = DISTANCE[2 * dcode - 2] + DISTANCE[2 * dcode - 1] * w;
          if (dist < 1) dist = 1;
        }
        if (dist > pos || len > total - pos) throw new IOException("Invalid WebP back reference");
        for (int i = 0; i < len; i++, pos++) px[pos] = px[pos - dist];
      } else {
        if (cache == null) throw new IOException("Invalid WebP color cache index");
        px[pos++] = cache[s - 280];
      }
      if (cache != null)
        for (; last < pos; last++) cache[(0x1e35a7bd * px[last]) >>> (32 - cacheBits)] = px[last];
    }
    return px;
  }

  private static int prefix(Bits r, int sym) throws IOException {
    if (sym < 4) return sym + 1;
    int extra = (sym - 2) >> 1;
    if (extra > 30) throw new IOException("Invalid WebP length");
    return ((2 + (sym & 1)) << extra) + r.read(extra) + 1;
  }
}
