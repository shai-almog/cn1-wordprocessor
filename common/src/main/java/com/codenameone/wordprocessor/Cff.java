package com.codenameone.wordprocessor;

import java.io.*;
import java.util.*;

/**
 * CFF (version 1) subsetting for PDF embedding as FontFile3 /CIDFontType0C. The output is a
 * CID-keyed font whose CID n is glyph n of the output, which holds .notdef followed by the used
 * glyphs in the order the PDF writer numbered them, so the PDF CIDs need no CIDToGIDMap. The kept
 * charstrings are interpreted to find the global and local subroutines they reach (directly or
 * through other subroutines); every other subroutine is replaced by a bare {@code return}, so the
 * INDEXes keep their counts and biased numbering and no charstring needs rewriting.
 * Name-keyed sources become a single-FD CID font; CID-keyed sources keep their whole FDArray.
 */
final class Cff {
  private Cff() {}

  private static final int ROS = 1230, CID_COUNT = 1234, FD_ARRAY = 1236, FD_SELECT = 1237,
      FONT_MATRIX = 1207, CHARSTRINGS = 17, PRIVATE = 18, SUBRS = 19, BBOX = 5;

  /** The CID-keyed subset of CFF table {@code cff} keeping {@code glyphs} as CIDs 1..n. */
  static byte[] subset(byte[] cff, List<Integer> glyphs) throws IOException {
    try {
      return build(cff, glyphs);
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid CFF font");
    }
  }

  private static byte[] build(byte[] d, List<Integer> glyphs) throws IOException {
    if (d.length < 4 || (d[0] & 255) != 1) throw new IOException("Unsupported CFF version");
    int[] names = index(d, d[2] & 255), tops = index(d, end(names)), strings = index(d, end(tops)),
        gsubrs = index(d, end(strings));
    if (tops.length < 2) throw new IOException("CFF without a Top DICT");
    var top = dict(d, tops[0], tops[1]);
    if (!top.containsKey(CHARSTRINGS)) throw new IOException("CFF without CharStrings");
    int[] charstrings = index(d, (int) num(top, CHARSTRINGS, 0));
    int count = charstrings.length - 1;
    // Font DICTs (as their private dict ranges) and each source glyph's FD.
    var privates = new ArrayList<int[]>();
    int[] fdOf = new int[count];
    if (top.containsKey(ROS)) {
      int[] fds = index(d, (int) num(top, FD_ARRAY, 0));
      for (int f = 0; f + 1 < fds.length; f++) {
        var fd = dict(d, fds[f], fds[f + 1]);
        privates.add(new int[] {(int) num(fd, PRIVATE, 1), (int) num(fd, PRIVATE, 0)});
      }
      int at = (int) num(top, FD_SELECT, 0), format = d[at] & 255;
      if (format == 0) for (int g = 0; g < count; g++) fdOf[g] = d[at + 1 + g] & 255;
      else if (format == 3) {
        int ranges = u16(d, at + 1);
        for (int r = 0; r < ranges; r++) {
          int first = u16(d, at + 3 + 3 * r), fd = d[at + 5 + 3 * r] & 255,
              next = u16(d, at + 6 + 3 * r);
          for (int g = first; g < next && g < count; g++) fdOf[g] = fd;
        }
      } else throw new IOException("Unsupported CFF FDSelect");
    } else {
      privates.add(top.containsKey(PRIVATE)
          ? new int[] {(int) num(top, PRIVATE, 1), (int) num(top, PRIVATE, 0)}
          : new int[] {0, 0});
    }
    // Subroutines the kept glyphs reach.
    int[] globalIdx = gsubrs;
    var localIdx = new ArrayList<int[]>();
    for (int[] p : privates) {
      int[] local = null;
      if (p[1] > 0) {
        var pd = dict(d, p[0], p[0] + p[1]);
        if (pd.containsKey(SUBRS)) local = index(d, p[0] + (int) num(pd, SUBRS, 0));
      }
      localIdx.add(local);
    }
    boolean[] usedGlobal = new boolean[Math.max(0, globalIdx.length - 1)];
    var usedLocal = new ArrayList<boolean[]>();
    for (int[] l : localIdx) usedLocal.add(new boolean[l == null ? 0 : l.length - 1]);
    // Output glyphs: .notdef then the used glyphs in CID order.
    int n = glyphs.size() + 1;
    var outStrings = new ArrayList<byte[]>();
    var outCharstrings = new ArrayList<byte[]>();
    int[] outFd = new int[n];
    for (int k = 0; k < n; k++) {
      int g = k == 0 ? 0 : glyphs.get(k - 1);
      if (g < 0 || g >= count) g = 0;
      outCharstrings.add(Arrays.copyOfRange(d, charstrings[g], charstrings[g + 1]));
      outFd[k] = Math.min(fdOf[g], privates.size() - 1);
      int f = Math.max(0, outFd[k]);
      new Walker(d, globalIdx, usedGlobal, localIdx.get(f), usedLocal.get(f)).run(charstrings[g], charstrings[g + 1], 0);
    }
    outStrings.add(ascii("Adobe"));
    outStrings.add(ascii("Identity"));
    // Private DICTs with their local subroutines placed directly after them.
    var privateBlobs = new ArrayList<byte[]>();
    var privateSizes = new ArrayList<Integer>();
    for (int[] p : privates) {
      var dict = new ByteArrayOutputStream();
      byte[] subrs = new byte[0];
      if (p[1] > 0) {
        var pd = dict(d, p[0], p[0] + p[1]);
        for (var e : pd.entrySet()) {
          if (e.getKey() == SUBRS) continue;
          dict.write(e.getValue());
          op(dict, e.getKey());
        }
        if (pd.containsKey(SUBRS)) {
          int f = privateBlobs.size();
          subrs = pruned(d, localIdx.get(f), usedLocal.get(f));
          int size = dict.size() + 6;
          int5(dict, size);
          op(dict, SUBRS);
        }
      }
      privateSizes.add(dict.size());
      dict.write(subrs);
      privateBlobs.add(dict.toByteArray());
    }
    byte[] charset = n > 1 ? new byte[] {2, 0, 1, (byte) ((n - 2) >> 8), (byte) (n - 2)}
        : new byte[] {0};
    var fdRanges = new ArrayList<int[]>();
    for (int k = 0; k < n; k++)
      if (fdRanges.isEmpty() || fdRanges.get(fdRanges.size() - 1)[1] != outFd[k])
        fdRanges.add(new int[] {k, outFd[k]});
    byte[] fdSelect = new byte[5 + 3 * fdRanges.size()];
    fdSelect[0] = 3;
    put16(fdSelect, 1, fdRanges.size());
    for (int r = 0; r < fdRanges.size(); r++) {
      put16(fdSelect, 3 + 3 * r, fdRanges.get(r)[0]);
      fdSelect[5 + 3 * r] = (byte) fdRanges.get(r)[1];
    }
    put16(fdSelect, 3 + 3 * fdRanges.size(), n);
    byte[] nameIndex = Arrays.copyOfRange(d, d[2] & 255, end(names));
    byte[] stringIndex = writeIndex(outStrings);
    byte[] gsubrIndex = pruned(d, gsubrs, usedGlobal);
    byte[] charstringIndex = writeIndex(outCharstrings);
    // Two passes: every offset is a fixed-size operand, so sizes do not depend on the values.
    byte[] topDict = topDict(top, n, 0, 0, 0, 0);
    int topIndexSize = writeIndex(Collections.singletonList(topDict)).length;
    int charsetAt = 4 + nameIndex.length + topIndexSize + stringIndex.length + gsubrIndex.length,
        fdSelectAt = charsetAt + charset.length,
        charstringsAt = fdSelectAt + fdSelect.length,
        fdArrayAt = charstringsAt + charstringIndex.length;
    var fdDicts = new ArrayList<byte[]>();
    for (int f = 0; f < privateBlobs.size(); f++) fdDicts.add(fdDict(0, 0));
    int privateAt = fdArrayAt + writeIndex(fdDicts).length;
    fdDicts.clear();
    for (int f = 0; f < privateBlobs.size(); f++) {
      fdDicts.add(fdDict(privateSizes.get(f), privateAt));
      privateAt += privateBlobs.get(f).length;
    }
    topDict = topDict(top, n, charsetAt, fdSelectAt, charstringsAt, fdArrayAt);
    var out = new ByteArrayOutputStream();
    out.write(new byte[] {1, 0, 4, 4});
    out.write(nameIndex);
    out.write(writeIndex(Collections.singletonList(topDict)));
    out.write(stringIndex);
    out.write(gsubrIndex);
    out.write(charset);
    out.write(fdSelect);
    out.write(charstringIndex);
    out.write(writeIndex(fdDicts));
    for (byte[] p : privateBlobs) out.write(p);
    return out.toByteArray();
  }

  /** {@code index} with every subroutine not in {@code used} replaced by a bare return. */
  private static byte[] pruned(byte[] d, int[] index, boolean[] used) {
    var items = new ArrayList<byte[]>();
    for (int i = 0; i + 1 < index.length; i++)
      items.add(used[i] ? Arrays.copyOfRange(d, index[i], index[i + 1]) : new byte[] {11});
    return writeIndex(items);
  }

  /** The subroutine bias of an INDEX with {@code count} entries (Type 2 charstring spec). */
  static int bias(int count) {
    return count < 1240 ? 107 : count < 33900 ? 1131 : 32768;
  }

  /**
   * A Type 2 charstring scan: tracks the operand stack and stem count (for hintmask bytes) and
   * marks every subroutine reached through callsubr/callgsubr.
   */
  static final class Walker {
    private final byte[] d;
    private final int[] global, local;
    private final boolean[] usedGlobal, usedLocal;
    private final double[] stack = new double[96];
    private int top, stems;
    private boolean done;

    Walker(byte[] d, int[] global, boolean[] usedGlobal, int[] local, boolean[] usedLocal) {
      this.d = d;
      this.global = global;
      this.usedGlobal = usedGlobal;
      this.local = local;
      this.usedLocal = usedLocal;
    }

    void run(int p, int end, int depth) {
      if (depth > 10) return;
      while (p < end && !done) {
        int b = d[p] & 255;
        if (b == 28 && p + 2 < end) {
          push((short) ((d[p + 1] & 255) << 8 | d[p + 2] & 255));
          p += 3;
        } else if (b >= 32 && b <= 246) {
          push(b - 139);
          p++;
        } else if (b >= 247 && b <= 250 && p + 1 < end) {
          push((b - 247) * 256 + (d[p + 1] & 255) + 108);
          p += 2;
        } else if (b >= 251 && b <= 254 && p + 1 < end) {
          push(-(b - 251) * 256 - (d[p + 1] & 255) - 108);
          p += 2;
        } else if (b == 255 && p + 4 < end) {
          push(((d[p + 1] & 255) << 24 | (d[p + 2] & 255) << 16 | (d[p + 3] & 255) << 8 | d[p + 4] & 255) / 65536.0);
          p += 5;
        } else if (b == 10 || b == 29) {
          p++;
          if (top == 0) continue;
          int[] idx = b == 10 ? local : global;
          boolean[] used = b == 10 ? usedLocal : usedGlobal;
          int k = (int) stack[--top] + bias(idx == null ? 0 : idx.length - 1);
          if (idx == null || k < 0 || k + 1 >= idx.length) continue;
          used[k] = true;
          run(idx[k], idx[k + 1], depth + 1);
        } else if (b == 11) {
          return;
        } else if (b == 14) {
          done = true;
          return;
        } else if (b == 1 || b == 3 || b == 18 || b == 23) {
          stems += top / 2;
          top = 0;
          p++;
        } else if (b == 19 || b == 20) {
          stems += top / 2;
          top = 0;
          p += 1 + (stems + 7) / 8;
        } else if (b == 12) {
          top = 0;
          p += 2;
        } else {
          top = 0;
          p++;
        }
      }
    }

    private void push(double v) {
      if (top < stack.length) stack[top++] = v;
    }
  }

  private static byte[] topDict(Map<Integer, byte[]> source, int n, int charset, int fdSelect,
      int charstrings, int fdArray) throws IOException {
    var out = new ByteArrayOutputStream();
    int5(out, 391);
    int5(out, 392);
    int5(out, 0);
    op(out, ROS);
    for (int key : new int[] {FONT_MATRIX, BBOX})
      if (source.containsKey(key)) {
        out.write(source.get(key));
        op(out, key);
      }
    int5(out, n);
    op(out, CID_COUNT);
    int5(out, charset);
    op(out, 15);
    int5(out, fdSelect);
    op(out, FD_SELECT);
    int5(out, charstrings);
    op(out, CHARSTRINGS);
    int5(out, fdArray);
    op(out, FD_ARRAY);
    return out.toByteArray();
  }

  private static byte[] fdDict(int size, int offset) {
    var out = new ByteArrayOutputStream();
    int5(out, size);
    int5(out, offset);
    op(out, PRIVATE);
    return out.toByteArray();
  }

  // ------------------------------------------------------------------------ primitives

  /** INDEX offsets as absolute positions; the last entry is the end of the INDEX. */
  static int[] index(byte[] d, int p) throws IOException {
    int count = u16(d, p);
    if (count == 0) return new int[] {p + 2};
    int size = d[p + 2] & 255;
    if (size < 1 || size > 4) throw new IOException("Invalid CFF INDEX");
    int base = p + 3 + (count + 1) * size - 1;
    int[] offsets = new int[count + 1];
    for (int i = 0; i <= count; i++) {
      int v = 0;
      for (int k = 0; k < size; k++) v = v << 8 | d[p + 3 + i * size + k] & 255;
      offsets[i] = base + v;
      if (offsets[i] > d.length || i > 0 && offsets[i] < offsets[i - 1])
        throw new IOException("Invalid CFF INDEX offset");
    }
    return offsets;
  }

  private static int end(int[] index) {
    return index[index.length - 1];
  }

  static byte[] writeIndex(List<byte[]> items) {
    if (items.isEmpty()) return new byte[2];
    int data = 0;
    for (byte[] b : items) data += b.length;
    byte[] out = new byte[3 + 4 * (items.size() + 1) + data];
    put16(out, 0, items.size());
    out[2] = 4;
    int offset = 1, at = 3 + 4 * (items.size() + 1);
    for (int i = 0; i < items.size(); i++) {
      put32(out, 3 + 4 * i, offset);
      System.arraycopy(items.get(i), 0, out, at, items.get(i).length);
      at += items.get(i).length;
      offset += items.get(i).length;
    }
    put32(out, 3 + 4 * items.size(), offset);
    return out;
  }

  /** DICT operators (12 x as 1200 + x) to their raw operand bytes. */
  static Map<Integer, byte[]> dict(byte[] d, int start, int end) throws IOException {
    var out = new LinkedHashMap<Integer, byte[]>();
    int operands = start;
    for (int p = start; p < end; ) {
      int b = d[p] & 255;
      if (b <= 21) {
        int key = b, at = p++;
        if (b == 12) key = 1200 + (d[p++] & 255);
        out.put(key, Arrays.copyOfRange(d, operands, at));
        operands = p;
      } else if (b == 28) p += 3;
      else if (b == 29) p += 5;
      else if (b == 30) {
        p++;
        while (p < end && (d[p] & 0x0f) != 0x0f && (d[p] & 0xf0) != 0xf0) p++;
        p++;
      } else if (b >= 247 && b <= 254) p += 2;
      else p++;
    }
    return out;
  }

  /** Operand {@code i} of {@code key} as a number. */
  static double num(Map<Integer, byte[]> dict, int key, int i) throws IOException {
    byte[] raw = dict.get(key);
    if (raw == null) throw new IOException("Missing CFF DICT entry " + key);
    int index = 0;
    for (int p = 0; p < raw.length; index++) {
      int b = raw[p] & 255;
      double v;
      if (b == 28) {
        v = (short) ((raw[p + 1] & 255) << 8 | raw[p + 2] & 255);
        p += 3;
      } else if (b == 29) {
        v = (raw[p + 1] & 255) << 24 | (raw[p + 2] & 255) << 16 | (raw[p + 3] & 255) << 8
            | raw[p + 4] & 255;
        p += 5;
      } else if (b == 30) {
        v = 0;
        p++;
        while (p < raw.length && (raw[p] & 0x0f) != 0x0f && (raw[p] & 0xf0) != 0xf0) p++;
        p++;
      } else if (b >= 32 && b <= 246) {
        v = b - 139;
        p++;
      } else if (b >= 247 && b <= 250) {
        v = (b - 247) * 256 + (raw[p + 1] & 255) + 108;
        p += 2;
      } else if (b >= 251 && b <= 254) {
        v = -(b - 251) * 256 - (raw[p + 1] & 255) - 108;
        p += 2;
      } else throw new IOException("Invalid CFF operand");
      if (index == i) return v;
    }
    throw new IOException("Missing CFF operand");
  }

  private static void int5(ByteArrayOutputStream out, int v) {
    out.write(29);
    out.write(v >>> 24);
    out.write(v >>> 16);
    out.write(v >>> 8);
    out.write(v);
  }

  private static void op(ByteArrayOutputStream out, int key) {
    if (key >= 1200) {
      out.write(12);
      out.write(key - 1200);
    } else out.write(key);
  }

  private static byte[] ascii(String s) {
    byte[] b = new byte[s.length()];
    for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
    return b;
  }

  private static int u16(byte[] d, int at) {
    return (d[at] & 255) << 8 | d[at + 1] & 255;
  }

  private static void put16(byte[] b, int at, int v) {
    b[at] = (byte) (v >> 8);
    b[at + 1] = (byte) v;
  }

  private static void put32(byte[] b, int at, int v) {
    put16(b, at, v >>> 16);
    put16(b, at + 2, v);
  }
}
