package com.codenameone.wordprocessor;

import java.io.IOException;
import java.util.*;

/**
 * A small OpenType layout engine over one TrueType face: GSUB lookup types 1, 2, 4, 5, 6 and 7
 * and GPOS lookup types 1, 2, 4, 5, 6, 7, 8 and 9, with lookup flags (ignore base / ligature /
 * marks, mark attachment type, mark filtering sets) from GDEF. Features are applied by mask, so a
 * shaper can enable a feature for some glyphs only (Arabic joining forms, Indic half forms).
 * Not covered: GSUB alternate (3) and reverse chaining (8) lookups and variable fonts; faces
 * shaped with 'morx' go through {@link AatShaper} instead.
 */
final class OpenType {
  /** One glyph of the shaping buffer; positions are in font units. */
  static final class Glyph {
    int id, cp, start, end, mask, cat, pos, flags, syllable, adv, dx, dy, components = 1;

    Glyph(int id, int cp, int start, int end, int mask) {
      this.id = id;
      this.cp = cp;
      this.start = start;
      this.end = end;
      this.mask = mask;
    }

    Glyph copy(int newId) {
      var g = new Glyph(newId, cp, start, end, mask);
      g.cat = cat;
      g.pos = pos;
      g.flags = flags;
      g.syllable = syllable;
      return g;
    }
  }

  /**
   * Glyph flags: substituted, ligated and multiplied as HarfBuzz's glyph properties (bit 8 is
   * PdfShaping's MERGED); HIDDEN marks a default-ignorable shown as a zero-advance space.
   */
  static final int SUBSTITUTED = 1, LIGATED = 2, BASE = 4, MULTIPLIED = 16, HIDDEN = 32;

  private final PdfFonts.TrueType font;
  /**
   * Whether GSUB context matching stops at syllable boundaries (HarfBuzz's F_PER_SYLLABLE): a
   * glyph of another syllable (non-zero {@link Glyph#syllable}) fails the match.
   */
  boolean perSyllable;
  /** Whether the buffer being positioned is a right-to-left run in visual order. */
  boolean rtl;
  private final int gsub, gpos, gdef;
  private final Map<String, int[]> featureCache = new HashMap<>();

  OpenType(PdfFonts.TrueType font) {
    this.font = font;
    gsub = offset("GSUB");
    gpos = offset("GPOS");
    gdef = offset("GDEF");
  }

  private int offset(String tag) {
    var t = font.tables.get(tag);
    return t == null || t[1] < 10 ? -1 : t[0];
  }

  boolean hasGsub() {
    return gsub >= 0;
  }

  boolean hasGpos() {
    return gpos >= 0;
  }

  /** Whether the GSUB or GPOS table lists {@code script}. */
  boolean hasScript(boolean substitution, String script) {
    int table = substitution ? gsub : gpos;
    try {
      return table >= 0 && script(table, new String[] {script}) >= 0;
    } catch (IOException e) {
      return false;
    }
  }

  /** GDEF glyph class: 1 base, 2 ligature, 3 mark, 4 component, 0 unclassified. */
  int glyphClass(int glyph) {
    if (gdef < 0) return 0;
    try {
      int at = font.u16(gdef + 4);
      return at == 0 ? 0 : font.classOf(gdef + at, glyph);
    } catch (IOException e) {
      return 0;
    }
  }

  private int markAttachClass(int glyph) throws IOException {
    int at = gdef < 0 ? 0 : font.u16(gdef + 10);
    return at == 0 ? 0 : font.classOf(gdef + at, glyph);
  }

  private boolean inMarkSet(int set, int glyph) throws IOException {
    if (gdef < 0 || font.u16(gdef + 2) < 2) return false;
    int sets = font.u16(gdef + 12);
    if (sets == 0 || set >= font.u16(gdef + sets + 2)) return false;
    return font.coverage(gdef + sets + font.u32(gdef + sets + 4 + 4 * set), glyph) >= 0;
  }

  /** Script table offset for the first of {@code tags} the table lists (then DFLT), or -1. */
  private int script(int table, String[] tags) throws IOException {
    int list = table + font.u16(table + 4), n = font.u16(list);
    for (String tag : tags)
      for (int i = 0; i < n; i++) {
        int rec = list + 2 + 6 * i;
        if (tag.equals(font.tag(rec)))
          return list + font.u16(rec + 4);
      }
    return -1;
  }

  /** Lookup indices (sorted) of {@code feature} for the first matching script, default language. */
  int[] lookups(boolean substitution, String[] scripts, String feature) {
    var sb = new StringBuilder().append(substitution);
    for (String sc : scripts) sb.append(',').append(sc);
    var key = sb.append(':').append(feature).toString();
    var cached = featureCache.get(key);
    if (cached != null) return cached;
    var result = new TreeSet<Integer>();
    int table = substitution ? gsub : gpos;
    try {
      if (table >= 0) {
        // harfrust's script selection: the run's tags, then DFLT, dflt and latn.
        var all = new String[scripts.length + 3];
        System.arraycopy(scripts, 0, all, 0, scripts.length);
        all[scripts.length] = "DFLT";
        all[scripts.length + 1] = "dflt";
        all[scripts.length + 2] = "latn";
        int script = script(table, all);
        if (script >= 0 && font.u16(script) != 0) {
          int lang = script + font.u16(script),
              features = table + font.u16(table + 6),
              count = font.u16(features),
              lookupCount = font.u16(table + font.u16(table + 8));
          for (int i = 0, n = font.u16(lang + 4); i < n; i++) {
            int index = font.u16(lang + 6 + 2 * i);
            if (index >= count) continue;
            int rec = features + 2 + 6 * index;
            if (!feature.equals(font.tag(rec))) continue;
            int f = features + font.u16(rec + 4);
            for (int k = 0, c = font.u16(f + 2); k < c; k++) {
              int l = font.u16(f + 4 + 2 * k);
              if (l < lookupCount) result.add(l);
            }
          }
        }
      }
    } catch (IOException | RuntimeException malformed) {
      result.clear();
    }
    int[] out = new int[result.size()];
    int i = 0;
    for (int l : result) out[i++] = l;
    featureCache.put(key, out);
    return out;
  }

  /** Applies each feature's lookups (in lookup order within a stage) to glyphs whose mask matches. */
  void apply(boolean substitution, String[] scripts, List<Glyph> buffer, String[] features,
      int[] masks) {
    var order = new TreeMap<Integer, Integer>();
    for (int f = 0; f < features.length; f++)
      for (int l : lookups(substitution, scripts, features[f]))
        order.merge(l, masks[f], (a, b) -> a | b);
    for (var e : order.entrySet()) {
      try {
        runLookup(substitution, e.getKey(), buffer, e.getValue());
      } catch (IOException | RuntimeException malformed) {
        // A malformed lookup is skipped; the remaining ones still apply.
      }
    }
  }

  /**
   * Whether a GSUB lookup of {@code feature} would apply to exactly {@code glyphs}, as HarfBuzz's
   * would_substitute: single, multiple and alternate lookups for one glyph, ligatures with
   * exactly these components, and context lookups matching from the first glyph.
   */
  boolean wouldSubstitute(String[] scripts, String feature, int... glyphs) {
    for (int l : lookups(true, scripts, feature)) {
      try {
        int lookup = lookupTable(true, l), type = font.u16(lookup);
        for (int s = 0, n = font.u16(lookup + 4); s < n; s++) {
          int sub = lookup + font.u16(lookup + 6 + 2 * s), t = type;
          if (t == 7) {
            t = font.u16(sub + 2);
            sub = sub + font.u32(sub + 4);
          }
          if (t == 5 || t == 6) {
            var buffer = new ArrayList<Glyph>();
            for (int k = 0; k < glyphs.length; k++) buffer.add(new Glyph(glyphs[k], 0, k, k + 1, -1));
            if (context(true, t, sub, lookup, 0, buffer, 0, 0) >= 0) return true;
            continue;
          }
          int index = font.coverage(sub + font.u16(sub + 2), glyphs[0]);
          if (index < 0) continue;
          if (t <= 3 && glyphs.length == 1) return true;
          if (t != 4 || index >= font.u16(sub + 4)) continue;
          int set = sub + font.u16(sub + 6 + 2 * index);
          for (int k = 0, m = font.u16(set); k < m; k++) {
            int lig = set + font.u16(set + 2 + 2 * k), count = font.u16(lig + 2);
            boolean match = count == glyphs.length;
            for (int c = 1; c < count && match; c++) match = font.u16(lig + 4 + 2 * (c - 1)) == glyphs[c];
            if (match) return true;
          }
        }
      } catch (IOException | RuntimeException malformed) {
        // A malformed lookup does not apply.
      }
    }
    return false;
  }

  // ------------------------------------------------------------------------------- lookups

  private int lookupTable(boolean substitution, int index) throws IOException {
    int table = substitution ? gsub : gpos, list = table + font.u16(table + 8);
    if (index >= font.u16(list)) throw new IOException("Invalid lookup index");
    return list + font.u16(list + 2 + 2 * index);
  }

  private void runLookup(boolean substitution, int index, List<Glyph> buffer, int mask)
      throws IOException {
    int lookup = lookupTable(substitution, index), flag = font.u16(lookup + 2);
    positioning = !substitution;
    for (int i = 0; i < buffer.size(); ) {
      var g = buffer.get(i);
      if ((g.mask & mask) == 0 || skip(lookup, flag, g)) {
        i++;
        continue;
      }
      int next = applyAt(substitution, lookup, buffer, i, 0);
      i = next < 0 ? i + 1 : Math.max(next, i + 1);
    }
  }

  /** Whether the lookup being run is a GPOS one (set by runLookup). */
  boolean positioning;

  /**
   * A default-ignorable character (ZWJ, ZWNJ, variation selectors...) not replaced by GSUB,
   * as HarfBuzz/harfrust's _hb_glyph_info_is_default_ignorable (harfrust's is_default_ignorable
   * table: U+115F, U+1160, U+3164, U+FFA0, U+180F and U+1BCA0..1BCA3 are not in it).
   */
  static boolean ignorable(Glyph g) {
    return (g.flags & SUBSTITUTED) == 0 && ignorable(g.cp);
  }

  static boolean ignorable(int c) {
    return c == 0xad || c == 0x34f || c == 0x61c || c >= 0x17b4 && c <= 0x17b5
        || c >= 0x180b && c <= 0x180e || c >= 0x200b && c <= 0x200f
        || c >= 0x202a && c <= 0x202e || c >= 0x2060 && c <= 0x206f
        || c >= 0xfe00 && c <= 0xfe0f || c == 0xfeff || c >= 0xfff0 && c <= 0xfff8
        || c >= 0x1d173 && c <= 0x1d17a || c >= 0xe0000 && c <= 0xe0fff;
  }

  /**
   * harfrust's HIDDEN unicode property: CGJ, Mongolian free variation selectors 1-3 and TAG
   * characters, default ignorables that GSUB matching does not skip (GPOS does).
   */
  static boolean hidden(int c) {
    return c == 0x34f || c >= 0x180b && c <= 0x180d || c >= 0xe0020 && c <= 0xe007f;
  }

  /** The glyph a lookup expects at a matched position, for skippable glyphs it may still match. */
  interface Want {
    boolean ok(int glyph) throws IOException;
  }

  /** Whether lookup flag {@code flag} ignores {@code g}. */
  private boolean skip(int lookup, int flag, Glyph g) throws IOException {
    if (flag == 0) return false;
    int cls = glyphClass(g.id);
    if (cls == 1 && (flag & 2) != 0 || cls == 2 && (flag & 4) != 0 || cls == 3 && (flag & 8) != 0)
      return true;
    if (cls == 3) {
      if ((flag & 0x10) != 0 && !inMarkSet(font.u16(lookup + 6 + 2 * font.u16(lookup + 4)), g.id))
        return true;
      int type = flag >>> 8;
      if (type != 0 && markAttachClass(g.id) != type) return true;
    }
    return false;
  }

  private int nextIndex(int lookup, int flag, List<Glyph> buffer, int i, int step)
      throws IOException {
    return nextIndex(lookup, flag, buffer, i, step, null);
  }

  /**
   * The next position from {@code i} the lookup matches against, as harfrust's skippy iterator:
   * glyphs the lookup flag ignores are skipped; a default ignorable is SKIP_MAYBE (skipped
   * unless it is the glyph {@code want} expects). GPOS treats every default ignorable so
   * (ignore_zwnj, ignore_zwj and ignore_hidden); GSUB never skips hidden ones (CGJ, Mongolian
   * FVS, TAG) and keeps ZWJ/ZWNJ, since its auto-ZWJ depends on per-feature manual-joiner flags
   * not modelled here. The glyph a lookup starts at is never skipped for being ignorable.
   */
  int nextIndex(int lookup, int flag, List<Glyph> buffer, int i, int step, Want want)
      throws IOException {
    for (int j = i + step; j >= 0 && j < buffer.size(); j += step) {
      var g = buffer.get(j);
      if (skip(lookup, flag, g)) continue;
      boolean maybe = ignorable(g)
          && (positioning || !hidden(g.cp) && g.cp != 0x200c && g.cp != 0x200d);
      if (maybe && (want == null || !want.ok(g.id))) continue;
      int syllable = buffer.get(i).syllable;
      return perSyllable && syllable != 0 && g.syllable != syllable ? -1 : j;
    }
    return -1;
  }

  /**
   * Applies lookup at buffer position {@code i}; returns the index after the affected glyphs, or
   * -1 when no subtable matched.
   */
  private int applyAt(boolean substitution, int lookup, List<Glyph> buffer, int i, int depth)
      throws IOException {
    if (depth > 8) return -1;
    int type = font.u16(lookup), flag = font.u16(lookup + 2);
    for (int s = 0, n = font.u16(lookup + 4); s < n; s++) {
      int sub = lookup + font.u16(lookup + 6 + 2 * s), t = type;
      if (substitution && t == 7 || !substitution && t == 9) {
        t = font.u16(sub + 2);
        sub = sub + font.u32(sub + 4);
      }
      int r =
          substitution
              ? substitute(t, sub, lookup, flag, buffer, i, depth)
              : position(t, sub, lookup, flag, buffer, i, depth);
      if (r >= 0) return r;
    }
    return -1;
  }

  private int substitute(int type, int sub, int lookup, int flag, List<Glyph> buffer, int i,
      int depth) throws IOException {
    var g = buffer.get(i);
    int format = font.u16(sub);
    if (type == 5 || type == 6) return context(true, type, sub, lookup, flag, buffer, i, depth);
    int index = font.coverage(sub + font.u16(sub + 2), g.id);
    if (index < 0) return -1;
    if (type == 1) {
      int id =
          format == 1
              ? (g.id + font.s16(sub + 4)) & 0xffff
              : index < font.u16(sub + 4) ? font.u16(sub + 6 + 2 * index) : -1;
      if (id < 0 || id >= font.glyphs) return -1;
      g.id = id;
      g.flags |= SUBSTITUTED;
      return i + 1;
    }
    if (type == 2) {
      if (index >= font.u16(sub + 4)) return -1;
      int seq = sub + font.u16(sub + 6 + 2 * index), count = font.u16(seq);
      if (count == 0 || count > 32) return -1;
      g.id = font.u16(seq + 2);
      g.flags |= SUBSTITUTED | (count > 1 ? MULTIPLIED : 0);
      for (int k = 1; k < count; k++) {
        var extra = g.copy(font.u16(seq + 2 + 2 * k));
        buffer.add(i + k, extra);
      }
      return i + count;
    }
    if (type == 3) {
      // AlternateSubst: a feature turned on with value 1 picks the first alternate, as HarfBuzz.
      if (index >= font.u16(sub + 4)) return -1;
      int set = sub + font.u16(sub + 6 + 2 * index);
      if (font.u16(set) == 0) return -1;
      int id = font.u16(set + 2);
      if (id >= font.glyphs) return -1;
      g.id = id;
      g.flags |= SUBSTITUTED;
      return i + 1;
    }
    if (type == 4) {
      if (index >= font.u16(sub + 4)) return -1;
      int set = sub + font.u16(sub + 6 + 2 * index);
      for (int l = 0, n = font.u16(set); l < n; l++) {
        int lig = set + font.u16(set + 2 + 2 * l), count = font.u16(lig + 2);
        int[] at = new int[count];
        at[0] = i;
        boolean match = count > 0;
        for (int k = 1; k < count && match; k++) {
          int expected = font.u16(lig + 4 + 2 * (k - 1));
          at[k] = nextIndex(lookup, flag, buffer, at[k - 1], 1, id -> id == expected);
          match = at[k] >= 0 && buffer.get(at[k]).id == expected;
        }
        if (!match) continue;
        int start = g.start, end = g.end, flags = g.flags;
        for (int k = 0; k < count; k++) {
          var c = buffer.get(at[k]);
          start = Math.min(start, c.start);
          end = Math.max(end, c.end);
          flags |= c.flags;
        }
        for (int k = i; k <= at[count - 1]; k++) {
          buffer.get(k).start = start;
          buffer.get(k).end = end;
        }
        g.id = font.u16(lig);
        g.flags = (flags & ~MULTIPLIED) | (g.flags & MULTIPLIED) | SUBSTITUTED
            | (count > 1 ? LIGATED : 0);
        g.components = count;
        for (int k = count - 1; k > 0; k--) buffer.remove(at[k]);
        return i + 1;
      }
      return -1;
    }
    return -1;
  }

  // ----------------------------------------------------------------------- shared context

  /** Context (5/7) and chaining context (6/8) lookups of formats 1, 2 and 3. */
  private int context(boolean substitution, int type, int sub, int lookup, int flag,
      List<Glyph> buffer, int i, int depth) throws IOException {
    int format = font.u16(sub);
    boolean chain = substitution ? type == 6 : type == 8;
    var g = buffer.get(i);
    if (format == 3) {
      int at = sub + 2;
      int backtrack = 0, lookahead = 0, backAt = 0, lookAt = 0;
      if (chain) {
        backtrack = font.u16(at);
        backAt = at + 2;
        at += 2 + 2 * backtrack;
      }
      int input = font.u16(at), inputAt;
      if (chain) {
        inputAt = at + 2;
        at += 2 + 2 * input;
        lookahead = font.u16(at);
        lookAt = at + 2;
        at += 2 + 2 * lookahead;
      } else {
        int records = font.u16(at + 2);
        inputAt = at + 4;
        int[] positions = matchCoverage(lookup, flag, buffer, i, input, inputAt, sub);
        return positions == null ? -1
            : apply(substitution, buffer, positions, inputAt + 2 * input, records, depth);
      }
      int[] positions = matchCoverage(lookup, flag, buffer, i, input, inputAt, sub);
      if (positions == null) return -1;
      if (!sideCoverage(lookup, flag, buffer, i, -1, backtrack, backAt, sub)
          || !sideCoverage(lookup, flag, buffer, positions[positions.length - 1], 1, lookahead,
              lookAt, sub)) return -1;
      return apply(substitution, buffer, positions, at + 2, font.u16(at), depth);
    }
    int index = font.coverage(sub + font.u16(sub + 2), g.id);
    if (index < 0) return -1;
    if (format == 1 || format == 2) {
      int backDef = 0, inputDef = 0, lookDef = 0, setsAt;
      if (format == 2) {
        if (chain) {
          backDef = sub + font.u16(sub + 4);
          inputDef = sub + font.u16(sub + 6);
          lookDef = sub + font.u16(sub + 8);
          setsAt = sub + 10;
        } else {
          inputDef = sub + font.u16(sub + 4);
          setsAt = sub + 6;
        }
        index = font.classOf(inputDef, g.id);
      } else setsAt = sub + 4;
      if (index >= font.u16(setsAt) || font.u16(setsAt + 2 + 2 * index) == 0) return -1;
      int set = sub + font.u16(setsAt + 2 + 2 * index);
      for (int r = 0, n = font.u16(set); r < n; r++) {
        int rule = set + font.u16(set + 2 + 2 * r), at = rule;
        int backtrack = 0, backAt = 0;
        if (chain) {
          backtrack = font.u16(at);
          backAt = at + 2;
          at += 2 + 2 * backtrack;
        }
        int input = font.u16(at), inputAt, lookahead = 0, lookAt = 0, records;
        if (chain) {
          inputAt = at + 2;
          at = inputAt + 2 * (input - 1);
          lookahead = font.u16(at);
          lookAt = at + 2;
          at += 2 + 2 * lookahead;
          records = font.u16(at);
          at += 2;
        } else {
          records = font.u16(at + 2);
          inputAt = at + 4;
          at = inputAt + 2 * (input - 1);
        }
        if (input == 0) continue;
        int[] positions = new int[input];
        positions[0] = i;
        boolean ok = true;
        for (int k = 1; k < input && ok; k++) {
          int value = font.u16(inputAt + 2 * (k - 1)), def = inputDef;
          Want want = id -> matches(format, def, id, value);
          positions[k] = nextIndex(lookup, flag, buffer, positions[k - 1], 1, want);
          ok = positions[k] >= 0 && want.ok(buffer.get(positions[k]).id);
        }
        if (!ok) continue;
        int p = i;
        for (int k = 0; k < backtrack && ok; k++) {
          int value = font.u16(backAt + 2 * k), def = backDef;
          Want want = id -> matches(format, def, id, value);
          p = nextIndex(lookup, flag, buffer, p, -1, want);
          ok = p >= 0 && want.ok(buffer.get(p).id);
        }
        p = positions[input - 1];
        for (int k = 0; k < lookahead && ok; k++) {
          int value = font.u16(lookAt + 2 * k), def = lookDef;
          Want want = id -> matches(format, def, id, value);
          p = nextIndex(lookup, flag, buffer, p, 1, want);
          ok = p >= 0 && want.ok(buffer.get(p).id);
        }
        if (!ok) continue;
        return apply(substitution, buffer, positions, at, records, depth);
      }
    }
    return -1;
  }

  private int[] matchCoverage(int lookup, int flag, List<Glyph> buffer, int i, int input,
      int inputAt, int sub) throws IOException {
    if (input == 0) return null;
    int[] positions = new int[input];
    positions[0] = i;
    if (font.coverage(sub + font.u16(inputAt), buffer.get(i).id) < 0) return null;
    for (int k = 1; k < input; k++) {
      int cover = sub + font.u16(inputAt + 2 * k);
      Want want = id -> font.coverage(cover, id) >= 0;
      positions[k] = nextIndex(lookup, flag, buffer, positions[k - 1], 1, want);
      if (positions[k] < 0 || !want.ok(buffer.get(positions[k]).id)) return null;
    }
    return positions;
  }
  private boolean sideCoverage(int lookup, int flag, List<Glyph> buffer, int from, int step,
      int count, int listAt, int sub) throws IOException {
    int p = from;
    for (int k = 0; k < count; k++) {
      int cover = sub + font.u16(listAt + 2 * k);
      Want want = id -> font.coverage(cover, id) >= 0;
      p = nextIndex(lookup, flag, buffer, p, step, want);
      if (p < 0 || !want.ok(buffer.get(p).id)) return false;
    }
    return true;
  }

  private boolean matches(int format, int classDef, int glyph, int value) throws IOException {
    return format == 1 ? glyph == value : font.classOf(classDef, glyph) == value;
  }

  /** Runs the nested lookup records at matched positions; returns the index after the match. */
  private int apply(boolean substitution, List<Glyph> buffer, int[] positions, int at,
      int records, int depth) throws IOException {
    int last = positions[positions.length - 1];
    for (int r = 0; r < records; r++) {
      int seq = font.u16(at + 4 * r), index = font.u16(at + 4 * r + 2);
      if (seq >= positions.length) continue;
      int pos = positions[seq];
      if (pos >= buffer.size()) continue;
      int before = buffer.size();
      applyAt(substitution, lookupTable(substitution, index), buffer, pos, depth + 1);
      int delta = buffer.size() - before;
      if (delta != 0) {
        for (int k = seq + 1; k < positions.length; k++) positions[k] += delta;
        last += delta;
      }
    }
    return Math.max(positions[0] + 1, last + 1);
  }

  // ----------------------------------------------------------------------------- GPOS

  private int position(int type, int sub, int lookup, int flag, List<Glyph> buffer, int i,
      int depth) throws IOException {
    var g = buffer.get(i);
    if (type == 7 || type == 8) return context(false, type, sub, lookup, flag, buffer, i, depth);
    int format = font.u16(sub);
    if (type == 1) {
      int index = font.coverage(sub + font.u16(sub + 2), g.id);
      if (index < 0) return -1;
      int vf = font.u16(sub + 4);
      value(g, vf, format == 1 ? sub + 6 : sub + 8 + index * size(vf));
      return i + 1;
    }
    if (type == 2) {
      int index = font.coverage(sub + font.u16(sub + 2), g.id);
      if (index < 0) return -1;
      int j = nextIndex(lookup, flag, buffer, i, 1);
      if (j < 0) return -1;
      var h = buffer.get(j);
      int f1 = font.u16(sub + 4), f2 = font.u16(sub + 6), s1 = size(f1), s2 = size(f2);
      if (format == 1) {
        if (index >= font.u16(sub + 8)) return -1;
        int set = sub + font.u16(sub + 10 + 2 * index), record = 2 + s1 + s2;
        int low = 0, high = font.u16(set) - 1;
        while (low <= high) {
          int mid = (low + high) >>> 1, at = set + 2 + mid * record, glyph = font.u16(at);
          if (glyph < h.id) low = mid + 1;
          else if (glyph > h.id) high = mid - 1;
          else {
            value(g, f1, at + 2);
            value(h, f2, at + 2 + s1);
            return s2 == 0 ? j : j + 1;
          }
        }
        return -1;
      }
      if (format != 2) return -1;
      int c1 = font.classOf(sub + font.u16(sub + 8), g.id),
          c2 = font.classOf(sub + font.u16(sub + 10), h.id);
      int count1 = font.u16(sub + 12), count2 = font.u16(sub + 14);
      if (c1 >= count1 || c2 >= count2) return -1;
      int at = sub + 16 + (c1 * count2 + c2) * (s1 + s2);
      value(g, f1, at);
      value(h, f2, at + s1);
      return s2 == 0 ? j : j + 1;
    }
    if (type == 3) return cursive(sub, lookup, flag, buffer, i);
    if (type == 4 || type == 5 || type == 6) {
      int markIndex = font.coverage(sub + font.u16(sub + 2), g.id);
      if (markIndex < 0) return -1;
      int classes = font.u16(sub + 6), marks = sub + font.u16(sub + 8),
          bases = sub + font.u16(sub + 10);
      int b = -1;
      for (int j = i - 1; j >= 0; j--) {
        var p = buffer.get(j);
        int cls = glyphClass(p.id);
        if (type == 6) {
          if (ignorable(p) || skip(lookup, flag, p)) continue;
          b = cls == 3 ? j : -1;
          break;
        }
        if (cls == 3 || ignorable(p)) continue;
        b = j;
        break;
      }
      if (b < 0) return -1;
      var base = buffer.get(b);
      int baseIndex = font.coverage(sub + font.u16(sub + 4), base.id);
      if (baseIndex < 0 || baseIndex >= font.u16(bases) || markIndex >= font.u16(marks)) return -1;
      int markClass = font.u16(marks + 2 + 4 * markIndex),
          markAnchor = marks + font.u16(marks + 4 + 4 * markIndex);
      if (markClass >= classes) return -1;
      int anchor;
      if (type == 5) {
        int lig = bases + font.u16(bases + 2 + 2 * baseIndex), comps = font.u16(lig);
        if (comps == 0) return -1;
        int rel = font.u16(lig + 2 + 2 * ((comps - 1) * classes + markClass));
        if (rel == 0) return -1;
        anchor = lig + rel;
      } else {
        int rel = font.u16(bases + 2 + 2 * (baseIndex * classes + markClass));
        if (rel == 0) return -1;
        anchor = bases + rel;
      }
      int between = 0;
      for (int k = b; k < i; k++) between += buffer.get(k).adv;
      g.dx = base.dx + font.s16(anchor + 2) - font.s16(markAnchor + 2) - between;
      g.dy = base.dy + font.s16(anchor + 4) - font.s16(markAnchor + 4);
      return i + 1;
    }
    return -1;
  }

  /**
   * Cursive attachment between glyph {@code i} and the next glyph to its right (the buffer is in
   * visual order). Left to right, the left glyph's exit meets the right glyph's entry; right to
   * left, the right glyph (logically first) exits into the left one's entry. The left glyph's
   * advance ends at the joint and the right glyph starts there; the right glyph is moved
   * vertically so the anchors coincide.
   */
  private int cursive(int sub, int lookup, int flag, List<Glyph> buffer, int i)
      throws IOException {
    if (font.u16(sub) != 1) return -1;
    var left = buffer.get(i);
    int li = font.coverage(sub + font.u16(sub + 2), left.id), count = font.u16(sub + 4);
    if (li < 0 || li >= count) return -1;
    int j = nextIndex(lookup, flag, buffer, i, 1);
    if (j < 0) return -1;
    var right = buffer.get(j);
    int ri = font.coverage(sub + font.u16(sub + 2), right.id);
    if (ri < 0 || ri >= count) return -1;
    // EntryExitRecord: entry anchor offset, then exit anchor offset. The buffer is in logical
    // order: glyph i's exit anchor meets glyph j's entry anchor, as HarfBuzz's CursivePos.
    int la = font.u16(sub + 6 + 4 * li + 2), ra = font.u16(sub + 6 + 4 * ri);
    if (la == 0 || ra == 0) return -1;
    var cur = left;
    var next = right;
    int exitX = font.s16(sub + la + 2), exitY = font.s16(sub + la + 4),
        entryX = font.s16(sub + ra + 2), entryY = font.s16(sub + ra + 4);
    if (rtl) {
      int d = exitX + cur.dx;
      cur.adv -= d;
      cur.dx -= d;
      next.adv = entryX + next.dx;
    } else {
      cur.adv = exitX + cur.dx;
      int d = entryX + next.dx;
      next.adv -= d;
      next.dx -= d;
    }
    next.dy = cur.dy + exitY - entryY;
    return j;
  }

  private static int size(int format) {
    int n = 0;
    for (int v = format & 0xff; v != 0; v &= v - 1) n++;
    return 2 * n;
  }

  private void value(Glyph g, int format, int at) throws IOException {
    if ((format & 1) != 0) {
      g.dx += font.s16(at);
      at += 2;
    }
    if ((format & 2) != 0) {
      g.dy += font.s16(at);
      at += 2;
    }
    if ((format & 4) != 0) g.adv += font.s16(at);
  }
}
