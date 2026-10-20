package com.codenameone.wordprocessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Pure-Java WMF and EMF rasterizer (no AWT), following the record subset Wordcraft's metafile crate
 * reads: placeable/standard WMF and EMF headers, window/viewport/map mode, world transforms, save and
 * restore, pens, brushes and stock objects, lines, polylines, polygons, poly-polygons, Beziers,
 * rectangles, rounded rectangles (drawn square), ellipses, arcs/chords/pies, EMF path brackets and
 * DIB bitmaps (StretchDIBits, DIBStretchBlt, DIBBitBlt, BitBlt/StretchBlt/AlphaBlend, pattern
 * fills). Text, regions, clipping, ROP modes other than plain copy and hatch/pattern brushes (painted
 * as their solid color) are not rendered. Unknown or malformed records are skipped; every count,
 * allocation and amount of fill work is bounded.
 */
final class MetafileDecoder {
  /** Longest output edge in pixels. */
  static final int MAX_EDGE = 2048;
  private static final int MAX_RECORDS = 200000;
  private static final int MAX_OBJECTS = 4096;
  private static final int MAX_STACK = 1024;
  private static final int MAX_POINTS = 2000000;
  private static final long MAX_WORK = 300000000L;
  private static final long MAX_BITMAP_PIXELS = 64000000L;
  private static final int SUB = 4;

  private MetafileDecoder() {}

  static boolean isWmf(byte[] b) {
    if (b == null || b.length < 18) return false;
    if (PictureCodecs.s32le(b, 0) == 0x9AC6CDD7 || (b[0] & 255) == 0xD7 && (b[1] & 255) == 0xCD
        && (b[2] & 255) == 0xC6 && (b[3] & 255) == 0x9A) return true;
    int type = PictureCodecs.u16le(b, 0), hs = PictureCodecs.u16le(b, 2), v = PictureCodecs.u16le(b, 4);
    return (type == 1 || type == 2) && hs == 9 && (v == 0x0300 || v == 0x0100);
  }

  static boolean isEmf(byte[] b) {
    return b != null && b.length >= 88 && PictureCodecs.s32le(b, 0) == 1
        && b[40] == ' ' && b[41] == 'E' && b[42] == 'M' && b[43] == 'F';
  }

  static PictureCodecs.Raster decode(byte[] b) throws IOException {
    if (isEmf(b)) return new MetafileDecoder.Canvas(b).emf();
    if (isWmf(b)) return new MetafileDecoder.Canvas(b).wmf();
    throw new IOException("Not a WMF or EMF picture");
  }

  /** One GDI object: kind 1 pen, 2 brush, 3 anything else; color is ARGB, 0 alpha when null. */
  private static final class Obj {
    final int kind, color;
    final double width;

    Obj(int kind, int color, double width) {
      this.kind = kind;
      this.color = color;
      this.width = width;
    }
  }

  private static final class State {
    double wox, woy, wex = 1, wey = 1, vox, voy, vex = 1, vey = 1;
    boolean windowSet, viewportSet;
    int mapMode = 1, fillMode = 1;
    double[] xf = {1, 0, 0, 1, 0, 0};
    Obj pen = new Obj(1, 0xff000000, 0), brush = new Obj(2, 0xffffffff, 0);
    double cx, cy;

    State copy() {
      State s = new State();
      s.wox = wox; s.woy = woy; s.wex = wex; s.wey = wey;
      s.vox = vox; s.voy = voy; s.vex = vex; s.vey = vey;
      s.windowSet = windowSet; s.viewportSet = viewportSet;
      s.mapMode = mapMode; s.fillMode = fillMode; s.xf = xf.clone();
      s.pen = pen; s.brush = brush; s.cx = cx; s.cy = cy;
      return s;
    }
  }

  private static final class Canvas {
    final byte[] d;
    int recEnd;
    int width, height;
    int[] px;
    State st = new State();
    final ArrayList<State> stack = new ArrayList<>();
    final Obj[] objects = new Obj[MAX_OBJECTS];
    boolean wmf;
    // Frame: WMF logical rectangle, or EMF device rectangle, mapped onto the pixel grid.
    double fx, fy, fw, fh;
    double pxPerMmX = 1, pxPerMmY = 1;
    long work, points, bitmapPixels;
    ArrayList<double[]> path;
    double[] figure;
    int figureLen;

    Canvas(byte[] d) {
      this.d = d;
    }

    // ---- bounded little-endian readers inside the current record ----

    int u16(int at) {
      if (at < 0 || at + 2 > recEnd) throw new IndexOutOfBoundsException();
      return (d[at] & 255) | (d[at + 1] & 255) << 8;
    }

    int s16(int at) {
      return (short) u16(at);
    }

    int s32(int at) {
      if (at < 0 || at + 4 > recEnd) throw new IndexOutOfBoundsException();
      return (d[at] & 255) | (d[at + 1] & 255) << 8 | (d[at + 2] & 255) << 16 | (d[at + 3] & 255) << 24;
    }

    float f32(int at) {
      return Float.intBitsToFloat(s32(at));
    }

    void allocate(double wPt, double hPt) throws IOException {
      if (!(wPt > 0) || !(hPt > 0) || wPt > 1e7 || hPt > 1e7) throw new IOException("Metafile has no usable size");
      double s = 2;
      double max = Math.max(wPt, hPt) * s;
      if (max > MAX_EDGE) s = MAX_EDGE / Math.max(wPt, hPt);
      width = Math.max(1, (int) Math.round(wPt * s));
      height = Math.max(1, (int) Math.round(hPt * s));
      PictureCodecs.checkSize(width, height);
      px = new int[width * height];
    }

    PictureCodecs.Raster done() {
      return new PictureCodecs.Raster(width, height, px);
    }

    // ---- WMF ----

    PictureCodecs.Raster wmf() throws IOException {
      wmf = true;
      int pos = 0;
      recEnd = d.length;
      boolean placeable = s32(0) == 0x9AC6CDD7;
      double wPt = 0, hPt = 0;
      if (placeable) {
        int l = s16(6), t = s16(8), r = s16(10), bt = s16(12), inch = u16(14);
        if (inch == 0) inch = 1440;
        fx = Math.min(l, r);
        fy = Math.min(t, bt);
        fw = Math.abs(r - l);
        fh = Math.abs(bt - t);
        wPt = fw * 72.0 / inch;
        hPt = fh * 72.0 / inch;
        pos = 22;
      }
      if (pos + 18 > d.length) throw new IOException("Truncated WMF header");
      int hs = u16(pos + 2) * 2;
      if (hs < 18) throw new IOException("Invalid WMF header");
      int start = pos + hs;
      if (!placeable) {
        // Size from the first SetWindowExt; one logical unit is taken as one point.
        for (int at = start, n = 0; at + 6 <= d.length && n < MAX_RECORDS; n++) {
          long size = (PictureCodecs.s32le(d, at) & 0xffffffffL) * 2;
          if (size < 6 || at + size > d.length) break;
          recEnd = (int) (at + size);
          int fn = u16(at + 4);
          try {
            if (fn == 0x020B && size >= 10) { fy = s16(at + 6); fx = s16(at + 8); }
            if (fn == 0x020C && size >= 10) { fh = Math.abs(s16(at + 6)); fw = Math.abs(s16(at + 8)); break; }
          } catch (IndexOutOfBoundsException ex) {
            break;
          }
          at += (int) size;
        }
        wPt = fw;
        hPt = fh;
      }
      allocate(wPt, hPt);
      int parsed = 0;
      for (int at = start; at + 6 <= d.length && parsed < MAX_RECORDS && work < MAX_WORK; parsed++) {
        long size = (PictureCodecs.s32le(d, at) & 0xffffffffL) * 2;
        if (size < 6 || at + size > d.length) break;
        recEnd = (int) (at + size);
        int fn = PictureCodecs.u16le(d, at + 4);
        if (fn == 0) break;
        try {
          wmfRecord(fn, at + 6);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
          // Malformed record: skip it.
        }
        at += (int) size;
      }
      return done();
    }

    private void wmfRecord(int fn, int p) {
      switch (fn) {
        case 0x0103: st.mapMode = u16(p); break;
        case 0x0106: st.fillMode = u16(p); break;
        case 0x020B: st.woy = s16(p); st.wox = s16(p + 2); st.windowSet = true; break;
        case 0x020C: st.wey = s16(p); st.wex = s16(p + 2); st.windowSet = true; break;
        case 0x020D: st.voy = s16(p); st.vox = s16(p + 2); break;
        case 0x020E: st.vey = s16(p); st.vex = s16(p + 2); break;
        case 0x001E: save(); break;
        case 0x0127: restore(s16(p)); break;
        case 0x0214: moveTo(s16(p + 2), s16(p)); break;
        case 0x0213: lineTo(s16(p + 2), s16(p)); break;
        case 0x0325: case 0x0324: {
          int n = u16(p);
          double[] pts = new double[2 * checkPoints(n)];
          for (int i = 0; i < n; i++) {
            pts[2 * i] = s16(p + 2 + 4 * i);
            pts[2 * i + 1] = s16(p + 4 + 4 * i);
          }
          if (fn == 0x0324) shape(new double[][] {pts}, true, true, true);
          else shape(new double[][] {pts}, false, false, true);
          break;
        }
        case 0x0538: {
          int n = u16(p), at = p + 2 + 2 * n;
          double[][] polys = new double[checkPoints(n)][];
          for (int i = 0; i < n; i++) {
            int c = u16(p + 2 + 2 * i);
            double[] pts = new double[2 * checkPoints(c)];
            for (int k = 0; k < c; k++, at += 4) {
              pts[2 * k] = s16(at);
              pts[2 * k + 1] = s16(at + 2);
            }
            polys[i] = pts;
          }
          shape(polys, true, true, true);
          break;
        }
        case 0x041B: shape(new double[][] {rect(s16(p + 6), s16(p + 4), s16(p + 2), s16(p))}, true, true, true); break;
        case 0x061C: shape(new double[][] {rect(s16(p + 10), s16(p + 8), s16(p + 6), s16(p + 4))}, true, true, true); break;
        case 0x0418: shape(new double[][] {ellipse(s16(p + 6), s16(p + 4), s16(p + 2), s16(p))}, true, true, true); break;
        case 0x0817: case 0x081A: case 0x0830: {
          int kind = fn == 0x0817 ? 0 : fn == 0x081A ? 2 : 1;
          double[] a = arc(s16(p + 14), s16(p + 12), s16(p + 10), s16(p + 8), s16(p + 6), s16(p + 4),
              s16(p + 2), s16(p), kind);
          shape(new double[][] {a}, kind != 0, kind != 0, true);
          break;
        }
        case 0x02FA: {
          int style = u16(p), w = s16(p + 2);
          create(new Obj(1, (style & 15) == 5 ? 0 : colorref(p + 6), Math.abs(w)));
          break;
        }
        case 0x02FC: {
          int style = u16(p);
          create(new Obj(2, style == 1 ? 0 : colorref(p + 2), 0));
          break;
        }
        case 0x02FB: case 0x00F7: case 0x01F9: case 0x0142: case 0x06FF:
          create(new Obj(3, 0, 0));
          break;
        case 0x012D: select(u16(p)); break;
        case 0x01F0: {
          int h = u16(p);
          if (h < MAX_OBJECTS) objects[h] = null;
          break;
        }
        case 0x0F43: case 0x0B41: {
          // StretchDIBits carries a ColorUsage word after the raster operation; DIBStretchBlt does not.
          int o = fn == 0x0F43 ? p + 2 : p;
          int sh = s16(o + 4), sw = s16(o + 6), sy = s16(o + 8), sx = s16(o + 10);
          int dh = s16(o + 12), dw = s16(o + 14), dy = s16(o + 16), dx = s16(o + 18);
          int dib = o + 20;
          if (recEnd - dib < 12) {
            patternFill(dx, dy, dw, dh);
            break;
          }
          blit(packedDib(dib, recEnd), dx, dy, dw, dh, sx, sy, sw, sh);
          break;
        }
        case 0x0940: {
          int sy = s16(p + 4), sx = s16(p + 6), dh = s16(p + 8), dw = s16(p + 10), dy = s16(p + 12),
              dx = s16(p + 14);
          if (recEnd - (p + 16) < 12) {
            patternFill(dx, dy, dw, dh);
            break;
          }
          blit(packedDib(p + 16, recEnd), dx, dy, dw, dh, sx, sy, dw, dh);
          break;
        }
        default: break;
      }
    }

    private int colorref(int at) {
      return 0xff000000 | (d[checked(at)] & 255) << 16 | (d[checked(at + 1)] & 255) << 8 | d[checked(at + 2)] & 255;
    }

    private int checked(int at) {
      if (at < 0 || at >= recEnd) throw new IndexOutOfBoundsException();
      return at;
    }

    private int checkPoints(int n) {
      if (n < 0 || n > (recEnd - 0) || points + n > MAX_POINTS) throw new IllegalArgumentException();
      points += n;
      return n;
    }

    private void create(Obj o) {
      for (int i = 0; i < MAX_OBJECTS; i++)
        if (objects[i] == null) {
          objects[i] = o;
          return;
        }
    }

    private void select(int h) {
      if (h < 0 || h >= MAX_OBJECTS || objects[h] == null) return;
      Obj o = objects[h];
      if (o.kind == 1) st.pen = o;
      else if (o.kind == 2) st.brush = o;
    }

    private void save() {
      if (stack.size() < MAX_STACK) stack.add(st.copy());
    }

    private void restore(int n) {
      int target = n < 0 ? stack.size() + n : n - 1;
      if (target < 0 || target >= stack.size()) return;
      st = stack.get(target);
      while (stack.size() > target) stack.remove(stack.size() - 1);
    }

    // ---- EMF ----

    PictureCodecs.Raster emf() throws IOException {
      recEnd = d.length;
      int hsize = s32(4);
      int bl = s32(8), bt = s32(12), br = s32(16), bb = s32(20);
      int fl = s32(24), ft = s32(28), fr = s32(32), fb = s32(36);
      int dw = s32(72), dh = s32(76), mw = s32(80), mh = s32(84);
      double wPt, hPt;
      if (dw > 0 && dh > 0 && mw > 0 && mh > 0) {
        pxPerMmX = (double) dw / mw;
        pxPerMmY = (double) dh / mh;
      }
      if (fr > fl && fb > ft && dw > 0 && mw > 0) {
        fx = fl * pxPerMmX / 100;
        fy = ft * pxPerMmY / 100;
        fw = (double) (fr - fl) * pxPerMmX / 100;
        fh = (double) (fb - ft) * pxPerMmY / 100;
        wPt = (double) (fr - fl) / 2540 * 72;
        hPt = (double) (fb - ft) / 2540 * 72;
      } else if (br > bl && bb > bt) {
        fx = bl;
        fy = bt;
        fw = (double) br - bl;
        fh = (double) bb - bt;
        wPt = fw;
        hPt = fh;
      } else throw new IOException("EMF has no usable frame");
      allocate(wPt, hPt);
      int pos = Math.max(hsize, 88);
      for (int n = 0; n < MAX_RECORDS && work < MAX_WORK && pos >= 0 && pos + 8 <= d.length; n++) {
        int type = PictureCodecs.s32le(d, pos);
        long size = PictureCodecs.s32le(d, pos + 4) & 0xffffffffL;
        if (size < 8 || (size & 3) != 0 || pos + size > d.length) break;
        if (type == 14) break;
        recEnd = (int) (pos + size);
        try {
          emfRecord(type, pos + 8);
        } catch (IndexOutOfBoundsException | IllegalArgumentException ex) {
          // Malformed record: skip it.
        }
        pos += (int) size;
      }
      return done();
    }

    private void emfRecord(int type, int p) {
      switch (type) {
        case 9: st.wex = s32(p); st.wey = s32(p + 4); st.windowSet = true; break;
        case 10: st.wox = s32(p); st.woy = s32(p + 4); st.windowSet = true; break;
        case 11: st.vex = s32(p); st.vey = s32(p + 4); st.viewportSet = true; break;
        case 12: st.vox = s32(p); st.voy = s32(p + 4); st.viewportSet = true; break;
        case 17: st.mapMode = s32(p); break;
        case 19: st.fillMode = s32(p); break;
        case 33: save(); break;
        case 34: restore(s32(p)); break;
        case 35: st.xf = xform(p); break;
        case 36: {
          double[] x = xform(p);
          int mode = s32(p + 24);
          if (mode == 1) st.xf = new double[] {1, 0, 0, 1, 0, 0};
          else if (mode == 2) st.xf = mul(x, st.xf);
          else if (mode == 3) st.xf = mul(st.xf, x);
          else if (mode == 4) st.xf = x;
          break;
        }
        case 37: emfSelect(s32(p)); break;
        case 38: {
          int ih = s32(p), style = s32(p + 4), w = s32(p + 8);
          put(ih, new Obj(1, (style & 15) == 5 ? 0 : colorref(p + 16), Math.abs(w)));
          break;
        }
        case 95: {
          int ih = s32(p), style = s32(p + 20), w = s32(p + 24), bs = s32(p + 28);
          put(ih, new Obj(1, (style & 15) == 5 || bs == 1 ? 0 : colorref(p + 32), Math.abs(w)));
          break;
        }
        case 39: {
          int ih = s32(p), style = s32(p + 4);
          put(ih, new Obj(2, style == 1 ? 0 : colorref(p + 8), 0));
          break;
        }
        case 82: case 93: case 94: put(s32(p), new Obj(type == 82 ? 3 : 2, type == 82 ? 0 : 0xff808080, 0)); break;
        case 40: {
          int ih = s32(p);
          if (ih > 0 && ih < MAX_OBJECTS) objects[ih] = null;
          break;
        }
        case 27: moveTo(s32(p), s32(p + 4)); break;
        case 54: lineTo(s32(p), s32(p + 4)); break;
        case 42: shape(new double[][] {ellipse(s32(p), s32(p + 4), s32(p + 8), s32(p + 12))}, true, true, true); break;
        case 43: case 44: shape(new double[][] {rect(s32(p), s32(p + 4), s32(p + 8), s32(p + 12))}, true, true, true); break;
        case 45: case 46: case 47: {
          int kind = type == 45 ? 0 : type == 46 ? 1 : 2;
          double[] a = arc(s32(p), s32(p + 4), s32(p + 8), s32(p + 12), s32(p + 16), s32(p + 20),
              s32(p + 24), s32(p + 28), kind);
          shape(new double[][] {a}, kind != 0, kind != 0, true);
          break;
        }
        case 3: case 86: shape(new double[][] {counted(p, type == 86)}, true, true, true); break;
        case 4: case 87: shape(new double[][] {counted(p, type == 87)}, false, false, true); break;
        case 6: case 89: {
          double[] pts = counted(p, type == 89);
          for (int i = 0; i + 1 < pts.length; i += 2) lineTo(pts[i], pts[i + 1]);
          break;
        }
        case 2: case 85: {
          double[] pts = counted(p, type == 85);
          if (pts.length < 2) break;
          double[] flat = bezier(pts[0], pts[1], pts, 2);
          shape(new double[][] {flat}, false, false, true);
          break;
        }
        case 5: case 88: {
          double[] pts = counted(p, type == 88);
          double[] flat = bezier(st.cx, st.cy, pts, 0);
          for (int i = 2; i + 1 < flat.length; i += 2) lineTo(flat[i], flat[i + 1]);
          break;
        }
        case 7: case 8: case 90: case 91: {
          boolean small = type >= 90;
          int n = s32(p + 16), total = s32(p + 20);
          checkPoints(n);
          checkPoints(total);
          int at = p + 24 + 4 * n, step = small ? 4 : 8;
          double[][] polys = new double[n][];
          for (int i = 0; i < n; i++) {
            int c = s32(p + 24 + 4 * i);
            if (c < 0 || c > total) throw new IllegalArgumentException();
            double[] pts = new double[2 * c];
            for (int k = 0; k < c; k++, at += step) {
              pts[2 * k] = small ? s16(at) : s32(at);
              pts[2 * k + 1] = small ? s16(at + 2) : s32(at + 4);
            }
            polys[i] = pts;
          }
          boolean closed = type == 8 || type == 91;
          shape(polys, closed, closed, true);
          break;
        }
        case 59: path = new ArrayList<>(); figure = null; break;
        case 60: endFigure(); break;
        case 61: closeFigure(); break;
        case 62: case 63: case 64: {
          endFigure();
          ArrayList<double[]> pth = path;
          path = null;
          if (pth == null || pth.isEmpty()) break;
          double[][] polys = pth.toArray(new double[0][]);
          if (type != 64) fillDevice(polys, st.brush.color, st.fillMode == 2);
          if (type != 62) strokeDevice(polys, true);
          break;
        }
        case 76: case 77: case 114: {
          int dx = s32(p + 16), dy = s32(p + 20), dw = s32(p + 24), dh = s32(p + 28);
          int sx = s32(p + 36), sy = s32(p + 40);
          int offBmi = s32(p + 76), cbBmi = s32(p + 80), offBits = s32(p + 84), cbBits = s32(p + 88);
          int sw = dw, sh = dh;
          if (type != 76) {
            sw = s32(p + 92);
            sh = s32(p + 96);
          }
          if (cbBmi == 0) {
            if (type == 76 || type == 77) patternFill(dx, dy, dw, dh);
            break;
          }
          blit(splitDib(p - 8, offBmi, cbBmi, offBits, cbBits), dx, dy, dw, dh, sx, sy, sw, sh);
          break;
        }
        case 81: {
          int dx = s32(p + 16), dy = s32(p + 20), sx = s32(p + 24), sy = s32(p + 28);
          int sw = s32(p + 32), sh = s32(p + 36);
          int offBmi = s32(p + 40), cbBmi = s32(p + 44), offBits = s32(p + 48), cbBits = s32(p + 52);
          int dw = s32(p + 64), dh = s32(p + 68);
          blit(splitDib(p - 8, offBmi, cbBmi, offBits, cbBits), dx, dy, dw, dh, sx, sy, sw, sh);
          break;
        }
        case 80: {
          int dx = s32(p + 16), dy = s32(p + 20), sx = s32(p + 24), sy = s32(p + 28);
          int w = s32(p + 32), h = s32(p + 36);
          int offBmi = s32(p + 40), cbBmi = s32(p + 44), offBits = s32(p + 48), cbBits = s32(p + 52);
          blit(splitDib(p - 8, offBmi, cbBmi, offBits, cbBits), dx, dy, w, h, sx, sy, w, h);
          break;
        }
        default: break;
      }
    }

    private double[] xform(int p) {
      double[] x = {f32(p), f32(p + 4), f32(p + 8), f32(p + 12), f32(p + 16), f32(p + 20)};
      for (double v : x) if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 1e9) throw new IllegalArgumentException();
      return x;
    }

    /** a then b: x' = x*m11 + y*m21 + dx. */
    private double[] mul(double[] a, double[] b) {
      return new double[] {
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]};
    }

    private void put(int ih, Obj o) {
      if (ih > 0 && ih < MAX_OBJECTS) objects[ih] = o;
    }

    private void emfSelect(int ih) {
      if (ih < 0) {
        switch (ih & 0x7fffffff) {
          case 0: st.brush = new Obj(2, 0xffffffff, 0); break;
          case 1: st.brush = new Obj(2, 0xffc0c0c0, 0); break;
          case 2: st.brush = new Obj(2, 0xff808080, 0); break;
          case 3: st.brush = new Obj(2, 0xff404040, 0); break;
          case 4: st.brush = new Obj(2, 0xff000000, 0); break;
          case 5: st.brush = new Obj(2, 0, 0); break;
          case 6: st.pen = new Obj(1, 0xffffffff, 0); break;
          case 7: st.pen = new Obj(1, 0xff000000, 0); break;
          case 8: st.pen = new Obj(1, 0, 0); break;
          default: break;
        }
        return;
      }
      select(ih);
    }

    private double[] counted(int p, boolean small) {
      int n = checkPoints(s32(p + 16));
      double[] pts = new double[2 * n];
      int step = small ? 4 : 8, at = p + 20;
      if ((long) at + (long) n * step > recEnd) throw new IndexOutOfBoundsException();
      for (int i = 0; i < n; i++, at += step) {
        pts[2 * i] = small ? s16(at) : s32(at);
        pts[2 * i + 1] = small ? s16(at + 2) : s32(at + 4);
      }
      return pts;
    }

    /** Flattens cubic segments starting at (x0, y0) with control triples from pts[from..]. */
    private double[] bezier(double x0, double y0, double[] pts, int from) {
      int curves = (pts.length - from) / 6;
      checkPoints(curves * 16);
      double[] out = new double[2 + curves * 32];
      out[0] = x0;
      out[1] = y0;
      int o = 2;
      for (int c = 0; c < curves; c++) {
        int at = from + 6 * c;
        double ax = c == 0 ? x0 : pts[at - 2], ay = c == 0 ? y0 : pts[at - 1];
        for (int i = 1; i <= 16; i++) {
          double t = i / 16.0, u = 1 - t;
          out[o++] = u * u * u * ax + 3 * u * u * t * pts[at] + 3 * u * t * t * pts[at + 2] + t * t * t * pts[at + 4];
          out[o++] = u * u * u * ay + 3 * u * u * t * pts[at + 1] + 3 * u * t * t * pts[at + 3] + t * t * t * pts[at + 5];
        }
      }
      return out;
    }

    // ---- DIBs ----

    /** A packed DIB (BITMAPINFO then bits) at [at, end), decoded through the BMP reader. */
    private PictureCodecs.Raster packedDib(int at, int end) {
      int hs = s32(at);
      if (hs != 12 && (hs < 40 || hs > 124)) return null;
      int bpp = hs == 12 ? u16(at + 10) : u16(at + 14);
      int compression = hs == 12 ? 0 : s32(at + 16), used = hs == 12 ? 0 : s32(at + 32);
      long colors = bpp <= 8 ? (used > 0 && used <= 256 ? used : 1 << Math.min(bpp, 8)) : 0;
      long table = colors * (hs == 12 ? 3 : 4) + (hs == 40 && compression == 3 ? 12 : hs == 40 && compression == 6 ? 16 : 0);
      if (hs + table > end - at) return null;
      return dibRaster(at, (int) (hs + table), at + (int) (hs + table), end - at - (int) (hs + table));
    }

    /** EMF: BITMAPINFO and bits at record-relative offsets. */
    private PictureCodecs.Raster splitDib(int rec, int offBmi, int cbBmi, int offBits, int cbBits) {
      if (offBmi < 0 || cbBmi < 12 || offBits < 0 || cbBits < 0) return null;
      long a = (long) rec + offBmi, b = (long) rec + offBits;
      if (a + cbBmi > recEnd || b + cbBits > recEnd) return null;
      return dibRaster((int) a, cbBmi, (int) b, cbBits);
    }

    private PictureCodecs.Raster dibRaster(int bmi, int cbBmi, int bits, int cbBits) {
      byte[] f = new byte[14 + cbBmi + cbBits];
      f[0] = 'B';
      f[1] = 'M';
      le(f, 2, f.length);
      le(f, 10, 14 + cbBmi);
      System.arraycopy(d, bmi, f, 14, cbBmi);
      System.arraycopy(d, bits, f, 14 + cbBmi, cbBits);
      // Metafile DIBs may omit biSizeImage-consistent data; the BMP reader bounds every access.
      try {
        long w = Math.abs((long) PictureCodecs.s32le(f, 18)), h = Math.abs((long) PictureCodecs.s32le(f, 22));
        if (PictureCodecs.s32le(f, 14) == 12) {
          w = PictureCodecs.u16le(f, 18);
          h = PictureCodecs.u16le(f, 20);
        }
        if (bitmapPixels + w * h > MAX_BITMAP_PIXELS) return null;
        bitmapPixels += w * h;
        return PictureCodecs.bmp(f);
      } catch (IOException | RuntimeException ex) {
        return null;
      }
    }

    private static void le(byte[] b, int at, int v) {
      b[at] = (byte) v;
      b[at + 1] = (byte) (v >> 8);
      b[at + 2] = (byte) (v >> 16);
      b[at + 3] = (byte) (v >> 24);
    }

    private void patternFill(double dx, double dy, double dw, double dh) {
      int c = st.brush.color;
      if ((c >>> 24) == 0) return;
      fillDevice(new double[][] {map(rect(dx, dy, dx + dw, dy + dh))}, c, false);
    }

    private void blit(PictureCodecs.Raster r, double dx, double dy, double dw, double dh, int sx, int sy,
        int sw, int sh) {
      if (r == null || sw == 0 || sh == 0) return;
      double[] q = map(new double[] {dx, dy, dx + dw, dy + dh});
      double x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3];
      int ax = (int) Math.max(0, Math.floor(Math.min(x0, x1))), bx = (int) Math.min(width, Math.ceil(Math.max(x0, x1)));
      int ay = (int) Math.max(0, Math.floor(Math.min(y0, y1))), by = (int) Math.min(height, Math.ceil(Math.max(y0, y1)));
      if (ax >= bx || ay >= by || x1 == x0 || y1 == y0) return;
      work += (long) (bx - ax) * (by - ay);
      for (int y = ay; y < by; y++) {
        double v = (y + 0.5 - y0) / (y1 - y0);
        int syy = sy + (int) Math.floor(v * sh);
        if (syy < 0 || syy >= r.height) continue;
        for (int x = ax; x < bx; x++) {
          double u = (x + 0.5 - x0) / (x1 - x0);
          int sxx = sx + (int) Math.floor(u * sw);
          if (sxx < 0 || sxx >= r.width) continue;
          int c = r.argb[syy * r.width + sxx];
          blend(y * width + x, c, 1);
        }
      }
    }

    // ---- geometry ----

    private static double[] rect(double l, double t, double r, double b) {
      return new double[] {l, t, r, t, r, b, l, b};
    }

    private double[] ellipse(double l, double t, double r, double b) {
      double cx = (l + r) / 2, cy = (t + b) / 2, rx = Math.abs(r - l) / 2, ry = Math.abs(b - t) / 2;
      double[] out = new double[128];
      for (int i = 0; i < 64; i++) {
        double a = i * Math.PI * 2 / 64;
        out[2 * i] = cx + rx * Math.cos(a);
        out[2 * i + 1] = cy + ry * Math.sin(a);
      }
      return out;
    }

    /** kind 0 arc, 1 chord, 2 pie; GDI's default counter-clockwise direction. */
    private double[] arc(double l, double t, double r, double b, double xs, double ys, double xe, double ye,
        int kind) {
      double cx = (l + r) / 2, cy = (t + b) / 2, rx = Math.abs(r - l) / 2, ry = Math.abs(b - t) / 2;
      if (rx == 0 || ry == 0) return new double[0];
      double a0 = com.codename1.util.MathUtil.atan2((ys - cy) / ry, (xs - cx) / rx), a1 = com.codename1.util.MathUtil.atan2((ye - cy) / ry, (xe - cx) / rx);
      if (a1 >= a0) a1 -= Math.PI * 2;
      int n = 48;
      double[] out = new double[2 * (n + 1) + (kind == 2 ? 2 : 0)];
      for (int i = 0; i <= n; i++) {
        double a = a0 + (a1 - a0) * i / n;
        out[2 * i] = cx + rx * Math.cos(a);
        out[2 * i + 1] = cy + ry * Math.sin(a);
      }
      if (kind == 2) {
        out[out.length - 2] = cx;
        out[out.length - 1] = cy;
      }
      return out;
    }

    /** Logical to pixel coordinates, in place on a copy. */
    private double[] map(double[] pts) {
      double[] o = new double[pts.length];
      double[] x = st.xf;
      double sx = fw == 0 ? 1 : width / fw, sy = fh == 0 ? 1 : height / fh;
      for (int i = 0; i + 1 < pts.length; i += 2) {
        double lx = pts[i], ly = pts[i + 1], dx, dy;
        if (wmf) {
          // The window maps onto the placeable bounds (or itself when there is none).
          if (st.windowSet && st.wex != 0 && st.wey != 0) {
            dx = fx + (lx - st.wox) * fw / st.wex;
            dy = fy + (ly - st.woy) * fh / st.wey;
          } else {
            dx = lx;
            dy = ly;
          }
        } else {
          double wx = lx * x[0] + ly * x[2] + x[4], wy = lx * x[1] + ly * x[3] + x[5];
          int mm = st.mapMode;
          if (mm == 7 || mm == 8) {
            double kx = st.wex == 0 ? 1 : st.vex / st.wex, ky = st.wey == 0 ? 1 : st.vey / st.wey;
            if (mm == 7) {
              double k = Math.min(Math.abs(kx), Math.abs(ky));
              kx = (kx < 0 ? -1 : 1) * k;
              ky = (ky < 0 ? -1 : 1) * k;
            }
            dx = (wx - st.wox) * kx + st.vox;
            dy = (wy - st.woy) * ky + st.voy;
          } else if (mm >= 2 && mm <= 6) {
            double mmPer = mm == 2 ? 0.1 : mm == 3 ? 0.01 : mm == 4 ? 0.254 : mm == 5 ? 0.0254 : 25.4 / 1440;
            dx = (wx - st.wox) * mmPer * pxPerMmX + st.vox;
            dy = -(wy - st.woy) * mmPer * pxPerMmY + st.voy;
          } else {
            dx = wx - st.wox + st.vox;
            dy = wy - st.woy + st.voy;
          }
        }
        o[i] = (dx - fx) * sx;
        o[i + 1] = (dy - fy) * sy;
        if (Double.isNaN(o[i]) || Double.isNaN(o[i + 1])) throw new IllegalArgumentException();
      }
      return o;
    }

    private double penPixels() {
      double[] a = map(new double[] {0, 0, st.pen.width, st.pen.width});
      double w = (Math.abs(a[2] - a[0]) + Math.abs(a[3] - a[1])) / 2;
      return Math.max(1, Math.min(w, 4 * MAX_EDGE));
    }

    // ---- drawing ----

    private void moveTo(double x, double y) {
      st.cx = x;
      st.cy = y;
      if (path != null) {
        endFigure();
        figure = new double[16];
        figure[0] = x;
        figure[1] = y;
        figureLen = 2;
      }
    }

    private void lineTo(double x, double y) {
      if (path != null) {
        if (figure == null) {
          figure = new double[16];
          figure[0] = st.cx;
          figure[1] = st.cy;
          figureLen = 2;
        }
        if (figureLen + 2 > figure.length) {
          checkPoints(figure.length);
          figure = Arrays.copyOf(figure, figure.length * 2);
        }
        figure[figureLen++] = x;
        figure[figureLen++] = y;
      } else strokeDevice(new double[][] {map(new double[] {st.cx, st.cy, x, y})}, false);
      st.cx = x;
      st.cy = y;
    }

    private void endFigure() {
      if (path != null && figure != null && figureLen >= 4) path.add(map(Arrays.copyOf(figure, figureLen)));
      figure = null;
      figureLen = 0;
    }

    private void closeFigure() {
      if (figure != null && figureLen >= 4) {
        lineTo(figure[0], figure[1]);
        endFigure();
      }
    }

    private void shape(double[][] polys, boolean fill, boolean closed, boolean stroke) {
      if (path != null) {
        endFigure();
        for (double[] p : polys)
          if (p != null && p.length >= 4) {
            double[] q = closed ? Arrays.copyOf(p, p.length + 2) : p;
            if (closed) {
              q[p.length] = p[0];
              q[p.length + 1] = p[1];
            }
            path.add(map(q));
          }
        return;
      }
      double[][] dev = new double[polys.length][];
      for (int i = 0; i < polys.length; i++) dev[i] = polys[i] == null ? new double[0] : map(polys[i]);
      if (fill) fillDevice(dev, st.brush.color, st.fillMode == 2);
      if (stroke) strokeDevice(dev, closed);
    }

    private void strokeDevice(double[][] polys, boolean closed) {
      int c = st.pen.color;
      if ((c >>> 24) == 0) return;
      double hw = penPixels() / 2;
      ArrayList<double[]> quads = new ArrayList<>();
      for (double[] p : polys) {
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
          int j = i + 1;
          if (j == n) {
            if (!closed || n < 3) break;
            j = 0;
          }
          double x0 = p[2 * i], y0 = p[2 * i + 1], x1 = p[2 * j], y1 = p[2 * j + 1];
          double dx = x1 - x0, dy = y1 - y0, len = Math.sqrt(dx * dx + dy * dy);
          if (len > 0) {
            double nx = -dy / len * hw, ny = dx / len * hw;
            quads.add(oriented(new double[] {x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny}));
          }
          if (n > 2 || hw > 1) quads.add(new double[] {x1 - hw, y1 - hw, x1 + hw, y1 - hw, x1 + hw, y1 + hw, x1 - hw, y1 + hw});
        }
      }
      fillDevice(quads.toArray(new double[0][]), c, true);
    }

    private static double[] oriented(double[] q) {
      double a = 0;
      for (int i = 0; i < 4; i++) {
        int j = (i + 1) & 3;
        a += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1];
      }
      if (a >= 0) return q;
      return new double[] {q[6], q[7], q[4], q[5], q[2], q[3], q[0], q[1]};
    }

    /** Scanline fill with 4 sub-rows per pixel and fractional horizontal coverage. */
    private void fillDevice(double[][] polys, int color, boolean nonZero) {
      if ((color >>> 24) == 0) return;
      int edges = 0;
      double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
      for (double[] p : polys) {
        edges += p.length / 2;
        for (int i = 1; i < p.length; i += 2) {
          minY = Math.min(minY, p[i]);
          maxY = Math.max(maxY, p[i]);
        }
      }
      if (edges < 2) return;
      int y0 = (int) Math.max(0, Math.floor(minY)), y1 = (int) Math.min(height, Math.ceil(maxY));
      if (y0 >= y1) return;
      double[] ex0 = new double[edges], ey0 = new double[edges], ex1 = new double[edges], ey1 = new double[edges];
      int[] dir = new int[edges];
      int m = 0;
      for (double[] p : polys) {
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
          int j = (i + 1) % n;
          double ya = p[2 * i + 1], yb = p[2 * j + 1];
          if (ya == yb) continue;
          if (ya < yb) {
            ex0[m] = p[2 * i]; ey0[m] = ya; ex1[m] = p[2 * j]; ey1[m] = yb; dir[m] = 1;
          } else {
            ex0[m] = p[2 * j]; ey0[m] = yb; ex1[m] = p[2 * i]; ey1[m] = ya; dir[m] = -1;
          }
          m++;
        }
      }
      work += (long) (y1 - y0) * SUB * (m + 8);
      if (work > MAX_WORK) return;
      long[] xs = new long[m];
      float[] cover = new float[width + 1];
      for (int y = y0; y < y1; y++) {
        Arrays.fill(cover, 0);
        int minX = width, maxX = -1;
        for (int s = 0; s < SUB; s++) {
          double sy = y + (s + 0.5) / SUB;
          int k = 0;
          for (int e = 0; e < m; e++) {
            if (sy < ey0[e] || sy >= ey1[e]) continue;
            double x = ex0[e] + (sy - ey0[e]) * (ex1[e] - ex0[e]) / (ey1[e] - ey0[e]);
            x = Math.max(-1, Math.min(width + 1, x));
            xs[k++] = ((long) Math.round(x * 256) + 512) << 1 | (dir[e] > 0 ? 1 : 0);
          }
          if (k < 2) continue;
          Arrays.sort(xs, 0, k);
          int wind = 0;
          for (int i = 0; i < k - 1; i++) {
            int dirBit = (int) (xs[i] & 1);
            wind += nonZero ? (dirBit == 1 ? 1 : -1) : 1;
            boolean inside = nonZero ? wind != 0 : (wind & 1) == 1;
            if (!inside) continue;
            double a = ((xs[i] >> 1) - 512) / 256.0, b = ((xs[i + 1] >> 1) - 512) / 256.0;
            a = Math.max(0, a);
            b = Math.min(width, b);
            if (b <= a) continue;
            int ia = (int) a, ib = (int) b;
            minX = Math.min(minX, ia);
            maxX = Math.max(maxX, Math.min(ib, width - 1));
            if (ia == ib) cover[ia] += (float) (b - a);
            else {
              cover[ia] += (float) (ia + 1 - a);
              for (int x = ia + 1; x < ib; x++) cover[x] += 1;
              if (ib < width) cover[ib] += (float) (b - ib);
            }
          }
        }
        work += Math.max(0, maxX - minX + 1);
        for (int x = Math.max(0, minX); x <= maxX; x++) {
          float c = cover[x] / SUB;
          if (c > 0.002f) blend(y * width + x, color, Math.min(1, c));
        }
      }
    }

    private void blend(int at, int c, double cov) {
      double sa = ((c >>> 24) / 255.0) * cov;
      if (sa <= 0) return;
      int dst = px[at];
      double da = (dst >>> 24) / 255.0, oa = sa + da * (1 - sa);
      int r = (int) Math.round((((c >> 16) & 255) * sa + ((dst >> 16) & 255) * da * (1 - sa)) / oa);
      int g = (int) Math.round((((c >> 8) & 255) * sa + ((dst >> 8) & 255) * da * (1 - sa)) / oa);
      int b = (int) Math.round(((c & 255) * sa + (dst & 255) * da * (1 - sa)) / oa);
      px[at] = (int) Math.round(oa * 255) << 24 | Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
    }
  }
}
