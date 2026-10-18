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
 * proofing language is set to something other than English are skipped.
 */
final class ProofingCommands implements Commands.Extension {
  private static final SessionMap<Set<String>> ONCE = new SessionMap<>();

  private static final class Found {
    final int block;
    final Proofing.Issue issue;
    final String word;

    Found(int block, Proofing.Issue issue, String word) {
      this.block = block;
      this.issue = issue;
      this.word = word;
    }

    Map<String, Object> map() {
      var m = new LinkedHashMap<String, Object>();
      m.put("block", Integer.valueOf(block));
      m.put("start", Integer.valueOf(issue.start));
      m.put("end", Integer.valueOf(issue.end));
      m.put("kind", issue.kind);
      m.put("message", issue.message);
      m.put("word", word);
      m.put("suggestions", issue.suggestions);
      return m;
    }
  }

  public void register(Commands c) {
    c.register(
        "review.spelling",
        "Spelling and grammar: action check (default), next, ignore, ignoreAll, add, change, changeAll",
        "action?:string,word?:string,replacement?:string",
        true,
        ProofingCommands::spelling);
    c.register(
        "review.thesaurus",
        "Thesaurus: synonyms of the word (default: selection or word at the caret); replacement swaps it",
        "word?:string,replacement?:string",
        true,
        ProofingCommands::thesaurus);
  }

  private static boolean english(String lang) {
    return lang.length() == 0 || lang.toLowerCase().startsWith("en");
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
      if (!(d.blocks.get(b) instanceof Paragraph)) continue;
      var t = d.paragraph(b).text;
      var text = t.plainText();
      for (Proofing.Issue i : Proofing.check(text, o)) {
        if (!english(t.styleAt(i.start).characters.lang)) continue;
        var word = text.substring(i.start, i.end);
        if (once != null && once.contains(b + ":" + i.start + ":" + word)) continue;
        out.add(new Found(b, i, word));
      }
    }
    return out;
  }

  private static Object spelling(Session s, Map<String, Object> a) {
    String action = a.containsKey("action") ? Commands.str(a, "action") : "check";
    if ("check".equals(action)) return report(issues(s));
    if ("next".equals(action)) {
      var all = issues(s);
      var sel = s.selection();
      int eb = sel.endBlock(), eo = sel.end();
      Found hit = null;
      for (Found f : all)
        if (f.block > eb || f.block == eb && f.issue.start >= eo) {
          hit = f;
          break;
        }
      if (hit == null && !all.isEmpty()) hit = all.get(0);
      if (hit == null) return report(all);
      s.select(hit.block, hit.issue.start, hit.issue.end);
      Map<String, Object> m = hit.map();
      m.put("remaining", Integer.valueOf(all.size()));
      return m;
    }
    String word = a.containsKey("word") ? Commands.str(a, "word") : null;
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
        once.add(f.block + ":" + f.issue.start + ":" + f.word);
      }
      return report(issues(s));
    }
    if ("change".equals(action) || "changeAll".equals(action)) {
      String replacement = Commands.str(a, "replacement");
      if ("change".equals(action)) {
        Found f = target(s, word);
        if (f == null) throw new IllegalArgumentException("No issue to change");
        s.select(f.block, f.issue.start, f.issue.end);
        s.replace(replacement);
      } else {
        if (word == null) word = current(s);
        if (word == null) throw new IllegalArgumentException("No word to change");
        var all = issues(s);
        for (int i = all.size() - 1; i >= 0; i--) {
          var f = all.get(i);
          if (!f.word.equals(word)) continue;
          s.select(f.block, f.issue.start, f.issue.end);
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
    if (!(s.document().blocks.get(x.block) instanceof Paragraph)) return null;
    var text = s.document().paragraph(x.block).text.plainText();
    for (int[] w : Proofing.words(text)) if (x.caret >= w[0] && x.caret <= w[1]) return text.substring(w[0], w[1]);
    return null;
  }

  /** The issue matching {@code word}, else the one at the selection/caret. */
  private static Found target(Session s, String word) {
    var all = issues(s);
    var x = s.selection();
    for (Found f : all)
      if (word != null ? f.word.equals(word) : f.block == x.block && x.caret >= f.issue.start && x.caret <= f.issue.end)
        return f;
    return null;
  }

  private static Object thesaurus(Session s, Map<String, Object> a) {
    String word = a.containsKey("word") ? Commands.str(a, "word") : current(s);
    if (word == null) throw new IllegalArgumentException("No word");
    String replacement = a.containsKey("replacement") ? Commands.str(a, "replacement") : null;
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
