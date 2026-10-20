package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Answers Zotero's document calls against a {@link Session}. Zotero fields are the editor's
 * ordinary atomic inline fields; ids are handed out per field in document order and tracked
 * across inserts and deletes within a session, as Zotero expects stable ids until
 * Document_complete.
 *
 * <p>Limits (see docs/STATUS.md): citations live in body paragraphs only (not table cells,
 * footnotes or endnotes), so note styles are rejected; field results are plain text.
 */
public final class ZoteroBridge implements Zotero.Handler {
  public static final int PROTOCOL_VERSION = 3;
  /** Text a new field holds until Zotero writes its result. */
  public static final String PLACEHOLDER = "{Citation}";

  /** The editor-side UI Zotero can ask for. */
  public interface Host {
    /** Shows text; returns the button index Zotero expects (0 = OK/No/Cancel, 1 = Yes...). */
    int alert(String text, int icon, int buttons);

    default void activate() {}
  }

  private final Session session;
  private final Host host;
  private long nextId = 1, docId;
  private final List<Long> ids = new ArrayList<>();
  private Long caretAfter;

  public ZoteroBridge(Session session, Host host) {
    this.session = session;
    this.host = host;
  }

  /** A Zotero field located in the body: block index, offset and field. */
  static final class Located {
    final int block, offset;
    final InlineField field;

    Located(int block, int offset, InlineField field) {
      this.block = block;
      this.offset = offset;
      this.field = field;
    }
  }

  static List<Located> fields(Document d) {
    var out = new ArrayList<Located>();
    for (int i = 0; i < d.blocks.size(); i++) {
      if (!(d.blocks.get(i) instanceof Paragraph p) || !p.text.hasFields()) continue;
      final int block = i;
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {}

            public void field(int offset, InlineField f, TextStyle style) {
              if (Zotero.is(f)) out.add(new Located(block, offset, f));
            }
          });
    }
    return out;
  }

  private List<Located> sync() {
    var list = fields(session.document());
    if (list.size() != ids.size()) {
      ids.clear();
      for (int i = 0; i < list.size(); i++) ids.add(nextId++);
    }
    return list;
  }

  private int index(Object idArg) {
    long id = id(idArg);
    sync();
    int k = ids.indexOf(id);
    if (k < 0) throw new IllegalArgumentException("Field " + id + " no longer exists");
    return k;
  }

  private static long id(Object v) {
    if (v instanceof Number n) return n.longValue();
    if (v instanceof String s)
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ex) {
        // falls through
      }
    throw new IllegalArgumentException("Bad field id");
  }

  private static Object arg(List<Object> a, int i) {
    if (i >= a.size()) throw new IllegalArgumentException("Missing argument " + i);
    return a.get(i);
  }

  private static String str(List<Object> a, int i) {
    Object v = arg(a, i);
    if (!(v instanceof String s)) throw new IllegalArgumentException("Argument " + i + " must be text");
    return s;
  }

  private static long num(List<Object> a, int i, long fallback) {
    return i < a.size() && a.get(i) instanceof Number n ? n.longValue() : fallback;
  }

  private static boolean bool(List<Object> a, int i) {
    Object v = i < a.size() ? a.get(i) : null;
    return Boolean.TRUE.equals(v) || "true".equals(v) || "1".equals(v)
        || (v instanceof Number n && n.longValue() != 0);
  }

  private static List<Object> triple(long id, Located f) {
    return Arrays.asList(id, Zotero.code(f.field.instruction), 0);
  }

  private void requireInText(long noteType) {
    if (noteType != 0)
      throw new IllegalArgumentException(
          "Footnote and endnote citation styles are not supported yet; choose an in-text style");
  }

  @Override
  public Object handle(String method, List<Object> a) {
    switch (method) {
      case "Application_getActiveDocument": {
        long theirs = num(a, 0, PROTOCOL_VERSION);
        docId++;
        caretAfter = null;
        return Arrays.asList(Math.max(1, Math.min(PROTOCOL_VERSION, theirs)), docId);
      }
      case "Document_displayAlert":
        return host.alert(str(a, 1), (int) num(a, 2, 0), (int) num(a, 3, 0));
      case "Document_activate":
        host.activate();
        return null;
      case "Document_canInsertField":
        return session.cellSelection() == null
            && session.document().blocks.get(session.selection().block) instanceof Paragraph;
      case "Document_getDocumentData":
        return Zotero.prefs(session.document());
      case "Document_setDocumentData": {
        String data = str(a, 1);
        if (!data.equals(Zotero.prefs(session.document())))
          session.documentProperties(Zotero.prefs(session.document().properties, data));
        return null;
      }
      case "Document_cursorInField": {
        var sel = session.selection();
        var list = sync();
        for (int k = 0; k < list.size(); k++) {
          Located f = list.get(k);
          if (f.block == sel.startBlock()
              && f.offset >= sel.start()
              && f.offset < Math.max(sel.end(), sel.start() + 1)
              && (sel.startBlock() == sel.endBlock()))
            if (!sel.collapsed() || f.offset == sel.start()) return triple(ids.get(k), f);
        }
        return null;
      }
      case "Document_insertField": {
        requireInText(num(a, 2, 0));
        sync();
        session.insertField(new InlineField(Zotero.stored("TEMP"), PLACEHOLDER, false, false));
        var sel = session.selection();
        var list = fields(session.document());
        for (int k = 0; k < list.size(); k++)
          if (list.get(k).block == sel.block && list.get(k).offset == sel.caret - 1) {
            long id = nextId++;
            ids.add(Math.min(k, ids.size()), id);
            caretAfter = id;
            return triple(id, list.get(k));
          }
        throw new IllegalStateException("The new field was not found");
      }
      case "Document_getFields": {
        var list = sync();
        var codes = new ArrayList<Object>();
        var notes = new ArrayList<Object>();
        for (Located f : list) {
          codes.add(Zotero.code(f.field.instruction));
          notes.add(0);
        }
        return Arrays.asList(new ArrayList<Object>(ids), codes, notes);
      }
      case "Document_insertText": {
        session.replace(plain(str(a, 1), true));
        return null;
      }
      case "Document_setBibliographyStyle":
        // Accepted; the bibliography is one field whose entries share the paragraph's style.
        return null;
      case "Document_convert": {
        Object notes = a.size() > 3 ? a.get(3) : null;
        if (notes instanceof List<?> l) for (Object n : l) requireInText(n instanceof Number x ? x.longValue() : 0);
        else requireInText(notes instanceof Number x ? x.longValue() : 0);
        return null;
      }
      case "Field_convert":
        index(arg(a, 1));
        requireInText(num(a, 3, 0));
        return null;
      case "Document_complete": {
        if (caretAfter != null) {
          int k = ids.indexOf(caretAfter);
          var list = sync();
          if (k >= 0 && k < list.size())
            session.select(list.get(k).block, list.get(k).offset + 1, list.get(k).offset + 1);
        }
        caretAfter = null;
        return null;
      }
      case "Field_delete": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        session.select(f.block, f.offset, f.offset + 1);
        session.replace("");
        ids.remove(k);
        return null;
      }
      case "Field_select": {
        Located f = fields(session.document()).get(index(arg(a, 1)));
        session.select(f.block, f.offset, f.offset + 1);
        return null;
      }
      case "Field_removeCode": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        session.select(f.block, f.offset, f.offset + 1);
        session.replace(f.field.result);
        ids.remove(k);
        return null;
      }
      case "Field_getText":
        return fields(session.document()).get(index(arg(a, 1))).field.result;
      case "Field_setText": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        replaceField(f, f.field.result(plain(str(a, 2), bool(a, 3))));
        return null;
      }
      case "Field_setCode": {
        int k = index(arg(a, 1));
        Located f = fields(session.document()).get(k);
        String code = str(a, 2);
        replaceField(
            f, new InlineField(Zotero.stored(code), f.field.result, f.field.locked, f.field.showCode));
        return null;
      }
      default:
        throw new UnsupportedOperationException("This editor doesn't support " + method);
    }
  }

  private void replaceField(Located f, InlineField value) {
    final String pid = ((Paragraph) session.document().blocks.get(f.block)).id;
    session.mapFields((paragraph, at, field) -> pid.equals(paragraph) && at == f.offset ? value : field);
  }

  /** Plain text from Zotero's RTF ("rich") or HTML results; paragraph breaks become newlines. */
  static String plain(String s, boolean rich) {
    if (!rich) return s;
    var b = new StringBuilder();
    boolean html = s.indexOf('<') >= 0 && s.indexOf("{\\rtf") < 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (html) {
        if (c == '<') {
          int end = s.indexOf('>', i);
          if (end < 0) break;
          String tag = s.substring(i + 1, end).toLowerCase();
          if (tag.startsWith("/p") || tag.startsWith("br") || tag.startsWith("/div")) b.append('\n');
          i = end;
        } else if (c == '&') {
          int end = s.indexOf(';', i);
          String e = end > i && end - i < 8 ? s.substring(i + 1, end) : "";
          String r = "amp".equals(e) ? "&" : "lt".equals(e) ? "<" : "gt".equals(e) ? ">"
              : "quot".equals(e) ? "\"" : "nbsp".equals(e) ? "\u00a0" : null;
          if (r == null) b.append(c);
          else {
            b.append(r);
            i = end;
          }
        } else b.append(c);
      } else if (c == '{' || c == '}') {
        // group markers
      } else if (c == '\\') {
        if (i + 1 < s.length() && "\\{}".indexOf(s.charAt(i + 1)) >= 0) b.append(s.charAt(++i));
        else {
          int j = i + 1;
          while (j < s.length() && ((s.charAt(j) >= 'a' && s.charAt(j) <= 'z') || (s.charAt(j) >= 'A' && s.charAt(j) <= 'Z'))) j++;
          String word = s.substring(i + 1, j);
          int k = j;
          if (k < s.length() && (s.charAt(k) == '-' || (s.charAt(k) >= '0' && s.charAt(k) <= '9'))) {
            k++;
            while (k < s.length() && s.charAt(k) >= '0' && s.charAt(k) <= '9') k++;
          }
          if ("uc".equals(word) || word.isEmpty()) {
            // skip
          } else if ("u".equals(word)) {
            try {
              int v = Integer.parseInt(s.substring(j, k));
              b.append((char) (v < 0 ? v + 65536 : v));
              if (k < s.length() && s.charAt(k) == '?') k++;
            } catch (NumberFormatException ignored) {
            }
          } else if ("par".equals(word) || "line".equals(word)) b.append('\n');
          else if ("tab".equals(word)) b.append('\t');
          else if ("rtf".equals(word)) b.setLength(0);
          if (k < s.length() && s.charAt(k) == ' ') k++;
          i = k - 1;
        }
      } else if (c != '\r' && c != '\n') b.append(c);
    }
    String r = b.toString();
    while (r.endsWith("\n")) r = r.substring(0, r.length() - 1);
    return r;
  }
}
