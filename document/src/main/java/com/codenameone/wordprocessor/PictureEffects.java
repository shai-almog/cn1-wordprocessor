package com.codenameone.wordprocessor;

/** Pure-Java artistic effects and background removal on ARGB pixels. */
public final class PictureEffects {
  private PictureEffects() {}

  /** Returns a new pixel array; the input is not modified. */
  public static int[] apply(int[] argb, int w, int h, Picture.Look look) {
    var px = argb.clone();
    if (w <= 0 || h <= 0 || px.length < w * h) return px;
    var e = look.effect;
    if ("negative".equals(e)) {
      for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xff000000) | (~px[i] & 0xffffff);
    } else if ("posterize".equals(e)) {
      for (int i = 0; i < px.length; i++) {
        int v = px[i];
        px[i] = (v & 0xff000000) | q(v >> 16) << 16 | q(v >> 8) << 8 | q(v);
      }
    } else if ("blur".equals(e)) {
      px = convolve(px, w, h, new int[] {1, 2, 1, 2, 4, 2, 1, 2, 1}, 16, 0);
    } else if ("sharpen".equals(e)) {
      px = convolve(px, w, h, new int[] {0, -1, 0, -1, 5, -1, 0, -1, 0}, 1, 0);
    } else if ("glowEdges".equals(e)) {
      px = edges(px, w, h, false);
    } else if ("pencilSketch".equals(e)) {
      px = edges(px, w, h, true);
    }
    if (look.backgroundTolerance >= 0) removeBackground(px, w, h, look.backgroundTolerance);
    return px;
  }

  private static int q(int c) {
    return ((c & 255) / 64) * 85;
  }

  private static int[] convolve(int[] src, int w, int h, int[] k, int div, int bias) {
    int[] out = new int[src.length];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int r = 0, g = 0, b = 0, i = 0;
        for (int dy = -1; dy <= 1; dy++)
          for (int dx = -1; dx <= 1; dx++, i++) {
            int xx = Math.max(0, Math.min(w - 1, x + dx)), yy = Math.max(0, Math.min(h - 1, y + dy));
            int v = src[yy * w + xx];
            r += (v >> 16 & 255) * k[i];
            g += (v >> 8 & 255) * k[i];
            b += (v & 255) * k[i];
          }
        out[y * w + x] =
            (src[y * w + x] & 0xff000000) | c(r / div + bias) << 16 | c(g / div + bias) << 8 | c(b / div + bias);
      }
    return out;
  }

  private static int c(int v) {
    return v < 0 ? 0 : v > 255 ? 255 : v;
  }

  private static int luma(int v) {
    return ((v >> 16 & 255) * 299 + (v >> 8 & 255) * 587 + (v & 255) * 114) / 1000;
  }

  /** Sobel edge magnitude: dark lines on white (sketch) or bright lines on black (glow). */
  private static int[] edges(int[] src, int w, int h, boolean sketch) {
    int[] out = new int[src.length];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int[] n = new int[9];
        int i = 0;
        for (int dy = -1; dy <= 1; dy++)
          for (int dx = -1; dx <= 1; dx++, i++)
            n[i] = luma(src[Math.max(0, Math.min(h - 1, y + dy)) * w + Math.max(0, Math.min(w - 1, x + dx))]);
        int gx = -n[0] + n[2] - 2 * n[3] + 2 * n[5] - n[6] + n[8];
        int gy = -n[0] - 2 * n[1] - n[2] + n[6] + 2 * n[7] + n[8];
        int m = c(Math.abs(gx) + Math.abs(gy));
        int l = sketch ? 255 - m : m;
        out[y * w + x] = (src[y * w + x] & 0xff000000) | l << 16 | l << 8 | l;
      }
    return out;
  }

  /** Flood fill from every border pixel over colors near the average border color. */
  private static void removeBackground(int[] px, int w, int h, int tolerance) {
    long r = 0, g = 0, b = 0;
    int count = 0;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
          int v = px[y * w + x];
          r += v >> 16 & 255;
          g += v >> 8 & 255;
          b += v & 255;
          count++;
        }
    final int ar = (int) (r / count), ag = (int) (g / count), ab = (int) (b / count);
    // Tolerance 100 spans the whole RGB cube diagonal.
    final long limit = (long) (tolerance * 4.42f) * (long) (tolerance * 4.42f);
    boolean[] seen = new boolean[w * h];
    int[] stack = new int[w * h];
    int sp = 0;
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
          seen[y * w + x] = true;
          stack[sp++] = y * w + x;
        }
    // Border pixels are only seeds when they match; others are un-marked as we pop.
    int[] queue = new int[w * h];
    int qn = 0;
    while (sp > 0) {
      int i = stack[--sp];
      int v = px[i];
      long dr = (v >> 16 & 255) - ar, dg = (v >> 8 & 255) - ag, db = (v & 255) - ab;
      if (dr * dr + dg * dg + db * db > limit) continue;
      queue[qn++] = i;
      px[i] = v & 0xffffff;
      int x = i % w, y = i / w;
      if (x > 0 && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1; }
      if (x < w - 1 && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1; }
      if (y > 0 && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w; }
      if (y < h - 1 && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w; }
    }
  }
}
