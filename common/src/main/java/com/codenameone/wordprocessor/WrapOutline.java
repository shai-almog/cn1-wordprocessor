package com.codenameone.wordprocessor;

/**
 * Wrap polygons as Wordcraft's doc::wrap: points in a space where 21600 spans the object's width
 * or height, stored as "edited;x,y x,y ...".
 */
final class WrapOutline {
  private WrapOutline() {}

  /** The polygon in unit coordinates as x0,y0,x1,y1,..., or null without one. */
  static float[] parse(String polygon) {
    if (polygon == null || polygon.isEmpty()) return null;
    String[] pts = TextUtil.split(polygon.substring(polygon.indexOf(';') + 1), ' ');
    if (pts.length < 3) return null;
    float[] out = new float[pts.length * 2];
    try {
      for (int i = 0; i < pts.length; i++) {
        String[] xy = TextUtil.split(pts[i], ',');
        out[2 * i] = Integer.parseInt(xy[0].trim()) / 21600f;
        out[2 * i + 1] = Integer.parseInt(xy[1].trim()) / 21600f;
      }
    } catch (RuntimeException ex) {
      return null;
    }
    return out;
  }

  /**
   * The horizontal extent {min, max} the closed outline covers between unit heights a and b,
   * or null when it does not reach that band.
   */
  static float[] extent(float[] o, float a, float b) {
    float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
    int n = o.length / 2;
    for (int i = 0; i < n; i++) {
      float x0 = o[2 * i], y0 = o[2 * i + 1];
      float x1 = o[2 * ((i + 1) % n)], y1 = o[2 * ((i + 1) % n) + 1];
      float lo = Math.max(a, Math.min(y0, y1)), hi = Math.min(b, Math.max(y0, y1));
      if (lo > hi) continue;
      for (float yy : new float[] {lo, hi}) {
        float x = y1 == y0 ? x0 : x0 + (x1 - x0) * (yy - y0) / (y1 - y0);
        float x2 = y1 == y0 ? x1 : x;
        min = Math.min(min, Math.min(x, x2));
        max = Math.max(max, Math.max(x, x2));
      }
    }
    return min > max ? null : new float[] {min, max};
  }
}
