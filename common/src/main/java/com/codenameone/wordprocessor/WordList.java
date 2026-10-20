package com.codenameone.wordprocessor;

/**
 * Sorted lowercase word list with a hyphenation break mask per word (bit i = hyphen before char
 * i), read from the bundled front-coded raw-deflate {@code proof/en-us.dic}; loaded once, lazily.
 */
final class WordList {
  private static final String MAGIC = "DCHYPH1\n";

  /*
   * The words packed as Latin-1 bytes (every dictionary word is Latin-1) with start offsets:
   * about 3 MB resident where one String per word cost over 15 MB on the native VMs.
   */
  private final byte[] text;
  private final int[] starts;
  final long[] masks;

  private WordList(byte[] text, int[] starts, long[] masks) {
    this.text = text;
    this.starts = starts;
    this.masks = masks;
  }

  // Volatile so loaded() never waits on the lock get() holds while the list loads: paint asks
  // whether proofing is ready and must not block on the background preload.
  private static volatile WordList instance;

  static boolean loaded() {
    return instance != null;
  }

  static synchronized WordList get() {
    if (instance == null) instance = load();
    return instance;
  }

  private static WordList load() {
    byte[] raw = ProofData.bytes("/proof/en-us.dic", true);
    int n = 0;
    for (int i = 8; i < raw.length; i++) if (raw[i] == '\n') n++;
    var text = new byte[raw.length];
    int[] starts = new int[n + 2];
    long[] masks = new long[n + 1];
    int count = 0, size = 0;
    byte[] cur = new byte[256];
    int curLen = 0;
    int i = MAGIC.length();
    while (i < raw.length) {
      int p = raw[i] & 255;
      int end = i + 1;
      while (end < raw.length && raw[end] != '\n') end++;
      if (p > curLen) break;
      int add = end - i - 1;
      if (p + add > cur.length) cur = java.util.Arrays.copyOf(cur, Math.max(cur.length * 2, p + add));
      System.arraycopy(raw, i + 1, cur, p, add);
      curLen = p + add;
      i = end + 1;
      // Decode the UTF-8 entry to Latin-1, dropping the hyphens that mark break points.
      int start = size, letters = 0;
      long mask = 0;
      boolean latin1 = true;
      for (int k = 0; k < curLen && latin1; k++) {
        int b = cur[k] & 255;
        if (b == '-') {
          if (letters > 0 && letters < 64) mask |= 1L << letters;
          continue;
        }
        if (b >= 0x80) {
          if ((b & 0xe0) != 0xc0 || k + 1 >= curLen) latin1 = false;
          else b = (b & 0x1f) << 6 | (cur[++k] & 0x3f);
          if (b > 0xff) latin1 = false;
        }
        if (size >= text.length) text = java.util.Arrays.copyOf(text, text.length * 2);
        text[size++] = (byte) b;
        letters++;
      }
      if (!latin1) {
        size = start;
        continue;
      }
      starts[count] = start;
      masks[count++] = mask;
    }
    starts[count] = size;
    return new WordList(
        java.util.Arrays.copyOf(text, size),
        java.util.Arrays.copyOf(starts, count + 1),
        java.util.Arrays.copyOf(masks, count));
  }

  int size() {
    return masks.length;
  }

  int length(int k) {
    return starts[k + 1] - starts[k];
  }

  char charAt(int k, int i) {
    return (char) (text[starts[k] + i] & 255);
  }

  String word(int k) {
    int start = starts[k], n = starts[k + 1] - start;
    var c = new char[n];
    for (int i = 0; i < n; i++) c[i] = (char) (text[start + i] & 255);
    return new String(c);
  }

  /** Compares word k with s by UTF-16 unit, as String.compareTo does. */
  private int compare(int k, String s) {
    int start = starts[k], n = starts[k + 1] - start, m = s.length();
    for (int i = 0, l = Math.min(n, m); i < l; i++) {
      int c = (text[start + i] & 255) - s.charAt(i);
      if (c != 0) return c;
    }
    return n - m;
  }

  int index(String word) {
    int lo = 0, hi = size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int c = compare(mid, word);
      if (c < 0) lo = mid + 1;
      else if (c > 0) hi = mid - 1;
      else return mid;
    }
    return -1;
  }

  boolean contains(String word) {
    return index(word) >= 0;
  }
}
