package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/**
 * The Read Aloud queue, matching Wordcraft's player (crates/engine/src/speech.rs): the sentences
 * from the caret to the end of the document, split at . ! ? (with closing quotes and brackets) but
 * not after abbreviations ("et al.", "Fig.") or initials ("J. Smith"); very long sentences are
 * split at a space. With skip citations on, Zotero, Mendeley, EndNote and Word citation fields
 * and the bibliography are left out of what is spoken, and the gaps they leave ("( )", " .") are
 * tidied. Speed is 0.5x to 3x, 1x being about 180 words per minute.
 */
final class ReadAloud {
  static final float MIN_RATE = .5f, MAX_RATE = 3f;
  static final int MAX_SENTENCE = 600, MAX_SENTENCES = 20000;
  static final float BASE_WPM = 180;

  private static final String[] ABBREVIATIONS = {
    "al", "e.g", "i.e", "eg", "ie", "cf", "vs", "etc", "fig", "figs", "eq", "eqs", "no", "nos", "p", "pp", "vol", "vols", "ch", "chap", "sec",
    "tab", "ref", "refs", "dr", "mr", "mrs", "ms", "prof", "st", "approx", "ca", "resp", "ed", "eds", "suppl", "jr", "sr", "inc", "ltd", "co",
    "dept", "est", "min", "max", "viz", "ibid"
  };

  /** One sentence: its paragraph, character range there and the text to speak. */
  static final class Utterance {
    final int block, start, end;
    final String text;

    Utterance(int block, int start, int end, String text) {
      this.block = block;
      this.start = start;
      this.end = end;
      this.text = text;
    }
  }

  /** The reading state of a session. */
  static final class Reading {
    final List<Utterance> queue;
    int index;
    boolean paused;

    Reading(List<Utterance> queue) {
      this.queue = queue;
    }
  }

  private ReadAloud() {}

  static float clamp(float rate) {
    if (Float.isNaN(rate) || Float.isInfinite(rate)) return 1f;
    return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
  }

  /** Is this field code a citation or a bibliography (Zotero, Mendeley, EndNote, Word)? */
  static boolean isCitationCode(String instr) {
    String code = instr == null ? "" : instr.trim();
    int sp = 0;
    while (sp < code.length() && !Character.isWhitespace(code.charAt(sp))) sp++;
    String word = code.substring(0, sp).toUpperCase();
    if (word.equals("CITATION") || word.equals("BIBLIOGRAPHY")) return true;
    if (!word.equals("ADDIN")) return false;
    String rest = code.substring(sp).trim().toUpperCase();
    for (String k : new String[] {"ZOTERO_ITEM", "ZOTERO_BIBL", "CSL_CITATION", "CSL_BIBLIOGRAPHY", "MENDELEY", "EN.CITE", "EN.REFLIST"})
      if (rest.startsWith(k)) return true;
    return false;
  }

  /** The sentences from (block, offset) to the end of the document's body paragraphs. */
  static List<Utterance> sentences(Document d, int fromBlock, int fromOffset, boolean skipCitations) {
    var out = new ArrayList<Utterance>();
    int[] bibliography = skipCitations ? Citations.range(d) : null;
    for (int b = Math.max(0, fromBlock); b < d.blocks.size() && out.size() < MAX_SENTENCES; b++) {
      if (!(d.blocks.get(b) instanceof Paragraph)) continue;
      if (bibliography != null && b >= bibliography[0] && b < bibliography[1]) continue;
      RichText rt = d.paragraph(b).text;
      String plain = rt.toString(); // document offsets: each field or note is one object character
      int from = b == fromBlock ? Math.min(fromOffset, plain.length()) : 0;
      for (int[] r : split(plain, from)) {
        String spoken = tidy(spoken(rt, plain, r[0], r[1], skipCitations));
        if (!spoken.isEmpty()) out.add(new Utterance(b, r[0], r[1], spoken));
        if (out.size() >= MAX_SENTENCES) break;
      }
    }
    return out;
  }

  /** The text to speak for [start, end): fields read as their result, citations left out if asked. */
  private static String spoken(RichText rt, String plain, int start, int end, boolean skipCitations) {
    var b = new StringBuilder();
    for (int i = start; i < end; i++) {
      char c = plain.charAt(i);
      if (c == InlineField.OBJECT.charAt(0) && rt.objectAt(i)) {
        InlineField f = rt.fieldAt(i);
        if (f != null && !(skipCitations && isCitationCode(f.instruction))) b.append(f.result.replace('\n', ' '));
        else b.append(' ');
      } else b.append(c);
    }
    return b.toString();
  }

  /** Sentence ranges in text from offset from, as [start, end) pairs. */
  static List<int[]> split(String text, int from) {
    var out = new ArrayList<int[]>();
    int start = from, i = from, n = text.length();
    while (i < n) {
      char c = text.charAt(i);
      int end = -1;
      if (c == '.' || c == '!' || c == '?' || c == '…' || c == '。' || c == '！' || c == '？') {
        int j = i + 1;
        while (j < n && "\"')]”’».!?".indexOf(text.charAt(j)) >= 0) j++;
        boolean atEnd = j >= n, spaceAfter = j < n && Character.isWhitespace(text.charAt(j));
        if ((atEnd || spaceAfter) && (c != '.' || !abbreviation(text, start, i))) end = j;
        i = Math.max(j, i + 1);
      } else i++;
      if (end < 0 && i - start >= MAX_SENTENCE && i < n && Character.isWhitespace(text.charAt(i))) end = i;
      if (end >= 0) {
        trimmed(text, start, end, out);
        start = end;
      }
    }
    trimmed(text, start, n, out);
    return out;
  }

  private static void trimmed(String t, int a, int b, List<int[]> out) {
    while (a < b && Character.isWhitespace(t.charAt(a))) a++;
    while (b > a && Character.isWhitespace(t.charAt(b - 1))) b--;
    if (a < b) out.add(new int[] {a, b});
  }

  private static boolean wordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c > 127 && c != '\ufffc');
  }

  /** Does the full stop at dot end an abbreviation or an initial rather than a sentence? */
  static boolean abbreviation(String t, int start, int dot) {
    int w = dot;
    while (w > start && (wordChar(t.charAt(w - 1)) || t.charAt(w - 1) == '.')) w--;
    String word = t.substring(w, dot).toLowerCase();
    if (word.isEmpty()) return false;
    int letters = 0;
    char only = 0;
    for (int k = 0; k < word.length(); k++)
      if (word.charAt(k) != '.') {
        letters++;
        only = word.charAt(k);
      }
    if (letters == 1 && !(only >= '0' && only <= '9')) return true;
    for (String a : ABBREVIATIONS) if (a.equals(word)) return true;
    return false;
  }

  /** Collapse whitespace and tidy what removed citations leave behind: "( )", "[]", " ." to ".". */
  static String tidy(String s) {
    var b = new StringBuilder();
    boolean space = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) space = b.length() > 0;
      else {
        if (space) b.append(' ');
        space = false;
        b.append(c);
      }
    }
    String t = b.toString();
    String[][] fixes = {{"()", ""}, {"[]", ""}, {"( )", ""}, {"[ ]", ""}, {" .", "."}, {" ,", ","}, {" ;", ";"}, {" :", ":"}, {"  ", " "}};
    while (true) {
      String before = t;
      for (String[] f : fixes) t = replaceAll(t, f[0], f[1]);
      if (t.equals(before)) break;
    }
    return t.trim();
  }

  private static String replaceAll(String s, String from, String to) {
    int i = s.indexOf(from);
    if (i < 0) return s;
    var b = new StringBuilder();
    int last = 0;
    while (i >= 0) {
      b.append(s, last, i).append(to);
      last = i + from.length();
      i = s.indexOf(from, last);
    }
    return b.append(s, last, s.length()).toString();
  }

  /** Estimated speaking time in milliseconds at BASE_WPM words per minute times rate. */
  static long duration(String text, float rate) {
    int words = 0;
    boolean in = false;
    for (int i = 0; i < text.length(); i++) {
      boolean sp = text.charAt(i) <= ' ';
      if (!sp && !in) words++;
      in = !sp;
    }
    return (long) (Math.max(1, words) * 60000f / (BASE_WPM * clamp(rate))) + 400;
  }
}
