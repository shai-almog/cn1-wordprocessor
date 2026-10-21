package com.codenameone.wordprocessor;

import com.codename1.xml.Element;

/**
 * Freeform (a:custGeom) outlines as Wordcraft's docx freeform reader: each a:path scaled from its
 * w/h to the shape, moveTo starting a subpath, cubic and quadratic Beziers flattened, close
 * closing it, fill="none" leaving it open. Stored as "c:x,y x,y;o:x,y ..." in unit coordinates
 * (0..1 across the shape); "c" closed and filled, "o" open.
 */
final class Freeform {
  private Freeform() {}

  static final int STEPS = 16, MAX_POINTS = 4096;

  static String read(Element custGeom, float shapeW, float shapeH) {
    Element list = Xml.child(custGeom, "pathLst");
    if (list == null) return "";
    var out = new StringBuilder();
    int[] count = {0};
    for (Element path : Xml.children(list, null)) {
      if (!"path".equals(Xml.local(path))) continue;
      float pw = Xml.number(Xml.attr(path, "w", "0")), ph = Xml.number(Xml.attr(path, "h", "0"));
      if (!(pw > 0)) pw = shapeW;
      if (!(ph > 0)) ph = shapeH;
      if (!(pw > 0) || !(ph > 0)) continue;
      boolean open = "none".equals(Xml.attr(path, "fill", ""));
      var sub = new StringBuilder();
      float cx = 0, cy = 0;
      for (Element c : Xml.children(path, null)) {
        String n = Xml.local(c);
        java.util.List<Element> pts = new java.util.ArrayList<>();
        for (Element p : Xml.children(c, null)) if ("pt".equals(Xml.local(p))) pts.add(p);
        if ("moveTo".equals(n) && !pts.isEmpty()) {
          flush(out, sub, false, open);
          cx = x(pts.get(0), pw);
          cy = y(pts.get(0), ph);
          point(sub, cx, cy, count);
        } else if ("lnTo".equals(n) && !pts.isEmpty()) {
          cx = x(pts.get(0), pw);
          cy = y(pts.get(0), ph);
          point(sub, cx, cy, count);
        } else if ("cubicBezTo".equals(n) && pts.size() >= 3) {
          float x1 = x(pts.get(0), pw), y1 = y(pts.get(0), ph), x2 = x(pts.get(1), pw), y2 = y(pts.get(1), ph);
          float x3 = x(pts.get(2), pw), y3 = y(pts.get(2), ph);
          for (int i = 1; i <= STEPS; i++) {
            float t = i / (float) STEPS, u = 1 - t;
            point(sub, u * u * u * cx + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                u * u * u * cy + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3, count);
          }
          cx = x3;
          cy = y3;
        } else if ("quadBezTo".equals(n) && pts.size() >= 2) {
          float x1 = x(pts.get(0), pw), y1 = y(pts.get(0), ph), x2 = x(pts.get(1), pw), y2 = y(pts.get(1), ph);
          for (int i = 1; i <= STEPS; i++) {
            float t = i / (float) STEPS, u = 1 - t;
            point(sub, u * u * cx + 2 * u * t * x1 + t * t * x2, u * u * cy + 2 * u * t * y1 + t * t * y2, count);
          }
          cx = x2;
          cy = y2;
        } else if ("close".equals(n)) flush(out, sub, true, open);
        // arcTo is skipped, as in Wordcraft.
      }
      flush(out, sub, false, open);
    }
    return out.toString();
  }

  private static float x(Element pt, float w) {
    return Math.max(-4, Math.min(4, Xml.number(Xml.attr(pt, "x", "0")) / w));
  }

  private static float y(Element pt, float h) {
    return Math.max(-4, Math.min(4, Xml.number(Xml.attr(pt, "y", "0")) / h));
  }

  private static void point(StringBuilder sub, float x, float y, int[] count) {
    if (count[0]++ >= MAX_POINTS) return;
    if (sub.length() > 0) sub.append(' ');
    sub.append(round(x)).append(',').append(round(y));
  }

  private static String round(float v) {
    return String.valueOf(Math.round(v * 100000) / 100000f);
  }

  private static void flush(StringBuilder out, StringBuilder sub, boolean closed, boolean open) {
    if (sub.length() == 0) return;
    if (out.length() > 0) out.append(';');
    out.append(closed && !open ? "c:" : "o:").append(sub);
    sub.setLength(0);
  }

  /** The subpaths scaled to w x h: points per subpath, and whether each is closed. */
  static java.util.List<float[]> paths(String path, float w, float h, java.util.List<Boolean> closed) {
    var out = new java.util.ArrayList<float[]>();
    for (String sub : TextUtil.split(path, ';')) {
      if (sub.length() < 3 || sub.charAt(1) != ':') continue;
      String[] pts = TextUtil.split(sub.substring(2), ' ');
      float[] p = new float[pts.length * 2];
      try {
        for (int i = 0; i < pts.length; i++) {
          String[] xy = TextUtil.split(pts[i], ',');
          p[2 * i] = Float.parseFloat(xy[0]) * w;
          p[2 * i + 1] = Float.parseFloat(xy[1]) * h;
        }
      } catch (RuntimeException ex) {
        continue;
      }
      if (p.length < 4) continue;
      out.add(p);
      closed.add(sub.charAt(0) == 'c');
    }
    return out;
  }
}
