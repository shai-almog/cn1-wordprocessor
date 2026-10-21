package com.codename1.ui.editor;

/**
 * Exposes a full Unicode bidirectional algorithm (UAX #9) to the word processor. It is Codename One's
 * {@code BidiUtil} (package-private, so this class lives in its package) extended with explicit
 * embeddings, overrides and isolates (X1-X10), paired brackets (N0) and the L1 separator reset. The same
 * change is proposed upstream as docs/framework-patches/bidi-uax9-full.patch; once the framework ships it,
 * the section between the UAX9 markers can be deleted and these methods can delegate to BidiUtil again.
 */
public final class BidiBridge {
  private BidiBridge() {}

  /** Resolved embedding level of every char; even is left-to-right, odd right-to-left. */
  public static byte[] levels(String text, boolean rtlParagraph) {
    return resolveLevels(text, rtlParagraph);
  }

  /** Rule L2: element v is the logical index of the item drawn at visual position v. */
  public static int[] visualOrder(byte[] levels) {
    return BidiUtil.reorderVisual(levels);
  }

  /** True when the text is plain left-to-right and needs no bidi processing. */
  public static boolean trivial(String text, boolean rtlParagraph) {
    return isTrivialLtr(text, rtlParagraph);
  }

  private static final byte L = BidiUtil.L;
  private static final byte R = BidiUtil.R;
  private static final byte AL = BidiUtil.AL;
  private static final byte EN = BidiUtil.EN;
  private static final byte ES = BidiUtil.ES;
  private static final byte ET = BidiUtil.ET;
  private static final byte AN = BidiUtil.AN;
  private static final byte CS = BidiUtil.CS;
  private static final byte NSM = BidiUtil.NSM;
  private static final byte BN = BidiUtil.BN;
  private static final byte ON = BidiUtil.ON;
  private static final byte WS = BidiUtil.WS;

  // BEGIN UAX9 (kept identical to the framework patch body)
    // Explicit formatting characters, separators and paragraph separators (UAX #9 Table 4).
    static final byte LRE = 12;
    static final byte RLE = 13;
    static final byte LRO = 14;
    static final byte RLO = 15;
    static final byte PDF = 16;
    static final byte LRI = 17;
    static final byte RLI = 18;
    static final byte FSI = 19;
    static final byte PDI = 20;
    static final byte B = 21;   // Paragraph Separator
    static final byte S = 22;   // Segment Separator (tab)

    private static final int MAX_DEPTH = 125;
    private static final int MAX_BRACKETS = 63;
    // BD14/BD15 paired brackets (Bidi_Paired_Bracket), opening and closing at the same index.
    private static final String OPEN = "([{\u0F3A\u0F3C\u169B\u2045\u207D\u208D\u2308\u230A\u3008\u300A"
            + "\u300C\u300E\u3010\u3014\u3016\u3018\u301A\uFE59\uFE5B\uFE5D\uFF08\uFF3B\uFF5B\uFF5F\uFF62";
    private static final String CLOSE = ")]}\u0F3B\u0F3D\u169C\u2046\u207E\u208E\u2309\u230B\u3009\u300B"
            + "\u300D\u300F\u3011\u3015\u3017\u3019\u301B\uFE5A\uFE5C\uFE5E\uFF09\uFF3D\uFF5D\uFF60\uFF63";

    /// Bidi type of a UTF-16 code unit, including the explicit embedding, override and isolate controls,
    /// segment and paragraph separators, boundary neutrals and paired brackets.
    static byte typeOf(char c) {
        switch (c) {
            case 0x202A: return LRE;
            case 0x202B: return RLE;
            case 0x202D: return LRO;
            case 0x202E: return RLO;
            case 0x202C: return PDF;
            case 0x2066: return LRI;
            case 0x2067: return RLI;
            case 0x2068: return FSI;
            case 0x2069: return PDI;
            case 0x200E: return L;
            case 0x200F: return R;
            case 0x061C: return AL;
            case '\t': case 0x0B: case 0x1F: return S;
            case '\n': case '\r': case 0x1C: case 0x1D: case 0x1E: case 0x85: case 0x2029: return B;
            case 0x200B: case 0x200C: case 0x200D: case 0x2060: case 0xFEFF: case 0x00AD: return BN;
            case 0x2329: case 0x232A: return ON;
            default: break;
        }
        if (OPEN.indexOf(c) >= 0 || CLOSE.indexOf(c) >= 0) {
            return ON;
        }
        return BidiUtil.typeOf(c);
    }

    /// Resolves the embedding level of every character of one paragraph following UAX #9 in full: explicit
    /// embeddings, overrides and isolates (X1-X10), weak types (W1-W7), paired brackets (N0), neutrals
    /// (N1-N2), implicit levels (I1-I2) and the separator/trailing-whitespace reset of L1. Characters removed
    /// by X9 take the level of the preceding character so callers can still draw them.
    static byte[] resolveLevels(String text, boolean baseRtl) {
        int n = text.length();
        byte[] levels = new byte[n];
        if (n == 0) {
            return levels;
        }
        byte para = (byte) (baseRtl ? 1 : 0);
        byte[] initial = new byte[n];
        for (int i = 0; i < n; i++) {
            initial[i] = typeOf(text.charAt(i));
        }
        // BD9: matching isolate initiators and PDIs.
        int[] matchingPdi = new int[n];
        int[] matchingInit = new int[n];
        int[] open = new int[n];
        int openCount = 0;
        for (int i = 0; i < n; i++) {
            matchingPdi[i] = -1;
            matchingInit[i] = -1;
            byte t = initial[i];
            if (t == LRI || t == RLI || t == FSI) {
                matchingPdi[i] = n;
                open[openCount++] = i;
            } else if (t == PDI && openCount > 0) {
                int o = open[--openCount];
                matchingPdi[o] = i;
                matchingInit[i] = o;
            } else if (t == B) {
                openCount = 0;
            }
        }
        // X1-X8: the directional status stack.
        byte[] types = new byte[n];
        System.arraycopy(initial, 0, types, 0, n);
        int[] stackLevel = new int[MAX_DEPTH + 2];
        byte[] stackOverride = new byte[MAX_DEPTH + 2];
        boolean[] stackIsolate = new boolean[MAX_DEPTH + 2];
        stackLevel[0] = para;
        stackOverride[0] = ON;
        int sp = 1;
        int overflowIsolates = 0;
        int overflowEmbeddings = 0;
        int validIsolates = 0;
        for (int i = 0; i < n; i++) {
            byte t = initial[i];
            int cur = stackLevel[sp - 1];
            if (t == RLE || t == LRE || t == RLO || t == LRO || t == RLI || t == LRI || t == FSI) {
                boolean isolate = t == RLI || t == LRI || t == FSI;
                boolean rtl;
                if (t == FSI) {
                    rtl = firstStrongRtl(initial, matchingPdi, i + 1, matchingPdi[i]);
                } else {
                    rtl = t == RLE || t == RLO || t == RLI;
                }
                levels[i] = (byte) cur;
                if (isolate && stackOverride[sp - 1] != ON) {
                    types[i] = stackOverride[sp - 1];
                }
                int next = rtl ? (cur + 1) | 1 : (cur + 2) & ~1;
                if (next <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                    if (isolate) {
                        validIsolates++;
                    }
                    stackLevel[sp] = next;
                    stackOverride[sp] = t == LRO ? L : t == RLO ? R : ON;
                    stackIsolate[sp] = isolate;
                    sp++;
                } else if (isolate) {
                    overflowIsolates++;
                } else if (overflowIsolates == 0) {
                    overflowEmbeddings++;
                }
            } else if (t == PDI) {
                if (overflowIsolates > 0) {
                    overflowIsolates--;
                } else if (validIsolates > 0) {
                    overflowEmbeddings = 0;
                    while (!stackIsolate[sp - 1]) {
                        sp--;
                    }
                    sp--;
                    validIsolates--;
                }
                levels[i] = (byte) stackLevel[sp - 1];
                if (stackOverride[sp - 1] != ON) {
                    types[i] = stackOverride[sp - 1];
                }
            } else if (t == PDF) {
                if (overflowIsolates == 0) {
                    if (overflowEmbeddings > 0) {
                        overflowEmbeddings--;
                    } else if (!stackIsolate[sp - 1] && sp >= 2) {
                        sp--;
                    }
                }
                levels[i] = (byte) stackLevel[sp - 1];
            } else if (t == B) {
                levels[i] = para;
            } else {
                levels[i] = (byte) cur;
                if (t != BN && stackOverride[sp - 1] != ON) {
                    types[i] = stackOverride[sp - 1];
                }
            }
        }
        // X9/X10: level runs over the retained characters, chained into isolating run sequences.
        int[] kept = new int[n];
        int keptCount = 0;
        for (int i = 0; i < n; i++) {
            if (!removed(initial[i])) {
                kept[keptCount++] = i;
            }
        }
        int[] runStart = new int[keptCount + 1];
        int[] runOf = new int[n];
        int runCount = 0;
        for (int k = 0; k < keptCount; k++) {
            if (k == 0 || levels[kept[k]] != levels[kept[k - 1]]) {
                runStart[runCount++] = k;
            }
            runOf[kept[k]] = runCount - 1;
        }
        runStart[runCount] = keptCount;
        int[] seq = new int[keptCount];
        for (int r = 0; r < runCount; r++) {
            int first = kept[runStart[r]];
            if (initial[first] == PDI && matchingInit[first] >= 0) {
                continue;
            }
            int len = 0;
            int run = r;
            while (true) {
                for (int k = runStart[run]; k < runStart[run + 1]; k++) {
                    seq[len++] = kept[k];
                }
                int last = seq[len - 1];
                byte lt = initial[last];
                if ((lt == LRI || lt == RLI || lt == FSI) && matchingPdi[last] < n) {
                    run = runOf[matchingPdi[last]];
                } else {
                    break;
                }
            }
            resolveSequence(text, seq, len, initial, types, levels, para, n);
        }
        // X9 removed characters take the level of their predecessor.
        for (int i = 0; i < n; i++) {
            if (removed(initial[i])) {
                levels[i] = i == 0 ? para : levels[i - 1];
            }
        }
        // L1: separators, and whitespace before them or at the end of the paragraph, reset to the base.
        boolean reset = true;
        for (int i = n - 1; i >= 0; i--) {
            byte t = initial[i];
            if (t == S || t == B) {
                levels[i] = para;
                reset = true;
            } else if (reset && (t == WS || t == LRI || t == RLI || t == FSI || t == PDI || removed(t))) {
                levels[i] = para;
            } else {
                reset = false;
            }
        }
        return levels;
    }

    private static boolean removed(byte t) {
        return t == RLE || t == LRE || t == RLO || t == LRO || t == PDF || t == BN;
    }

    // P2/P3 within an isolate: the first strong type between from and to, skipping nested isolates.
    private static boolean firstStrongRtl(byte[] initial, int[] matchingPdi, int from, int to) {
        for (int j = from; j < to && j < initial.length; j++) {
            byte t = initial[j];
            if (t == L) {
                return false;
            }
            if (t == R || t == AL) {
                return true;
            }
            if (t == LRI || t == RLI || t == FSI) {
                j = matchingPdi[j];
            } else if (t == B) {
                break;
            }
        }
        return false;
    }

    private static boolean isNi(byte t) {
        return t == ON || t == WS || t == S || t == B || t == LRI || t == RLI || t == FSI || t == PDI;
    }

    // Strong direction for N0/N1: numbers count as R; anything else is not strong.
    private static byte strongDir(byte t) {
        if (t == L) {
            return L;
        }
        if (t == R || t == AL || t == EN || t == AN) {
            return R;
        }
        return ON;
    }

    private static int openIndexOf(char c) {
        return OPEN.indexOf(c == 0x2329 ? (char) 0x3008 : c);
    }

    private static int closeIndexOf(char c) {
        return CLOSE.indexOf(c == 0x232A ? (char) 0x3009 : c);
    }

    private static void resolveSequence(String text, int[] seqIdx, int len, byte[] initial, byte[] types,
            byte[] levels, byte para, int n) {
        int first = seqIdx[0];
        int last = seqIdx[len - 1];
        int level = levels[first];
        int prev = para;
        for (int i = first - 1; i >= 0; i--) {
            if (!removed(initial[i])) {
                prev = levels[i];
                break;
            }
        }
        int next = para;
        byte lt = initial[last];
        if (!(lt == LRI || lt == RLI || lt == FSI)) {
            for (int i = last + 1; i < n; i++) {
                if (!removed(initial[i])) {
                    next = levels[i];
                    break;
                }
            }
        }
        byte sos = (Math.max(prev, level) & 1) != 0 ? R : L;
        byte eos = (Math.max(next, level) & 1) != 0 ? R : L;
        byte[] t = new byte[len];
        for (int k = 0; k < len; k++) {
            t[k] = types[seqIdx[k]];
        }
        // W1
        for (int k = 0; k < len; k++) {
            if (t[k] == NSM) {
                if (k == 0) {
                    t[k] = sos;
                } else {
                    byte p = t[k - 1];
                    t[k] = (p == LRI || p == RLI || p == FSI || p == PDI) ? ON : p;
                }
            }
        }
        // W2, W3
        byte strong = sos;
        for (int k = 0; k < len; k++) {
            byte c = t[k];
            if (c == L || c == R || c == AL) {
                strong = c;
            } else if (c == EN && strong == AL) {
                t[k] = AN;
            }
        }
        for (int k = 0; k < len; k++) {
            if (t[k] == AL) {
                t[k] = R;
            }
        }
        // W4
        for (int k = 1; k < len - 1; k++) {
            if (t[k] == ES && t[k - 1] == EN && t[k + 1] == EN) {
                t[k] = EN;
            } else if (t[k] == CS && t[k - 1] == t[k + 1] && (t[k - 1] == EN || t[k - 1] == AN)) {
                t[k] = t[k - 1];
            }
        }
        // W5
        for (int k = 0; k < len; k++) {
            if (t[k] == ET) {
                int s = k;
                while (k < len && t[k] == ET) {
                    k++;
                }
                if ((s > 0 && t[s - 1] == EN) || (k < len && t[k] == EN)) {
                    for (int j = s; j < k; j++) {
                        t[j] = EN;
                    }
                }
                k--;
            }
        }
        // W6
        for (int k = 0; k < len; k++) {
            if (t[k] == ES || t[k] == ET || t[k] == CS) {
                t[k] = ON;
            }
        }
        // W7
        strong = sos;
        for (int k = 0; k < len; k++) {
            if (t[k] == L || t[k] == R) {
                strong = t[k];
            } else if (t[k] == EN && strong == L) {
                t[k] = L;
            }
        }
        byte e = (level & 1) != 0 ? R : L;
        // N0: BD16 bracket pairs, processed in order of their opening bracket.
        int[] stackChar = new int[MAX_BRACKETS];
        int[] stackPos = new int[MAX_BRACKETS];
        int depth = 0;
        int[] pairOpen = new int[len];
        int[] pairClose = new int[len];
        int pairs = 0;
        for (int k = 0; k < len; k++) {
            if (t[k] != ON) {
                continue;
            }
            char c = text.charAt(seqIdx[k]);
            int o = openIndexOf(c);
            if (o >= 0) {
                if (depth == MAX_BRACKETS) {
                    break;
                }
                stackChar[depth] = o;
                stackPos[depth++] = k;
                continue;
            }
            int cl = closeIndexOf(c);
            if (cl >= 0) {
                for (int d = depth - 1; d >= 0; d--) {
                    if (stackChar[d] == cl) {
                        pairOpen[pairs] = stackPos[d];
                        pairClose[pairs++] = k;
                        depth = d;
                        break;
                    }
                }
            }
        }
        for (int a = 1; a < pairs; a++) {
            int po = pairOpen[a];
            int pc = pairClose[a];
            int b = a - 1;
            while (b >= 0 && pairOpen[b] > po) {
                pairOpen[b + 1] = pairOpen[b];
                pairClose[b + 1] = pairClose[b];
                b--;
            }
            pairOpen[b + 1] = po;
            pairClose[b + 1] = pc;
        }
        for (int p = 0; p < pairs; p++) {
            int o = pairOpen[p];
            int c = pairClose[p];
            boolean embedding = false;
            boolean opposite = false;
            for (int k = o + 1; k < c; k++) {
                byte d = strongDir(t[k]);
                if (d == e) {
                    embedding = true;
                    break;
                }
                if (d != ON) {
                    opposite = true;
                }
            }
            byte resolved = ON;
            if (embedding) {
                resolved = e;
            } else if (opposite) {
                byte before = sos;
                for (int k = o - 1; k >= 0; k--) {
                    byte d = strongDir(t[k]);
                    if (d != ON) {
                        before = d;
                        break;
                    }
                }
                resolved = before;
            }
            if (resolved != ON) {
                t[o] = resolved;
                t[c] = resolved;
                for (int k = o + 1; k < len && initial[seqIdx[k]] == NSM; k++) {
                    t[k] = resolved;
                }
                for (int k = c + 1; k < len && initial[seqIdx[k]] == NSM; k++) {
                    t[k] = resolved;
                }
            }
        }
        // N1/N2
        for (int k = 0; k < len; k++) {
            if (isNi(t[k])) {
                int s = k;
                while (k < len && isNi(t[k])) {
                    k++;
                }
                byte before = s == 0 ? sos : strongDir(t[s - 1]);
                byte after = k >= len ? eos : strongDir(t[k]);
                byte resolved = before == after ? before : e;
                for (int j = s; j < k; j++) {
                    t[j] = resolved;
                }
                k--;
            }
        }
        // I1/I2
        for (int k = 0; k < len; k++) {
            byte c = t[k];
            int lv = level;
            if ((level & 1) == 0) {
                if (c == R) {
                    lv++;
                } else if (c == AN || c == EN) {
                    lv += 2;
                }
            } else if (c == L || c == EN || c == AN) {
                lv++;
            }
            levels[seqIdx[k]] = (byte) lv;
        }
    }

    /// True if the line, laid out with the given base direction, is purely left-to-right (no reordering
    /// needed). This lets the editor keep its fast LTR path for the common case.
    static boolean isTrivialLtr(String text, boolean baseRtl) {
        if (baseRtl) {
            return text.length() == 0;
        }
        for (int i = 0; i < text.length(); i++) {
            byte t = typeOf(text.charAt(i));
            if (t == R || t == AL || t == AN || (t >= LRE && t <= PDI)) {
                return false;
            }
        }
        return true;
    }
  // END UAX9
}
