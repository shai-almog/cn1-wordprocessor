package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * English proofing commands: {@code review.spelling} (check, next, ignore, ignoreAll, add, change,
 * changeAll) and {@code review.thesaurus}. The user dictionary and ignore-all list live in the
 * document settings {@code proof.dictionary} and {@code proof.ignore}; "ignore once" is remembered
 * per session by position and word (so a later edit before it can make it flag again). Runs whose
 * proofing language is unproofed are skipped; Polish and Portuguese runs use ProofLanguages.
 * Table cells are proofed too (as in Wordcraft, whose review commands walk every paragraph path):
 * an issue in a cell carries the table block, row and column, and "next"/"change" enter the cell.
 */
final class ProofingCommands implements Commands.Extension {
  private static final SessionMap<Set<String>> ONCE = SessionMap.forDocument();

  /**
   * An issue: in body paragraph {@code block} (row -1), or in paragraph {@code block} of the cell
   * (row, column) of the table at body block {@code table}.
   */
  private record Found(int table, int row, int column, int block, Proofing.Issue issue, String word) {
    Found(int block, Proofing.Issue issue, String word) {
      this(block, -1, -1, block, issue, word);
    }

    /** Document order: table block, row, column, paragraph, offset. */
    long[] key() {
      return new long[] {table, row, column, row < 0 ? 0 : block, issue.start()};
    }

    String once() {
      return row < 0 ? block + ":" + issue.start() + ":" + word : table + "/" + row + "/" + column + "/" + block + ":" + issue.start() + ":" + word;
    }

    /** Selects the issue's text, entering or leaving its cell. */
    void select(Session s) {
      if (row < 0) {
        if (s.inCell()) s.leaveCell();
      } else s.enterCell(table, row, column);
      s.select(block, issue.start(), issue.end());
    }

    Map<String, Object> map() {
      var m = new LinkedHashMap<String, Object>();
      m.put("block", Integer.valueOf(row < 0 ? block : table));
      if (row >= 0) {
        m.put("row", Integer.valueOf(row));
        m.put("column", Integer.valueOf(column));
        m.put("paragraph", Integer.valueOf(block));
      }
      m.put("start", Integer.valueOf(issue.start()));
      m.put("end", Integer.valueOf(issue.end()));
      m.put("kind", issue.kind());
      m.put("message", issue.message());
      m.put("word", word);
      m.put("suggestions", issue.suggestions());
      return m;
    }
  }

  public void register(Commands c) {
    c.edit("review.spelling", "action?:string,word?:string,replacement?:string",
        "Spelling and grammar: action check (default), next, ignore, ignoreAll, add, change, changeAll",
        ProofingCommands::spelling);
    c.edit("review.thesaurus", "word?:string,replacement?:string",
        "Thesaurus: synonyms of the word (default: selection or word at the caret); replacement swaps it",
        ProofingCommands::thesaurus);
  }

  private static List<Found> issues(Session s) {
    var d = s.document();
    Proofing.Options o = Proofing.Options.of(d.properties);
    Set<String> once;
    synchronized (ONCE) {
      once = ONCE.get(s);
    }
    var out = new ArrayList<Found>();
    for (int b = 0; b < d.blocks.size(); b++) {
      if (d.blocks.get(b) instanceof Table table) {
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++) {
            if (table.covered(r, c)) continue;
            int k = 0;
            for (Paragraph p : table.cell(r, c)) proof(p, o, once, out, b, r, c, k++);
          }
        continue;
      }
      if (!(d.blocks.get(b) instanceof Paragraph)) continue;
      proof(d.paragraph(b), o, once, out, b, -1, -1, b);
    }
    return out;
  }

  private static void proof(Paragraph p, Proofing.Options o, Set<String> once, List<Found> out, int table, int row, int column, int block) {
    var t = p.text;
    var text = t.plainText();
    for (Proofing.Issue i : Proofing.check(text, o, off -> t.styleAt(off).characters.lang)) {
      var f = new Found(table, row, column, block, i, text.substring(i.start(), i.end()));
      if (once == null || !once.contains(f.once())) out.add(f);
    }
  }

  private static int compare(long[] a, long[] b) {
    for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
    return 0;
  }

  /** The selection's end (or caret, when at) as a document-order key like Found.key(). */
  private static long[] here(Session s, boolean end) {
    var sel = s.selection();
    int b = end ? sel.endBlock() : sel.block, off = end ? sel.end() : sel.caret;
    if (s.inCell()) return new long[] {s.cellBlock(), s.cellRow(), s.cellColumn(), b, off};
    return new long[] {b, -1, -1, 0, off};
  }

  private static Object spelling(Session s, Map<String, Object> a) {
    String action = Commands.str(a, "action", "check");
    if ("check".equals(action)) return report(issues(s));
    if ("next".equals(action)) {
      var all = issues(s);
      long[] at = here(s, true);
      Found hit = null;
      for (Found f : all)
        if (compare(f.key(), at) >= 0) {
          hit = f;
          break;
        }
      if (hit == null && !all.isEmpty()) hit = all.get(0);
      if (hit == null) return report(all);
      hit.select(s);
      Map<String, Object> m = hit.map();
      m.put("remaining", Integer.valueOf(all.size()));
      return m;
    }
    String word = Commands.str(a, "word", null);
    if ("add".equals(action) || "ignoreAll".equals(action)) {
      if (word == null) word = current(s);
      if (word == null || word.length() == 0) throw new IllegalArgumentException("No word to " + action);
      String key = "add".equals(action) ? Proofing.DICTIONARY : Proofing.IGNORE;
      var set = Proofing.list(s.document().properties.setting(key, ""));
      set.add(word.toLowerCase());
      s.documentProperties(s.document().properties.withSetting(key, Proofing.join(set)));
      var m = new LinkedHashMap<String, Object>();
      m.put("word", word);
      m.put("action", action);
      m.put("remaining", Integer.valueOf(issues(s).size()));
      return m;
    }
    if ("ignore".equals(action)) {
      Found f = target(s, word);
      if (f == null) throw new IllegalArgumentException("No issue to ignore");
      synchronized (ONCE) {
        var once = ONCE.get(s);
        if (once == null) ONCE.put(s, once = new HashSet<String>());
        once.add(f.once());
      }
      return report(issues(s));
    }
    if ("change".equals(action) || "changeAll".equals(action)) {
      String replacement = Commands.str(a, "replacement");
      if ("change".equals(action)) {
        Found f = target(s, word);
        if (f == null) throw new IllegalArgumentException("No issue to change");
        f.select(s);
        s.replace(replacement);
      } else {
        if (word == null) word = current(s);
        if (word == null) throw new IllegalArgumentException("No word to change");
        var all = issues(s);
        for (int i = all.size() - 1; i >= 0; i--) {
          var f = all.get(i);
          if (!f.word.equals(word)) continue;
          f.select(s);
          s.replace(replacement);
        }
      }
      return report(issues(s));
    }
    throw new IllegalArgumentException("Unknown action: " + action);
  }

  private static Map<String, Object> report(List<Found> all) {
    var list = new ArrayList<Map<String, Object>>();
    for (Found f : all) list.add(f.map());
    var m = new LinkedHashMap<String, Object>();
    m.put("count", Integer.valueOf(list.size()));
    m.put("issues", list);
    return m;
  }

  /** The selected word, else the word at the caret. */
  private static String current(Session s) {
    var sel = s.selectedText();
    if (sel != null && sel.trim().length() > 0 && sel.indexOf('\n') < 0) return sel.trim();
    var x = s.selection();
    if (!(s.editingDocument().blocks.get(x.block) instanceof Paragraph)) return null;
    var text = s.editingDocument().paragraph(x.block).text.plainText();
    for (int[] w : Proofing.words(text)) if (x.caret >= w[0] && x.caret <= w[1]) return text.substring(w[0], w[1]);
    return null;
  }

  /** The issue matching {@code word}, else the one at the selection/caret. */
  private static Found target(Session s, String word) {
    var all = issues(s);
    long[] at = here(s, false);
    for (Found f : all) {
      long[] k = f.key();
      boolean same = k[0] == at[0] && k[1] == at[1] && k[2] == at[2] && k[3] == at[3];
      if (word != null ? f.word.equals(word) : same && at[4] >= f.issue.start() && at[4] <= f.issue.end()) return f;
    }
    return null;
  }

  private static Object thesaurus(Session s, Map<String, Object> a) {
    String word = Commands.str(a, "word", current(s));
    if (word == null) throw new IllegalArgumentException("No word");
    String replacement = Commands.str(a, "replacement", null);
    if (replacement != null) {
      int[] at = locate(s, word);
      if (at == null) throw new IllegalArgumentException("Word not found: " + word);
      if (Character.isUpperCase(word.charAt(0)) && replacement.length() > 0)
        replacement = Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
      s.select(at[0], at[1], at[2]);
      s.replace(replacement);
    }
    var m = new LinkedHashMap<String, Object>();
    m.put("word", word.toLowerCase());
    m.put("synonyms", Thesaurus.synonyms(word));
    return m;
  }

  /** Block/start/end of the word: at the selection or caret first, else its first occurrence. */
  private static int[] locate(Session s, String word) {
    var x = s.selection();
    var d = s.document();
    if (d.blocks.get(x.block) instanceof Paragraph) {
      var text = d.paragraph(x.block).text.plainText();
      for (int[] w : Proofing.words(text))
        if (text.substring(w[0], w[1]).equalsIgnoreCase(word) && x.caret >= w[0] && x.caret <= w[1])
          return new int[] {x.block, w[0], w[1]};
    }
    for (int b = 0; b < d.blocks.size(); b++) {
      if (!(d.blocks.get(b) instanceof Paragraph)) continue;
      var text = d.paragraph(b).text.plainText();
      for (int[] w : Proofing.words(text))
        if (text.substring(w[0], w[1]).equalsIgnoreCase(word)) return new int[] {b, w[0], w[1]};
    }
    return null;
  }
}
