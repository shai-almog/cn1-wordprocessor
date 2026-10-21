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
 * <p>As in Wordcraft, citations live in body paragraphs, table cells, footnotes and endnotes;
 * a note's fields are listed at its reference mark with the note's number, and note citation
 * styles insert (Document_insertField) or move (Document_convert, Field_convert) a citation into
 * a new footnote or endnote. Placeholder links (Document_convertPlaceholdersToFields) become
 * fields. Document transfer (import/exportDocument) is refused as Wordcraft refuses it: fields
 * are already stored the way Word keeps them. Document_insertText keeps Zotero's rich
 * formatting as runs and paragraphs; a field is one atomic run, so its result keeps only the
 * formatting shared by the whole result.
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

  private final Session root;
  /** The session edits go through: the root, or a scratch session on a note's paragraphs. */
  private Session session;
  private String noteId;
  private final Host host;
  private long nextId = 1, docId;
  private final List<Long> ids = new ArrayList<>();
  private Long caretAfter;

  public ZoteroBridge(Session session, Host host) {
    this.root = session;
    this.session = session;
    this.host = host;
  }

  /** Makes {@link #session} edit f's story: the body, its table cell, or its note. */
  private void focus(Located f) {
    commit();
    if (f.note != null) {
      Note n = note(f.note);
      var paragraphs = new Sequence<Block>();
      for (Paragraph p : n.paragraphs) paragraphs = paragraphs.append(p);
      Document d = root.document();
      session = new Session(new Document(paragraphs, d.page, d.title, d.nextId).styles(d.styles));
      noteId = f.note;
    } else if (f.table >= 0) root.enterCell(f.table, f.row, f.column);
    else if (root.inCell() || root.noteStory() != null) root.leaveCell();
  }

  /** Writes a focused note's edited paragraphs back (one undo step) and returns to the root. */
  private void commit() {
    if (session == root) return;
    var paragraphs = new Sequence<Paragraph>();
    for (Block b : session.document().blocks) paragraphs = paragraphs.append((Paragraph) b);
    String id = noteId;
    session = root;
    noteId = null;
    root.updateNote(id, note(id).paragraphs(paragraphs));
  }

  private Note note(String id) {
    final Note[] found = {null};
    Notes.map(
        root.document(),
        n -> {
          if (n.id.equals(id)) found[0] = n;
          return n;
        });
    if (found[0] == null) throw new IllegalArgumentException("Unknown note " + id);
    return found[0];
  }

  /** f re-found in the focused story (after edits there), by its start mark. */
  private Located local(Located f) {
    for (Located l : storyFields(session.editingDocument().blocks))
      if (l.block == f.block && l.offset == f.offset) return l.place(f);
    throw new IllegalStateException("Field moved");
  }

  private Located global(int k) {
    Located f = fields(root.document()).get(k);
    focus(f);
    return f;
  }

  /**
   * A Zotero field located in the body: its start mark (block, offset), and the end mark
   * (endBlock, endOffset). The result is the text between them, as Wordcraft's FieldStart and
   * FieldEnd hold it. A legacy atomic field has its end at its own offset; a start whose end
   * mark was deleted ends with its paragraph ({@link #hasEnd} false).
   */
  static final class Located {
    final int block, offset;
    final InlineField field;
    int endBlock, endOffset;
    boolean hasEnd;
    /** The story: a table cell (table block, row, column) or a note (id, number); else the body. */
    int table = -1, row = -1, column = -1, noteIndex;
    String note;

    Located place(Located from) {
      table = from.table;
      row = from.row;
      column = from.column;
      note = from.note;
      noteIndex = from.noteIndex;
      return this;
    }

    Located(int block, int offset, InlineField field) {
      this.block = block;
      this.offset = offset;
      this.field = field;
      endBlock = block;
      endOffset = offset;
    }

    boolean atomic() {
      return !field.rangeStart();
    }

    /** True if (b, o) lies at the start mark or in the text before the end mark. */
    boolean holds(int b, int o) {
      return compare(b, o, block, offset) >= 0 && compare(b, o, endBlock, endOffset) <= 0;
    }
  }

  /**
   * Repairs unbalanced range-field marks so every start has its end, as Wordcraft keeps
   * FieldStart/FieldEnd paired: an end mark with no open start (its start was deleted) is removed,
   * leaving the citation's text as plain text, and a start whose end was deleted gets an end mark
   * at the end of its paragraph (or just before the next start in it). {@code caret}, if not null,
   * is a {block, offset} pair moved with the edits. Returns {@code d} when nothing is unbalanced.
   */
  static Document balance(Document d, int[] caret) {
    return everyStory(balanceStory(d, caret), x -> balanceStory(x, null));
  }

  /**
   * Applies {@code f} to the body's own paragraphs, then to each table cell's and each note's
   * paragraphs as their own stories (Wordcraft keeps a field's marks within one story), threading
   * the paragraph id counter. Returns {@code d} when nothing changed.
   */
  static Document everyStory(Document d, java.util.function.UnaryOperator<Document> f) {
    final long[] next = {d.nextId};
    Sequence<Block> blocks = d.blocks;
    for (int i = 0; i < blocks.size(); i++) {
      if (!(blocks.get(i) instanceof Table t)) continue;
      Table u = t;
      for (int r = 0; r < t.rows(); r++)
        for (int c = 0; c < t.columns(); c++) {
          if (t.covered(r, c)) continue;
          var cell = t.cell(r, c);
          var mapped = story(d, cell, next, f);
          if (mapped != cell) u = u.setCell(r, c, mapped);
        }
      if (u != t) blocks = blocks.set(i, u);
    }
    Document out = blocks == d.blocks ? d : d.blocks(blocks, next[0]);
    out =
        Notes.map(
            out,
            n -> {
              var mapped = story(d, n.paragraphs, next, f);
              return mapped == n.paragraphs ? n : n.paragraphs(mapped);
            });
    return out == d ? d : out.blocks(out.blocks, Math.max(out.nextId, next[0]));
  }

  private static Sequence<Paragraph> story(
      Document d, Sequence<Paragraph> paragraphs, long[] next, java.util.function.UnaryOperator<Document> f) {
    boolean fields = false;
    for (Paragraph p : paragraphs) fields |= p.text.hasFields();
    if (!fields) return paragraphs;
    var blocks = new Sequence<Block>();
    for (Paragraph p : paragraphs) blocks = blocks.append(p);
    var sub = new Document(blocks, d.page, d.title, next[0]).styles(d.styles);
    var r = f.apply(sub);
    if (r == sub) return paragraphs;
    next[0] = Math.max(next[0], r.nextId);
    var out = new Sequence<Paragraph>();
    for (Block b : r.blocks) out = out.append((Paragraph) b);
    return out;
  }

  private static Document balanceStory(Document d, int[] caret) {
    // Each fix: {block, offset, 0 = remove the mark there | 1 = insert an end mark there}.
    final var fixes = new ArrayList<int[]>();
    final var styles = new ArrayList<TextStyle>();
    final Object[] open = {null, null, null}; // block, offset, style of the open start
    for (int i = 0; i < d.blocks.size(); i++) {
      if (!(d.blocks.get(i) instanceof Paragraph p) || !p.text.hasFields()) continue;
      final int block = i;
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {}

            public void field(int offset, InlineField f, TextStyle style) {
              if (f.rangeEnd()) {
                if (open[0] == null) {
                  fixes.add(new int[] {block, offset, 0});
                  styles.add(null);
                }
                open[0] = null;
              } else if (f.rangeStart()) {
                if (open[0] != null) {
                  int b = (Integer) open[0];
                  int at = b == block ? offset : ((Paragraph) d.blocks.get(b)).text.length();
                  fixes.add(new int[] {b, at, 1});
                  styles.add((TextStyle) open[2]);
                }
                open[0] = block;
                open[1] = offset;
                open[2] = style;
              }
            }
          });
    }
    if (open[0] != null) {
      int b = (Integer) open[0];
      fixes.add(new int[] {b, ((Paragraph) d.blocks.get(b)).text.length(), 1});
      styles.add((TextStyle) open[2]);
    }
    if (fixes.isEmpty()) return d;
    Sequence<Block> blocks = d.blocks;
    for (int k = fixes.size() - 1; k >= 0; k--) {
      int[] x = fixes.get(k);
      var p = (Paragraph) blocks.get(x[0]);
      RichText t =
          x[2] == 0
              ? p.text.slice(0, x[1]).concat(p.text.slice(x[1] + 1, p.text.length()))
              : p.text.replace(x[1], x[1], RichText.field(InlineField.end(), styles.get(k)));
      blocks = blocks.set(x[0], p.text(t));
      if (caret != null && caret[0] == x[0]) {
        if (x[2] == 0 && x[1] < caret[1]) caret[1]--;
        else if (x[2] == 1 && x[1] <= caret[1]) caret[1]++;
      }
    }
    return d.blocks(blocks, d.nextId);
  }

  static int compare(int b1, int o1, int b2, int o2) {
    return b1 != b2 ? Integer.compare(b1, b2) : Integer.compare(o1, o2);
  }

  /**
   * Every Zotero field in document order, as Wordcraft lists them: the body's, a table's cells'
   * at the table, and each note's fields at its reference mark. Block indices are relative to
   * the field's story (the body, the cell's paragraphs or the note's paragraphs).
   */
  static List<Located> fields(Document d) {
    var out = new ArrayList<Located>();
    walk(d.blocks, new Located(-1, -1, null), out, new int[2], true);
    return out;
  }

  /** The fields of one story's own paragraphs (no cells or notes), block indices local. */
  static List<Located> storyFields(Sequence<? extends Block> blocks) {
    var out = new ArrayList<Located>();
    walk(blocks, new Located(-1, -1, null), out, null, false);
    return out;
  }

  private static void walk(Sequence<? extends Block> blocks, Located place, List<Located> out, int[] notes, boolean deep) {
    final Located[] open = {null};
    for (int i = 0; i < blocks.size(); i++) {
      Block blk = blocks.get(i);
      if (blk instanceof Table t) {
        if (!deep || place.table >= 0 || place.note != null) continue;
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++) {
            if (t.covered(r, c)) continue;
            var cell = new Located(-1, -1, null);
            cell.table = i;
            cell.row = r;
            cell.column = c;
            var paragraphs = new Sequence<Block>();
            for (Paragraph cp : t.cell(r, c)) paragraphs = paragraphs.append(cp);
            walk(paragraphs, cell, out, notes, true);
          }
        continue;
      }
      if (!(blk instanceof Paragraph p)) continue;
      final var events = new ArrayList<Object[]>();
      final int block = i;
      if (p.text.hasFields())
        p.text.visit(
            new RichText.Visitor() {
              public void run(int offset, String text, TextStyle style) {}

              public void field(int offset, InlineField f, TextStyle style) {
                events.add(new Object[] {offset, f});
              }
            });
      if (deep && place.note == null)
        p.text.mapNotes(
            (offset, note) -> {
              events.add(new Object[] {offset, note});
              return note;
            });
      events.sort((x, y) -> (Integer) x[0] - (Integer) y[0]);
      for (Object[] e : events) {
        int offset = (Integer) e[0];
        if (e[1] instanceof Note n) {
          int k = "footnote".equals(n.kind) ? 0 : 1;
          if (n.mark == null || n.mark.isEmpty()) notes[k]++;
          var np = new Located(-1, -1, null);
          np.note = n.id;
          np.noteIndex = Math.max(1, notes[k]);
          var paragraphs = new Sequence<Block>();
          for (Paragraph q : n.paragraphs) paragraphs = paragraphs.append(q);
          walk(paragraphs, np, out, notes, true);
          continue;
        }
        InlineField f = (InlineField) e[1];
        if (f.rangeEnd() && open[0] != null) {
          open[0].endBlock = block;
          open[0].endOffset = offset;
          open[0].hasEnd = true;
          open[0] = null;
        } else if (Zotero.is(f)) {
          close(blocks, open[0]);
          var l = new Located(block, offset, f).place(place);
          out.add(l);
          open[0] = f.rangeStart() ? l : null;
        }
      }
    }
    close(blocks, open[0]);
  }

  private static void close(Sequence<? extends Block> blocks, Located l) {
    if (l == null) return;
    l.endBlock = l.block;
    l.endOffset = ((Paragraph) blocks.get(l.block)).text.length();
  }

  /** The text between a range field's marks, paragraphs joined by newlines. */
  static String text(Document d, Located f) {
    if (f.atomic()) return f.field.result;
    var b = new StringBuilder();
    for (int i = f.block; i <= f.endBlock; i++) {
      if (!(d.blocks.get(i) instanceof Paragraph p)) continue;
      int from = i == f.block ? f.offset + 1 : 0, to = i == f.endBlock ? f.endOffset : p.text.length();
      if (i > f.block) b.append('\n');
      for (int k = from; k < to; k++) if (!p.text.objectAt(k)) b.append(p.text.charAt(k));
    }
    return b.toString();
  }

  /**
   * Turns atomic Zotero fields in body paragraphs into range fields: a start mark, the result as
   * ordinary runs (keeping its formatted pieces) and an end mark; newlines in a bibliography's
   * result become paragraphs. Returns {@code d} itself when there is nothing to convert.
   */
  public static Document expand(Document d) {
    return everyStory(expandStory(d), ZoteroBridge::expandStory);
  }

  private static Document expandStory(Document d) {
    Sequence<Block> blocks = d.blocks;
    long next = d.nextId;
    boolean changed = false;
    for (int i = blocks.size() - 1; i >= 0; i--) {
      if (!(blocks.get(i) instanceof Paragraph p) || !p.text.hasFields()) continue;
      final var atomic = new ArrayList<Object[]>();
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {}

            public void field(int offset, InlineField f, TextStyle style) {
              if (Zotero.is(f) && !f.rangeStart() && style.revision == null)
                atomic.add(new Object[] {offset, f, style});
            }
          });
      if (atomic.isEmpty()) continue;
      RichText t = p.text;
      for (int k = atomic.size() - 1; k >= 0; k--) {
        int at = (Integer) atomic.get(k)[0];
        var f = (InlineField) atomic.get(k)[1];
        var style = (TextStyle) atomic.get(k)[2];
        RichText r = RichText.field(f.start(), style);
        var pieces = f.piecesOf(f.result);
        if (pieces == null) pieces = InlineField.plainPieces(f.result);
        for (InlineField.Piece piece : pieces)
          if (!piece.text.isEmpty()) r = r.concat(RichText.of(piece.text.replace('\r', ' '), piece.style(style)));
        r = r.concat(RichText.field(InlineField.end(), style));
        t = t.replace(at, at + 1, r);
      }
      // Split at the newlines a bibliography's entries left, one paragraph each.
      var parts = new ArrayList<RichText>();
      int from = 0, length = t.length();
      for (int k = 0; k < length; k++)
        if (t.charAt(k) == '\n' && !t.objectAt(k)) {
          parts.add(t.slice(from, k));
          from = k + 1;
        }
      parts.add(t.slice(from, length));
      blocks = blocks.set(i, p.text(parts.get(0)));
      for (int k = 1; k < parts.size(); k++) blocks = blocks.insert(i + k, p.copy("p" + next++, parts.get(k)));
      changed = true;
    }
    return changed ? d.blocks(blocks, next) : d;
  }

  private List<Located> sync() {
    var list = fields(root.document());
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
    return Arrays.asList(id, Zotero.code(f.field.instruction), f.noteIndex);
  }

  /** The note kind a Zotero note type names (1 footnote, 2 endnote), else null (in the text). */
  private static String noteKind(long noteType) {
    return noteType == 1 ? "footnote" : noteType == 2 ? "endnote" : null;
  }

  /**
   * Inserts a field holding content (the placeholder when null) at the root's selection, in a
   * new footnote or endnote for a note style when the selection is not in a note already, as
   * Wordcraft's insert_field. Returns the new field's id; its index is added to ids.
   */
  private long insertAt(long noteType, String content, String code) {
    sync();
    String marker = "TEMP#" + nextId;
    String kind = noteKind(noteType);
    if (kind != null) {
      var before = new java.util.HashSet<String>();
      for (Notes.Reference r : Notes.references(root.document())) before.add(r.note.id);
      root.insertNote(kind, "", "");
      String id = null;
      for (Notes.Reference r : Notes.references(root.document())) if (!before.contains(r.note.id)) id = r.note.id;
      var np = new Located(0, 0, null);
      np.note = id;
      focus(np);
      session.select(0, 0, 0);
    }
    final Session s = session;
    s.transact(
        x -> {
          s.insertField(new InlineField(Zotero.stored(marker), "", false, false).start());
          s.replace(content == null ? PLACEHOLDER : content);
          s.insertField(InlineField.end());
        });
    commit();
    var list = fields(root.document());
    for (int k = 0; k < list.size(); k++)
      if (marker.equals(Zotero.code(list.get(k).field.instruction))) {
        Located f = list.get(k);
        focus(f);
        Located l = local(f);
        replaceField(l, new InlineField(Zotero.stored(code), l.field.result, l.field.locked, l.field.showCode, l.field.formats));
        commit();
        long id = nextId++;
        ids.add(Math.min(k, ids.size()), id);
        return id;
      }
    throw new IllegalStateException("The new field was not found");
  }

  /**
   * Moves citation k to the text (0), a footnote (1) or an endnote (2), keeping its code and
   * text, as Wordcraft's convert_field: a note left empty is removed and the citation takes its
   * reference mark's place; otherwise it goes right after the mark. Ids keep their order.
   */
  private void convert(int k, long noteType) {
    Located f = fields(root.document()).get(k);
    String current = f.note == null ? null : note(f.note).kind;
    String target = noteKind(noteType);
    if (current == null ? target == null : current.equals(target)) return;
    if (f.note != null && !"footnote".equals(current) && !"endnote".equals(current)) return;
    focus(f);
    Located l = local(f);
    String code = Zotero.code(l.field.instruction), text = text(session.editingDocument(), l);
    session.selectRange(l.block, l.offset, l.endBlock, l.endOffset + (l.hasEnd || l.atomic() ? 1 : 0));
    session.replace("");
    boolean empty = true;
    for (Block b : session.editingDocument().blocks)
      if (b instanceof Paragraph p && p.text.plainText().trim().length() > 0) empty = false;
    commit();
    if (f.note != null) {
      root.selectNote(f.note);
      var sel = root.selection();
      if (empty) root.replace("");
      else root.select(sel.block, sel.end(), sel.end());
    }
    ids.remove(k);
    insertAt(noteType, text, code);
  }

  /** Every placeholder link to Zotero ("https://www.zotero.org/?" + key, optionally "&..."). */
  private Object[] findLink(String url) {
    final Object[] hit = {null};
    for (Located f : allParagraphs()) {
      if (hit[0] != null) break;
      Paragraph p = paragraphOf(f);
      final int[] range = {-1, -1};
      p.text.visit(
          new RichText.Visitor() {
            public void run(int offset, String text, TextStyle style) {
              String l = style.link;
              boolean on = l != null && (l.equals(url) || (l.startsWith(url) && l.length() > url.length() && l.charAt(url.length()) == '&'));
              if (on && (range[0] < 0 || range[1] == offset)) {
                if (range[0] < 0) range[0] = offset;
                range[1] = offset + text.length();
              } else if (range[0] >= 0) range[1] = -range[1] - 1; // ended
            }

            public void field(int offset, InlineField f, TextStyle style) {}
          });
      if (range[0] >= 0) hit[0] = new Object[] {f, range[0], range[1] < 0 ? -range[1] - 1 : range[1]};
    }
    return (Object[]) hit[0];
  }

  /** One Located per paragraph of every story (block = paragraph index in its story). */
  private List<Located> allParagraphs() {
    var out = new ArrayList<Located>();
    var d = root.document();
    for (int i = 0; i < d.blocks.size(); i++) {
      Block b = d.blocks.get(i);
      if (b instanceof Paragraph p) {
        out.add(new Located(i, 0, null));
        notesOf(p, out);
      } else if (b instanceof Table t)
        for (int r = 0; r < t.rows(); r++)
          for (int c = 0; c < t.columns(); c++) {
            if (t.covered(r, c)) continue;
            int k = 0;
            for (Paragraph cp : t.cell(r, c)) {
              var l = new Located(k++, 0, null);
              l.table = i;
              l.row = r;
              l.column = c;
              out.add(l);
              notesOf(cp, out);
            }
          }
    }
    return out;
  }

  private static void notesOf(Paragraph p, List<Located> out) {
    p.text.mapNotes(
        (offset, n) -> {
          for (int k = 0; k < n.paragraphs.size(); k++) {
            var l = new Located(k, 0, null);
            l.note = n.id;
            out.add(l);
          }
          return n;
        });
  }

  private Paragraph paragraphOf(Located f) {
    if (f.note != null) return note(f.note).paragraphs.get(f.block);
    if (f.table >= 0) return ((Table) root.document().blocks.get(f.table)).cell(f.row, f.column).get(f.block);
    return root.document().paragraph(f.block);
  }

  static final String PLACEHOLDER_URL = "https://www.zotero.org/?";

  /** Document_convertPlaceholdersToFields: each placeholder link becomes a field holding its text. */
  private Object convertPlaceholders(List<?> keys, long noteType) {
    var newIds = new ArrayList<Long>();
    for (Object key : keys) {
      String k = key instanceof Number n ? "" + n.longValue() : String.valueOf(key);
      Object[] hit = findLink(PLACEHOLDER_URL + k);
      if (hit == null) throw new IllegalArgumentException("Placeholder " + k + " was not found");
      Located f = (Located) hit[0];
      int a0 = (Integer) hit[1], b0 = (Integer) hit[2];
      focus(f);
      String text = paragraphOf(f).text.plainText().substring(a0, b0);
      session.select(f.block, a0, b0);
      session.replace("");
      if (f.note != null) {
        // Already in a note: the field goes where the link was.
        final Session s = session;
        String marker = "TEMP#" + nextId;
        s.transact(
            x -> {
              s.insertField(new InlineField(Zotero.stored(marker), "", false, false).start());
              s.replace(text);
              s.insertField(InlineField.end());
            });
        commit();
        sync();
        var list = fields(root.document());
        for (int i = 0; i < list.size(); i++)
          if (marker.equals(Zotero.code(list.get(i).field.instruction))) {
            Located g = list.get(i);
            focus(g);
            Located l = local(g);
            replaceField(l, new InlineField(Zotero.stored("TEMP"), l.field.result, false, false, l.field.formats));
            commit();
            newIds.add(ids.get(i));
          }
      } else newIds.add(insertAt(noteType, text, "TEMP"));
    }
    var list = sync();
    var codes = new ArrayList<Object>();
    var notes = new ArrayList<Object>();
    for (Long id : newIds) {
      int k = ids.indexOf(id);
      codes.add(k >= 0 ? Zotero.code(list.get(k).field.instruction) : "");
      notes.add(k >= 0 ? list.get(k).noteIndex : 0);
    }
    return Arrays.asList(new ArrayList<Object>(newIds), codes, notes);
  }

  @Override
  public Object handle(String method, List<Object> a) {
    try {
      return dispatch(method, a);
    } finally {
      commit();
    }
  }

  private Object dispatch(String method, List<Object> a) {
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
        return root.cellSelection() == null
            && root.editingDocument().blocks.get(root.selection().block) instanceof Paragraph;
      case "Document_getDocumentData":
        return Zotero.prefs(session.document());
      case "Document_setDocumentData": {
        String data = str(a, 1);
        if (!data.equals(Zotero.prefs(session.document())))
          session.documentProperties(Zotero.prefs(session.document().properties, data));
        return null;
      }
      case "Document_cursorInField": {
        var sel = root.selection();
        var list = sync();
        for (int k = 0; k < list.size(); k++) {
          Located f = list.get(k);
          // Only fields in the story the caret is in: the body, the current cell or note.
          String story = root.noteStory();
          if (story != null ? !story.equals(f.note)
              : f.note != null || f.table != root.cellBlock() || f.row != root.cellRow() || f.column != root.cellColumn()) continue;
          // The caret at the start mark or anywhere in the field's text, or a selection that
          // overlaps the field, as Wordcraft's cursor_in_field.
          boolean in = sel.collapsed()
              ? f.holds(sel.startBlock(), sel.start())
              : compare(sel.startBlock(), sel.start(), f.endBlock, f.endOffset) <= 0
                  && compare(sel.endBlock(), sel.end(), f.block, f.offset) > 0;
          if (in) return triple(ids.get(k), f);
        }
        return null;
      }
      case "Document_insertField": {
        long id = insertAt(num(a, 2, 0), null, "TEMP");
        caretAfter = id;
        var list = sync();
        return triple(id, list.get(ids.indexOf(id)));
      }
      case "Document_getFields": {
        var list = sync();
        var codes = new ArrayList<Object>();
        var notes = new ArrayList<Object>();
        for (Located f : list) {
          codes.add(Zotero.code(f.field.instruction));
          notes.add(f.noteIndex);
        }
        return Arrays.asList(new ArrayList<Object>(ids), codes, notes);
      }
      case "Document_insertText": {
        insertRich(runs(str(a, 1), true));
        return null;
      }
      case "Document_setBibliographyStyle":
        setBibliographyStyle(a);
        return null;
      case "Document_convert": {
        if (!(arg(a, 1) instanceof List<?> l)) throw new IllegalArgumentException("Field ids must be a list");
        Object notes = a.size() > 3 ? a.get(3) : null;
        var targets = new ArrayList<Long>();
        for (Object o : l) targets.add(id(o));
        for (int i = 0; i < targets.size(); i++) {
          long nt = notes instanceof List<?> nl ? (i < nl.size() && nl.get(i) instanceof Number x ? x.longValue() : 0)
              : notes instanceof Number x ? x.longValue() : 0;
          convertKeepingId(targets.get(i), nt);
        }
        return null;
      }
      case "Field_convert":
        convertKeepingId(id(arg(a, 1)), num(a, 3, 0));
        return null;
      case "Document_convertPlaceholdersToFields": {
        if (!(arg(a, 1) instanceof List<?> l)) throw new IllegalArgumentException("Placeholder ids must be a list");
        return convertPlaceholders(l, num(a, 2, 0));
      }
      case "Document_importDocument":
      case "Document_exportDocument":
        throw new UnsupportedOperationException(
            "This editor doesn't support Zotero's document transfer; the citations are already stored the way Word keeps them");
      case "Document_complete": {
        if (caretAfter != null) {
          int k = ids.indexOf(caretAfter);
          var list = sync();
          if (k >= 0 && k < list.size()) {
            Located f = list.get(k);
            if (f.note != null) {
              root.selectNote(f.note);
              var sel = root.selection();
              root.select(sel.block, sel.end(), sel.end());
            } else {
              focus(f);
              root.select(f.endBlock, f.endOffset + 1, f.endOffset + 1);
            }
          }
        }
        caretAfter = null;
        return null;
      }
      case "Field_delete": {
        int k = index(arg(a, 1));
        Located f = local(global(k));
        session.selectRange(f.block, f.offset, f.endBlock, f.endOffset + (f.hasEnd || f.atomic() ? 1 : 0));
        session.replace("");
        ids.remove(k);
        return null;
      }
      case "Field_select": {
        Located f = global(index(arg(a, 1)));
        if (f.note != null) {
          // As Wordcraft's select_field: the selection moves into the note's story, over the
          // citation's marks and text.
          commit();
          root.enterNote(f.note);
          root.selectRange(f.block, f.offset, f.endBlock, f.endOffset + (f.hasEnd || f.atomic() ? 1 : 0));
          return null;
        }
        session.selectRange(f.block, f.offset, f.endBlock, f.endOffset + (f.hasEnd || f.atomic() ? 1 : 0));
        return null;
      }
      case "Field_removeCode": {
        int k = index(arg(a, 1));
        Located f = local(global(k));
        session.transact(
            s -> {
              if (f.hasEnd) {
                session.select(f.endBlock, f.endOffset, f.endOffset + 1);
                session.replace("");
              }
              session.select(f.block, f.offset, f.offset + 1);
              session.replace(f.atomic() ? f.field.result : "");
            });
        ids.remove(k);
        return null;
      }
      case "Field_getText": {
        Located f = local(global(index(arg(a, 1)))); // focuses f's story first
        return text(session.editingDocument(), f);
      }
      case "Field_setText": {
        final int k = index(arg(a, 1));
        final Located at = global(k);
        final var rich = runs(str(a, 2), bool(a, 3));
        // As Wordcraft's set_text: the text between the marks is replaced by the formatted
        // runs, newlines starting paragraphs; one undo step.
        session.transact(
            s -> {
              Located f = local(at);
              if (f.atomic()) {
                replaceField(f, f.field.start());
                session.select(f.block, f.offset + 1, f.offset + 1);
                session.insertField(InlineField.end());
                f = local(at);
              }
              session.selectRange(f.block, f.offset + 1, f.endBlock, f.endOffset);
              insertRich(rich);
              if (Zotero.code(f.field.instruction).regionMatches(true, 0, "BIBL", 0, 4)) {
                // The bibliography's paragraphs take the Bibliography style.
                ensureBibliographyStyle();
                var end = session.selection();
                session.selectRange(f.block, f.offset, end.block, end.caret);
                session.applyStyle(BIB_STYLE);
              }
              Located g = local(at);
              session.select(g.endBlock, g.endOffset + (g.hasEnd ? 1 : 0), g.endOffset + (g.hasEnd ? 1 : 0));
            });
        return null;
      }
      case "Field_setCode": {
        int k = index(arg(a, 1));
        Located f = local(global(k));
        String code = str(a, 2);
        replaceField(
            f, new InlineField(Zotero.stored(code), f.field.result, f.field.locked, f.field.showCode, f.field.formats));
        return null;
      }
      default:
        throw new UnsupportedOperationException("This editor doesn't support " + method);
    }
  }

  static final String BIB_STYLE = "Bibliography";

  /** Converts the field with this id, which keeps its id. */
  private void convertKeepingId(long id, long noteType) {
    int k = index(id);
    int size = ids.size();
    convert(k, noteType);
    // convert re-added the field under a new id at its new index; give it back the old one.
    int at = ids.size() == size ? ids.indexOf(nextId - 1) : -1;
    if (at >= 0) ids.set(at, id);
  }

  private void ensureBibliographyStyle() {
    if (!root.document().styles.containsKey(BIB_STYLE)) {
      ParagraphStyle normal = root.document().styles.get("Normal");
      if (normal == null) normal = ParagraphStyle.defaults().get("Normal");
      root.defineStyle(new ParagraphStyle(BIB_STYLE, BIB_STYLE, normal.paragraph, normal.text));
    }
    // A note's story was copied with the styles of the moment it was focused.
    if (session != root && !session.document().styles.containsKey(BIB_STYLE))
      session.defineStyle(root.document().styles.get(BIB_STYLE));
  }

  private static float twips(long v) {
    return Math.max(-31680, Math.min(31680, v)) / 20f;
  }

  /**
   * Zotero's bibliography paragraph format (first-line indent, body indent, line spacing and entry
   * spacing in twips/240ths, then tab stops in twips) applied to the Bibliography style, as
   * Wordcraft's set_bibliography_style does: no space before, entry spacing after.
   */
  private void setBibliographyStyle(List<Object> a) {
    long first = num(a, 1, 0), body = num(a, 2, 0), line = num(a, 3, 240), entry = num(a, 4, 0);
    var stops = new ArrayList<TabStop>();
    if (a.size() > 5 && a.get(5) instanceof List<?> l)
      for (Object o : l) {
        if (stops.size() >= 64) break;
        if (o instanceof Number n) stops.add(new TabStop(Math.max(0, twips(n.longValue())), "left", "none"));
      }
    ensureBibliographyStyle();
    ParagraphStyle old = root.document().styles.get(BIB_STYLE);
    float spacing = line > 0 ? Math.max(.5f, Math.min(5f, line / 240f)) : 1f;
    Paragraph p = old.paragraph.with("indent", "" + twips(body)).with("firstIndent", "" + twips(first))
        .with("spacing", "" + spacing).with("lineRule", "auto").with("before", "0")
        .with("after", "" + Math.max(0, twips(entry))).with("tabs", TabStop.encode(stops));
    root.defineStyle(new ParagraphStyle(BIB_STYLE, old.name, p, old.text));
  }

  private void replaceField(Located f, InlineField value) {
    final String pid = ((Paragraph) session.editingDocument().blocks.get(f.block)).id;
    session.mapFields((paragraph, at, field) -> pid.equals(paragraph) && at == f.offset ? value : field);
  }

  /** One formatted piece of a Zotero rich result; newlines inside separate paragraphs. */
  static final class Run {
    final StringBuilder text = new StringBuilder();
    boolean bold, italic, underline, smallCaps;
    String script = "baseline";

    Run copy() {
      var r = new Run();
      r.bold = bold;
      r.italic = italic;
      r.underline = underline;
      r.smallCaps = smallCaps;
      r.script = script;
      return r;
    }

    boolean sameFormat(Run o) {
      return bold == o.bold && italic == o.italic && underline == o.underline
          && smallCaps == o.smallCaps && script.equals(o.script);
    }

    String[][] format() {
      return new String[][] {
        {"bold", "" + bold}, {"italic", "" + italic}, {"underline", "" + underline},
        {"smallCaps", "" + smallCaps}, {"script", script}
      };
    }
  }

  /** Plain text from Zotero's RTF ("rich") or HTML results; paragraph breaks become newlines. */
  static String plain(String s, boolean rich) {
    if (!rich) return s;
    var b = new StringBuilder();
    for (Run r : runs(s, true)) b.append(r.text);
    return b.toString();
  }

  /**
   * Formatted runs from Zotero's RTF or HTML results: bold, italic, underline, superscript,
   * subscript and small caps are kept; trailing paragraph breaks are dropped.
   */
  static List<Run> runs(String s, boolean rich) {
    var out = new ArrayList<Run>();
    Run cur = new Run();
    if (!rich) {
      cur.text.append(s);
      out.add(cur);
      return out;
    }
    boolean html = s.indexOf('<') >= 0 && s.indexOf("{\\rtf") < 0;
    var stack = new ArrayList<Run>();
    var tags = new ArrayList<String>();
    out.add(cur);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      Run next = null;
      if (html) {
        if (c == '<') {
          int end = s.indexOf('>', i);
          if (end < 0) break;
          String tag = s.substring(i + 1, end).trim();
          String lower = tag.toLowerCase();
          i = end;
          if (lower.startsWith("/")) {
            String name = tagName(lower.substring(1));
            if ("p".equals(name) || "div".equals(name)) append(out, cur, '\n');
            int k = tags.lastIndexOf(name);
            if (k >= 0) {
              next = stack.get(k).copy();
              while (tags.size() > k) {
                tags.remove(tags.size() - 1);
                stack.remove(stack.size() - 1);
              }
            }
          } else {
            String name = tagName(lower);
            if ("br".equals(name)) {
              append(out, cur, '\n');
              continue;
            }
            if (lower.endsWith("/")) continue;
            tags.add(name);
            stack.add(cur.copy());
            next = cur.copy();
            if ("i".equals(name) || "em".equals(name) || "cite".equals(name)) next.italic = true;
            else if ("b".equals(name) || "strong".equals(name)) next.bold = true;
            else if ("u".equals(name)) next.underline = true;
            else if ("sup".equals(name)) next.script = "superscript";
            else if ("sub".equals(name)) next.script = "subscript";
            String style = lower.replace(" ", "");
            if (style.indexOf("font-style:italic") >= 0) next.italic = true;
            if (style.indexOf("font-style:normal") >= 0) next.italic = false;
            if (style.indexOf("font-weight:bold") >= 0) next.bold = true;
            if (style.indexOf("font-weight:normal") >= 0) next.bold = false;
            if (style.indexOf("font-variant:small-caps") >= 0) next.smallCaps = true;
            if (style.indexOf("font-variant:normal") >= 0) next.smallCaps = false;
            if (style.indexOf("text-decoration:underline") >= 0) next.underline = true;
            if (style.indexOf("vertical-align:super") >= 0) next.script = "superscript";
            if (style.indexOf("vertical-align:sub") >= 0) next.script = "subscript";
          }
        } else if (c == '&') {
          int end = s.indexOf(';', i);
          String e = end > i && end - i < 8 ? s.substring(i + 1, end) : "";
          String r = "amp".equals(e) ? "&" : "lt".equals(e) ? "<" : "gt".equals(e) ? ">"
              : "quot".equals(e) ? "\"" : "nbsp".equals(e) ? "\u00a0" : null;
          if (r == null) append(out, cur, c);
          else {
            append(out, cur, r.charAt(0));
            i = end;
          }
        } else if (c != '\r' && c != '\n') append(out, cur, c);
      } else if (c == '{') {
        stack.add(cur.copy());
      } else if (c == '}') {
        if (!stack.isEmpty()) next = stack.remove(stack.size() - 1).copy();
      } else if (c == '\\') {
        if (i + 1 < s.length() && "\\{}".indexOf(s.charAt(i + 1)) >= 0) append(out, cur, s.charAt(++i));
        else {
          int j = i + 1;
          while (j < s.length() && ((s.charAt(j) >= 'a' && s.charAt(j) <= 'z') || (s.charAt(j) >= 'A' && s.charAt(j) <= 'Z'))) j++;
          String word = s.substring(i + 1, j);
          int k = j;
          if (k < s.length() && (s.charAt(k) == '-' || (s.charAt(k) >= '0' && s.charAt(k) <= '9'))) {
            k++;
            while (k < s.length() && s.charAt(k) >= '0' && s.charAt(k) <= '9') k++;
          }
          boolean on = !"0".equals(s.substring(j, k));
          if ("uc".equals(word) || word.isEmpty()) {
            // skip
          } else if ("u".equals(word)) {
            try {
              int v = Integer.parseInt(s.substring(j, k));
              append(out, cur, (char) (v < 0 ? v + 65536 : v));
              if (k < s.length() && s.charAt(k) == '?') k++;
            } catch (NumberFormatException ignored) {
            }
          } else if ("par".equals(word) || "line".equals(word)) append(out, cur, '\n');
          else if ("tab".equals(word)) append(out, cur, '\t');
          else if ("rtf".equals(word)) {
            out.clear();
            cur = new Run();
            out.add(cur);
          } else if ("i".equals(word)) (next = cur.copy()).italic = on;
          else if ("b".equals(word)) (next = cur.copy()).bold = on;
          else if ("ul".equals(word)) (next = cur.copy()).underline = on;
          else if ("ulnone".equals(word)) (next = cur.copy()).underline = false;
          else if ("scaps".equals(word)) (next = cur.copy()).smallCaps = on;
          else if ("super".equals(word)) (next = cur.copy()).script = "superscript";
          else if ("sub".equals(word)) (next = cur.copy()).script = "subscript";
          else if ("nosupersub".equals(word)) (next = cur.copy()).script = "baseline";
          else if ("plain".equals(word)) next = new Run();
          if (k < s.length() && s.charAt(k) == ' ') k++;
          i = k - 1;
        }
      } else if (c != '\r' && c != '\n') append(out, cur, c);
      if (next != null && !next.sameFormat(cur)) {
        if (cur.text.length() == 0) out.remove(out.size() - 1);
        cur = next;
        out.add(cur);
      }
    }
    // Drop trailing paragraph breaks and empty runs.
    for (int k = out.size() - 1; k >= 0; k--) {
      var t = out.get(k).text;
      while (t.length() > 0 && t.charAt(t.length() - 1) == '\n') t.setLength(t.length() - 1);
      if (t.length() > 0) break;
      if (out.size() > 1) out.remove(k);
    }
    return out;
  }

  private static String tagName(String tag) {
    int e = 0;
    while (e < tag.length() && tag.charAt(e) > ' ' && tag.charAt(e) != '/') e++;
    return tag.substring(0, e);
  }

  private static void append(List<Run> out, Run cur, char c) {
    cur.text.append(c);
  }

  /**
   * Replaces the selection with formatted runs as one undo step; each newline starts a new
   * paragraph (bibliography entries), the caret ends after the text.
   */
  private void insertRich(final List<Run> runs) {
    session.transact(
        s -> {
          var all = new StringBuilder();
          for (Run r : runs) all.append(r.text);
          var x = session.selection();
          int block = x.startBlock(), at = x.start();
          session.replace(all.toString());
          var end = session.selection();
          for (Run r : runs) {
            String t = r.text.toString();
            int i = 0;
            while (i < t.length()) {
              int nl = t.indexOf('\n', i);
              int stop = nl < 0 ? t.length() : nl;
              if (stop > i) {
                session.select(block, at, at + (stop - i));
                for (String[] kv : r.format()) session.format(kv[0], kv[1]);
              }
              at += stop - i;
              if (nl < 0) break;
              block++;
              at = 0;
              i = nl + 1;
            }
          }
          session.select(end.block, end.caret, end.caret);
        });
  }

  /** The {@link InlineField#formats} encoding of rich runs ("" when none is formatted). */
  static String formats(List<Run> runs) {
    var b = new StringBuilder();
    boolean any = false;
    for (Run r : runs) {
      if (r.text.length() == 0) continue;
      int flags = (r.bold ? InlineField.BOLD : 0) | (r.italic ? InlineField.ITALIC : 0)
          | (r.underline ? InlineField.UNDERLINE : 0) | (r.smallCaps ? InlineField.SMALL_CAPS : 0)
          | ("superscript".equals(r.script) ? InlineField.SUPER : "subscript".equals(r.script) ? InlineField.SUB : 0);
      any |= flags != 0;
      if (b.length() > 0) b.append(';');
      b.append(r.text.length()).append(':').append(flags);
    }
    return any ? b.toString() : "";
  }

  /** Formatting shared by every visible character of a result, for an atomic field's run. */
  static List<String[]> uniform(List<Run> runs) {
    var out = new ArrayList<String[]>();
    String[][] first = null;
    boolean[] same = null;
    for (Run r : runs) {
      if (r.text.toString().trim().isEmpty()) continue;
      String[][] f = r.format();
      if (first == null) {
        first = f;
        same = new boolean[f.length];
        Arrays.fill(same, true);
      } else for (int k = 0; k < f.length; k++) same[k] &= f[k][1].equals(first[k][1]);
    }
    if (first != null) for (int k = 0; k < first.length; k++) if (same[k]) out.add(first[k]);
    return out;
  }
}
