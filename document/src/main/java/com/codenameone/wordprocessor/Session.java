package com.codenameone.wordprocessor;

import java.util.*;

/** Single-thread confined editing session. Immutable roots can be passed to background workers. */
public final class Session {
  public interface Edit {
    void apply(Session session);
  }

  private Document document;
  private Selection selection = new Selection(0, 0, 0);
  private long revision;
  private int transactionDepth;
  private State composition;
  private TextStyle insertionStyle;
  private static final int HISTORY_LIMIT = 200;
  private final ArrayList<State> undo = new ArrayList<State>(), redo = new ArrayList<State>();

  private static final class State {
    final Document doc;
    final Selection sel;
    final TextStyle style;

    State(Document d, Selection s, TextStyle t) {
      style = t;
      doc = d;
      sel = s;
    }
  }

  public Session(Document d) {
    document = d;
    selectFirst();
  }

  public Document document() {
    return document;
  }

  public Selection selection() {
    return selection;
  }

  public long revision() {
    return revision;
  }

  public void select(int block, int a, int c) {
    selectRange(block, a, block, c);
  }

  public void selectRange(int anchorBlock, int anchor, int block, int caret) {
    Paragraph first = document.paragraph(anchorBlock), last = document.paragraph(block);
    first.text.slice(anchor, anchor);
    last.text.slice(caret, caret);
    selection = new Selection(anchorBlock, anchor, block, caret);
    insertionStyle = null;
  }

  public TextStyle activeStyle() {
    return insertionStyle != null
        ? insertionStyle
        : document.paragraph(selection.block).text.styleAt(Math.max(0, selection.caret - 1));
  }

  public String selectedText() {
    Selection x = selection;
    StringBuilder b = new StringBuilder();
    for (int i = x.startBlock(); i <= x.endBlock(); i++) {
      Block block = document.blocks.get(i);
      if (i > x.startBlock()) b.append('\n');
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        int a = i == x.startBlock() ? x.start() : 0,
            end = i == x.endBlock() ? x.end() : p.text.length();
        b.append(p.text.slice(a, end));
      } else if (block instanceof Table) {
        Table t = (Table) block;
        for (int r = 0; r < t.rows(); r++) {
          if (r > 0) b.append('\n');
          for (int c = 0; c < t.columns(); c++) {
            if (c > 0) b.append('\t');
            for (Paragraph p : t.cell(r, c)) b.append(p.text);
          }
        }
      }
    }
    return b.toString();
  }

  private void selectFirst() {
    for (int i = 0; i < document.blocks.size(); i++)
      if (document.blocks.get(i) instanceof Paragraph) {
        selection = new Selection(i, 0, 0);
        return;
      }
    long id = document.nextId;
    document =
        document.blocks(document.blocks.append(new Paragraph("p" + id, new RichText())), id + 1);
    selection = new Selection(document.blocks.size() - 1, 0, 0);
  }

  public Session fork() {
    Session s = new Session(document);
    s.selection = selection;
    s.revision = revision;
    s.insertionStyle = insertionStyle;
    return s;
  }

  public void transact(Edit edit) {
    if (composition != null) throw new IllegalStateException("Finish text composition first");
    if (transactionDepth > 0) {
      edit.apply(this);
      return;
    }
    State before = new State(document, selection, insertionStyle);
    long old = revision;
    transactionDepth++;
    try {
      edit.apply(this);
      if (document != before.doc) {
        undo.add(before);
        if (undo.size() > HISTORY_LIMIT) undo.remove(0);
        redo.clear();
        revision = old + 1;
      }
    } catch (RuntimeException e) {
      document = before.doc;
      selection = before.sel;
      insertionStyle = before.style;
      revision = old;
      throw e;
    } finally {
      transactionDepth--;
    }
  }

  public boolean isComposing() {
    return composition != null;
  }

  public void compose(String text, int relativeCaret) {
    if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Composition cannot span paragraphs");
    if (composition == null) composition = new State(document, selection, insertionStyle);
    Session draft = new Session(composition.doc);
    draft.selection = composition.sel;
    draft.insertionStyle = composition.style;
    draft.replace(text);
    document = draft.document;
    int start = composition.sel.start(),
        caret = Math.max(start, Math.min(start + text.length(), start + relativeCaret));
    Paragraph p = document.paragraph(composition.sel.startBlock());
    if (caret > 0 && caret < p.text.length() && Character.isLowSurrogate(p.text.charAt(caret)))
      caret++;
    selection = new Selection(composition.sel.startBlock(), caret, caret);
  }

  public void finishComposition() {
    if (composition == null) return;
    State before = composition;
    composition = null;
    if (document != before.doc) {
      undo.add(before);
      if (undo.size() > HISTORY_LIMIT) undo.remove(0);
      redo.clear();
      revision++;
    }
  }

  public void cancelComposition() {
    if (composition != null) {
      document = composition.doc;
      selection = composition.sel;
      insertionStyle = composition.style;
      composition = null;
    }
  }

  /** Start a new file without carrying history or typing attributes across documents. */
  public void loadDocument(Document d) {
    if (transactionDepth > 0) throw new IllegalStateException("Load inside transaction");
    if (d == null) throw new NullPointerException("document");
    composition = null;
    insertionStyle = null;
    document = d;
    selectFirst();
    undo.clear();
    redo.clear();
    revision++;
  }

  public void replaceDocument(final Document d) {
    transact(
        new Edit() {
          public void apply(Session s) {
            document = d;
            selectFirst();
          }
        });
  }

  public void replace(final String value) {
    if (value == null) throw new NullPointerException();
    transact(
        new Edit() {
          public void apply(Session s) {
            Selection x = selection;
            int first = x.startBlock();
            Paragraph p = document.paragraph(first), last = document.paragraph(x.endBlock());
            String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n", -1);
            TextStyle style =
                insertionStyle != null
                    ? insertionStyle
                    : p.text.styleAt(Math.max(0, x.start() - 1));
            RichText prefix = p.text.slice(0, x.start()),
                suffix = last.text.slice(x.end(), last.text.length());
            Sequence<Block> b = document.blocks;
            for (int i = x.endBlock(); i > first; i--) b = b.remove(i);
            long next = document.nextId;
            if (lines.length == 1) {
              b = b.set(first, p.text(prefix.concat(RichText.of(lines[0], style)).concat(suffix)));
              selection =
                  new Selection(
                      first, x.start() + lines[0].length(), x.start() + lines[0].length());
            } else {
              b = b.set(first, p.text(prefix.concat(RichText.of(lines[0], style))));
              for (int i = 1; i < lines.length; i++) {
                RichText t = RichText.of(lines[i], style);
                if (i == lines.length - 1) t = t.concat(suffix);
                b = b.insert(first + i, p.copy("p" + (next++), t));
              }
              selection =
                  new Selection(
                      first + lines.length - 1,
                      lines[lines.length - 1].length(),
                      lines[lines.length - 1].length());
            }
            document = document.blocks(b, next);
          }
        });
  }

  public void format(final String key, final String value) {
    if (selection.collapsed()) {
      insertionStyle = activeStyle().with(key, value);
      return;
    }
    transact(
        s -> {
          Selection x = selection;
          Sequence<Block> blocks = document.blocks;
          for (int i = x.startBlock(); i <= x.endBlock(); i++) {
            Block block = blocks.get(i);
            if (block instanceof Paragraph) {
              Paragraph p = (Paragraph) block;
              int a = i == x.startBlock() ? x.start() : 0,
                  b = i == x.endBlock() ? x.end() : p.text.length();
              blocks = blocks.set(i, p.text(p.text.format(a, b, key, value)));
            } else if (block instanceof Table) {
              Table t = (Table) block;
              for (int r = 0; r < t.rows(); r++)
                for (int c = 0; c < t.columns(); c++) {
                  Sequence<Paragraph> cell = t.cell(r, c);
                  for (int n = 0; n < cell.size(); n++) {
                    Paragraph p = cell.get(n);
                    cell = cell.set(n, p.text(p.text.format(0, p.text.length(), key, value)));
                  }
                  t = t.setCell(r, c, cell);
                }
              blocks = blocks.set(i, t);
            }
          }
          document = document.blocks(blocks, document.nextId);
        });
  }

  public void paragraph(final String key, final String value) {
    transact(
        new Edit() {
          public void apply(Session s) {
            Sequence<Block> blocks = document.blocks;
            for (int i = selection.startBlock(); i <= selection.endBlock(); i++) {
              if (blocks.get(i) instanceof Paragraph)
                blocks = blocks.set(i, ((Paragraph) blocks.get(i)).with(key, value));
            }
            document = document.blocks(blocks, document.nextId);
          }
        });
  }

  public void deleteBackward() {
    Selection x = selection;
    if (!x.collapsed()) {
      replace("");
      return;
    }
    if (x.caret > 0) {
      Paragraph p = document.paragraph(x.block);
      int start = x.caret - 1;
      if (start > 0
          && Character.isLowSurrogate(p.text.charAt(start))
          && Character.isHighSurrogate(p.text.charAt(start - 1))) start--;
      select(x.block, start, x.caret);
      replace("");
    } else if (x.block > 0 && document.blocks.get(x.block - 1) instanceof Paragraph) {
      transact(
          new Edit() {
            public void apply(Session s) {
              int i = selection.block;
              Paragraph a = document.paragraph(i - 1), b = document.paragraph(i);
              document =
                  document.blocks(
                      document.blocks.set(i - 1, a.text(a.text.concat(b.text))).remove(i),
                      document.nextId);
              selection = new Selection(i - 1, a.text.length(), a.text.length());
            }
          });
    }
  }

  public void insertTable(final int rows, final int columns) {
    if (rows < 1 || rows > 100 || columns < 1 || columns > 30)
      throw new IllegalArgumentException("Table size outside 1..100 by 1..30");
    transact(
        new Edit() {
          public void apply(Session s) {
            long id = document.nextId;
            String tid = "t" + id++;
            List<List<Sequence<Paragraph>>> cells = new ArrayList<List<Sequence<Paragraph>>>();
            for (int r = 0; r < rows; r++) {
              List<Sequence<Paragraph>> row = new ArrayList<Sequence<Paragraph>>();
              for (int c = 0; c < columns; c++)
                row.add(
                    new Sequence<Paragraph>().append(new Paragraph("p" + id++, new RichText())));
              cells.add(row);
            }
            int i = selection.block + 1;
            Sequence<Block> blocks =
                document
                    .blocks
                    .insert(i, new Table(tid, cells))
                    .insert(i + 1, new Paragraph("p" + id++, new RichText()));
            document = document.blocks(blocks, id);
            selection = new Selection(i + 1, 0, 0);
          }
        });
  }

  public void setCell(final int block, final int row, final int column, final String text) {
    transact(
        new Edit() {
          public void apply(Session s) {
            Table t = (Table) document.blocks.get(block);
            Paragraph p = t.cell(row, column).get(0);
            Table next =
                t.setCell(row, column, new Sequence<Paragraph>().append(p.text(RichText.of(text))));
            document = document.blocks(document.blocks.set(block, next), document.nextId);
          }
        });
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  public void undo() {
    finishComposition();
    if (transactionDepth > 0) throw new IllegalStateException("Undo inside transaction");
    if (undo.isEmpty()) return;
    redo.add(new State(document, selection, insertionStyle));
    State s = undo.remove(undo.size() - 1);
    document = s.doc;
    selection = s.sel;
    insertionStyle = s.style;
    revision++;
  }

  public void redo() {
    finishComposition();
    if (transactionDepth > 0) throw new IllegalStateException("Redo inside transaction");
    if (redo.isEmpty()) return;
    undo.add(new State(document, selection, insertionStyle));
    State s = redo.remove(redo.size() - 1);
    document = s.doc;
    selection = s.sel;
    insertionStyle = s.style;
    revision++;
  }

  public void applyPreview(final Session preview, long expectedRevision) {
    if (revision != expectedRevision)
      throw new IllegalStateException("Document changed since preview");
    transact(
        new Edit() {
          public void apply(Session s) {
            document = preview.document;
            selection = preview.selection;
            insertionStyle = preview.insertionStyle;
          }
        });
  }
}
