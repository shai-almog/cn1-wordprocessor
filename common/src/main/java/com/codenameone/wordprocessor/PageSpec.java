package com.codenameone.wordprocessor;

public final class PageSpec {
  public static final PageSpec A4 = new PageSpec(595.28f, 841.89f, 54, 54, 54, 54);
  public final float width, height, top, right, bottom, left;

  public PageSpec(float w, float h, float t, float r, float b, float l) {
    if (!ok(w)
        || !ok(h)
        || !ok(t)
        || !ok(r)
        || !ok(b)
        || !ok(l)
        || w < 72
        || h < 72
        || t + b >= h
        || l + r >= w) throw new IllegalArgumentException("Invalid page geometry");
    width = w;
    height = h;
    top = t;
    right = r;
    bottom = b;
    left = l;
  }

  private static boolean ok(float x) {
    return !Float.isNaN(x) && !Float.isInfinite(x) && x >= 0;
  }

  public float contentWidth() {
    return width - left - right;
  }
}
