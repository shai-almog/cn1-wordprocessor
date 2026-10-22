package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * HarfBuzz/harfrust's Indic shaper (ot_shaper_indic) for Devanagari through Malayalam: the
 * normalizer's split-matra decomposition, the Indic category/position table, the syllable machine,
 * consonant positions from the font (would-substitute checks of blwf/vatu/pstf/pref), initial
 * reordering (base, reph, pre-base matras, positions, masks), the basic features one stage at a
 * time, final reordering (pre-base matras, reph, pref) and the presentation features. The tables
 * are generated from harfrust 0.13.3 (ot_shaper_indic_table.rs, Unicode 17, and
 * ot_shaper_indic_machine.rs); regenerate them from those files rather than editing them by hand.
 */
final class IndicShaper {
  private IndicShaper() {}

  // ot_category_t
  static final int X = 0, C = 1, V = 2, N = 3, H = 4, ZWNJ = 5, ZWJ = 6, M = 7, SM = 8, A = 9,
      PLACEHOLDER = 10, DOTTEDCIRCLE = 11, RS = 12, MPST = 13, REPHA = 14, RA = 15, CM = 16,
      SYMBOL = 17, CS = 18, SMPST = 57;
  // ot_position_t
  static final int START = 0, RA_TO_BECOME_REPH = 1, PRE_M = 2, PRE_C = 3, BASE_C = 4,
      AFTER_MAIN = 5, ABOVE_C = 6, BEFORE_SUB = 7, BELOW_C = 8, AFTER_SUB = 9, BEFORE_POST = 10,
      POST_C = 11, AFTER_POST = 12, SMVD = 13, END = 14;
  // Syllable types
  private static final int CONSONANT_SYLLABLE = 0, VOWEL_SYLLABLE = 1, STANDALONE_CLUSTER = 2,
      SYMBOL_CLUSTER = 3, BROKEN_CLUSTER = 4, NON_INDIC_CLUSTER = 5;
  /** Glyph flag of a multiple substitution's outputs (HarfBuzz's MULTIPLIED glyph property). */
  static final int MULTIPLIED = OpenType.MULTIPLIED;

  private static final long CONSONANT_FLAGS = bit(C) | bit(CS) | bit(RA) | bit(CM) | bit(V)
      | bit(PLACEHOLDER) | bit(DOTTEDCIRCLE);
  private static final long JOINER_FLAGS = bit(ZWJ) | bit(ZWNJ);

  // Reph positions, blwf modes and reph modes of harfrust's INDIC_CONFIGS, by block
  // (Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada, Malayalam).
  private static final int IMPLICIT = 0, EXPLICIT = 1, LOG_REPHA = 2;
  private static final int[] REPH_POS = {BEFORE_POST, AFTER_SUB, BEFORE_SUB, BEFORE_POST,
      AFTER_MAIN, AFTER_POST, AFTER_POST, AFTER_POST, AFTER_MAIN};
  private static final int[] REPH_MODE = {IMPLICIT, IMPLICIT, IMPLICIT, IMPLICIT, IMPLICIT,
      IMPLICIT, EXPLICIT, IMPLICIT, LOG_REPHA};
  private static final boolean[] BLWF_POST_ONLY = {false, false, false, false, false, false, true,
      true, false};
  private static final int DEVANAGARI = 0, TAMIL = 5, KANNADA = 7, MALAYALAM = 8;

  /**
   * Canonical decompositions in the Indic blocks: code point, then its NFD parts (0-terminated
   * triples); 'x' marks a composition exclusion (never recomposed).
   */
  private static final int[] DECOMPOSITIONS = {
      0x929, 0x928, 0x93c, 0, 0x934, 0x933, 0x93c, 0,
      0x958, 0x915, 0x93c, 'x', 0x959, 0x916, 0x93c, 'x', 0x95a, 0x917, 0x93c, 'x',
      0x95b, 0x91c, 0x93c, 'x', 0x95c, 0x921, 0x93c, 'x', 0x95d, 0x922, 0x93c, 'x',
      0x95e, 0x92b, 0x93c, 'x', 0x95f, 0x92f, 0x93c, 'x',
      0x9cb, 0x9c7, 0x9be, 0, 0x9cc, 0x9c7, 0x9d7, 0, 0x9df, 0x9af, 0x9bc, 0,
      0xa33, 0xa32, 0xa3c, 'x', 0xa36, 0xa38, 0xa3c, 'x', 0xa59, 0xa16, 0xa3c, 'x',
      0xa5a, 0xa17, 0xa3c, 'x', 0xa5b, 0xa1c, 0xa3c, 'x', 0xa5e, 0xa2b, 0xa3c, 'x',
      0xb48, 0xb47, 0xb56, 0, 0xb4b, 0xb47, 0xb3e, 0, 0xb4c, 0xb47, 0xb57, 0,
      0xb5c, 0xb21, 0xb3c, 'x', 0xb5d, 0xb22, 0xb3c, 'x',
      0xbca, 0xbc6, 0xbbe, 0, 0xbcb, 0xbc7, 0xbbe, 0, 0xbcc, 0xbc6, 0xbd7, 0,
      0xc48, 0xc46, 0xc56, 0, 0xcc0, 0xcbf, 0xcd5, 0, 0xcc7, 0xcc6, 0xcd5, 0,
      0xcc8, 0xcc6, 0xcd6, 0, 0xcca, 0xcc6, 0xcc2, 0, 0xccb, 0xcca, 0xcd5, 0,
      0xd4a, 0xd46, 0xd3e, 0, 0xd4b, 0xd47, 0xd3e, 0, 0xd4c, 0xd46, 0xd57, 0
  };

  private static long bit(int cat) {
    return 1L << cat;
  }

  /** harfrust's get_categories: {category, position} of a code point. */
  static int[] categories(int u) {
    int index = 37;
    if (u >= 0 && u < 71396) {
      int i1 = (U8[u >> 10] >> (((u >> 9) & 1) << 2)) & 15;
      int i2 = U8[70 + (i1 << 3) + ((u >> 6) & 7)];
      int i3 = U8[186 + (i2 << 3) + ((u >> 3) & 7)];
      int i4 = U8[488 + (i3 << 2) + ((u >> 1) & 3)];
      index = U8[996 + i4 + (u & 1)];
    }
    int v = VALUES[index];
    return new int[] {v & 0xff, v >> 8};
  }

  /** Feature masks of the Indic stages ({@link PdfShaping}'s GLOBAL bit is 1). */
  static final int INIT = 16, RPHF = 32, HALF = 64, PREF = 1 << 14, BLWF = 1 << 15,
      ABVF = 1 << 16, PSTF = 1 << 17;

  private static final class Plan {
    final PdfFonts.TrueType font;
    final OpenType ot;
    final String[] scripts;
    final int block, viramaGlyph;
    final boolean oldSpec, hasRphf, hasPref;

    Plan(PdfFonts.TrueType font, OpenType ot, String[] scripts, int block, boolean oldSpec)
        throws java.io.IOException {
      this.font = font;
      this.ot = ot;
      this.scripts = scripts;
      this.block = block;
      this.oldSpec = oldSpec;
      viramaGlyph = font.glyph(0x094d + 0x80 * block);
      hasRphf = ot.lookups(true, scripts, "rphf").length > 0;
      hasPref = ot.lookups(true, scripts, "pref").length > 0;
    }

    boolean would(String feature, int... glyphs) {
      return ot.wouldSubstitute(scripts, feature, glyphs);
    }
  }

  /**
   * Shapes an Indic run's GSUB stage in place: {@code scripts} is the chosen GSUB script (the
   * version 2 tag when the font has it), {@code block} the Indic block (0 Devanagari .. 8
   * Malayalam) and {@code dotted} whether broken clusters get a dotted circle.
   */
  static void substitute(PdfFonts.TrueType font, OpenType ot, List<OpenType.Glyph> buffer,
      String[] scripts, int block, boolean dotted) throws java.io.IOException {
    normalize(font, buffer);
    for (var g : buffer) {
      int[] c = categories(g.cp);
      g.cat = c[0];
      g.pos = c[1];
    }
    boolean broken = findSyllables(buffer);
    mergeSyllables(buffer);
    // As harfrust's IndicShapePlan: old spec unless the chosen script is a version 2 tag.
    var plan = new Plan(font, ot, scripts, block, !scripts[0].endsWith("2"));
    ot.perSyllable = true;
    try {
      gsub(ot, scripts, buffer, -1, "locl", "ccmp");
      // initial_reordering
      if (plan.viramaGlyph != 0)
        for (var g : buffer) if (g.pos == BASE_C) g.pos = consonantPosition(plan, g.id);
      if (broken && dotted) dottedCircles(font, buffer);
      for (int s = 0, e; s < buffer.size(); s = e) {
        e = next(buffer, s);
        int type = buffer.get(s).syllable & 15;
        if (type == CONSONANT_SYLLABLE || type == VOWEL_SYLLABLE || type == STANDALONE_CLUSTER
            || type == BROKEN_CLUSTER) initialReordering(plan, buffer, s, e);
      }
      // The basic features, one stage each.
      gsub(ot, scripts, buffer, -1, "nukt");
      gsub(ot, scripts, buffer, -1, "akhn");
      gsub(ot, scripts, buffer, RPHF, "rphf");
      gsub(ot, scripts, buffer, -1, "rkrf");
      gsub(ot, scripts, buffer, PREF, "pref");
      gsub(ot, scripts, buffer, BLWF, "blwf");
      gsub(ot, scripts, buffer, ABVF, "abvf");
      gsub(ot, scripts, buffer, HALF, "half");
      gsub(ot, scripts, buffer, PSTF, "pstf");
      gsub(ot, scripts, buffer, -1, "vatu");
      gsub(ot, scripts, buffer, -1, "cjct");
      for (int s = 0, e; s < buffer.size(); s = e) {
        e = next(buffer, s);
        finalReordering(plan, buffer, s, e);
      }
      // The presentation features and the common ones (liga is disabled), in lookup order.
      String[] tags = {"init", "pres", "abvs", "blws", "psts", "haln", "rlig", "calt", "clig",
          "rclt"};
      int[] masks = new int[tags.length];
      java.util.Arrays.fill(masks, -1);
      masks[0] = INIT;
      ot.apply(true, scripts, buffer, tags, masks);
    } finally {
      ot.perSyllable = false;
    }
    hideIgnorables(font, buffer);
  }

  private static void gsub(OpenType ot, String[] scripts, List<OpenType.Glyph> buffer, int mask,
      String... features) {
    int[] masks = new int[features.length];
    java.util.Arrays.fill(masks, mask);
    ot.apply(true, scripts, buffer, features, masks);
  }

  private static int next(List<OpenType.Glyph> buffer, int s) {
    int syllable = buffer.get(s).syllable, e = s + 1;
    while (e < buffer.size() && buffer.get(e).syllable == syllable) e++;
    return e;
  }

  /** Each syllable's glyphs carry the syllable's whole text span (one extraction cluster). */
  private static void mergeSyllables(List<OpenType.Glyph> buffer) {
    for (int s = 0, e; s < buffer.size(); s = e) {
      e = next(buffer, s);
      int start = Integer.MAX_VALUE, end = 0;
      for (int k = s; k < e; k++) {
        start = Math.min(start, buffer.get(k).start);
        end = Math.max(end, buffer.get(k).end);
      }
      for (int k = s; k < e; k++) {
        buffer.get(k).start = start;
        buffer.get(k).end = end;
      }
    }
  }

  /**
   * HarfBuzz's normalizer in the Indic shaper's composed-diacritics, no-short-circuit mode: a
   * character is decomposed when the font has its parts, and only a consonant plus nukta that is
   * not a composition exclusion is recomposed (split matras stay split).
   */
  private static void normalize(PdfFonts.TrueType font, List<OpenType.Glyph> buffer)
      throws java.io.IOException {
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      int at = entry(g.cp);
      if (at < 0) continue;
      int[] parts = decompose(font, g.cp);
      if (parts == null) continue;
      int first = DECOMPOSITIONS[at + 1];
      int cat = categories(first)[0];
      if (DECOMPOSITIONS[at + 3] == 0 && (cat == C || cat == RA) && font.glyph(g.cp) != 0)
        continue;
      buffer.remove(i);
      for (int k = 0; k < parts.length; k++)
        buffer.add(i + k, new OpenType.Glyph(font.glyph(parts[k]), parts[k], g.start, g.end,
            g.mask));
      i += parts.length - 1;
    }
  }

  private static int entry(int cp) {
    if (cp < 0x0929 || cp > 0x0d4c) return -1;
    for (int k = 0; k < DECOMPOSITIONS.length; k += 4) if (DECOMPOSITIONS[k] == cp) return k;
    return -1;
  }

  private static int[] decompose(PdfFonts.TrueType font, int cp) throws java.io.IOException {
    int at = entry(cp);
    if (at < 0) return null;
    int a = DECOMPOSITIONS[at + 1], b = DECOMPOSITIONS[at + 2];
    if (font.glyph(b) == 0) return null;
    int[] head = decompose(font, a);
    if (head != null) {
      int[] out = java.util.Arrays.copyOf(head, head.length + 1);
      out[head.length] = b;
      return out;
    }
    return font.glyph(a) != 0 ? new int[] {a, b} : null;
  }

  /** The default-ignorable joiners left unsubstituted become the space glyph with no advance. */
  private static void hideIgnorables(PdfFonts.TrueType font, List<OpenType.Glyph> buffer)
      throws java.io.IOException {
    int space = font.glyph(' ');
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      if ((g.cp != 0x200c && g.cp != 0x200d) || (g.flags & OpenType.SUBSTITUTED) != 0) continue;
      if (space != 0) {
        g.id = space;
        g.flags |= OpenType.HIDDEN;
      } else if (buffer.size() > 1) {
        var keep = buffer.get(i > 0 ? i - 1 : i + 1);
        keep.start = Math.min(keep.start, g.start);
        keep.end = Math.max(keep.end, g.end);
        buffer.remove(i--);
      }
    }
  }

  private static boolean ligated(OpenType.Glyph g) {
    return (g.flags & OpenType.LIGATED) != 0;
  }

  private static boolean ligatedAndDidntMultiply(OpenType.Glyph g) {
    return ligated(g) && (g.flags & MULTIPLIED) == 0;
  }

  private static boolean isOneOf(OpenType.Glyph g, long flags) {
    return !ligated(g) && (bit(g.cat) & flags) != 0;
  }

  private static boolean isConsonant(OpenType.Glyph g) {
    return isOneOf(g, CONSONANT_FLAGS);
  }

  private static boolean isJoiner(OpenType.Glyph g) {
    return isOneOf(g, JOINER_FLAGS);
  }

  private static boolean isHalant(OpenType.Glyph g) {
    return isOneOf(g, bit(H));
  }

  private static int consonantPosition(Plan p, int consonant) {
    int v = p.viramaGlyph;
    if (p.would("blwf", v, consonant) || p.would("blwf", consonant, v)
        || p.would("vatu", v, consonant) || p.would("vatu", consonant, v)) return BELOW_C;
    if (p.would("pstf", v, consonant) || p.would("pstf", consonant, v)) return POST_C;
    if (p.would("pref", v, consonant) || p.would("pref", consonant, v)) return POST_C;
    return BASE_C;
  }

  /** harfrust's insert_dotted_circles: a dotted circle opens each broken cluster (after a repha). */
  private static void dottedCircles(PdfFonts.TrueType font, List<OpenType.Glyph> buffer)
      throws java.io.IOException {
    int circle = font.glyph(0x25cc);
    if (circle == 0) return;
    int last = -1;
    for (int i = 0; i < buffer.size(); i++) {
      var g = buffer.get(i);
      if (g.syllable == last || (g.syllable & 15) != BROKEN_CLUSTER) continue;
      last = g.syllable;
      int at = i;
      while (at < buffer.size() && buffer.get(at).syllable == last && buffer.get(at).cat == REPHA)
        at++;
      var ref = buffer.get(Math.min(at, buffer.size() - 1));
      var d = new OpenType.Glyph(circle, 0x25cc, ref.start, ref.end, g.mask);
      d.cat = DOTTEDCIRCLE;
      d.pos = END;
      d.syllable = last;
      buffer.add(at, d);
      i = at;
    }
  }

  private static void move(List<OpenType.Glyph> b, int from, int to) {
    b.add(to, b.remove(from));
  }

  private static void reverse(List<OpenType.Glyph> b, int from, int to) {
    for (int i = from, j = to - 1; i < j; i++, j--) {
      var t = b.get(i);
      b.set(i, b.get(j));
      b.set(j, t);
    }
  }

  /** harfrust's initial_reordering_consonant_syllable (also used for standalone/broken ones). */
  private static void initialReordering(Plan p, List<OpenType.Glyph> b, int start, int end) {
    if (p.block == KANNADA && start + 3 <= end && isOneOf(b.get(start), bit(RA))
        && isOneOf(b.get(start + 1), bit(H)) && isOneOf(b.get(start + 2), bit(ZWJ)))
      move(b, start + 2, start + 1);
    int mode = REPH_MODE[p.block];
    int base = end, limit = start;
    boolean hasReph = false;
    if (p.hasRphf && start + 3 <= end
        && (mode == IMPLICIT && !isJoiner(b.get(start + 2))
            || mode == EXPLICIT && b.get(start + 2).cat == ZWJ)) {
      int ra = b.get(start).id, h = b.get(start + 1).id;
      if (p.would("rphf", ra, h)
          || mode == EXPLICIT && p.would("rphf", ra, h, b.get(start + 2).id)) {
        limit += 2;
        while (limit < end && isJoiner(b.get(limit))) limit++;
        base = start;
        hasReph = true;
      }
    } else if (mode == LOG_REPHA && b.get(start).cat == REPHA) {
      limit++;
      while (limit < end && isJoiner(b.get(limit))) limit++;
      base = start;
      hasReph = true;
    }
    boolean seenBelow = false;
    for (int i = end - 1; ; i--) {
      var g = b.get(i);
      if (isConsonant(g)) {
        if (g.pos != BELOW_C && (g.pos != POST_C || seenBelow)) {
          base = i;
          break;
        }
        if (g.pos == BELOW_C) seenBelow = true;
        base = i;
      } else if (start < i && g.cat == ZWJ && b.get(i - 1).cat == H) break;
      if (i <= limit) break;
    }
    if (hasReph && base == start && limit - base <= 2) hasReph = false;
    for (int i = start; i < base; i++) b.get(i).pos = Math.min(PRE_C, b.get(i).pos);
    if (base < end) b.get(base).pos = BASE_C;
    if (hasReph) b.get(start).pos = RA_TO_BECOME_REPH;
    if (p.oldSpec) {
      boolean noDoubleHalants = p.block == KANNADA;
      for (int i = base + 1; i < end; i++) {
        if (b.get(i).cat != H) continue;
        int j = end - 1;
        while (j > i) {
          if (isConsonant(b.get(j)) || noDoubleHalants && b.get(j).cat == H) break;
          j--;
        }
        if (b.get(j).cat != H && j > i) move(b, i, j);
        break;
      }
    }
    int lastPos = START;
    long attach = bit(ZWJ) | bit(ZWNJ) | bit(N) | bit(RS) | bit(CM) | bit(H);
    for (int i = start; i < end; i++) {
      var g = b.get(i);
      if ((bit(g.cat) & attach) != 0) {
        g.pos = lastPos;
        if (g.cat == H && g.pos == PRE_M)
          for (int j = i; j > start; j--)
            if (b.get(j - 1).pos != PRE_M) {
              g.pos = b.get(j - 1).pos;
              break;
            }
      } else if (g.pos != SMVD) {
        if (g.cat == MPST && i > start && b.get(i - 1).cat == SM) b.get(i - 1).pos = g.pos;
        lastPos = g.pos;
      }
    }
    int last = base;
    for (int i = base + 1; i < end; i++) {
      var g = b.get(i);
      if (isConsonant(g)) {
        for (int j = last + 1; j < i; j++) if (b.get(j).pos < SMVD) b.get(j).pos = g.pos;
        last = i;
      } else if ((bit(g.cat) & (bit(M) | bit(MPST))) != 0) last = i;
    }
    // Stable sort by position.
    for (int i = start + 1; i < end; i++) {
      var g = b.get(i);
      int j = i;
      while (j > start && b.get(j - 1).pos > g.pos) {
        b.set(j, b.get(j - 1));
        j--;
      }
      b.set(j, g);
    }
    int firstLeft = end, lastLeft = end;
    base = end;
    for (int i = start; i < end; i++) {
      int pos = b.get(i).pos;
      if (pos == BASE_C) {
        base = i;
        break;
      } else if (pos == PRE_M) {
        if (firstLeft == end) firstLeft = i;
        lastLeft = i;
      }
    }
    if (firstLeft < lastLeft) {
      reverse(b, firstLeft, lastLeft + 1);
      int i = firstLeft;
      for (int j = i; j <= lastLeft; j++)
        if ((bit(b.get(j).cat) & (bit(M) | bit(MPST))) != 0) {
          reverse(b, i, j + 1);
          i = j + 1;
        }
    }
    for (int i = start; i < end && b.get(i).pos == RA_TO_BECOME_REPH; i++) b.get(i).mask |= RPHF;
    int mask = HALF;
    if (!p.oldSpec && !BLWF_POST_ONLY[p.block]) mask |= BLWF;
    for (int i = start; i < base; i++) b.get(i).mask |= mask;
    for (int i = base + 1; i < end; i++) b.get(i).mask |= BLWF | ABVF | PSTF;
    if (p.oldSpec && p.block == DEVANAGARI)
      for (int i = start; i + 1 < base; i++)
        if (b.get(i).cat == RA && b.get(i + 1).cat == H
            && (i + 2 == base || b.get(i + 2).cat != ZWJ)) {
          b.get(i).mask |= BLWF;
          b.get(i + 1).mask |= BLWF;
        }
    if (p.hasPref && base + 2 < end)
      for (int i = base + 1; i + 1 < end; i++)
        if (p.would("pref", b.get(i).id, b.get(i + 1).id)) {
          b.get(i).mask |= PREF;
          b.get(i + 1).mask |= PREF;
          break;
        }
    for (int i = start + 1; i < end; i++) {
      if (!isJoiner(b.get(i))) continue;
      boolean nonJoiner = b.get(i).cat == ZWNJ;
      int j = i;
      do {
        j--;
        if (nonJoiner) b.get(j).mask &= ~HALF;
      } while (j > start && !isConsonant(b.get(j)));
    }
  }

  /** harfrust's final_reordering_impl for one syllable. */
  private static void finalReordering(Plan p, List<OpenType.Glyph> b, int start, int end) {
    if (p.viramaGlyph != 0)
      for (int i = start; i < end; i++) {
        var g = b.get(i);
        if (g.id == p.viramaGlyph && ligated(g) && (g.flags & MULTIPLIED) != 0) {
          g.cat = H;
          g.flags &= ~(OpenType.LIGATED | MULTIPLIED);
        }
      }
    boolean tryPref = p.hasPref;
    int base = start;
    while (base < end) {
      if (b.get(base).pos >= BASE_C) {
        if (tryPref && base + 1 < end)
          for (int i = base + 1; i < end; i++)
            if ((b.get(i).mask & PREF) != 0) {
              var g = b.get(i);
              if (!((g.flags & OpenType.SUBSTITUTED) != 0 && ligatedAndDidntMultiply(g))) {
                base = i;
                while (base < end && isHalant(b.get(base))) base++;
                if (base < end) b.get(base).pos = BASE_C;
                tryPref = false;
              }
              break;
            }
        if (p.block == MALAYALAM)
          for (int i = base + 1; i < end; i++) {
            while (i < end && isJoiner(b.get(i))) i++;
            if (i == end || !isHalant(b.get(i))) break;
            i++;
            while (i < end && isJoiner(b.get(i))) i++;
            if (i < end && isConsonant(b.get(i)) && b.get(i).pos == BELOW_C) {
              base = i;
              b.get(base).pos = BASE_C;
            }
          }
        if (start < base && base < end && b.get(base).pos > BASE_C) base--;
        break;
      }
      base++;
    }
    if (base == end && start < base && isOneOf(b.get(base - 1), bit(ZWJ))) base--;
    if (base < end)
      while (start < base && isOneOf(b.get(base), bit(N) | bit(H))) base--;
    // Pre-base matras.
    if (start + 1 < end && start < base) {
      int newPos = base == end ? base - 2 : base - 1;
      if (p.block != MALAYALAM && p.block != TAMIL)
        while (true) {
          while (newPos > start && !isOneOf(b.get(newPos), bit(M) | bit(MPST) | bit(H))) newPos--;
          if (isHalant(b.get(newPos)) && b.get(newPos).pos != PRE_M) {
            if (newPos + 1 < end && b.get(newPos + 1).cat == ZWJ && newPos > start) {
              newPos--;
              continue;
            }
          } else newPos = start;
          break;
        }
      if (start < newPos && b.get(newPos).pos != PRE_M)
        for (int i = newPos; i > start; i--)
          if (b.get(i - 1).pos == PRE_M) {
            int old = i - 1;
            if (old < base && base <= newPos) base--;
            move(b, old, newPos);
            newPos--;
          }
    }
    // Reph.
    var first = b.get(start);
    if (start + 1 < end && first.pos == RA_TO_BECOME_REPH
        && (first.cat == REPHA) ^ ligatedAndDidntMultiply(first)) {
      int to = rephPosition(p, b, start, end, base);
      move(b, start, to);
      if (start < base && base <= to) base--;
    }
    // Pre-base reordering forms.
    if (tryPref && base + 1 < end)
      for (int i = base + 1; i < end; i++)
        if ((b.get(i).mask & PREF) != 0) {
          if (ligatedAndDidntMultiply(b.get(i))) {
            int newPos = base;
            if (p.block != MALAYALAM && p.block != TAMIL)
              while (newPos > start && !isOneOf(b.get(newPos - 1), bit(M) | bit(MPST) | bit(H)))
                newPos--;
            if (newPos > start && isHalant(b.get(newPos - 1)) && newPos < end
                && isJoiner(b.get(newPos))) newPos++;
            move(b, i, newPos);
            if (newPos <= base && base < i) base++;
          }
          break;
        }
    if (b.get(start).pos == PRE_M && (start == 0 || !letterOrMark(b.get(start - 1).cp)))
      b.get(start).mask |= INIT;
  }

  private static int rephPosition(Plan p, List<OpenType.Glyph> b, int start, int end, int base) {
    int rp = REPH_POS[p.block], to;
    if (rp != AFTER_POST) {
      to = start + 1;
      while (to < base && !isHalant(b.get(to))) to++;
      if (to < base && isHalant(b.get(to))) {
        if (to + 1 < base && isJoiner(b.get(to + 1))) to++;
        return to;
      }
      if (rp == AFTER_MAIN) {
        to = base;
        while (to + 1 < end && b.get(to + 1).pos <= AFTER_MAIN) to++;
        if (to < end) return to;
      }
      if (rp == AFTER_SUB) {
        to = base;
        long stop = bit(POST_C) | bit(AFTER_POST) | bit(SMVD);
        while (to + 1 < end && (bit(b.get(to + 1).pos) & stop) == 0) to++;
        if (to < end) return to;
      }
    }
    to = start + 1;
    while (to < base && !isHalant(b.get(to))) to++;
    if (to < base && isHalant(b.get(to))) {
      if (to + 1 < base && isJoiner(b.get(to + 1))) to++;
      return to;
    }
    to = end - 1;
    while (to > start && b.get(to).pos == SMVD) to--;
    if (isHalant(b.get(to))) {
      int limit = to;
      for (int k = base + 1; k < limit; k++)
        if ((bit(b.get(k).cat) & (bit(M) | bit(MPST))) != 0) to--;
    }
    return to;
  }

  /**
   * Whether a code point is a letter, mark or format character (HarfBuzz's general category
   * range Format..NonSpacingMark that blocks 'init'): the Indic blocks, joiners and letters.
   */
  private static boolean letterOrMark(int cp) {
    if (cp >= 0x0900 && cp <= 0x0dff) {
      int o = cp & 0x7f;
      return !(o >= 0x66 && o <= 0x6f) && cp != 0x0964 && cp != 0x0965;
    }
    if (cp >= 0x200b && cp <= 0x200f || cp == 0x00ad || cp == 0x2060 || cp == 0xfeff) return true;
    if (cp >= 'a' && cp <= 'z' || cp >= 'A' && cp <= 'Z') return true;
    return cp >= 0x00c0 && cp <= 0x024f && cp != 0x00d7 && cp != 0x00f7;
  }

  /** harfrust's find_syllables_indic: the Ragel scanner; returns whether a cluster was broken. */
  static boolean findSyllables(List<OpenType.Glyph> buffer) {
    int cs = 31, p = 0, pe = buffer.size(), eof = pe, ts = 0, te = 0, act = 0, trans = 0;
    int[] serial = {1};
    boolean broken = false;
    resume:
    while (p != pe || p == eof) {
      if (FROM_STATE_ACTIONS[cs] == 10) ts = p;
      if (p == eof) {
        if (EOF_TRANS[cs] > 0) trans = EOF_TRANS[cs] - 1;
      } else {
        int c = buffer.get(p).cat, keys = cs << 1, inds = INDEX_OFFSETS[cs];
        trans = INDEX_DEFAULTS[cs];
        if (c >= 1 && c <= 57) {
          int ic = CHAR_CLASS[c - 1];
          if (ic <= TRANS_KEYS[keys + 1] && ic >= TRANS_KEYS[keys])
            trans = INDICES[inds + ic - TRANS_KEYS[keys]];
        }
      }
      cs = COND_TARGS[trans];
      int type = -1;
      switch (COND_ACTIONS[trans]) {
        case 2: te = p + 1; break;
        case 11: te = p + 1; type = NON_INDIC_CLUSTER; break;
        case 14: te = p; p--; type = CONSONANT_SYLLABLE; break;
        case 15: te = p; p--; type = VOWEL_SYLLABLE; break;
        case 18: te = p; p--; type = STANDALONE_CLUSTER; break;
        case 20: te = p; p--; type = SYMBOL_CLUSTER; break;
        case 16: te = p; p--; type = BROKEN_CLUSTER; break;
        case 17: te = p; p--; type = NON_INDIC_CLUSTER; break;
        case 1: p = te - 1; type = CONSONANT_SYLLABLE; break;
        case 3: p = te - 1; type = VOWEL_SYLLABLE; break;
        case 7: p = te - 1; type = STANDALONE_CLUSTER; break;
        case 8: p = te - 1; type = SYMBOL_CLUSTER; break;
        case 4: p = te - 1; type = BROKEN_CLUSTER; break;
        case 6:
          if (act == 1) type = CONSONANT_SYLLABLE;
          else if (act == 5 || act == 7) type = NON_INDIC_CLUSTER;
          else if (act == 6) type = BROKEN_CLUSTER;
          if (type >= 0) p = te - 1;
          break;
        case 19: te = p + 1; act = 1; break;
        case 13: te = p + 1; act = 5; break;
        case 5: te = p + 1; act = 6; break;
        case 12: te = p + 1; act = 7; break;
        default: break;
      }
      if (type >= 0) {
        for (int i = ts; i < te; i++) buffer.get(i).syllable = serial[0] << 4 | type;
        if (++serial[0] == 16) serial[0] = 1;
        broken |= type == BROKEN_CLUSTER;
      }
      if (p == eof) {
        if (cs >= 31) break;
      } else {
        if (TO_STATE_ACTIONS[cs] == 9) ts = 0;
        p++;
        continue;
      }
      break;
    }
    return broken;
  }

  private static final int[] VALUES = {
      3337, 3616, 1025, 1040, 1042, 1035, 1034, 2052, 1540, 3588, 1287, 3079, 2311, 2055, 1799, 519,
      3619, 3625, 3085, 3620, 3621, 3622, 3587, 3623, 1039, 3598, 3609, 3345, 1800, 3336, 3385, 1026,
      1556, 2069, 790, 2839, 3624, 3584, 3610, 3611, 3590, 3589
  };

  private static final int[] U8 = {
      1, 0, 50, 4, 5, 96, 0, 7, 8, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 186, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 192, 0, 0, 0, 0, 208, 224, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 4, 5,
      6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 0, 0, 22, 23, 24, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 25, 26, 0, 0, 0, 27, 0, 0, 0, 0, 28, 29, 30, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      31, 0, 0, 0, 32, 0, 0, 0, 33, 0, 34, 0, 0, 0, 0, 0, 0, 35, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 36,
      0, 0, 0, 0, 0, 0, 37, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 25, 2, 10, 0, 0, 0, 0, 26, 0, 27,
      0, 0, 0, 28, 0, 0, 0, 0, 0, 29, 11, 30, 1, 1, 1, 4, 31, 32, 33, 34, 1, 6, 2, 35, 1, 12, 13, 7,
      1, 1, 3, 36, 14, 15, 37, 16, 17, 6, 2, 38, 39, 18, 40, 7, 1, 1, 3, 41, 42, 43, 44, 45, 46, 19,
      2, 47, 0, 18, 48, 49, 1, 1, 3, 20, 14, 50, 51, 0, 0, 21, 2, 0, 52, 53, 13, 7, 1, 1, 3, 20, 54,
      15, 55, 56, 17, 6, 2, 57, 0, 58, 59, 60, 61, 62, 63, 4, 64, 65, 66, 16, 0, 19, 2, 0, 0, 67, 8,
      9, 1, 1, 3, 4, 22, 68, 69, 70, 71, 23, 2, 0, 0, 12, 8, 9, 1, 1, 3, 72, 22, 73, 74, 75, 76, 23,
      2, 77, 0, 78, 8, 9, 1, 1, 1, 4, 79, 80, 81, 82, 83, 21, 2, 0, 84, 85, 1, 1, 86, 87, 88, 89, 90,
      2, 91, 92, 93, 94, 95, 96, 1, 97, 98, 2, 99, 0, 0, 0, 0, 1, 1, 1, 100, 101, 11, 102, 103, 104,
      105, 106, 107, 2, 10, 0, 0, 0, 0, 108, 5, 5, 109, 110, 111, 0, 112, 113, 0, 114, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 115, 0, 116, 0, 0, 0, 0, 0, 0, 0, 0, 117, 0, 0, 0, 0, 0, 118, 0, 0, 0, 0, 5, 5,
      119, 120, 0, 0, 0, 0, 121, 1, 2, 122, 0, 0, 0, 0, 1, 1, 123, 124, 24, 24, 0, 0, 0, 0, 0, 0,
      125, 0, 0, 0, 0, 0, 0, 126, 0, 0, 2, 2, 2, 10, 0, 0, 0, 0, 2, 2, 2, 2, 4, 4, 4, 4, 10, 2, 2, 2,
      26, 2, 2, 2, 8, 8, 8, 8, 6, 18, 0, 4, 20, 14, 24, 2, 6, 6, 20, 6, 20, 6, 24, 2, 4, 0, 0, 0, 6,
      6, 6, 6, 66, 12, 14, 6, 6, 6, 20, 14, 2, 0, 36, 50, 52, 18, 38, 68, 0, 0, 0, 32, 0, 0, 2, 16,
      56, 12, 14, 6, 0, 0, 0, 4, 44, 2, 16, 2, 6, 22, 0, 4, 2, 0, 36, 28, 6, 28, 0, 4, 30, 30, 30,
      30, 0, 0, 42, 0, 34, 0, 0, 0, 0, 64, 0, 0, 0, 0, 0, 42, 12, 12, 6, 6, 6, 6, 24, 2, 2, 18, 36,
      112, 18, 18, 18, 18, 18, 18, 114, 116, 118, 120, 122, 18, 0, 6, 6, 6, 44, 10, 0, 2, 54, 32, 46,
      10, 26, 0, 0, 0, 0, 0, 34, 70, 6, 20, 0, 14, 44, 2, 16, 10, 2, 0, 72, 50, 124, 48, 0, 32, 48,
      32, 46, 0, 126, 0, 0, 0, 16, 2, 10, 10, 12, 2, 128, 0, 6, 6, 6, 14, 6, 14, 24, 2, 22, 22, 52,
      74, 76, 32, 46, 0, 16, 58, 58, 78, 130, 12, 14, 6, 2, 0, 36, 132, 134, 32, 46, 0, 0, 0, 80,
      136, 16, 0, 0, 0, 0, 70, 14, 6, 6, 20, 0, 6, 20, 6, 24, 0, 16, 10, 10, 2, 0, 16, 10, 0, 2, 10,
      0, 2, 2, 0, 0, 22, 76, 48, 0, 82, 54, 22, 84, 0, 12, 12, 138, 6, 28, 60, 38, 28, 86, 28, 88, 0,
      0, 0, 140, 86, 2, 10, 16, 0, 26, 2, 16, 2, 28, 60, 38, 60, 38, 18, 88, 0, 0, 0, 74, 38, 0, 0,
      16, 10, 142, 144, 0, 0, 12, 12, 146, 6, 2, 148, 150, 22, 22, 22, 48, 82, 54, 22, 84, 152, 0, 0,
      2, 154, 0, 0, 0, 14, 0, 2, 2, 2, 2, 2, 26, 2, 2, 156, 2, 2, 90, 6, 6, 6, 6, 158, 92, 94, 160,
      162, 62, 58, 164, 166, 168, 170, 4, 4, 0, 10, 2, 6, 6, 96, 98, 26, 2, 172, 174, 100, 176, 178,
      100, 102, 102, 2, 104, 62, 180, 2, 2, 182, 184, 186, 12, 12, 12, 188, 4, 12, 190, 0, 2, 26, 2,
      2, 2, 90, 6, 6, 6, 6, 0, 92, 62, 94, 98, 192, 194, 196, 96, 198, 200, 106, 106, 108, 108, 202,
      0, 0, 42, 0, 204, 0, 8, 206, 8, 8, 208, 40, 210, 40, 40, 2, 212, 214, 8, 34, 0, 0, 0, 0, 216,
      0, 4, 4, 4, 0, 0, 34, 0, 0, 0, 0, 110, 0, 0, 64, 110, 0, 0, 0, 218, 0, 0, 42, 4, 34, 8, 40, 40,
      40, 0, 0, 0, 220, 2, 2, 104, 16, 4, 2, 2, 10, 16, 2, 4, 34, 0, 222, 78, 2, 56, 12, 0, 0, 0, 80,
      72, 0, 37, 37, 2, 2, 6, 6, 31, 31, 0, 0, 2, 37, 29, 29, 37, 31, 37, 2, 12, 12, 31, 37, 11, 11,
      31, 2, 24, 2, 14, 14, 36, 36, 37, 11, 6, 37, 22, 27, 12, 37, 27, 27, 37, 6, 24, 37, 11, 7, 11,
      37, 11, 15, 11, 12, 15, 37, 37, 29, 0, 22, 14, 12, 32, 32, 30, 30, 6, 29, 37, 15, 29, 37, 22,
      37, 37, 12, 12, 11, 22, 22, 37, 22, 15, 15, 11, 8, 14, 37, 14, 8, 2, 31, 35, 32, 32, 33, 35,
      35, 33, 33, 35, 23, 23, 23, 2, 32, 26, 38, 38, 38, 30, 37, 12, 15, 12, 7, 15, 12, 37, 0, 0, 29,
      29, 12, 18, 11, 37, 13, 37, 3, 37, 28, 11, 10, 10, 37, 10, 11, 29, 31, 37, 14, 37, 4, 4, 29, 6,
      31, 2, 12, 12, 27, 25, 37, 2, 11, 2, 24, 31, 35, 33, 34, 0, 32, 29, 9, 1, 21, 19, 20, 16, 2,
      21, 21, 17, 2, 23, 2, 2, 35, 32, 2, 20, 35, 34, 32, 32, 29, 2, 29, 29, 32, 32, 35, 35, 34, 34,
      34, 38, 39, 39, 26, 9, 39, 27, 39, 0, 37, 0, 27, 27, 0, 0, 4, 4, 0, 41, 40, 5, 37, 31, 12, 2,
      23
  };

  private static final int[] TRANS_KEYS = {
      7, 19, 3, 19, 4, 19, 4, 19, 12, 12, 3, 19, 3, 19, 3, 19, 7, 19, 4, 19, 4, 19, 12, 12, 3, 19,
      3, 19, 3, 19, 3, 19, 7, 19, 4, 19, 4, 19, 12, 12, 3, 19, 3, 19, 3, 19, 7, 19, 4, 19, 4, 19,
      12, 12, 3, 19, 3, 19, 4, 19, 7, 19, 0, 19, 2, 19, 2, 19, 3, 19, 0, 19, 4, 19, 4, 19, 8, 8, 4,
      8, 0, 19, 0, 19, 0, 19, 2, 19, 3, 19, 4, 19, 4, 19, 3, 19, 4, 19, 2, 19, 4, 19, 2, 19, 2, 19,
      2, 19, 2, 19, 3, 19, 0, 19, 2, 19, 2, 19, 3, 19, 0, 19, 4, 19, 8, 8, 4, 8, 0, 19, 0, 19, 2,
      19, 3, 19, 4, 19, 4, 19, 3, 19, 4, 19, 4, 19, 2, 19, 4, 19, 2, 19, 2, 19, 3, 19, 2, 19, 2,
      19, 3, 19, 0, 19, 2, 19, 0, 19, 4, 19, 8, 8, 4, 8, 0, 19, 0, 19, 2, 19, 3, 19, 4, 19, 4, 19,
      2, 19, 3, 19, 4, 19, 4, 19, 2, 19, 4, 19, 2, 19, 3, 19, 3, 19, 2, 19, 2, 19, 3, 19, 0, 19, 2,
      19, 0, 19, 4, 19, 8, 8, 4, 8, 0, 19, 0, 19, 2, 19, 3, 19, 4, 19, 4, 19, 2, 19, 3, 19, 4, 19,
      4, 19, 2, 19, 4, 19, 0, 19, 2, 19, 0, 19, 3, 19, 4, 19, 4, 19, 8, 8, 4, 8, 0, 19, 2, 19, 4,
      19, 4, 19, 8, 8, 4, 8, 0, 14, 0, 0
  };

  private static final int[] CHAR_CLASS = {
      0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 18, 18, 18, 18, 18, 18, 18,
      18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18, 18,
      18, 18, 18, 18, 18, 18, 18, 19, 0, 0
  };

  private static final int[] INDEX_OFFSETS = {
      0, 13, 30, 46, 62, 63, 80, 97, 114, 127, 143, 159, 160, 177, 194, 211, 228, 241, 257, 273,
      274, 291, 308, 325, 338, 354, 370, 371, 388, 405, 421, 434, 454, 472, 490, 507, 527, 543,
      559, 560, 565, 585, 605, 625, 643, 660, 676, 692, 709, 725, 743, 759, 777, 795, 813, 831,
      848, 868, 886, 904, 921, 941, 957, 958, 963, 983, 1003, 1021, 1038, 1054, 1070, 1087, 1103,
      1119, 1137, 1153, 1171, 1189, 1206, 1224, 1242, 1259, 1279, 1297, 1317, 1333, 1334, 1339,
      1359, 1379, 1397, 1414, 1430, 1446, 1464, 1481, 1497, 1513, 1531, 1547, 1565, 1582, 1599,
      1617, 1635, 1652, 1672, 1690, 1710, 1726, 1727, 1732, 1752, 1772, 1790, 1807, 1823, 1839,
      1857, 1874, 1890, 1906, 1924, 1940, 1960, 1978, 1998, 2015, 2031, 2047, 2048, 2053, 2073,
      2091, 2107, 2123, 2124, 2129, 0, 0
  };

  private static final int[] INDICES = {
      1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 4, 5, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 5, 3,
      3, 4, 6, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 6, 3, 3, 4, 5, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 5,
      4, 7, 3, 3, 4, 5, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 5, 2, 3, 3, 4, 5, 0, 0, 0, 8, 4, 0, 0, 0,
      0, 0, 0, 5, 10, 11, 11, 12, 13, 9, 9, 9, 9, 12, 9, 9, 9, 9, 9, 9, 13, 14, 9, 9, 9, 9, 9, 9,
      9, 9, 9, 9, 9, 14, 11, 11, 12, 15, 9, 9, 9, 9, 12, 9, 9, 9, 9, 9, 9, 15, 11, 11, 12, 13, 9,
      9, 9, 9, 12, 9, 9, 9, 9, 9, 9, 13, 12, 16, 11, 11, 12, 13, 9, 9, 9, 9, 12, 9, 9, 9, 9, 9, 9,
      13, 10, 11, 11, 12, 13, 9, 9, 9, 17, 12, 9, 9, 9, 9, 9, 9, 13, 10, 11, 11, 12, 13, 9, 9, 9,
      18, 12, 9, 9, 9, 9, 9, 9, 13, 20, 21, 21, 22, 23, 19, 19, 19, 24, 22, 19, 19, 19, 19, 19, 19,
      23, 25, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 25, 21, 21, 22, 27, 26, 26, 26, 26, 22,
      26, 26, 26, 26, 26, 26, 27, 21, 21, 22, 23, 19, 19, 19, 19, 22, 19, 19, 19, 19, 19, 19, 23,
      22, 20, 21, 21, 22, 23, 19, 19, 19, 19, 22, 19, 19, 19, 19, 19, 19, 23, 28, 21, 21, 22, 23,
      19, 19, 19, 19, 22, 19, 19, 19, 19, 19, 19, 23, 30, 31, 31, 32, 33, 29, 29, 29, 34, 32, 29,
      29, 29, 29, 29, 29, 33, 35, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 29, 35, 31, 31, 32, 36,
      29, 29, 29, 29, 32, 29, 29, 29, 29, 29, 29, 36, 31, 31, 32, 33, 29, 29, 29, 29, 32, 29, 29,
      29, 29, 29, 29, 33, 32, 30, 31, 31, 32, 33, 29, 29, 29, 29, 32, 29, 29, 29, 29, 29, 29, 33,
      37, 31, 31, 32, 33, 29, 29, 29, 29, 32, 29, 29, 29, 29, 29, 29, 33, 21, 21, 22, 38, 0, 0, 0,
      0, 22, 0, 0, 0, 0, 0, 0, 38, 40, 39, 39, 39, 39, 39, 39, 39, 39, 39, 39, 39, 40, 43, 44, 45,
      46, 47, 48, 22, 23, 49, 50, 50, 24, 22, 51, 52, 53, 54, 55, 42, 56, 58, 59, 60, 61, 4, 5, 62,
      57, 57, 8, 4, 57, 57, 63, 57, 57, 57, 5, 64, 59, 65, 65, 4, 5, 62, 57, 57, 57, 4, 57, 57, 63,
      57, 57, 57, 5, 59, 65, 65, 4, 5, 62, 57, 57, 57, 4, 57, 57, 63, 57, 57, 57, 5, 43, 57, 57,
      57, 66, 67, 57, 1, 62, 57, 57, 57, 57, 57, 43, 57, 57, 57, 57, 1, 68, 68, 57, 1, 62, 57, 57,
      57, 57, 57, 57, 57, 57, 57, 57, 1, 62, 57, 57, 69, 62, 57, 57, 57, 57, 57, 57, 57, 57, 57,
      57, 69, 62, 62, 57, 57, 57, 62, 43, 57, 70, 57, 68, 68, 57, 1, 62, 57, 57, 57, 57, 57, 43,
      57, 57, 57, 57, 1, 43, 57, 57, 57, 68, 68, 57, 1, 62, 57, 57, 57, 57, 57, 43, 57, 57, 57, 57,
      1, 43, 57, 57, 57, 68, 67, 57, 1, 62, 57, 57, 57, 57, 57, 43, 57, 57, 57, 57, 1, 71, 72, 73,
      73, 4, 5, 62, 57, 57, 57, 4, 57, 57, 57, 57, 57, 57, 5, 72, 73, 73, 4, 5, 62, 57, 57, 57, 4,
      57, 57, 57, 57, 57, 57, 5, 73, 73, 4, 5, 62, 57, 57, 57, 4, 57, 57, 57, 57, 57, 57, 5, 62,
      57, 57, 69, 62, 57, 57, 57, 4, 57, 57, 57, 57, 57, 57, 69, 74, 75, 75, 4, 5, 62, 57, 57, 57,
      4, 57, 57, 57, 57, 57, 57, 5, 66, 76, 57, 1, 62, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 1,
      66, 57, 68, 68, 57, 1, 62, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 1, 68, 76, 57, 1, 62, 57,
      57, 57, 57, 57, 57, 57, 57, 57, 57, 1, 58, 59, 65, 65, 4, 5, 62, 57, 57, 57, 4, 57, 57, 63,
      57, 57, 57, 5, 58, 59, 60, 65, 4, 5, 62, 57, 57, 8, 4, 57, 57, 63, 57, 57, 57, 5, 78, 79, 80,
      81, 12, 13, 82, 77, 77, 18, 12, 77, 77, 83, 77, 77, 77, 13, 84, 79, 85, 81, 12, 13, 82, 77,
      77, 77, 12, 77, 77, 83, 77, 77, 77, 13, 79, 85, 81, 12, 13, 82, 77, 77, 77, 12, 77, 77, 83,
      77, 77, 77, 13, 86, 77, 77, 77, 87, 88, 77, 14, 82, 77, 77, 77, 77, 77, 86, 77, 77, 77, 77,
      14, 89, 79, 90, 91, 12, 13, 82, 77, 77, 17, 12, 77, 77, 83, 77, 77, 77, 13, 92, 79, 85, 85,
      12, 13, 82, 77, 77, 77, 12, 77, 77, 83, 77, 77, 77, 13, 79, 85, 85, 12, 13, 82, 77, 77, 77,
      12, 77, 77, 83, 77, 77, 77, 13, 86, 77, 77, 77, 93, 88, 77, 14, 82, 77, 77, 77, 77, 77, 86,
      77, 77, 77, 77, 14, 82, 77, 77, 94, 82, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 94, 82, 82,
      77, 77, 77, 82, 86, 77, 95, 77, 93, 93, 77, 14, 82, 77, 77, 77, 77, 77, 86, 77, 77, 77, 77,
      14, 86, 77, 77, 77, 93, 93, 77, 14, 82, 77, 77, 77, 77, 77, 86, 77, 77, 77, 77, 14, 96, 97,
      98, 98, 12, 13, 82, 77, 77, 77, 12, 77, 77, 77, 77, 77, 77, 13, 97, 98, 98, 12, 13, 82, 77,
      77, 77, 12, 77, 77, 77, 77, 77, 77, 13, 98, 98, 12, 13, 82, 77, 77, 77, 12, 77, 77, 77, 77,
      77, 77, 13, 82, 77, 77, 94, 82, 77, 77, 77, 12, 77, 77, 77, 77, 77, 77, 94, 99, 100, 100, 12,
      13, 82, 77, 77, 77, 12, 77, 77, 77, 77, 77, 77, 13, 87, 101, 77, 14, 82, 77, 77, 77, 77, 77,
      77, 77, 77, 77, 77, 14, 93, 93, 77, 14, 82, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 14, 87,
      77, 93, 93, 77, 14, 82, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 14, 93, 101, 77, 14, 82, 77,
      77, 77, 77, 77, 77, 77, 77, 77, 77, 14, 89, 79, 85, 85, 12, 13, 82, 77, 77, 77, 12, 77, 77,
      83, 77, 77, 77, 13, 89, 79, 90, 85, 12, 13, 82, 77, 77, 17, 12, 77, 77, 83, 77, 77, 77, 13,
      10, 11, 11, 12, 13, 77, 77, 77, 77, 12, 77, 77, 77, 77, 77, 77, 13, 78, 79, 85, 81, 12, 13,
      82, 77, 77, 77, 12, 77, 77, 83, 77, 77, 77, 13, 103, 46, 104, 104, 22, 23, 49, 102, 102, 102,
      22, 102, 102, 53, 102, 102, 102, 23, 46, 104, 104, 22, 23, 49, 102, 102, 102, 22, 102, 102,
      53, 102, 102, 102, 23, 105, 102, 102, 102, 106, 107, 102, 25, 49, 102, 102, 102, 102, 102,
      105, 102, 102, 102, 102, 25, 45, 46, 108, 109, 22, 23, 49, 102, 102, 24, 22, 102, 102, 53,
      102, 102, 102, 23, 105, 102, 102, 102, 110, 107, 102, 25, 49, 102, 102, 102, 102, 102, 105,
      102, 102, 102, 102, 25, 49, 102, 102, 111, 49, 102, 102, 102, 102, 102, 102, 102, 102, 102,
      102, 111, 49, 49, 102, 102, 102, 49, 105, 102, 112, 102, 110, 110, 102, 25, 49, 102, 102,
      102, 102, 102, 105, 102, 102, 102, 102, 25, 105, 102, 102, 102, 110, 110, 102, 25, 49, 102,
      102, 102, 102, 102, 105, 102, 102, 102, 102, 25, 113, 114, 115, 115, 22, 23, 49, 102, 102,
      102, 22, 102, 102, 102, 102, 102, 102, 23, 114, 115, 115, 22, 23, 49, 102, 102, 102, 22, 102,
      102, 102, 102, 102, 102, 23, 115, 115, 22, 23, 49, 102, 102, 102, 22, 102, 102, 102, 102,
      102, 102, 23, 49, 26, 26, 111, 49, 26, 26, 26, 22, 26, 26, 26, 26, 26, 26, 111, 45, 46, 104,
      104, 22, 23, 49, 102, 102, 102, 22, 102, 102, 53, 102, 102, 102, 23, 116, 117, 117, 22, 23,
      49, 102, 102, 102, 22, 102, 102, 102, 102, 102, 102, 23, 106, 118, 102, 25, 49, 102, 102,
      102, 102, 102, 102, 102, 102, 102, 102, 25, 110, 110, 102, 25, 49, 102, 102, 102, 102, 102,
      102, 102, 102, 102, 102, 25, 106, 102, 110, 110, 102, 25, 49, 102, 102, 102, 102, 102, 102,
      102, 102, 102, 102, 25, 110, 118, 102, 25, 49, 102, 102, 102, 102, 102, 102, 102, 102, 102,
      102, 25, 45, 46, 108, 104, 22, 23, 49, 102, 102, 24, 22, 102, 102, 53, 102, 102, 102, 23, 20,
      21, 21, 22, 23, 119, 119, 119, 24, 22, 119, 119, 119, 119, 119, 119, 23, 20, 21, 21, 22, 23,
      119, 119, 119, 119, 22, 119, 119, 119, 119, 119, 119, 23, 121, 122, 123, 124, 32, 33, 125,
      120, 120, 34, 32, 120, 120, 126, 120, 120, 120, 33, 127, 122, 124, 124, 32, 33, 125, 120,
      120, 120, 32, 120, 120, 126, 120, 120, 120, 33, 122, 124, 124, 32, 33, 125, 120, 120, 120,
      32, 120, 120, 126, 120, 120, 120, 33, 128, 120, 120, 120, 129, 130, 120, 35, 125, 120, 120,
      120, 120, 120, 128, 120, 120, 120, 120, 35, 121, 122, 123, 50, 32, 33, 125, 120, 120, 34, 32,
      120, 120, 126, 120, 120, 120, 33, 128, 120, 120, 120, 131, 130, 120, 35, 125, 120, 120, 120,
      120, 120, 128, 120, 120, 120, 120, 35, 125, 120, 120, 132, 125, 120, 120, 120, 120, 120, 120,
      120, 120, 120, 120, 132, 125, 125, 120, 120, 120, 125, 128, 120, 133, 120, 131, 131, 120, 35,
      125, 120, 120, 120, 120, 120, 128, 120, 120, 120, 120, 35, 128, 120, 120, 120, 131, 131, 120,
      35, 125, 120, 120, 120, 120, 120, 128, 120, 120, 120, 120, 35, 134, 135, 136, 136, 32, 33,
      125, 120, 120, 120, 32, 120, 120, 120, 120, 120, 120, 33, 135, 136, 136, 32, 33, 125, 120,
      120, 120, 32, 120, 120, 120, 120, 120, 120, 33, 136, 136, 32, 33, 125, 120, 120, 120, 32,
      120, 120, 120, 120, 120, 120, 33, 125, 120, 120, 132, 125, 120, 120, 120, 32, 120, 120, 120,
      120, 120, 120, 132, 121, 122, 124, 124, 32, 33, 125, 120, 120, 120, 32, 120, 120, 126, 120,
      120, 120, 33, 137, 138, 138, 32, 33, 125, 120, 120, 120, 32, 120, 120, 120, 120, 120, 120,
      33, 129, 139, 120, 35, 125, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 35, 131, 131,
      120, 35, 125, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 35, 129, 120, 131, 131, 120,
      35, 125, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 35, 131, 139, 120, 35, 125, 120,
      120, 120, 120, 120, 120, 120, 120, 120, 120, 35, 43, 44, 45, 46, 108, 104, 22, 23, 49, 50,
      50, 24, 22, 102, 43, 53, 102, 102, 102, 23, 58, 140, 60, 61, 4, 5, 62, 57, 57, 8, 4, 57, 57,
      63, 57, 57, 57, 5, 43, 44, 45, 46, 141, 142, 22, 143, 144, 57, 50, 24, 22, 57, 43, 53, 57,
      57, 57, 143, 20, 145, 145, 22, 143, 62, 57, 57, 24, 22, 57, 57, 57, 57, 57, 57, 143, 62, 57,
      57, 69, 62, 57, 57, 57, 22, 57, 57, 57, 57, 57, 57, 69, 144, 57, 57, 146, 144, 57, 57, 57,
      22, 57, 57, 57, 57, 57, 57, 146, 144, 144, 57, 57, 57, 144, 43, 57, 70, 20, 145, 145, 22,
      143, 62, 57, 57, 57, 22, 57, 43, 57, 57, 57, 57, 143, 148, 147, 149, 149, 147, 40, 150, 147,
      147, 147, 147, 147, 147, 147, 147, 147, 147, 40, 149, 149, 147, 40, 150, 147, 147, 147, 147,
      147, 147, 147, 147, 147, 147, 40, 150, 147, 147, 151, 150, 147, 147, 147, 147, 147, 147, 147,
      147, 147, 147, 151, 150, 150, 147, 147, 147, 150, 43, 119, 119, 119, 119, 119, 119, 119, 119,
      50, 119, 119, 119, 119, 43, 0, 0
  };

  private static final int[] INDEX_DEFAULTS = {
      0, 0, 0, 0, 0, 0, 0, 9, 9, 9, 9, 9, 9, 9, 9, 19, 19, 26, 19, 26, 19, 19, 29, 29, 29, 29, 29,
      29, 29, 0, 39, 42, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57,
      57, 57, 57, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77, 77,
      77, 77, 77, 77, 77, 77, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 26,
      102, 102, 102, 102, 102, 102, 102, 119, 119, 120, 120, 120, 120, 120, 120, 120, 120, 120,
      120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 120, 102, 57, 57, 57, 57, 57, 57, 57,
      57, 147, 147, 147, 147, 147, 119, 0, 0
  };

  private static final int[] COND_TARGS = {
      31, 37, 42, 2, 43, 46, 4, 50, 51, 31, 60, 9, 66, 69, 61, 11, 74, 75, 78, 31, 83, 17, 89, 92,
      93, 84, 31, 19, 98, 31, 107, 24, 113, 116, 117, 108, 26, 122, 127, 31, 134, 31, 31, 32, 53,
      79, 81, 100, 101, 85, 102, 123, 124, 94, 132, 137, 92, 31, 33, 35, 6, 52, 38, 47, 34, 1, 36,
      40, 0, 39, 41, 44, 45, 3, 48, 5, 49, 31, 54, 56, 14, 77, 62, 70, 55, 7, 57, 72, 64, 58, 13,
      76, 59, 8, 63, 65, 67, 68, 10, 71, 12, 73, 31, 80, 20, 82, 96, 87, 15, 99, 16, 86, 88, 90,
      91, 18, 95, 21, 97, 31, 31, 103, 105, 22, 27, 109, 118, 104, 106, 120, 111, 23, 110, 112,
      114, 115, 25, 119, 28, 121, 125, 126, 131, 128, 129, 29, 130, 31, 133, 30, 135, 136, 0, 0
  };

  private static final int[] COND_ACTIONS = {
      1, 0, 2, 0, 2, 0, 0, 2, 2, 3, 2, 0, 2, 0, 0, 0, 2, 2, 2, 4, 2, 0, 5, 5, 5, 0, 6, 0, 2, 7, 2,
      0, 2, 0, 2, 0, 0, 2, 0, 8, 0, 0, 11, 2, 2, 5, 0, 12, 12, 0, 2, 5, 2, 5, 2, 0, 13, 14, 2, 0,
      0, 2, 0, 2, 2, 0, 2, 2, 0, 0, 2, 2, 2, 0, 0, 0, 2, 15, 2, 0, 0, 2, 0, 2, 2, 0, 2, 2, 2, 2, 0,
      2, 2, 0, 0, 2, 2, 2, 0, 0, 0, 2, 16, 5, 0, 5, 2, 2, 0, 5, 0, 0, 2, 5, 5, 0, 0, 0, 2, 17, 18,
      2, 0, 0, 0, 0, 2, 2, 2, 2, 2, 0, 0, 2, 2, 2, 0, 0, 0, 2, 0, 19, 19, 0, 0, 0, 0, 20, 2, 0, 0,
      0, 0, 0
  };

  private static final int[] TO_STATE_ACTIONS = {
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  private static final int[] FROM_STATE_ACTIONS = {
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  private static final int[] EOF_TRANS = {
      1, 1, 1, 1, 1, 1, 1, 10, 10, 10, 10, 10, 10, 10, 10, 20, 20, 27, 20, 27, 20, 20, 30, 30, 30,
      30, 30, 30, 30, 1, 40, 42, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58, 58,
      58, 58, 58, 58, 58, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78, 78,
      78, 78, 78, 78, 78, 78, 78, 78, 103, 103, 103, 103, 103, 103, 103, 103, 103, 103, 103, 103,
      103, 27, 103, 103, 103, 103, 103, 103, 103, 120, 120, 121, 121, 121, 121, 121, 121, 121, 121,
      121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121, 103, 58, 58, 58, 58, 58, 58,
      58, 58, 148, 148, 148, 148, 148, 120, 0, 0
  };

}
