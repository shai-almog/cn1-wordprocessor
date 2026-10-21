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
   * An issue in paragraph {@code block} of the story at cell {@code path} (Session.cellPath:
   * empty for the body, block/row/column triples for cells of tables at any depth).
   */
  private record Found(int[] path, int block, Proofing.Issue issue, String word) {
    /** Document order: the cell path, paragraph, offset. */
    long[] key() {
      long[] k = new long[path.length + 2];
      for (int i = 0; i < path.length; i++) k[i] = path[i];
      k[path.length] = block;
      k[path.length + 1] = issue.start();
      return k;
    }

    String once() {
      var b = new StringBuilder();
      for (int i = 0; i < path.length; i++) {
        if (i > 0) b.append('/');
        b.append(path[i]);
      }
      if (path.length > 0) b.append('/');
      return b.append(block).append(':').append(issue.start()).append(':').append(word).toString();
    }

    /** Selects the issue's text, entering or leaving its cell. */
    void select(Session s) {
      if (path.length == 0) {
        if (s.inCell()) s.leaveCell();
      } else s.enterCellPath(path);
      s.select(block, issue.start(), issue.end());
    }

    Map<String, Object> map() {
      var m = new LinkedHashMap<String, Object>();
      m.put("block", Integer.valueOf(path.length == 0 ? block : path[0]));
      if (path.length > 0) {
        m.put("row", Integer.valueOf(path[1]));
        m.put("column", Integer.valueOf(path[2]));
        m.put("paragraph", Integer.valueOf(block));
      }
      if (path.length > 3) {
        var list = new ArrayList<Object>();
        for (int v : path) list.add(Integer.valueOf(v));
        m.put("path", list);
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
    for (MixedRange.Covered c : MixedRange.paragraphs(d)) proof(c.paragraph, o, once, out, c.path, c.block);
    return out;
  }

  private static void proof(Paragraph p, Proofing.Options o, Set<String> once, List<Found> out, int[] path, int block) {
    var t = p.text;
    var text = t.plainText();
    for (Proofing.Issue i : Proofing.check(text, o, off -> t.styleAt(off).characters.lang)) {
      var f = new Found(path, block, i, text.substring(i.start(), i.end()));
      if (once == null || !once.contains(f.once())) out.add(f);
    }
  }

  private static int compare(long[] a, long[] b) {
    for (int i = 0; i < Math.min(a.length, b.length); i++) if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
    return Integer.compare(a.length, b.length);
  }

  /** The selection's end (or caret, when at) as a document-order key like Found.key(). */
  private static long[] here(Session s, boolean end) {
    var sel = s.selection();
    int b = end ? sel.endBlock() : sel.block, off = end ? sel.end() : sel.caret;
    int[] path = s.inCell() ? s.cellPath() : new int[0];
    long[] k = new long[path.length + 2];
    for (int i = 0; i < path.length; i++) k[i] = path[i];
    k[path.length] = b;
    k[path.length + 1] = off;
    return k;
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
      boolean same = k.length == at.length;
      for (int i = 0; same && i < k.length - 1; i++) same = k[i] == at[i];
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
