package com.codenameone.wordprocessor;

/**
 * Sorted lowercase word list with a hyphenation break mask per word (bit i = hyphen before char
 * i), read from the bundled front-coded raw-deflate {@code proof/en-us.dic}; loaded once, lazily.
 */
final class WordList {
  private static final String MAGIC = "DCHYPH1\n";
  private static WordList instance;

  final String[] words;
  final long[] masks;

  private WordList(String[] w, long[] m) {
    words = w;
    masks = m;
  }

  static synchronized WordList get() {
    if (instance == null) instance = load();
    return instance;
  }

  private static WordList load() {
    byte[] raw = ProofData.bytes("/proof/en-us.dic", true);
    int n = 0;
    for (int i = 8; i < raw.length; i++) if (raw[i] == '\n') n++;
    String[] words = new String[n + 1];
    long[] masks = new long[n + 1];
    int count = 0;
    byte[] cur = new byte[256];
    int curLen = 0;
    int i = MAGIC.length();
    try {
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
        String hyphenated = new String(cur, 0, curLen, "UTF-8");
        StringBuilder w = new StringBuilder(hyphenated.length());
        long mask = 0;
        for (int k = 0; k < hyphenated.length(); k++) {
          char c = hyphenated.charAt(k);
          if (c == '-') {
            if (w.length() > 0 && w.length() < 64) mask |= 1L << w.length();
          } else w.append(c);
        }
        words[count] = w.toString();
        masks[count++] = mask;
      }
    } catch (java.io.UnsupportedEncodingException e) {
      count = 0;
    }
    return new WordList(java.util.Arrays.copyOf(words, count), java.util.Arrays.copyOf(masks, count));
  }

  int index(String word) {
    int lo = 0, hi = words.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      int c = words[mid].compareTo(word);
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
