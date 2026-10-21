package com.codenameone.wordprocessor;

import java.io.IOException;
import java.util.*;

/**
 * Apple Advanced Typography shaping as HarfBuzz / harfrust's aat module: the 'morx' extended
 * glyph metamorphosis table (rearrangement, contextual, ligature, noncontextual and insertion
 * subtables, run by the AAT state machine driver), 'kerx' kerning (formats 0, 1, 2, 4 and 6) and
 * 'trak' tracking. HarfBuzz takes this path when a face has 'morx' but its GSUB does not list the run's
 * script; positioning then uses 'kerx' (or the legacy 'kern' table), never GPOS, and marks keep
 * their advances (the "dumber" shaper).
 *
 * <p>Chains run with their default flags. harfrust maps only user-requested OpenType features
 * onto morx settings, and Wordcraft requests only "-kern" (which has no morx mapping and turns
 * off kerx pair and format 1 kerning here too) and "ssty" for math, so the result is the same
 * (as in Wordcraft). Vertical subtables are skipped: Wordcraft never shapes vertically (as in
 * Wordcraft). The "kashida-like" insertion flags are ignored, as HarfBuzz does.
 */
final class AatShaper {
  static final int DELETED = 0xffff;
  private static final int DONT_ADVANCE = 0x4000;
  private static final int[] VERBS = {0x00, 0x10, 0x01, 0x11, 0x20, 0x30, 0x02, 0x03, 0x12, 0x13,
      0x21, 0x31, 0x22, 0x32, 0x23, 0x33};

  private final PdfFonts.TrueType font;
  private final int numGlyphs;

  AatShaper(PdfFonts.TrueType font) throws IOException {
    this.font = font;
    var maxp = font.tables.get("maxp");
    numGlyphs = maxp == null ? 0xffff : font.u16(maxp[0] + 4);
  }

  /**
   * Whether harfrust would shape a horizontal run in this face with 'morx': whenever the face has
   * one, even beside a GSUB with the run's script (harfrust's apply_morx, HarfBuzz issue 2124).
   */
  static boolean applies(PdfFonts.TrueType font) {
    var morx = font.tables.get("morx");
    return morx != null && morx[1] >= 8;
  }

  /** A 32-bit field as is (AAT flags and actions use the high bit). */
  private int i32(int at) throws IOException {
    return (int) font.raw32(at);
  }

  // ---------------------------------------------------------------------------- lookup tables

  /** Value of an AAT lookup table (formats 0, 2, 4, 6, 8, 10) for {@code g}, or -1. */
  int lookup(int t, int g) throws IOException {
    return lookup(t, g, false);
  }

  /** As {@link #lookup(int, int)}; {@code wide} tables (kerx format 6 long) hold 32-bit values. */
  private int lookup(int t, int g, boolean wide) throws IOException {
    if (g == DELETED) return -1;
    int vs = wide ? 4 : 2;
    int format = font.u16(t);
    switch (format) {
      case 0:
        return g < numGlyphs ? value(t + 2 + vs * g, wide) : -1;
      case 2:
      case 4: {
        int unit = font.u16(t + 2), lo = 0, hi = font.u16(t + 4) - 1;
        while (lo <= hi) {
          int mid = (lo + hi) >>> 1, seg = t + 12 + mid * unit;
          int last = font.u16(seg), first = font.u16(seg + 2);
          if (g < first) hi = mid - 1;
          else if (g > last) lo = mid + 1;
          else return format == 2 ? value(seg + 4, wide)
              : value(t + font.u16(seg + 4) + vs * (g - first), wide);
        }
        return -1;
      }
      case 6: {
        int unit = font.u16(t + 2), lo = 0, hi = font.u16(t + 4) - 1;
        while (lo <= hi) {
          int mid = (lo + hi) >>> 1, e = t + 12 + mid * unit, key = font.u16(e);
          if (g < key) hi = mid - 1;
          else if (g > key) lo = mid + 1;
          else return value(e + 2, wide);
        }
        return -1;
      }
      case 8: {
        int i = g - font.u16(t + 2);
        return i >= 0 && i < font.u16(t + 4) ? value(t + 6 + vs * i, wide) : -1;
      }
      case 10: {
        int unit = font.u16(t + 2), i = g - font.u16(t + 4);
        if (i < 0 || i >= font.u16(t + 6)) return -1;
        int v = 0;
        for (int k = 0; k < unit; k++) v = v << 8 | font.data[t + 8 + unit * i + k] & 0xff;
        return v;
      }
      default:
        return -1;
    }
  }

  private int value(int at, boolean wide) throws IOException {
    return wide ? i32(at) : font.u16(at);
  }

  // --------------------------------------------------------------------------------- buffer

  /** HarfBuzz's buffer with an output side: positions recorded by subtables are output indices. */
  private static final class Buf {
    final ArrayList<OpenType.Glyph> in;
    final ArrayList<OpenType.Glyph> out = new ArrayList<>();
    final boolean output;
    int idx;

    Buf(List<OpenType.Glyph> glyphs, boolean output) {
      in = new ArrayList<>(glyphs);
      this.output = output;
    }

    int len() {
      return in.size();
    }

    OpenType.Glyph cur() {
      return in.get(idx);
    }

    void next() {
      if (output) out.add(in.get(idx));
      idx++;
    }

    void moveTo(int i) {
      if (!output) {
        idx = i;
        return;
      }
      while (out.size() < i && idx < in.size()) out.add(in.get(idx++));
      if (out.size() > i) {
        var tail = new ArrayList<>(out.subList(i, out.size()));
        out.subList(i, out.size()).clear();
        in.addAll(idx, tail);
      }
    }

    void replace(int id) {
      out.add(cur().copy(id));
      idx++;
    }

    /** Outputs a glyph cloned from the current (or the last) one, without consuming input. */
    void emit(int id) {
      var src = idx < in.size() ? cur() : out.isEmpty() ? in.get(in.size() - 1) : out.get(out.size() - 1);
      out.add(src.copy(id));
    }

    List<OpenType.Glyph> result() {
      if (!output) return in;
      while (idx < in.size()) out.add(in.get(idx++));
      return out;
    }
  }

  private static void merge(List<OpenType.Glyph> list, int from, int to) {
    if (to - from < 2) return;
    int start = Integer.MAX_VALUE, end = 0;
    for (int i = from; i < to; i++) {
      start = Math.min(start, list.get(i).start);
      end = Math.max(end, list.get(i).end);
    }
    for (int i = from; i < to; i++) {
      list.get(i).start = start;
      list.get(i).end = end;
    }
  }

  // ------------------------------------------------------------------------------------ morx

  /** Runs every enabled 'morx' subtable over {@code buffer} (logical order). */
  void morx(List<OpenType.Glyph> buffer, boolean rtl) throws IOException {
    int m = font.tables.get("morx")[0], version = font.u16(m);
    if (version < 2) return;
    long chains = i32(m + 4) & 0xffffffffL;
    int at = m + 8;
    for (long c = 0; c < chains; c++) {
      int flags = i32(at), length = i32(at + 4);
      int features = i32(at + 8), subtables = i32(at + 12);
      int s = at + 16 + 12 * features;
      for (int k = 0; k < subtables; k++) {
        int len = i32(s), coverage = i32(s + 4), subFlags = i32(s + 8);
        boolean verticalOnly = (coverage & 0x80000000) != 0 && (coverage & 0x20000000) == 0;
        if ((subFlags & flags) != 0 && !verticalOnly) {
          boolean descending = (coverage & 0x40000000) != 0;
          boolean reverse = (coverage & 0x10000000) != 0 ? descending : descending != rtl;
          if (reverse) Collections.reverse(buffer);
          var result = subtable(coverage & 0xff, s + 12, buffer);
          if (result != buffer) {
            buffer.clear();
            buffer.addAll(result);
          }
          if (reverse) Collections.reverse(buffer);
        }
        s += len;
      }
      at += length;
    }
  }

  private List<OpenType.Glyph> subtable(int type, int st, List<OpenType.Glyph> buffer)
      throws IOException {
    switch (type) {
      case 0: {
        var b = new Buf(buffer, false);
        drive(st, 4, b, new Rearrangement());
        return b.result();
      }
      case 1: {
        var b = new Buf(buffer, false);
        drive(st, 8, b, new Contextual(st));
        return b.result();
      }
      case 2: {
        var b = new Buf(buffer, true);
        drive(st, 6, b, new Ligature(st));
        return b.result();
      }
      case 4:
        for (var g : buffer) {
          int v = lookup(st, g.id);
          if (v >= 0) g.id = v;
        }
        return buffer;
      case 5: {
        var b = new Buf(buffer, true);
        drive(st, 8, b, new Insertion(st));
        return b.result();
      }
      default:
        return buffer;
    }
  }

  private interface Machine {
    void transition(Buf b, int entry, int flags) throws IOException;
  }

  /** HarfBuzz's StateTableDriver over an extended state table at {@code st}. */
  private void drive(int st, int entrySize, Buf b, Machine machine) throws IOException {
    int classes = i32(st), classTable = st + i32(st + 4);
    int states = st + i32(st + 8), entries = st + i32(st + 12);
    int state = 0, ops = 64 * (b.len() + 8);
    for (;;) {
      int klass = 0;
      if (b.idx < b.len()) {
        int g = b.cur().id;
        if (g == DELETED) klass = 2;
        else {
          int v = lookup(classTable, g);
          klass = v < 0 ? 1 : v;
        }
      }
      if (klass >= classes) klass = 1;
      int e = entries + entrySize * font.u16(states + 2 * (state * classes + klass));
      int next = font.u16(e), flags = font.u16(e + 2);
      machine.transition(b, e, flags);
      state = next;
      if (b.idx >= b.len()) break;
      if ((flags & DONT_ADVANCE) == 0 || ops-- <= 0) b.next();
    }
  }

  private static final class Rearrangement implements Machine {
    int start, end;

    @Override
    public void transition(Buf b, int e, int flags) {
      if ((flags & 0x8000) != 0) start = b.idx;
      if ((flags & 0x2000) != 0) end = Math.min(b.idx + 1, b.len());
      int verb = flags & 0xf;
      if (verb == 0 || start >= end) return;
      int m = VERBS[verb], l = Math.min(2, m >> 4), r = Math.min(2, m & 0xf);
      boolean reverseL = (m >> 4) == 3, reverseR = (m & 0xf) == 3;
      if (end - start < l + r || end - start > 64) return;
      var info = b.in;
      merge(info, start, Math.min(b.idx + 1, b.len()));
      merge(info, start, end);
      var left = new ArrayList<>(info.subList(start, start + l));
      var right = new ArrayList<>(info.subList(end - r, end));
      var middle = new ArrayList<>(info.subList(start + l, end - r));
      int at = start;
      for (var g : right) info.set(at++, g);
      for (var g : middle) info.set(at++, g);
      for (var g : left) info.set(at++, g);
      if (reverseL) Collections.swap(info, end - 1, end - 2);
      if (reverseR) Collections.swap(info, start, start + 1);
    }
  }

  private final class Contextual implements Machine {
    final int subs;
    boolean markSet;
    int mark;

    Contextual(int st) throws IOException {
      subs = st + i32(st + 16);
    }

    private int substitute(int index, int glyph) throws IOException {
      return index == 0xffff ? -1 : lookup(subs + i32(subs + 4 * index), glyph);
    }

    @Override
    public void transition(Buf b, int e, int flags) throws IOException {
      // CoreText substitutes nothing at end of text unless a mark was set.
      if (b.idx == b.len() && !markSet) return;
      var info = b.in;
      if (mark < info.size()) {
        int v = substitute(font.u16(e + 4), info.get(mark).id);
        if (v >= 0) info.get(mark).id = v;
      }
      int i = Math.min(b.idx, b.len() - 1);
      int v = substitute(font.u16(e + 6), info.get(i).id);
      if (v >= 0) info.get(i).id = v;
      if ((flags & 0x8000) != 0) {
        markSet = true;
        mark = b.idx;
      }
    }
  }

  private final class Ligature implements Machine {
    final int actions, components, ligatures;
    final int[] positions = new int[64];
    int matched;

    Ligature(int st) throws IOException {
      actions = st + i32(st + 16);
      components = st + i32(st + 20);
      ligatures = st + i32(st + 24);
    }

    @Override
    public void transition(Buf b, int e, int flags) throws IOException {
      if ((flags & 0x8000) != 0) {
        // Never mark the same index twice (DontAdvance).
        if (matched > 0 && positions[(matched - 1) % 64] == b.out.size()) matched--;
        positions[matched++ % 64] = b.out.size();
      }
      if ((flags & 0x2000) == 0 || matched == 0) return;
      int end = b.out.size(), actionIndex = font.u16(e + 4), cursor = matched, ligature = 0;
      int action;
      do {
        if (cursor == 0) {
          matched = 0;
          break;
        }
        b.moveTo(positions[--cursor % 64]);
        if (b.idx >= b.len()) return;
        action = i32(actions + 4 * actionIndex);
        int offset = action & 0x3fffffff;
        if ((offset & 0x20000000) != 0) offset |= 0xc0000000;
        ligature += font.u16(components + 2 * (b.cur().id + offset));
        if ((action & 0xc0000000) != 0) {
          b.replace(font.u16(ligatures + 2 * ligature));
          int ligEnd = positions[(matched - 1) % 64] + 1;
          while (matched - 1 > cursor) {
            b.moveTo(positions[--matched % 64]);
            b.replace(DELETED);
          }
          b.moveTo(ligEnd);
          merge(b.out, positions[cursor % 64], b.out.size());
        }
        actionIndex++;
      } while ((action & 0x80000000) == 0);
      b.moveTo(end);
    }
  }

  private final class Insertion implements Machine {
    final int glyphs;
    int mark;

    Insertion(int st) throws IOException {
      glyphs = st + i32(st + 16);
    }

    private void insert(Buf b, int index, int count, boolean before) throws IOException {
      if (b.idx < b.len() && !before) b.out.add(b.cur());
      for (int k = 0; k < count; k++) b.emit(font.u16(glyphs + 2 * (index + k)));
      if (b.idx < b.len() && !before) b.idx++;
    }

    @Override
    public void transition(Buf b, int e, int flags) throws IOException {
      int markLoc = b.out.size();
      int marked = font.u16(e + 6);
      if (marked != 0xffff) {
        int count = flags & 0x1f, end = b.out.size();
        b.moveTo(mark);
        insert(b, marked, count, (flags & 0x0400) != 0);
        b.moveTo(end + count);
      }
      if ((flags & 0x8000) != 0) mark = markLoc;
      int current = font.u16(e + 4);
      if (current != 0xffff) {
        int count = (flags & 0x03e0) >> 5, end = b.out.size();
        insert(b, current, count, (flags & 0x0800) != 0);
        b.moveTo((flags & DONT_ADVANCE) != 0 ? end : end + count);
      }
    }
  }

  /**
   * Drops the glyphs ligature subtables deleted, their text joining the neighbouring glyph; as
   * harfrust, before GPOS but after 'kerx' / 'kern' (deleted glyphs break kerning pairs).
   */
  static void removeDeleted(List<OpenType.Glyph> buffer) {
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      if (g.id != DELETED) continue;
      if (buffer.size() > 1) {
        var keep = buffer.get(i > 0 ? i - 1 : i + 1);
        keep.start = Math.min(keep.start, g.start);
        keep.end = Math.max(keep.end, g.end);
      }
      buffer.remove(i--);
    }
  }

  // ------------------------------------------------------------------------------ kerx / trak

  /** Whether the face has 'kerx', which then replaces GPOS and 'kern' positioning. */
  boolean hasKerx() {
    var t = font.tables.get("kerx");
    return t != null && t[1] >= 8;
  }

  /**
   * Applies the horizontal 'kerx' subtables as harfrust: pair kerning (formats 0, 2 and 6) and
   * state-machine kerning (format 1) when {@code kerning} is on, cross-stream values as vertical
   * offsets, and format 4 anchor attachment (anchor points from 'ankr' or coordinates). The
   * buffer is in visual order.
   */
  void kerx(List<OpenType.Glyph> buffer, boolean kerning) throws IOException {
    int t = font.tables.get("kerx")[0];
    int count = i32(t + 4), s = t + 8;
    for (int k = 0; k < count; k++) {
      int len = i32(s), coverage = i32(s + 4), tuples = i32(s + 8);
      int format = coverage & 0xff;
      boolean cross = (coverage & 0x40000000) != 0;
      if ((coverage & 0x80000000) == 0) {
        if (format == 1) stateKerning(buffer, s, tuples, cross, kerning);
        else if (format == 4) attach(buffer, s);
        else if (kerning && (format == 0 && tuples == 0 || format == 2 && tuples == 0
            || format == 6)) {
          // As harfrust's apply_simple_kerning, only the second glyph skips marks.
          for (int i = 0; i < buffer.size(); i++) {
            int j = i + 1;
            while (j < buffer.size() && nonspacing(buffer.get(j).cp)) j++;
            if (j >= buffer.size()) break;
            var a = buffer.get(i);
            var b = buffer.get(j);
            int kern = format == 0 ? pair0(s, a.id, b.id) : format == 2 ? pair2(s, len, a.id, b.id)
                : pair6(s, len, tuples, a.id, b.id);
            if (kern != 0) {
              if (cross) b.dy = kern;
              else {
                int k1 = kern >> 1, k2 = kern - k1;
                a.adv += k1;
                b.adv += k2;
                b.dx += k2;
              }
            }
            i = j - 1;
          }
        }
      }
      s += len;
    }
  }

  /** Format 6: row and column lookups (16- or 32-bit) index the kerning array, maybe a tuple. */
  private int pair6(int s, int len, int tuples, int left, int right) throws IOException {
    boolean wide = (i32(s + 12) & 1) != 0;
    int rows = s + i32(s + 20), columns = s + i32(s + 24), array = s + i32(s + 28);
    int r = lookup(rows, left, wide), c = lookup(columns, right, wide);
    long idx = (long) Math.max(r, 0) + Math.max(c, 0);
    int at = (int) (array + idx * (wide ? 4 : 2));
    if (at < s || at + (wide ? 4 : 2) > s + len) return 0;
    int v = wide ? i32(at) : font.s16(at);
    if (tuples != 0) {
      int vector = s + i32(s + 32) + 2 * (v >> 1);
      if (vector + 2 > s + len) return 0;
      v = font.s16(vector);
    }
    return v;
  }

  /**
   * Format 1 contextual kerning: entries push glyphs on an eight-deep stack and an action pops
   * them, applying one value each until an odd value ends the list (harfrust's Driver1).
   */
  private void stateKerning(List<OpenType.Glyph> buffer, int s, int tuples, boolean cross,
      boolean kerning) throws IOException {
    int st = s + 12, values = st + i32(st + 16);
    int[] stack = new int[8];
    int[] depth = {0};
    var b = new Buf(buffer, false);
    int step = Math.max(tuples, 1);
    drive(st, 6, b, (buf, e, flags) -> {
      if ((flags & 0x2000) != 0) depth[0] = 0;
      if ((flags & 0x8000) != 0) {
        if (depth[0] < stack.length) stack[depth[0]++] = buf.idx;
        else depth[0] = 0;
      }
      int action = font.u16(e + 4);
      if (action == 0xffff || depth[0] == 0) return;
      boolean last = false;
      while (!last && depth[0] != 0) {
        int idx = stack[--depth[0]];
        int v = font.s16(values + 2 * action);
        action += step;
        if (idx >= buffer.size()) continue;
        last = (v & 1) != 0;
        v &= ~1;
        var g = buffer.get(idx);
        if (cross) g.dy = v == -0x8000 ? 0 : g.dy + v;
        else if (kerning) {
          g.adv += v;
          g.dx += v;
        }
      }
    });
  }

  /**
   * Format 4 attachment: an entry with the mark flag records the mark glyph; an action places the
   * current glyph so its anchor meets the mark's (anchor points from 'ankr', or coordinates).
   */
  private void attach(List<OpenType.Glyph> buffer, int s) throws IOException {
    int st = s + 12, flags4 = i32(st + 16), type = flags4 >>> 30 & 3;
    int actions = st + (flags4 & 0xffffff);
    var ankr = font.tables.get("ankr");
    int[] mark = {-1};
    var b = new Buf(buffer, false);
    drive(st, 6, b, (buf, e, flags) -> {
      int action = font.u16(e + 4);
      if (mark[0] >= 0 && action != 0xffff && buf.idx < buffer.size()) {
        var cur = buffer.get(buf.idx);
        var base = buffer.get(mark[0]);
        int mx = 0, my = 0, cx = 0, cy = 0;
        boolean placed = true;
        if (type == 1 && ankr != null) {
          int[] m = anchor(ankr[0], base.id, font.u16(actions + 4 * action));
          int[] c = anchor(ankr[0], cur.id, font.u16(actions + 4 * action + 2));
          mx = m[0];
          my = m[1];
          cx = c[0];
          cy = c[1];
        } else if (type == 2) {
          int at = actions + 8 * action;
          mx = font.s16(at);
          my = font.s16(at + 2);
          cx = font.s16(at + 4);
          cy = font.s16(at + 6);
        } else placed = false;
        if (placed) {
          // HarfBuzz's mark attachment chain: offset from the mark glyph's own position.
          int between = 0;
          for (int i = Math.min(mark[0], buf.idx); i < Math.max(mark[0], buf.idx); i++)
            between += buffer.get(i).adv;
          int shift = mark[0] < buf.idx ? -between : between;
          cur.dx = mx - cx + base.dx + shift;
          cur.dy = my - cy + base.dy;
        }
      }
      if ((flags & 0x8000) != 0) mark[0] = buf.idx;
    });
  }

  /** Anchor point {@code index} of {@code glyph} in the 'ankr' table at {@code t}, or 0,0. */
  private int[] anchor(int t, int glyph, int index) throws IOException {
    int v = lookup(t + i32(t + 4), glyph);
    if (v < 0) return new int[2];
    int data = t + i32(t + 8) + v, n = i32(data);
    if (index >= n) return new int[2];
    return new int[] {font.s16(data + 4 + 4 * index), font.s16(data + 6 + 4 * index)};
  }

  private int pair0(int s, int left, int right) throws IOException {
    int lo = 0, hi = i32(s + 12) - 1;
    long key = (long) left << 16 | right;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1, p = s + 28 + 6 * mid;
      long k = (long) font.u16(p) << 16 | font.u16(p + 2);
      if (key < k) hi = mid - 1;
      else if (key > k) lo = mid + 1;
      else return font.s16(p + 4);
    }
    return 0;
  }

  private int pair2(int s, int len, int left, int right) throws IOException {
    int l = lookup(s + i32(s + 16), left), r = lookup(s + i32(s + 20), right);
    // HarfBuzz kerns only glyphs both class tables list (its first / second glyph sets).
    // As harfrust (read-fonts), l + r indexes the value array, which ends with the subtable.
    int at = i32(s + 24) + 2 * (l + r);
    return l < 0 || r < 0 || at + 2 > len ? 0 : font.s16(s + at);
  }

  /**
   * Applies the 'trak' track-0 value for {@code ptem} (points) to each cluster's first glyph, half
   * of it as offset, as HarfBuzz. Nothing when the size is unknown or the face has no 'trak'.
   */
  void track(List<OpenType.Glyph> buffer, float ptem) throws IOException {
    var tr = font.tables.get("trak");
    if (tr == null || ptem <= 0) return;
    int t = tr[0], horiz = font.u16(t + 6);
    if (horiz == 0) return;
    int data = t + horiz, tracks = font.u16(data), sizes = font.u16(data + 2);
    int sizeTable = t + i32(data + 4);
    if (sizes == 0) return;
    for (int k = 0; k < tracks; k++) {
      int entry = data + 8 + 8 * k;
      if (i32(entry) != 0) continue;
      int values = t + font.u16(entry + 6);
      float value = font.s16(values);
      for (int i = 0; i < sizes; i++) {
        float size = i32(sizeTable + 4 * i) / 65536f;
        if (ptem <= size || i == sizes - 1) {
          if (i == 0 || ptem >= size) value = font.s16(values + 2 * i);
          else {
            float prev = i32(sizeTable + 4 * (i - 1)) / 65536f;
            float a = font.s16(values + 2 * (i - 1)), z = font.s16(values + 2 * i);
            value = a + (z - a) * (ptem - prev) / (size - prev);
          }
          break;
        }
      }
      int add = Math.round(value);
      if (add == 0) return;
      for (int i = 0; i < buffer.size(); i++) {
        var g = buffer.get(i);
        if (i > 0 && g.start < buffer.get(i - 1).end) continue;
        g.adv += add;
        g.dx += add / 2;
      }
      return;
    }
  }

  /** Unicode nonspacing marks of the scripts AAT fonts commonly serve (HarfBuzz's synthesized GDEF). */
  static boolean nonspacing(int cp) {
    if (PdfShaping.mark(cp)) return true;
    if (cp >= 0x0d00 && cp <= 0x0d7f)
      return cp <= 0x0d01 || cp == 0x0d3b || cp == 0x0d3c || cp >= 0x0d41 && cp <= 0x0d44
          || cp == 0x0d4d || cp == 0x0d62 || cp == 0x0d63;
    if (cp >= 0x0d80 && cp <= 0x0dff)
      return cp == 0x0d81 || cp == 0x0dca || cp >= 0x0dd2 && cp <= 0x0dd4 || cp == 0x0dd6;
    if (cp >= 0x0f00 && cp <= 0x0fff)
      return cp == 0x0f18 || cp == 0x0f19 || cp == 0x0f35 || cp == 0x0f37 || cp == 0x0f39
          || cp >= 0x0f71 && cp <= 0x0f7e || cp >= 0x0f80 && cp <= 0x0f84 || cp == 0x0f86
          || cp == 0x0f87 || cp >= 0x0f8d && cp <= 0x0fbc || cp == 0x0fc6;
    return false;
  }
}
