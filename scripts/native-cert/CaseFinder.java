import com.codenameone.wordprocessor.*;
import java.io.*;
import java.util.*;

/**
 * Finds a replayable JVM case for registry commands that the unit tests never call. Each command
 * is tried against document states recorded from the tests, with arguments derived from its
 * schema; the first call that changes the document or selection, or returns a value, is written
 * as a replay record. Usage: CaseFinder recorded.jsonl ids.txt out.jsonl
 */
public class CaseFinder {
  static final Map<String, Object[]> STRINGS = new HashMap<String, Object[]>();

  static void s(String k, Object... v) {
    STRINGS.put(k, v);
  }

  static {
    s("align", "center", "right");
    s("text", "Sample", "Smith v. Jones");
    s("replacement", "Example");
    s("color", "FF0000", "yellow");
    s("tool", "pen", "highlighter");
    s("points", "10 10 60 40 110 20", "10,10 60,40 110,20");
    s("format", "text", "unicode", "linear", "html", "rtf", "1, 2, 3");
    s("data", "Inserted text", "SGVsbG8=");
    s("code", "PAGE", "DATE");
    s("mode", "upper", "readOnly", "comments", "lower");
    s("name", "Arial", "Heading 1", "Title", "Strong", "Sample", "Author", "Emphasis");
    s("style", "Heading2", "Heading 2", "Title");
    s("id", "Heading2", "Title", "Strong");
    s("kind", "page", "bullet", "number", "column", "nextPage", "decimal");
    s("label", "Figure", "Table");
    s("property", "style", "font", "formatting");
    s("value", "true");
    s("action", "save", "insert", "list", "moveUp", "start");
    s("rule", "fillIn", "if", "ask", "nextRecord");
    s("field", "Name", "First");
    s("operator", "equals", "==");
    s("then", "Yes");
    s("else", "No");
    s("entry", "Smith v. Jones");
    s("separator", "tab", "\t", ",");
    s("clear", "character", "paragraph", "all");
    s("order", "name");
    s("signer", "Pat Lee");
    s("title", "Manager");
    s("author", "Reviewer");
    s("position", "below");
    s("preset", "two", "three");
    s("apply", "whole", "document");
    s("delivery", "1 Main St");
    s("return", "2 Side St");
    s("size", "DL", "10");
    s("product", "Avery 5160");
    s("font", "Arial");
    s("underlineStyle", "single");
    s("underlineColor", "FF0000");
    s("script", "superscript");
    s("tabLeader", "dot");
    s("tcIdentifier", "C");
    s("fromBlock", "0");
  }

  static Object[] candidates(String name, String type) {
    if ("string".equals(type)) {
      Object[] v = STRINGS.get(name);
      return v == null ? new Object[] {"Sample", "1"} : v;
    }
    if ("integer".equals(type)) {
      if (name.equals("level")) return new Object[] {2, 1};
      if (name.equals("rows") || name.equals("columns") || name.equals("cols"))
        return new Object[] {2, 1};
      if (name.equals("code")) return new Object[] {169};
      if (name.equals("style")) return new Object[] {3, 1};
      if (name.endsWith("Column") || name.equals("column") || name.equals("row"))
        return new Object[] {0, 1};
      if (name.equals("toBlock") || name.equals("endColumn")) return new Object[] {1, 3, 2};
      return new Object[] {0, 1, 2, 3};
    }
    if ("number".equals(type)) {
      if (name.equals("degrees")) return new Object[] {90};
      if (name.equals("value")) return new Object[] {2, 150, 3};
      return new Object[] {12, 2, 1};
    }
    if ("boolean".equals(type)) return new Object[] {Boolean.TRUE, Boolean.FALSE};
    if ("object".equals(type)) {
      Map<String, Object> m = new LinkedHashMap<String, Object>();
      if (name.equals("mapping")) m.put("Name", "Name");
      if (name.equals("single")) {
        m.put("row", 1);
        m.put("column", 1);
      }
      return new Object[] {m};
    }
    if ("array".equals(type)) return new Object[] {new ArrayList<Object>()};
    return new Object[] {"Sample", 1, Boolean.TRUE};
  }

  static List<Map<String, Object>> argSets(Commands.Descriptor d, int blocks) {
    List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    Map<String, Object> base = new LinkedHashMap<String, Object>();
    for (String k : d.parameters.keySet())
      if (d.required.contains(k)) base.put(k, candidates(k, d.parameters.get(k))[0]);
    out.add(base);
    // vary one required parameter at a time
    for (String k : d.parameters.keySet()) {
      Object[] c = candidates(k, d.parameters.get(k));
      if ("block".equals(k) || "fromBlock".equals(k)) {
        c = new Object[blocks];
        for (int i = 0; i < blocks; i++) c[i] = i;
      }
      for (int i = 0; i < c.length; i++) {
        Map<String, Object> m = new LinkedHashMap<String, Object>(base);
        m.put(k, c[i]);
        out.add(m);
      }
    }
    // all parameters with first candidates
    Map<String, Object> all = new LinkedHashMap<String, Object>();
    for (String k : d.parameters.keySet()) all.put(k, candidates(k, d.parameters.get(k))[0]);
    out.add(all);
    return out;
  }

  public static void main(String[] a) throws Exception {
    List<Map<String, Object>> recs = new ArrayList<Map<String, Object>>();
    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), "UTF-8"));
    Set<String> seen = new HashSet<String>();
    for (String line; (line = r.readLine()) != null; ) {
      Map<String, Object> m = Json.parse(line);
      String key = m.get("before") + "|" + Json.write(m.get("state"));
      if (seen.add(key)) recs.add(m);
    }
    List<String> ids = new ArrayList<String>();
    BufferedReader ir = new BufferedReader(new FileReader(a[1]));
    for (String line; (line = ir.readLine()) != null; ) if (!line.trim().isEmpty()) ids.add(line.trim());
    Writer out = new OutputStreamWriter(new FileOutputStream(a[2]), "UTF-8");
    Commands commands = new Commands();
    Map<String, Commands.Descriptor> ds = new HashMap<String, Commands.Descriptor>();
    for (Commands.Descriptor d : commands.descriptors()) ds.put(d.id, d);
    for (String id : ids) {
      Commands.Descriptor d = ds.get(id);
      String prefix = id.substring(0, id.indexOf('.') + 1);
      List<Map<String, Object>> ordered = new ArrayList<Map<String, Object>>();
      for (Map<String, Object> m : recs) if (((String) m.get("command")).startsWith(prefix)) ordered.add(m);
      for (Map<String, Object> m : recs) if (!((String) m.get("command")).startsWith(prefix)) ordered.add(m);
      Map<String, Object> found = null;
      int tries = 0;
      search:
      for (Map<String, Object> fx : ordered) {
        Document doc = DocumentXml.read((String) fx.get("before"));
        for (Map<String, Object> args : argSets(d, doc.blocks.size())) {
          if (++tries > 4000) break search;
          Session s;
          try {
            s = load((String) fx.get("before"), (Map<String, Object>) fx.get("state"));
          } catch (Exception ex) {
            continue search;
          }
          String before = DocumentXml.write(s.document());
          String st = Json.write(state(s));
          Object result;
          try {
            result = commands.execute(s, id, args);
          } catch (Throwable ex) {
            continue;
          }
          String after = DocumentXml.write(s.document());
          boolean effect = !after.equals(before) || !Json.write(state(s)).equals(st)
              || result != null && !"".equals(result) && !(result instanceof Map && ((Map) result).isEmpty());
          if (!effect) continue;
          found = new LinkedHashMap<String, Object>();
          found.put("test", "CaseFinder");
          found.put("command", id);
          found.put("args", Json.parse(Json.write(args)));
          found.put("before", before);
          found.put("state", Json.parse(st));
          found.put("after", after);
          found.put("stateAfter", state(s));
          break search;
        }
      }
      if (found == null) System.out.println("NOCASE " + id);
      else {
        out.write(Json.write(found));
        out.write('\n');
      }
    }
    out.close();
  }

  static Session load(String xml, Map<String, Object> st) throws IOException {
    Session s = new Session(DocumentXml.read(xml));
    if (st.get("reviewAuthor") instanceof String) s.reviewAuthor((String) st.get("reviewAuthor"));
    s.trackFormatting(Boolean.TRUE.equals(st.get("trackFormatting")));
    int cell = num(st, "cellBlock");
    if (cell >= 0) s.enterCell(cell, num(st, "cellRow"), num(st, "cellColumn"));
    s.selectRange(num(st, "anchorBlock"), num(st, "anchor"), num(st, "block"), num(st, "caret"));
    Map<String, Object> cs = (Map<String, Object>) st.get("cellSelection");
    if (cs != null)
      s.selectCells(num(cs, "block"), num(cs, "anchorRow"), num(cs, "anchorColumn"), num(cs, "focusRow"), num(cs, "focusColumn"));
    return s;
  }

  static int num(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v instanceof Number ? ((Number) v).intValue() : -1;
  }

  static Map<String, Object> state(Session s) {
    Selection x = s.selection();
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("anchorBlock", x.anchorBlock);
    m.put("anchor", x.anchor);
    m.put("block", x.block);
    m.put("caret", x.caret);
    m.put("cellBlock", s.cellBlock());
    m.put("cellRow", s.cellRow());
    m.put("cellColumn", s.cellColumn());
    CellSelection c = s.cellSelection();
    if (c != null) {
      Map<String, Object> cs = new LinkedHashMap<String, Object>();
      cs.put("block", c.block);
      cs.put("anchorRow", c.anchorRow);
      cs.put("anchorColumn", c.anchorColumn);
      cs.put("focusRow", c.focusRow);
      cs.put("focusColumn", c.focusColumn);
      m.put("cellSelection", cs);
    }
    m.put("reviewAuthor", s.reviewAuthor());
    m.put("trackFormatting", s.trackFormatting());
    return m;
  }
}
