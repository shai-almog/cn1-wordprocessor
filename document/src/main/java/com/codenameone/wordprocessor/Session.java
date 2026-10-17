package com.codenameone.wordprocessor;

import java.util.*;

/** Single-thread confined editing session. Immutable roots can be passed to background workers. */
public final class Session {
  public interface Edit {
    void apply(Session session);
  }

  private Document document;
  private String reviewAuthor = "Author";
  private boolean trackFormatting = true;
  private CellContext cell;
  private CellSelection cells;
  private Document integratedSource, integratedDocument;

  private static final class CellContext {
    final Document root, initial;
    final Selection outerSelection;
    final int block, row, column;

    CellContext(
        Document root, Document initial, Selection selection, int block, int row, int column) {
      this.root = root;
      this.initial = initial;
      outerSelection = selection;
      this.block = block;
      this.row = row;
      this.column = column;
    }
  }

  /** The document exposed to text commands; a cell's paragraphs while editing a table. */
  public Document editingDocument() {
    return document;
  }

  public boolean inCell() {
    return cell != null;
  }

  public int cellBlock() {
    return cell == null ? -1 : cell.block;
  }

  public int cellRow() {
    return cell == null ? -1 : cell.row;
  }

  public int cellColumn() {
    return cell == null ? -1 : cell.column;
  }

  public void enterCell(int block, int row, int column) {
    if (transactionDepth == 0) breakTypingGroup();
    finishComposition();
    cells = null;
    if (cell != null && cell.block == block && cell.row == row && cell.column == column) return;
    leaveCell();
    Table table = (Table) document.blocks.get(block);
    Table.Merge owner = table.span(row, column);
    row = owner.row;
    column = owner.column;
    Sequence<Block> paragraphs = new Sequence<Block>();
    for (Paragraph p : table.cell(row, column)) paragraphs = paragraphs.append(p);
    Document view =
        new Document(paragraphs, document.page, document.title, document.nextId)
            .styles(document.styles)
            .trackChanges(document.trackChanges);
    List<ReviewComment> comments = new ArrayList<ReviewComment>();
    for (ReviewComment c : document.comments)
      if (ReviewComment.block(view, c.startId) >= 0 && ReviewComment.block(view, c.endId) >= 0)
        comments.add(c);
    view = view.comments(comments);
    cell = new CellContext(document, view, selection, block, row, column);
    document = view;
    selection = new Selection(0, 0, 0);
    insertionStyle = null;
    integratedSource = null;
  }

  public void leaveCell() {
    cells = null;
    if (cell == null) return;
    if (transactionDepth == 0) breakTypingGroup();
    finishComposition();
    Document root = document();
    Selection outer = cell.outerSelection;
    cell = null;
    document = root;
    selection = outer;
    insertionStyle = null;
    integratedSource = null;
  }

  /** Tab traverses owning cells; tabbing beyond the final cell adds a row. */
  public void nextCell(boolean backwards) {
    if (cell == null) return;
    int block = cell.block, row = cell.row, column = cell.column;
    Table table = (Table) document().blocks.get(block);
    int index = row * table.columns() + column;
    for (; ; ) {
      index += backwards ? -1 : 1;
      if (index < 0) return;
      if (index >= table.rows() * table.columns()) {
        tableStructure(block, "insertRow", table.rows());
        enterCell(block, table.rows(), 0);
        return;
      }
      row = index / table.columns();
      column = index % table.columns();
      if (!table.covered(row, column)) {
        enterCell(block, row, column);
        return;
      }
    }
  }

  public CellSelection cellSelection() {
    return cells;
  }

  /** Select whole cells without changing document content or creating undo history. */
  public void selectCells(int block, int anchorRow, int anchorColumn, int row, int column) {
    Document root = document();
    if (block < 0 || block >= root.blocks.size() || !(root.blocks.get(block) instanceof Table))
      throw new IllegalArgumentException("Select a table");
    CellSelection next =
        new CellSelection(
            block, (Table) root.blocks.get(block), anchorRow, anchorColumn, row, column);
    enterCell(block, row, column);
    selectWholeCell();
    cells = next;
  }

  private void selectWholeCell() {
    int last = document.blocks.size() - 1;
    selectRange(0, 0, last, document.paragraph(last).text.length());
  }

  private void editCells(final Edit edit, final boolean keepSelection) {
    final CellSelection range = cells;
    if (range == null) throw new IllegalStateException("Select cells first");
    transact(
        s -> {
          Table table = (Table) document().blocks.get(range.block);
          for (int r = range.firstRow; r <= range.lastRow; r++)
            for (int c = range.firstColumn; c <= range.lastColumn; c++) {
              if (table.covered(r, c)) continue;
              enterCell(range.block, r, c);
              selectWholeCell();
              edit.apply(s);
            }
          if (keepSelection)
            selectCells(
                range.block,
                range.anchorRow,
                range.anchorColumn,
                range.focusRow,
                range.focusColumn);
          else {
            enterCell(range.block, range.firstRow, range.firstColumn);
            select(0, 0, 0);
          }
        });
  }

  /** One undoable replacement: clear picked cells, then type into the first cell. */
  private void replaceCells(final String value) {
    transact(
        s -> {
          editCells(x -> x.replace(""), false);
          if (!value.isEmpty()) replace(value);
        });
  }

  private State state() {
    return new State(document, selection, insertionStyle, cell, mergeData, cells);
  }

  private void restore(State value) {
    document = value.doc;
    mergeData = value.merge;
    selection = value.sel;
    insertionStyle = value.style;
    cell = value.cell;
    cells = value.cells;
    integratedSource = null;
  }

  private MergeData mergeData = MergeData.EMPTY;

  public MergeData mergeData() {
    return mergeData;
  }

  public void mergeData(final MergeData value) {
    if (value == null) throw new NullPointerException("merge data");
    transact(s -> mergeData = value);
  }

  private Selection selection = new Selection(0, 0, 0);
  private long revision;
  private int transactionDepth;
  private State composition;
  private TextStyle insertionStyle;
  private static final int HISTORY_LIMIT = 200;
  private Document typedDocument;
  private Selection typedSelection;
  private CellContext typedCell;
  private State typingUndo;
  private long typedAt;
  private int typedLength;
  private boolean typedSpace, typingEdit, mergingTyping;

  /** End a typing undo group at an explicit command, focus or selection boundary. */
  public void breakTypingGroup() {
    typedDocument = null;
    typedSelection = null;
    typedCell = null;
    typingUndo = null;
    typedLength = 0;
  }

  /**
   * Commit native typing, grouping adjacent single-code-point commits into one undo step.
   * Whitespace ends the preceding word; a pause, command, paste, composition or 256 UTF-16 units
   * starts another group. Every keystroke still advances the recovery revision.
   */
  public void typeText(String text, long timestamp) {
    if (transactionDepth > 0) {
      replace(text);
      return;
    }
    boolean single =
        text.length() == 1
            || text.length() == 2
                && Character.isHighSurrogate(text.charAt(0))
                && Character.isLowSurrogate(text.charAt(1));
    boolean eligible =
        single && text.charAt(0) != '\n' && text.charAt(0) != '\r' && text.charAt(0) != '\t';
    boolean space = eligible && TextNavigation.isWhitespace(Character.codePointAt(text, 0));
    boolean join =
        eligible
            && typedDocument == document
            && typedCell == cell
            && typedSelection != null
            && sameSelection(typedSelection, selection)
            && selection.collapsed()
            && timestamp >= typedAt
            && timestamp - typedAt <= 1500
            && typedLength + text.length() <= 256
            && (!typedSpace || space)
            && !undo.isEmpty()
            && undo.get(undo.size() - 1) == typingUndo;
    State group = join ? typingUndo : null;
    int length = join ? typedLength : 0;
    breakTypingGroup();
    typingEdit = true;
    mergingTyping = join;
    try {
      replace(text);
      if (eligible && !undo.isEmpty()) {
        typedDocument = document;
        typedSelection = selection;
        typedCell = cell;
        typingUndo = join ? group : undo.get(undo.size() - 1);
        typedAt = timestamp;
        typedLength = length + text.length();
        typedSpace = space;
      }
    } finally {
      typingEdit = mergingTyping = false;
    }
  }

  private static boolean sameSelection(Selection a, Selection b) {
    return a.anchorBlock == b.anchorBlock
        && a.anchor == b.anchor
        && a.block == b.block
        && a.caret == b.caret;
  }

  private final ArrayList<State> undo = new ArrayList<State>(), redo = new ArrayList<State>();

  private static final class State {
    final Document doc;
    final Selection sel;
    final TextStyle style;
    final CellContext cell;

    final MergeData merge;
    final CellSelection cells;

    State(
        Document d,
        Selection s,
        TextStyle t,
        CellContext cell,
        MergeData merge,
        CellSelection cells) {
      this.cells = cells;
      this.merge = merge;
      this.cell = cell;
      style = t;
      doc = d;
      sel = s;
    }
  }

  public Session(Document d) {
    document = d;
    mergeData = MergeData.EMPTY;
    selectFirst();
  }

  public Document document() {
    if (cell == null) return document;
    if (document == cell.initial) return cell.root;
    if (integratedSource != document) {
      Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
      for (Block block : document.blocks) {
        if (!(block instanceof Paragraph))
          throw new IllegalStateException("Nested tables are not supported");
        paragraphs = paragraphs.append((Paragraph) block);
      }
      Table table = (Table) cell.root.blocks.get(cell.block);
      integratedDocument =
          cell.root.blocks(
              cell.root.blocks.set(cell.block, table.setCell(cell.row, cell.column, paragraphs)),
              document.nextId);
      if (document.comments != cell.initial.comments) {
        Set<String> prior = new HashSet<String>();
        for (ReviewComment c : cell.initial.comments) prior.add(c.id);
        List<ReviewComment> comments = new ArrayList<ReviewComment>();
        for (ReviewComment c : cell.root.comments) if (!prior.contains(c.id)) comments.add(c);
        comments.addAll(document.comments);
        integratedDocument = integratedDocument.comments(comments);
      }
      integratedSource = document;
    }
    return integratedDocument;
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
    cells = null;
    Selection next = new Selection(anchorBlock, anchor, block, caret);
    if (!sameSelection(selection, next)) {
      breakTypingGroup();
      selection = next;
      insertionStyle = null;
    }
  }

  public TextStyle activeStyle() {
    return insertionStyle != null
        ? insertionStyle
        : typingStyle(document.paragraph(selection.block), selection.caret);
  }

  private static TextStyle typingStyle(Paragraph paragraph, int offset) {
    int before = Math.max(0, offset - 1);
    TextStyle style = paragraph.text.styleAt(before);
    return paragraph.text.noteAt(before) == null ? style : style.with("script", "baseline");
  }

  public String selectedText() {
    if (cells != null) {
      Table table = (Table) document().blocks.get(cells.block);
      StringBuilder text = new StringBuilder();
      for (int r = cells.firstRow; r <= cells.lastRow; r++) {
        if (r > cells.firstRow) text.append('\n');
        for (int c = cells.firstColumn; c <= cells.lastColumn; c++) {
          if (c > cells.firstColumn) text.append('\t');
          if (table.covered(r, c)) continue;
          int n = 0;
          for (Paragraph paragraph : table.cell(r, c)) {
            if (n++ > 0) text.append('\n');
            text.append(paragraph.text.plainText());
          }
        }
      }
      return text.toString();
    }
    Selection x = selection;
    StringBuilder b = new StringBuilder();
    for (int i = x.startBlock(); i <= x.endBlock(); i++) {
      Block block = document.blocks.get(i);
      if (i > x.startBlock()) b.append('\n');
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        int a = i == x.startBlock() ? x.start() : 0,
            end = i == x.endBlock() ? x.end() : p.text.length();
        b.append(p.text.slice(a, end).plainText());
      } else if (block instanceof Table) {
        Table t = (Table) block;
        for (int r = 0; r < t.rows(); r++) {
          if (r > 0) b.append('\n');
          for (int c = 0; c < t.columns(); c++) {
            if (c > 0) b.append('\t');
            for (Paragraph p : t.cell(r, c)) b.append(p.text.plainText());
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
    s.cell = cell;
    s.cells = cells;
    s.mergeData = mergeData;
    s.reviewAuthor = reviewAuthor;
    s.trackFormatting = trackFormatting;
    return s;
  }

  public void transact(Edit edit) {
    if (!typingEdit) breakTypingGroup();
    if (composition != null) throw new IllegalStateException("Finish text composition first");
    if (transactionDepth > 0) {
      Document before = document;
      edit.apply(this);
      reanchorComments(before);
      return;
    }
    State before = state();
    long old = revision;
    transactionDepth++;
    try {
      edit.apply(this);
      int noteCell = cellBlock(), noteRow = cellRow(), noteColumn = cellColumn();
      CellSelection noteCells = cells;
      Selection noteSelection = selection;
      TextStyle noteStyle = insertionStyle;
      if (noteCell >= 0) leaveCell();
      document = Notes.normalize(document);
      if (noteCell >= 0) enterCell(noteCell, noteRow, noteColumn);
      selection = noteSelection;
      cells = noteCells;
      insertionStyle = noteStyle;
      reanchorComments(before.doc);
      enforceProtection(before.doc);
      if (document != before.doc || mergeData != before.merge) {
        if (!mergingTyping) {
          undo.add(before);
          if (undo.size() > HISTORY_LIMIT) undo.remove(0);
        }
        redo.clear();
        revision = old + 1;
      }
    } catch (RuntimeException e) {
      restore(before);
      revision = old;
      throw e;
    } finally {
      transactionDepth--;
    }
  }

  /**
   * Rejects edits a protected document does not allow. Changing the protection mode itself is
   * always allowed (there is no password support), so it is the one way out.
   */
  private void enforceProtection(Document b) {
    String mode = b.properties.protection;
    if ("none".equals(mode) || !mode.equals(document.properties.protection)) return;
    if ("trackedChanges".equals(mode)) {
      if (!document.trackChanges)
        throw new IllegalStateException("Protected: changes must be tracked");
      return;
    }
    boolean content =
        document.blocks != b.blocks
            || document.sections != b.sections
            || document.styles != b.styles
            || document.furniture != b.furniture
            || document.page != b.page
            || !document.lastSection.columns.equals(b.lastSection.columns)
            || !document.lastSection.decor.equals(b.lastSection.decor)
            || document.trackChanges != b.trackChanges
            || !document.title.equals(b.title);
    if (content || "readOnly".equals(mode) && document.comments != b.comments)
      throw new IllegalStateException(
          "readOnly".equals(mode) ? "Protected: the document is read-only" : "Protected: only comments are allowed");
  }

  private void reanchorComments(Document before) {
    if (!before.bookmarks.isEmpty()
        && document.bookmarks == before.bookmarks
        && document.blocks != before.blocks)
      document = document.bookmarks(Bookmark.reanchor(before, document));
    if (!before.comments.isEmpty()
        && document.comments == before.comments
        && document.blocks != before.blocks) {
      java.util.List<ReviewComment> next = ReviewComment.reanchor(before, document);
      if (next != before.comments) document = document.comments(next);
    }
  }

  public void addBookmark(final String name) {
    if (inCell()) throw new IllegalArgumentException("Select body text for a bookmark");
    final Selection selected = selection;
    final Bookmark mark =
        new Bookmark(
            name,
            document.paragraph(selected.startBlock()).id,
            selected.start(),
            document.paragraph(selected.endBlock()).id,
            selected.end());
    transact(
        s -> {
          Map<String, Bookmark> next = new LinkedHashMap<String, Bookmark>(document.bookmarks);
          next.put(name, mark);
          document = document.bookmarks(next);
        });
  }

  public void deleteBookmark(final String name) {
    if (!document.bookmarks.containsKey(name))
      throw new IllegalArgumentException("Unknown bookmark: " + name);
    transact(
        s -> {
          Map<String, Bookmark> next = new LinkedHashMap<String, Bookmark>(document.bookmarks);
          next.remove(name);
          document = document.bookmarks(next);
        });
  }

  public void goToBookmark(String name) {
    Bookmark mark = document.bookmarks.get(name);
    if (mark == null) throw new IllegalArgumentException("Unknown bookmark: " + name);
    leaveCell();
    selectRange(
        ReviewComment.block(document, mark.startId),
        mark.start,
        ReviewComment.block(document, mark.endId),
        mark.end);
  }

  public void addComment(final String author, final String text) {
    if (author.trim().isEmpty() || text.trim().isEmpty())
      throw new IllegalArgumentException("Enter an author and comment");
    transact(
        s -> {
          List<ReviewComment> comments = new ArrayList<ReviewComment>(document.comments);
          String id = "c" + document.nextId;
          Selection x = selection;
          comments.add(
              new ReviewComment(
                  id,
                  author,
                  text,
                  document.paragraph(x.startBlock()).id,
                  x.start(),
                  document.paragraph(x.endBlock()).id,
                  x.end(),
                  false));
          document = document.blocks(document.blocks, document.nextId + 1).comments(comments);
        });
  }

  public void updateComment(final String id, final boolean resolve, final boolean delete) {
    leaveCell();
    transact(
        s -> {
          List<ReviewComment> comments = new ArrayList<ReviewComment>();
          boolean found = false;
          for (ReviewComment c : document.comments) {
            if (c.id.equals(id)) {
              found = true;
              if (!delete) comments.add(c.resolved(resolve));
            } else comments.add(c);
          }
          if (!found) throw new IllegalArgumentException("Unknown comment");
          document = document.comments(comments);
        });
  }

  public void replyComment(final String id, final String author, final String text) {
    leaveCell();
    transact(
        s -> {
          List<ReviewComment> next = new ArrayList<ReviewComment>(document.comments);
          boolean found = false;
          for (int i = 0; i < next.size(); i++)
            if (next.get(i).id.equals(id)) {
              next.set(i, next.get(i).reply(author, text));
              found = true;
              break;
            }
          if (!found) throw new IllegalArgumentException("Unknown comment");
          document = document.comments(next);
        });
  }

  /** Use -1 for the thread text, or a zero-based reply index. */
  public void editComment(final String id, final int replyIndex, final String text) {
    // Validate before leaving a cell, so a rejected command preserves selection too.
    ReviewComment target = null;
    for (ReviewComment c : document().comments) if (c.id.equals(id)) target = c;
    if (target == null) throw new IllegalArgumentException("Unknown comment");
    final ReviewComment replacement = target.edited(replyIndex, text);
    leaveCell();
    transact(
        s -> {
          List<ReviewComment> next = new ArrayList<ReviewComment>(document.comments);
          for (int i = 0; i < next.size(); i++)
            if (next.get(i).id.equals(id)) {
              next.set(i, replacement);
              break;
            }
          document = document.comments(next);
        });
  }

  /** Moves the caret to the next/previous comment start in document order, wrapping. */
  public String navigateComment(boolean backwards) {
    final Document root = document();
    final Map<String, ReviewComment.Anchor> anchors = ReviewComment.index(root);
    List<ReviewComment> ordered = new ArrayList<ReviewComment>();
    for (ReviewComment c : root.comments) if (anchors.containsKey(c.startId)) ordered.add(c);
    if (ordered.isEmpty()) return null;
    Collections.sort(
        ordered,
        (a, b) ->
            compareCommentPosition(
                commentPosition(anchors.get(a.startId), a.start),
                commentPosition(anchors.get(b.startId), b.start)));
    int[] caret =
        cell == null
            ? new int[] {selection.block, -1, -1, selection.block, selection.caret}
            : new int[] {cell.block, cell.row, cell.column, selection.block, selection.caret};
    ReviewComment target = ordered.get(backwards ? ordered.size() - 1 : 0);
    for (int i = 0; i < ordered.size(); i++) {
      ReviewComment c = ordered.get(backwards ? ordered.size() - 1 - i : i);
      int comparison =
          compareCommentPosition(commentPosition(anchors.get(c.startId), c.start), caret);
      if (backwards ? comparison < 0 : comparison > 0) {
        target = c;
        break;
      }
    }
    selectComment(target.id);
    select(selection.startBlock(), selection.start(), selection.start());
    return target.id;
  }

  private static int[] commentPosition(ReviewComment.Anchor anchor, int offset) {
    return new int[] {anchor.block, anchor.row, anchor.column, anchor.paragraph, offset};
  }

  private static int compareCommentPosition(int[] a, int[] b) {
    for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
    return 0;
  }

  public void selectComment(String id) {
    leaveCell();
    for (ReviewComment c : document.comments)
      if (c.id.equals(id)) {
        ReviewComment.Anchor first = ReviewComment.locate(document, c.startId);
        ReviewComment.Anchor last = ReviewComment.locate(document, c.endId);
        if (first == null || !first.sameStory(last))
          throw new IllegalStateException("Comment anchor is no longer available");
        if (first.row >= 0) enterCell(first.block, first.row, first.column);
        selectRange(first.paragraph, c.start, last.paragraph, c.end);
        return;
      }
    throw new IllegalArgumentException("Unknown comment");
  }

  public boolean isComposing() {
    return composition != null;
  }

  public void compose(String text, int relativeCaret) {
    breakTypingGroup();
    if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
      throw new IllegalArgumentException("Composition cannot span paragraphs");
    if (composition == null) composition = state();
    Session draft = new Session(composition.doc);
    draft.restore(composition);
    draft.reviewAuthor = reviewAuthor;
    draft.trackFormatting = trackFormatting;
    draft.replace(text);
    document = draft.document;
    cell = draft.cell;
    cells = draft.cells;
    integratedSource = null;
    int start = composition.cells == null ? composition.sel.start() : 0,
        caret = Math.max(start, Math.min(start + text.length(), start + relativeCaret));
    int composingBlock =
        composition.cells == null ? composition.sel.startBlock() : draft.selection.block;
    Paragraph p = document.paragraph(composingBlock);
    if (caret > 0 && caret < p.text.length() && Character.isLowSurrogate(p.text.charAt(caret)))
      caret++;
    selection = new Selection(composingBlock, caret, caret);
  }

  public void finishComposition() {
    if (composition == null) return;
    State before = composition;
    composition = null;
    if (document != before.doc || mergeData != before.merge) {
      undo.add(before);
      if (undo.size() > HISTORY_LIMIT) undo.remove(0);
      redo.clear();
      revision++;
    }
  }

  public void cancelComposition() {
    if (composition != null) {
      restore(composition);
      composition = null;
    }
  }

  /** Start a new file without carrying history or typing attributes across documents. */
  public void loadDocument(Document d) {
    breakTypingGroup();
    if (transactionDepth > 0) throw new IllegalStateException("Load inside transaction");
    if (d == null) throw new NullPointerException("document");
    composition = null;
    cells = null;
    cell = null;
    integratedSource = null;
    insertionStyle = null;
    document = d;
    mergeData = MergeData.EMPTY;
    selectFirst();
    undo.clear();
    redo.clear();
    revision++;
  }

  public void replaceDocument(final Document d) {
    transact(
        new Edit() {
          public void apply(Session s) {
            cell = null;
            cells = null;
            integratedSource = null;
            document = d;
            selectFirst();
          }
        });
  }

  public void bibliography(final Bibliography value) {
    transact(
        s -> {
          int block = cellBlock(), row = cellRow(), column = cellColumn();
          Selection selected = selection;
          TextStyle style = insertionStyle;
          if (block >= 0) leaveCell();
          document = document.bibliography(value);
          if (block >= 0) enterCell(block, row, column);
          selection = selected;
          insertionStyle = style;
        });
  }

  public String reviewAuthor() {
    return reviewAuthor;
  }

  public void reviewAuthor(String value) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException("Enter an author name");
    if (!reviewAuthor.equals(value.trim())) breakTypingGroup();
    reviewAuthor = value.trim();
  }

  public boolean trackFormatting() {
    return trackFormatting;
  }

  public void trackFormatting(boolean enabled) {
    if (trackFormatting != enabled) breakTypingGroup();
    trackFormatting = enabled;
  }

  public void trackChanges(final boolean enabled) {
    transact(s -> {
      int block = cellBlock(), row = cellRow(), column = cellColumn();
      Selection selected = selection;
      CellSelection range = cells;
      TextStyle style = insertionStyle;
      leaveCell();
      document = document.trackChanges(enabled);
      if (block >= 0) enterCell(block, row, column);
      selection = selected;
      cells = range;
      insertionStyle = style;
    });
  }

  private Revision newRevision(String kind) {
    Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    String date =
        TextUtil.padded(utc.get(Calendar.YEAR), 10, 4)
            + "-"
            + TextUtil.padded(utc.get(Calendar.MONTH) + 1, 10, 2)
            + "-"
            + TextUtil.padded(utc.get(Calendar.DAY_OF_MONTH), 10, 2)
            + "T"
            + TextUtil.padded(utc.get(Calendar.HOUR_OF_DAY), 10, 2)
            + ":"
            + TextUtil.padded(utc.get(Calendar.MINUTE), 10, 2)
            + ":"
            + TextUtil.padded(utc.get(Calendar.SECOND), 10, 2)
            + "Z";
    Revision result = new Revision(Long.toString(document.nextId), kind, reviewAuthor, date);
    document = document.blocks(document.blocks, document.nextId + 1);
    return result;
  }

  public void resolveRevision(final String id, final boolean accept) {
    if (id != null) {
      boolean found = false;
      for (Revisions.Change change : Revisions.list(document()))
        if (id.equals(change.revision.id)) found = true;
      if (!found) throw new IllegalArgumentException("Unknown tracked change: " + id);
    }
    leaveCell();
    transact(
        s -> {
          Document before = document;
          document = Revisions.resolve(document, id, accept);
          document = document.comments(ReviewComment.reanchor(before, document));
          selectFirst();
        });
  }

  public void resolveSelectedRevisions(final boolean accept) {
    Selection range = selection;
    boolean properties = !range.collapsed(), mark = false;
    if (range.collapsed()) {
      String paragraph = document.paragraph(range.block).id;
      Revisions.Change found = null;
      for (Revisions.Change change : Revisions.list(document))
        if (change.paragraph.equals(paragraph)
            && change.start <= range.caret
            && change.end >= range.caret) {
          found = change;
          break;
        }
      if (found == null) {
        nextRevision(false);
        return;
      }
      properties = "Paragraph formatting".equals(found.text);
      mark = "Paragraph break".equals(found.text);
      range = new Selection(range.block, found.start, found.end);
    }
    final Selection selected = range;
    final boolean props = properties, paragraphMark = mark;
    transact(
        s -> {
          Document before = document;
          document = Revisions.resolveSelection(document, selected, accept, props, paragraphMark);
          document = document.comments(ReviewComment.reanchor(before, document));
          int block = Math.min(selected.startBlock(), document.blocks.size() - 1);
          int offset = Math.min(selected.start(), document.paragraph(block).text.length());
          selection = new Selection(block, offset, offset);
          insertionStyle = null;
        });
  }

  public void selectRevision(Revisions.Change change) {
    leaveCell();
    if (!change.story.isEmpty())
      throw new IllegalArgumentException(
          "This change is in the " + change.story + "; use its margin editor");
    if (change.row >= 0) {
      enterCell(change.block, change.row, change.column);
      for (int i = 0; i < document.blocks.size(); i++)
        if (document.blocks.get(i).id().equals(change.paragraph)) {
          select(i, change.start, change.end);
          return;
        }
    } else select(change.block, change.start, change.end);
  }

  public String revisionAtSelection() {
    String paragraph = document.paragraph(selection.block).id;
    for (Revisions.Change change : Revisions.list(document()))
      if (change.paragraph.equals(paragraph)
          && change.start <= selection.end()
          && change.end >= selection.start()) return change.revision.id;
    throw new IllegalArgumentException("Select a tracked change first");
  }

  public Revisions.Change nextRevision(boolean previous) {
    List<Revisions.Change> changes = Revisions.list(document());
    List<Revisions.Change> body = new ArrayList<Revisions.Change>();
    for (Revisions.Change change : changes) if (change.story.isEmpty()) body.add(change);
    if (body.isEmpty())
      throw new IllegalArgumentException("No tracked changes in the document body");
    String paragraph = document.paragraph(selection.block).id;
    int current = -1;
    for (int i = 0; i < body.size(); i++) {
      Revisions.Change change = body.get(i);
      if (change.paragraph.equals(paragraph)
          && change.start == selection.start()
          && change.end == selection.end()) {
        current = i;
        break;
      }
    }
    int target;
    if (current >= 0) target = (current + (previous ? -1 : 1) + body.size()) % body.size();
    else {
      target = previous ? body.size() - 1 : 0;
      int block = inCell() ? cell.block : selection.block;
      for (int i = 0; i < body.size(); i++) {
        Revisions.Change change = body.get(i);
        if (!previous
            && (change.block > block || change.block == block && change.start >= selection.caret)) {
          target = i;
          break;
        }
        if (previous
            && (change.block < block || change.block == block && change.end <= selection.caret))
          target = i;
      }
    }
    Revisions.Change result = body.get(target);
    selectRevision(result);
    return result;
  }

  private void trackedReplace(String value) {
    Selection selected = selection;
    for (int b = selected.startBlock(); b <= selected.endBlock(); b++)
      if (!(document.blocks.get(b) instanceof Paragraph))
        throw new IllegalArgumentException(
            "Select text within paragraphs when tracking edits across blocks");
    Revision deletion = selected.collapsed() ? null : newRevision("delete");
    Set<String> cancelBreaks = new HashSet<String>();
    Sequence<Block> blocks = document.blocks;
    for (int b = selected.startBlock(); b <= selected.endBlock(); b++) {
      Paragraph p = (Paragraph) blocks.get(b);
      int from = b == selected.startBlock() ? selected.start() : 0;
      int to = b == selected.endBlock() ? selected.end() : p.text.length();
      if (to > from) p = p.text(p.text.replace(from, to, p.text.slice(from, to).deleted(deletion)));
      if (b < selected.endBlock()) {
        if (p.breakRevision != null
            && p.breakRevision.inserted()
            && p.breakRevision.author.equals(reviewAuthor)) cancelBreaks.add(p.id);
        else if (p.breakRevision == null || p.breakRevision.inserted())
          p = p.breakRevision(deletion.previous(p.breakRevision));
      }
      blocks = blocks.set(b, p);
    }
    Map<String, Section> keptSections = new LinkedHashMap<String, Section>(document.sections);
    for (int b = selected.startBlock(); b + 1 < blocks.size(); b++) {
      if (!cancelBreaks.contains(blocks.get(b).id())) continue;
      Paragraph a = (Paragraph) blocks.get(b), z = (Paragraph) blocks.get(b + 1);
      cancelBreaks.remove(a.id);
      if (cancelBreaks.remove(z.id)) cancelBreaks.add(a.id);
      Section boundary = keptSections.remove(z.id);
      keptSections.remove(a.id);
      if (boundary != null) keptSections.put(a.id, boundary);
      blocks =
          blocks.set(b, a.text(a.text.concat(z.text)).breakRevision(z.breakRevision)).remove(b + 1);
      b--;
    }
    document =
        document.blocks(blocks, document.nextId).sections(keptSections, document.lastSection);
    int first = selected.startBlock(), at = selected.start();
    Paragraph p = document.paragraph(first);
    String[] lines = TextUtil.split(value.replace("\r\n", "\n").replace('\r', '\n'), '\n');
    if (value.isEmpty()) {
      selection = new Selection(first, at, at);
      return;
    }
    Revision insertion = null;
    if (selected.collapsed() && at > 0) {
      Revision previous = p.text.styleAt(at - 1).revision;
      if (previous != null && previous.inserted() && previous.author.equals(reviewAuthor))
        insertion = previous;
    }
    if (insertion == null) insertion = newRevision("insert");
    TextStyle style =
        (insertionStyle == null ? typingStyle(p, at) : insertionStyle).clean().revision(insertion);
    RichText prefix = p.text.slice(0, at), suffix = p.text.slice(at, p.text.length());
    blocks = document.blocks;
    long next = document.nextId;
    if (lines.length == 1)
      blocks =
          blocks.set(first, p.text(prefix.concat(RichText.of(lines[0], style)).concat(suffix)));
    else {
      blocks =
          blocks.set(
              first, p.text(prefix.concat(RichText.of(lines[0], style))).breakRevision(insertion));
      for (int i = 1; i < lines.length; i++) {
        boolean last = i == lines.length - 1;
        RichText text = RichText.of(lines[i], style);
        if (last) text = text.concat(suffix);
        blocks =
            blocks.insert(
                first + i,
                p.copy("p" + next++, text).breakRevision(last ? p.breakRevision : insertion));
      }
    }
    Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
    Section boundary = sections.remove(p.id);
    if (boundary != null) sections.put(blocks.get(first + lines.length - 1).id(), boundary);
    document = document.blocks(blocks, next).sections(sections, document.lastSection);
    int caret = lines.length == 1 ? at + lines[0].length() : lines[lines.length - 1].length();
    selection = new Selection(first + lines.length - 1, caret, caret);
  }

  public void replace(final String value) {
    if (value == null) throw new NullPointerException();
    if (cells != null) {
      replaceCells(value);
      return;
    }
    transact(
        new Edit() {
          public void apply(Session s) {
            if (document.trackChanges) {
              trackedReplace(value);
              return;
            }
            Document before = document;
            Selection x = selection;
            int first = x.startBlock();
            Paragraph p = document.paragraph(first), last = document.paragraph(x.endBlock());
            String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = TextUtil.split(normalized, '\n');
            TextStyle style = insertionStyle != null ? insertionStyle : typingStyle(p, x.start());
            style = style.clean();
            RichText prefix = p.text.slice(0, x.start()),
                suffix = last.text.slice(x.end(), last.text.length());
            Sequence<Block> b = document.blocks;
            for (int i = x.endBlock(); i > first; i--) b = b.remove(i);
            long next = document.nextId;
            if (lines.length == 1) {
              b =
                  b.set(
                      first,
                      p.text(prefix.concat(RichText.of(lines[0], style)).concat(suffix))
                          .breakRevision(last.breakRevision));
              selection =
                  new Selection(
                      first, x.start() + lines[0].length(), x.start() + lines[0].length());
            } else {
              b =
                  b.set(
                      first,
                      p.text(prefix.concat(RichText.of(lines[0], style))).breakRevision(null));
              for (int i = 1; i < lines.length; i++) {
                RichText t = RichText.of(lines[i], style);
                if (i == lines.length - 1) t = t.concat(suffix);
                b =
                    b.insert(
                        first + i,
                        p.copy("p" + (next++), t)
                            .breakRevision(i == lines.length - 1 ? last.breakRevision : null));
              }
              selection =
                  new Selection(
                      first + lines.length - 1,
                      lines[lines.length - 1].length(),
                      lines[lines.length - 1].length());
            }
            document = document.blocks(b, next);
            if (!before.sections.isEmpty()) {
              Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
              sections.remove(p.id);
              Section tail = before.sections.get(last.id);
              if (tail != null) sections.put(b.get(first + lines.length - 1).id(), tail);
              document = document.sections(sections, document.lastSection);
            }
            if (!before.comments.isEmpty())
              document = document.comments(ReviewComment.reanchor(before, document, x, selection));
            if (!before.bookmarks.isEmpty())
              document = document.bookmarks(Bookmark.reanchor(before, document, x, selection));
          }
        });
  }

  /** Changes selected text while retaining each run's formatting and a single undo step. */
  public void changeCase(final boolean upper) {
    changeCase(upper ? "upper" : "lower");
  }

  public void changeCase(final String requestedMode) {
    if (cells != null) {
      editCells(x -> x.changeCase(requestedMode), true);
      return;
    }
    if (requestedMode != null
        && !Arrays.asList("upper", "lower", "title", "sentence", "toggle").contains(requestedMode))
      throw new IllegalArgumentException(
          "Case mode must be sentence, lower, upper, title or toggle");
    transact(
        s -> {
          Selection selected = selection;
          if (selected.collapsed()) {
            String text = document.paragraph(selected.block).text.toString();
            int caret = selected.caret;
            if (caret == 0
                || caret >= text.length()
                || !CaseConversion.word(text.charAt(caret - 1))
                || !CaseConversion.word(text.charAt(caret))) return;
            int from = caret, to = caret;
            while (from > 0 && CaseConversion.word(text.charAt(from - 1))) from--;
            while (to < text.length() && CaseConversion.word(text.charAt(to))) to++;
            selected = new Selection(selected.block, from, to);
          }
          String mode = requestedMode;
          if (mode == null) {
            StringBuilder text = new StringBuilder();
            for (int b = selected.startBlock(); b <= selected.endBlock(); b++) {
              if (!(document.blocks.get(b) instanceof Paragraph)) continue;
              Paragraph p = document.paragraph(b);
              text.append(
                  p.text
                      .slice(
                          b == selected.startBlock() ? selected.start() : 0,
                          b == selected.endBlock() ? selected.end() : p.text.length())
                      .plainText());
            }
            mode = CaseConversion.cycle(text.toString());
          }
          Revision insertion = document.trackChanges ? newRevision("insert") : null;
          Revision deletion = document.trackChanges ? newRevision("delete") : null;
          Sequence<Block> blocks = document.blocks;
          java.util.List<ReviewComment> comments = new ArrayList<ReviewComment>(document.comments);
          Map<String, Bookmark> bookmarks = new LinkedHashMap<String, Bookmark>(document.bookmarks);
          int end = selected.end();
          for (int b = selected.startBlock(); b <= selected.endBlock(); b++) {
            if (!(blocks.get(b) instanceof Paragraph)) continue;
            Paragraph p = (Paragraph) blocks.get(b);
            int from = b == selected.startBlock() ? selected.start() : 0;
            int to = b == selected.endBlock() ? selected.end() : p.text.length();
            CaseConversion conversion =
                new CaseConversion(p.text.slice(from, to), mode, from == 0, insertion, deletion);
            RichText converted = conversion.text();
            blocks = blocks.set(b, p.text(p.text.replace(from, to, converted)));
            for (int c = 0; c < comments.size(); c++) {
              ReviewComment comment = comments.get(c);
              int a =
                  comment.startId.equals(p.id)
                      ? caseOffset(comment.start, from, to, conversion)
                      : comment.start;
              int z =
                  comment.endId.equals(p.id)
                      ? caseOffset(comment.end, from, to, conversion)
                      : comment.end;
              if (a != comment.start || z != comment.end)
                comments.set(
                    c,
                    new ReviewComment(
                        comment.id,
                        comment.author,
                        comment.text,
                        comment.startId,
                        a,
                        comment.endId,
                        z,
                        comment.resolved,
                        comment.replies));
            }
            for (Bookmark mark : new ArrayList<Bookmark>(bookmarks.values())) {
              int a =
                  mark.startId.equals(p.id)
                      ? caseOffset(mark.start, from, to, conversion)
                      : mark.start;
              int z =
                  mark.endId.equals(p.id) ? caseOffset(mark.end, from, to, conversion) : mark.end;
              if (a != mark.start || z != mark.end)
                bookmarks.put(mark.name, new Bookmark(mark.name, mark.startId, a, mark.endId, z));
            }
            if (b == selected.endBlock()) end = from + converted.length();
          }
          document =
              document.blocks(blocks, document.nextId).comments(comments).bookmarks(bookmarks);
          selection =
              new Selection(selected.startBlock(), selected.start(), selected.endBlock(), end);
        });
  }

  private static int caseOffset(int offset, int from, int to, CaseConversion conversion) {
    if (offset <= from) return offset;
    return from + conversion.offset(Math.min(to, offset) - from) + Math.max(0, offset - to);
  }

  /** Word-style formatting uses the surrounding word only when the caret is inside it. */
  private Selection characterTarget() {
    if (!selection.collapsed()) return selection;
    RichText source = document.paragraph(selection.block).text;
    String text = source.toString();
    int at = selection.caret;
    if (at == 0
        || at == text.length()
        || !wordCharacter(Character.codePointBefore(text, at))
        || !wordCharacter(Character.codePointAt(text, at))) return selection;
    int from = at, to = at;
    while (from > 0 && wordCharacter(Character.codePointBefore(text, from)))
      from -= Character.charCount(Character.codePointBefore(text, from));
    while (to < text.length() && wordCharacter(Character.codePointAt(text, to)))
      to += Character.charCount(Character.codePointAt(text, to));
    return new Selection(selection.block, from, to);
  }

  private static boolean wordCharacter(int cp) {
    int type = Character.getType(cp);
    return Character.isLetterOrDigit(cp)
        || cp == '\''
        || cp == '_'
        || type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK;
  }

  public void format(final String key, final String value) {
    if (cells != null) {
      editCells(x -> x.format(key, value), true);
      return;
    }
    breakTypingGroup();
    final Selection target = characterTarget();
    if (target.collapsed()) {
      final TextStyle next = activeStyle().with(key, value).formatRevision(null, null);
      if (document.paragraph(selection.block).text.length() == 0)
        transact(
            s -> {
              Paragraph p = document.paragraph(selection.block);
              document =
                  document.blocks(
                      document.blocks.set(selection.block, p.text(RichText.of("", next))),
                      document.nextId);
            });
      insertionStyle = next;
      return;
    }
    transact(
        s -> {
          Revision change = document.trackChanges && trackFormatting ? newRevision("format") : null;
          Selection x = target;
          Sequence<Block> blocks = document.blocks;
          for (int i = x.startBlock(); i <= x.endBlock(); i++) {
            Block block = blocks.get(i);
            if (block instanceof Paragraph) {
              Paragraph p = (Paragraph) block;
              int a = i == x.startBlock() ? x.start() : 0,
                  b = i == x.endBlock() ? x.end() : p.text.length();
              blocks = blocks.set(i, p.text(p.text.format(a, b, key, value, change)));
            } else if (block instanceof Table) {
              Table t = (Table) block;
              for (int r = 0; r < t.rows(); r++)
                for (int c = 0; c < t.columns(); c++) {
                  Sequence<Paragraph> cell = t.cell(r, c);
                  for (int n = 0; n < cell.size(); n++) {
                    Paragraph p = cell.get(n);
                    cell =
                        cell.set(n, p.text(p.text.format(0, p.text.length(), key, value, change)));
                  }
                  t = t.setCell(r, c, cell);
                }
              blocks = blocks.set(i, t);
            }
          }
          document = document.blocks(blocks, document.nextId);
          insertionStyle = null;
        });
  }

  public void applyStyle(final String id) {
    if (cells != null) {
      editCells(x -> x.applyStyle(id), true);
      return;
    }
    final ParagraphStyle style = document().styles.get(id);
    if (style == null) throw new IllegalArgumentException("Unknown style: " + id);
    transact(
        s -> {
          Revision change = document.trackChanges && trackFormatting ? newRevision("format") : null;
          Sequence<Block> blocks = document.blocks;
          for (int i = selection.startBlock(); i <= selection.endBlock(); i++)
            if (blocks.get(i) instanceof Paragraph) {
              Paragraph p = (Paragraph) blocks.get(i);
              blocks = blocks.set(i, style.apply(p, document.styles.get(p.style), false, change));
            }
          document = document.blocks(blocks, document.nextId);
          insertionStyle =
              document.paragraph(selection.block).text.styleAt(Math.max(0, selection.caret - 1));
        });
  }

  public void defineStyle(final ParagraphStyle style) {
    transact(
        s -> {
          int table = cellBlock(), row = cellRow(), column = cellColumn();
          Selection selected = selection;
          leaveCell();
          ParagraphStyle old = document.styles.get(style.id);
          Map<String, ParagraphStyle> definitions =
              new LinkedHashMap<String, ParagraphStyle>(document.styles);
          definitions.put(style.id, style);
          Sequence<Block> blocks = updateStyledBlocks(document.blocks, style.id, style, old, false);
          document =
              document
                  .blocks(blocks, document.nextId)
                  .styles(definitions)
                  .furniture(
                      updateStyledFurniture(document.furniture, style.id, style, old, false));
          updateSectionStyles(style.id, style, old, false);
          if (table >= 0) {
            enterCell(table, row, column);
            selectRange(selected.anchorBlock, selected.anchor, selected.block, selected.caret);
          }
          insertionStyle =
              document.paragraph(selection.block).text.styleAt(Math.max(0, selection.caret - 1));
        });
  }

  public void deleteStyle(final String id) {
    if (ParagraphStyle.defaults().containsKey(id))
      throw new IllegalArgumentException("Built-in styles cannot be deleted");
    if (!document().styles.containsKey(id))
      throw new IllegalArgumentException("Unknown style: " + id);
    leaveCell();
    transact(
        s -> {
          Map<String, ParagraphStyle> definitions =
              new LinkedHashMap<String, ParagraphStyle>(document.styles);
          definitions.remove(id);
          document =
              document
                  .blocks(
                      updateStyledBlocks(
                          document.blocks, id, document.styles.get("Normal"), null, true),
                      document.nextId)
                  .styles(definitions)
                  .furniture(
                      updateStyledFurniture(
                          document.furniture, id, document.styles.get("Normal"), null, true));
          updateSectionStyles(id, document.styles.get("Normal"), null, true);
        });
  }

  private void updateSectionStyles(
      String id, ParagraphStyle style, ParagraphStyle old, boolean deleting) {
    Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
    for (Map.Entry<String, Section> entry : sections.entrySet())
      entry.setValue(
          entry
              .getValue()
              .furniture(
                  updateStyledFurniture(entry.getValue().furniture, id, style, old, deleting)));
    document = document.sections(sections, document.lastSection);
  }

  private PageFurniture updateStyledFurniture(
      PageFurniture furniture,
      String id,
      ParagraphStyle style,
      ParagraphStyle old,
      boolean deleting) {
    PageFurniture result = furniture;
    for (String key : PageFurniture.KEYS)
      if (furniture.stories.containsKey(key)) {
        Sequence<Paragraph> previous = furniture.get(key), next = previous;
        for (int i = 0; i < previous.size(); i++) {
          Paragraph p = previous.get(i);
          if (p.style.equals(id))
            next = next.set(i, deleting ? p.with("style", "Normal") : style.apply(p, old, true));
        }
        if (next != previous) result = result.story(key, next);
      }
    return result;
  }

  private Sequence<Block> updateStyledBlocks(
      Sequence<Block> blocks,
      String id,
      ParagraphStyle style,
      ParagraphStyle old,
      boolean deleting) {
    for (int i = 0; i < blocks.size(); i++) {
      Block block = blocks.get(i);
      if (block instanceof Paragraph) {
        Paragraph p = (Paragraph) block;
        if (p.style.equals(id))
          blocks = blocks.set(i, deleting ? p.with("style", "Normal") : style.apply(p, old, true));
      } else if (block instanceof Table) {
        Table table = (Table) block;
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++) {
            if (table.covered(r, c)) continue;
            Sequence<Paragraph> paras = table.cell(r, c);
            boolean changed = false;
            for (int n = 0; n < paras.size(); n++)
              if (paras.get(n).style.equals(id)) {
                Paragraph p = paras.get(n);
                paras =
                    paras.set(n, deleting ? p.with("style", "Normal") : style.apply(p, old, true));
                changed = true;
              }
            if (changed) table = table.setCell(r, c, paras);
          }
        if (table != block) blocks = blocks.set(i, table);
      }
    }
    return blocks;
  }

  public void paragraph(final String key, final String value) {
    if (cells != null) {
      editCells(x -> x.paragraph(key, value), true);
      return;
    }
    transact(
        new Edit() {
          public void apply(Session s) {
            Revision change =
                document.trackChanges && trackFormatting ? newRevision("format") : null;
            Sequence<Block> blocks = document.blocks;
            for (int i = selection.startBlock(); i <= selection.endBlock(); i++) {
              if (blocks.get(i) instanceof Paragraph)
                blocks =
                    blocks.set(
                        i,
                        ((Paragraph) blocks.get(i))
                            .with(key, value)
                            .trackedProperties((Paragraph) blocks.get(i), change));
            }
            document = document.blocks(blocks, document.nextId);
          }
        });
  }

  public void deleteForward() {
    if (cells != null) {
      replaceCells("");
      return;
    }
    if (!selection.collapsed()) {
      replace("");
      return;
    }
    int block = selection.block, at = selection.caret;
    while (true) {
      Paragraph p = document.paragraph(block);
      while (at < p.text.length()) {
        Revision revision = p.text.styleAt(at).revision;
        if (!document.trackChanges || revision == null || revision.inserted()) break;
        at++;
      }
      if (at < p.text.length()) {
        if (!Graphemes.isBoundary(p.text, at)) at = Graphemes.previous(p.text, at);
        int end = Graphemes.next(p.text, at);
        select(block, at, end);
        replace("");
        return;
      }
      if (block + 1 >= document.blocks.size()
          || !(document.blocks.get(block + 1) instanceof Paragraph)) return;
      if (document.trackChanges && p.breakRevision != null && !p.breakRevision.inserted()) {
        block++;
        at = 0;
        continue;
      }
      selectRange(block, at, block + 1, 0);
      replace("");
      return;
    }
  }

  public void deleteBackward() {
    if (cells != null) {
      replaceCells("");
      return;
    }
    Selection x = selection;
    if (!x.collapsed()) {
      replace("");
      return;
    }
    if (document.trackChanges) {
      int block = x.block, at = x.caret;
      while (true) {
        Paragraph p = document.paragraph(block);
        while (at > 0) {
          Revision revision = p.text.styleAt(at - 1).revision;
          if (revision == null || revision.inserted()) break;
          at--;
        }
        if (at > 0) {
          if (!Graphemes.isBoundary(p.text, at)) at = Graphemes.next(p.text, at);
          int from = Graphemes.previous(p.text, at);
          select(block, from, at);
          replace("");
          return;
        }
        if (block == 0 || !(document.blocks.get(block - 1) instanceof Paragraph)) return;
        Paragraph previous = document.paragraph(block - 1);
        if (previous.breakRevision != null && !previous.breakRevision.inserted()) {
          block--;
          at = previous.text.length();
          continue;
        }
        selectRange(block - 1, previous.text.length(), block, 0);
        replace("");
        return;
      }
    }
    if (x.caret > 0) {
      Paragraph p = document.paragraph(x.block);
      int start = Graphemes.previous(p.text, x.caret);
      int end = Graphemes.isBoundary(p.text, x.caret) ? x.caret : Graphemes.next(p.text, x.caret);
      select(x.block, start, end);
      replace("");
    } else if (x.block > 0 && document.blocks.get(x.block - 1) instanceof Paragraph) {
      if (document.trackChanges) {
        Paragraph previous = document.paragraph(x.block - 1);
        selectRange(x.block - 1, previous.text.length(), x.block, 0);
        replace("");
        return;
      }
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

  public Section.Range activeSection() {
    return document().sectionAt(inCell() ? cell.block : selection.block);
  }

  /** Change selected sections, or defaults and matching overrides, in one undo transaction. */
  public void noteOptions(
      final String kind, final NoteOptions.Properties change, final boolean whole) {
    document().notes.with(kind, change); // Validate before opening a transaction.
    if (change.isEmpty()) return;
    final int first = inCell() ? cell.block : selection.startBlock();
    final int last = inCell() ? cell.block : selection.endBlock();
    transact(
        s -> {
          int cb = cellBlock(), cr = cellRow(), cc = cellColumn();
          Selection saved = selection;
          TextStyle savedStyle = insertionStyle;
          leaveCell();
          Document root = document();
          if (whole) root = root.notes(root.notes.with(kind, change.resolve(root.notes.get(kind))));
          Map<String, Section> sections = new LinkedHashMap<String, Section>(root.sections);
          Section tail = root.lastSection;
          for (Section.Range range : root.sectionRanges()) {
            if (!whole && (range.last < first || range.first > last)) continue;
            NoteOptions.Properties old = range.raw.notes.get(kind);
            Section updated =
                range.raw.notes(
                    range.raw.notes.with(kind, whole ? old.clear(change) : change.resolve(old)));
            String id = range.last >= 0 ? root.blocks.get(range.last).id() : "";
            if (sections.containsKey(id)) sections.put(id, updated);
            else tail = updated;
          }
          root = root.sections(sections, tail);
          document = root;
          if (cb >= 0) enterCell(cb, cr, cc);
          selection = saved;
          insertionStyle = savedStyle;
        });
  }

  public void section(final Section value) {
    int block = inCell() ? cell.block : selection.block;
    leaveCell();
    transact(s -> document = document.section(block, value));
  }

  /**
   * Newspaper columns for the current section, every section ("document"), or from the
   * caret on ("forward", which first inserts a continuous section break), as one undo step.
   */
  public void columns(final Columns value, final String apply) {
    if (inCell()) leaveCell();
    transact(
        s -> {
          if ("document".equals(apply)) {
            Map<String, Section> changed = new LinkedHashMap<String, Section>();
            for (Map.Entry<String, Section> e : document.sections.entrySet())
              changed.put(e.getKey(), e.getValue().columns(value));
            document = document.sections(changed, document.lastSection.columns(value));
          } else {
            if ("forward".equals(apply)) sectionBreak("continuous");
            else if (!"section".equals(apply))
              throw new IllegalArgumentException("apply must be section, document or forward");
            document = document.section(selection.block, activeSection().raw.columns(value));
          }
        });
  }

  /** Split at the caret; the text after it starts at the top of the next column. */
  public void columnBreak() {
    if (inCell()) throw new IllegalArgumentException("Column breaks belong in the document body");
    transact(
        s -> {
          enterParagraph();
          paragraph("columnBreak", "true");
        });
  }

  /** Insert before the selection without deleting its contents. */
  public void sectionBreak(final String kind) {
    if (inCell()) throw new IllegalArgumentException("Section breaks belong in the document body");
    transact(
        s -> {
          Selection old = selection;
          int first = old.startBlock(),
              offset = old.start(),
              end = old.endBlock(),
              endOffset = old.end();
          Section current = document.sectionAt(first).raw;
          Section next = current.linked().start(kind).numbering(0);
          select(first, offset, offset);
          replace("\n");
          Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
          sections.put(document.blocks.get(first).id(), current);
          document = document.sections(sections, document.lastSection).section(first + 1, next);
          int last = end + 1, lastOffset = end == first ? endOffset - offset : endOffset;
          boolean forward =
              old.anchorBlock < old.block
                  || old.anchorBlock == old.block && old.anchor <= old.caret;
          selection =
              forward
                  ? new Selection(first + 1, 0, last, lastOffset)
                  : new Selection(last, lastOffset, first + 1, 0);
        });
  }

  public void furniture(final PageFurniture value) {
    final int block = inCell() ? cell.block : selection.block;
    leaveCell();
    transact(
        s -> {
          long next = document.nextId;
          PageFurniture updated = value;
          for (String key : PageFurniture.KEYS)
            if (value.stories.containsKey(key)) {
              Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
              for (Paragraph para : value.get(key))
                paragraphs = paragraphs.append(para.copy("p" + next++, para.text));
              updated = updated.story(key, paragraphs);
            }
          document =
              document
                  .blocks(document.blocks, next)
                  .section(block, document.sectionAt(block).raw.furniture(updated));
          // DOCX uses one document-wide odd/even-header switch.
          Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
          for (Map.Entry<String, Section> entry : sections.entrySet()) {
            PageFurniture f = entry.getValue().furniture;
            entry.setValue(
                entry
                    .getValue()
                    .furniture(
                        f.options(
                            f.differentFirst,
                            value.differentEven,
                            f.headerDistance,
                            f.footerDistance)));
          }
          PageFurniture f = document.furniture;
          document =
              document.sections(
                  sections,
                  document.lastSection.furniture(
                      f.options(
                          f.differentFirst,
                          value.differentEven,
                          f.headerDistance,
                          f.footerDistance)));
          document = document.blocks(document.blocks, Revisions.nextId(document));
        });
  }

  public interface FieldResolver {
    InlineField resolve(String paragraphId, int offset, InlineField field);
  }

  /** Updates field metadata/results without changing source offsets or selection. */
  public void mapFields(final FieldResolver resolver) {
    transact(
        s -> {
          int block = cellBlock(), row = cellRow(), column = cellColumn();
          Selection selected = selection;
          CellSelection selectedCells = cells;
          TextStyle selectedStyle = insertionStyle;
          if (block >= 0) leaveCell();
          Sequence<Block> blocks = document.blocks;
          for (int i = 0; i < blocks.size(); i++) {
            Block original = blocks.get(i), updated = original;
            if (original instanceof Paragraph)
              updated = mapParagraphFields((Paragraph) original, resolver);
            else if (original instanceof Table) {
              Table table = (Table) original;
              for (int r = 0; r < table.rows(); r++)
                for (int c = 0; c < table.columns(); c++) {
                  if (table.covered(r, c)) continue;
                  Sequence<Paragraph> cell = table.cell(r, c), next = cell;
                  for (int n = 0; n < cell.size(); n++) {
                    Paragraph old = cell.get(n), value = mapParagraphFields(old, resolver);
                    if (old != value) next = next.set(n, value);
                  }
                  if (next != cell) table = table.setCell(r, c, next);
                }
              updated = table;
            }
            if (updated != original) blocks = blocks.set(i, updated);
          }
          if (blocks != document.blocks) document = document.blocks(blocks, document.nextId);
          document =
              Notes.map(
                  document,
                  note -> {
                    Sequence<Paragraph> story = note.paragraphs;
                    for (int i = 0; i < story.size(); i++) {
                      Paragraph original = story.get(i),
                          value = mapParagraphFields(original, resolver);
                      if (original != value) story = story.set(i, value);
                    }
                    return note.paragraphs(story);
                  });
          if (block >= 0) enterCell(block, row, column);
          selection = selected;
          cells = selectedCells;
          insertionStyle = selectedStyle;
        });
  }

  private static Paragraph mapParagraphFields(Paragraph p, FieldResolver resolver) {
    RichText text = p.text.mapFields((offset, field) -> resolver.resolve(p.id, offset, field));
    return text == p.text ? p : p.text(text);
  }

  public void changeField(final int offset, final InlineField value) {
    Paragraph p = document.paragraph(selection.block);
    if (p.text.fieldAt(offset) == null) throw new IllegalArgumentException("Select a field");
    final String id = p.id;
    mapFields((paragraph, at, field) -> id.equals(paragraph) && at == offset ? value : field);
  }

  public void insertCaption(
      String label,
      final String text,
      final boolean above,
      final String format,
      final boolean excludeLabel,
      final int chapter,
      final String separator) {
    final String captionLabel = label.trim();
    if (captionLabel.isEmpty()
        || captionLabel.length() > 40
        || captionLabel.indexOf('"') >= 0
        || captionLabel.indexOf('\\') >= 0
        || captionLabel.indexOf('\n') >= 0
        || captionLabel.indexOf('\r') >= 0)
      throw new IllegalArgumentException(
          "Use a caption label of 1–40 characters without quotes or backslashes");
    if (!Arrays.asList("ARABIC", "roman", "ROMAN", "alphabetic", "ALPHABETIC").contains(format)
        || chapter < 0
        || chapter > 9
        || !Arrays.asList("-", ".", ":", "—", "–").contains(separator))
      throw new IllegalArgumentException("Invalid caption numbering options");
    final int target = inCell() ? cell.block : selection.block;
    leaveCell();
    transact(
        s -> {
          String identifier = captionLabel.replace(' ', '_').replace('\t', '_');
          TextStyle style =
              document.styles.containsKey("Caption")
                  ? document.styles.get("Caption").text
                  : TextStyle.DEFAULT.with("italic", "true");
          RichText content = RichText.of(excludeLabel ? "" : captionLabel + " ", style);
          if (chapter > 0)
            content =
                content
                    .concat(
                        RichText.field(
                            new InlineField("STYLEREF " + chapter + " \\s", "", false, false),
                            style))
                    .concat(RichText.of(separator, style));
          content =
              content.concat(
                  RichText.field(
                      new InlineField(
                          "SEQ "
                              + identifier
                              + " \\* "
                              + format
                              + (chapter > 0 ? " \\s " + chapter : ""),
                          "1",
                          false,
                          false),
                      style));
          if (!text.isEmpty()) content = content.concat(RichText.of(": " + text, style));
          int at = target + (above ? 0 : 1);
          Paragraph caption =
              new Paragraph("p" + document.nextId, content)
                  .with("style", "Caption")
                  .with("keepNext", "" + above);
          if (document.trackChanges) {
            boolean beforeParagraph =
                at < document.blocks.size() && document.blocks.get(at) instanceof Paragraph;
            int anchor = beforeParagraph ? at : at - 1;
            if (anchor < 0 || !(document.blocks.get(anchor) instanceof Paragraph))
              throw new IllegalArgumentException(
                  "Tracked captions require an adjacent text paragraph");
            Paragraph original = document.paragraph(anchor);
            select(
                anchor,
                beforeParagraph ? 0 : original.text.length(),
                beforeParagraph ? 0 : original.text.length());
            Paragraph empty = original.copy("caption-empty", new RichText());
            Sequence<Block> fragment = new Sequence<Block>();
            fragment =
                beforeParagraph
                    ? fragment.append(caption).append(empty)
                    : fragment.append(empty).append(caption);
            trackedPaste(new Document(fragment, document.page, "Caption", document.nextId + 1));
            Paragraph inserted = document.paragraph(at);
            Paragraph formatted =
                inserted
                    .with("style", "Caption")
                    .with("keepNext", "" + above)
                    .trackedProperties(inserted, newRevision("format"));
            document = document.blocks(document.blocks.set(at, formatted), document.nextId);
          } else
            document = document.blocks(document.blocks.insert(at, caption), document.nextId + 1);
          mapFields(SequenceFields.resolver(document));
          int end = document.paragraph(at).text.length();
          selection = new Selection(at, end, end);
          insertionStyle = null;
        });
  }

  public void insertNote(final String kind, final String text, final String mark) {
    transact(
        s -> {
          TextStyle typing = activeStyle();
          long next = document.nextId;
          String id = "n" + next++;
          Sequence<Paragraph> story = new Sequence<Paragraph>();
          for (String line : TextUtil.split(text, '\n'))
            story =
                story.append(
                    new Paragraph(
                            "p" + next++, RichText.of(line, TextStyle.DEFAULT.with("size", "10")))
                        .with("after", "0")
                        .with("spacing", "1"));
          if (story.size() == 0) story = story.append(new Paragraph("p" + next++, new RichText()));
          Note note = new Note(id, kind, mark, mark.isEmpty() ? "1" : mark, story);
          document = document.blocks(document.blocks, next);
          replace(InlineField.OBJECT);
          int block = selection.block, at = selection.caret - 1;
          Paragraph p = document.paragraph(block);
          document =
              document.blocks(
                  document.blocks.set(
                      block,
                      p.text(
                          p.text.replace(
                              at,
                              at + 1,
                              RichText.note(
                                  note, p.text.styleAt(at).with("script", "superscript"))))),
                  document.nextId);
          insertionStyle = typing;
        });
  }

  public void updateNote(final String id, final Note value) {
    transact(
        s -> {
          int block = cellBlock(), row = cellRow(), column = cellColumn();
          Selection selected = selection;
          leaveCell();
          final boolean[] found = {false};
          document =
              Notes.map(
                  document,
                  note -> {
                    if (!note.id.equals(id)) return note;
                    if (!id.equals(value.id))
                      throw new IllegalArgumentException("Note identity changed");
                    found[0] = true;
                    return value;
                  });
          if (!found[0]) throw new IllegalArgumentException("Unknown note");
          long next = Revisions.nextId(document);
          for (Paragraph p : value.paragraphs) {
            if (p.id.startsWith("p") && TextUtil.digits(p.id, 1))
              next = Math.max(next, Long.parseLong(p.id.substring(1)) + 1);
          }
          document = document.blocks(document.blocks, next);
          if (block >= 0) enterCell(block, row, column);
          selection = selected;
        });
  }

  /** Select the atomic reference, including references inside table cells. */
  public void selectNote(String id) {
    leaveCell();
    for (int b = 0; b < document.blocks.size(); b++) {
      Block block = document.blocks.get(b);
      if (block instanceof Paragraph) {
        int offset = noteOffset((Paragraph) block, id);
        if (offset >= 0) {
          select(b, offset, offset + 1);
          return;
        }
      } else if (block instanceof Table) {
        Table table = (Table) block;
        for (int r = 0; r < table.rows(); r++)
          for (int c = 0; c < table.columns(); c++) {
            if (table.covered(r, c)) continue;
            Sequence<Paragraph> cell = table.cell(r, c);
            for (int p = 0; p < cell.size(); p++) {
              int offset = noteOffset(cell.get(p), id);
              if (offset >= 0) {
                enterCell(b, r, c);
                select(p, offset, offset + 1);
                return;
              }
            }
          }
      }
    }
    throw new IllegalArgumentException("Unknown note");
  }

  public void nextNote(boolean previous) {
    List<Notes.Reference> refs = Notes.references(document());
    if (refs.isEmpty()) return;
    int b = inCell() ? cellBlock() : selection.block, r = cellRow(), c = cellColumn();
    int p = inCell() ? selection.block : 0, offset = previous ? selection.start() : selection.end();
    Notes.Reference target = previous ? refs.get(refs.size() - 1) : refs.get(0);
    for (int i = 0; i < refs.size(); i++) {
      Notes.Reference ref = refs.get(previous ? refs.size() - i - 1 : i);
      int compare = ref.compare(b, r, c, p, offset);
      if (previous ? compare < 0 : compare >= 0) {
        target = ref;
        break;
      }
    }
    selectNote(target.note.id);
  }

  private static int noteOffset(Paragraph p, String id) {
    final int[] result = {-1};
    p.text.mapNotes(
        (offset, note) -> {
          if (note.id.equals(id)) result[0] = offset;
          return note;
        });
    return result[0];
  }

  public void deleteNote(String id) {
    transact(
        s -> {
          selectNote(id);
          replace("");
        });
  }

  public void insertField(final InlineField field) {
    transact(
        s -> {
          TextStyle style = activeStyle();
          replace(InlineField.OBJECT);
          int block = selection.block, at = selection.caret - 1;
          Paragraph paragraph = document.paragraph(block);
          style = paragraph.text.styleAt(at);
          document =
              document.blocks(
                  document.blocks.set(
                      block,
                      paragraph.text(
                          paragraph.text.replace(at, at + 1, RichText.field(field, style)))),
                  document.nextId);
        });
  }

  public void insertPicture(
      final byte[] bytes, final float width, final float height, final String alt) {
    if (inCell())
      throw new IllegalArgumentException("Pictures inside table cells are not supported yet");
    final Picture input = new Picture("pending", bytes, width, height, alt, "left");
    transact(
        s -> {
          replace("\n");
          int at = selection.block;
          long id = document.nextId;
          document = document.blocks(document.blocks.insert(at, input.copy("i" + id)), id + 1);
          selection = new Selection(at + 1, 0, 0);
        });
  }

  public void updatePicture(
      final int block,
      final float width,
      final float height,
      final String alt,
      final String align,
      final boolean delete) {
    leaveCell();
    transact(
        s -> {
          Picture p = (Picture) document.blocks.get(block);
          document =
              document.blocks(
                  delete
                      ? document.blocks.remove(block)
                      : document.blocks.set(block, p.resize(width, height, alt, align)),
                  document.nextId);
          selectFirst();
        });
  }

  /** Inserts a prepared picture or drawing at the selection; returns its block index. */
  public int insertPicture(final Picture input) {
    if (inCell())
      throw new IllegalArgumentException("Pictures inside table cells are not supported yet");
    final int[] at = {0};
    transact(
        s -> {
          // An empty caret paragraph stays after the object instead of being split again.
          boolean empty =
              selection.collapsed() && document.paragraph(selection.block).text.length() == 0;
          if (!empty) replace("\n");
          at[0] = selection.block;
          long id = document.nextId;
          document = document.blocks(document.blocks.insert(at[0], input.copy("i" + id)), id + 1);
          selection = new Selection(at[0] + 1, 0, 0);
        });
    return at[0];
  }

  /** Replaces the picture at {@code block} (same id) as one undo step, keeping the selection. */
  public void replacePicture(final int block, final Picture next) {
    if (!(document.blocks.get(block) instanceof Picture))
      throw new IllegalArgumentException("Select a picture");
    leaveCell();
    transact(s -> document = document.blocks(document.blocks.set(block, next), document.nextId));
  }

  /** Replace a picture's crop, border and orientation as one undo step. */
  public void pictureLook(final int block, final Picture.Look look) {
    if (!(document.blocks.get(block) instanceof Picture))
      throw new IllegalArgumentException("Select a picture");
    leaveCell();
    transact(
        s ->
            document =
                document.blocks(
                    document.blocks.set(block, ((Picture) document.blocks.get(block)).look(look)),
                    document.nextId));
  }

  /** Inserts a template at the text selection, preserving the prefix and suffix paragraphs. */
  public void insertQuickTable(final String kind) {
    if (inCell()) throw new IllegalArgumentException("Nested tables are not supported yet");
    // Validate before deleting selected text or creating an undo entry.
    TableTemplates.create(kind, 1);
    transact(
        s -> {
          replace("\n");
          int at = selection.block;
          Table table = TableTemplates.create(kind, document.nextId);
          document =
              document.blocks(
                  document.blocks.insert(at, table),
                  document.nextId + 1 + table.rows() * table.columns());
          selection = new Selection(at + 1, 0, 0);
          enterCell(at, 0, 0);
        });
  }

  public void insertTable(final int rows, final int columns) {
    if (inCell()) throw new IllegalArgumentException("Nested tables are not supported yet");
    if (rows < 1 || rows > 100 || columns < 1 || columns > 30)
      throw new IllegalArgumentException("Table size outside 1..100 by 1..30");
    transact(
        new Edit() {
          public void apply(Session s) {
            replace("\n");
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
            int i = selection.block;
            Sequence<Block> blocks =
                document.blocks.insert(i, new Table(tid, cells));
            document = document.blocks(blocks, id);
            selection = new Selection(i + 1, 0, 0);
            enterCell(i, 0, 0);
          }
        });
  }

  public void setCell(final int block, final int row, final int column, final String text) {
    leaveCell();
    transact(
        new Edit() {
          public void apply(Session s) {
            Table t = (Table) document.blocks.get(block);
            Paragraph p = t.cell(row, column).get(0);
            Sequence<Paragraph> paragraphs = new Sequence<Paragraph>();
            long id = document.nextId;
            String[] lines = TextUtil.split(text.replace("\r\n", "\n").replace('\r', '\n'), '\n');
            for (int i = 0; i < lines.length; i++)
              paragraphs =
                  paragraphs.append(
                      i == 0
                          ? p.text(RichText.of(lines[i], p.text.styleAt(0)))
                          : p.copy("p" + id++, RichText.of(lines[i], p.text.styleAt(0))));
            Table next = t.setCell(row, column, paragraphs);
            document = document.blocks(document.blocks.set(block, next), id);
          }
        });
  }

  public void formatTable(final int block, final TableFormat format) {
    transact(
        s -> {
          int cellBlock = cellBlock(), row = cellRow(), column = cellColumn();
          CellSelection selectedCells = cells;
          Selection selected = selection;
          leaveCell();
          Table table = (Table) document.blocks.get(block);
          document =
              document.blocks(document.blocks.set(block, table.format(format)), document.nextId);
          if (cellBlock == block) {
            enterCell(block, row, column);
            selection = selected;
            cells = selectedCells;
          }
        });
  }

  public void tableStructure(final int block, final String action, final int index) {
    transact(
        s -> {
          leaveCell();
          Table table = (Table) document.blocks.get(block), next = table;
          long id = document.nextId;
          if ("insertRow".equals(action) || "insertColumn".equals(action)) {
            int count = "insertRow".equals(action) ? table.columns() : table.rows();
            if ("insertRow".equals(action) ? table.rows() >= 1000 : table.columns() >= 100)
              throw new IllegalArgumentException("Table size limit reached");
            List<Sequence<Paragraph>> cells = new ArrayList<Sequence<Paragraph>>();
            for (int i = 0; i < count; i++)
              cells.add(
                  new Sequence<Paragraph>().append(new Paragraph("p" + id++, new RichText())));
            next =
                "insertRow".equals(action)
                    ? table.insertRow(index, cells)
                    : table.insertColumn(index, cells);
          } else if ("deleteRow".equals(action)) next = table.deleteRow(index);
          else if ("deleteColumn".equals(action)) next = table.deleteColumn(index);
          else throw new IllegalArgumentException("Unknown table operation");
          document = document.blocks(document.blocks.set(block, next), id);
        });
  }

  public void mergeCells(
      final int block, final int row, final int column, final int rows, final int columns) {
    transact(
        s -> {
          leaveCell();
          Table t = (Table) document.blocks.get(block);
          long[] id = {document.nextId};
          Table merged = t.merge(row, column, rows, columns, id);
          document = document.blocks(document.blocks.set(block, merged), id[0]);
        });
  }

  public void splitCell(final int block, final int row, final int column) {
    transact(
        s -> {
          leaveCell();
          Table t = (Table) document.blocks.get(block);
          document =
              document.blocks(document.blocks.set(block, t.split(row, column)), document.nextId);
        });
  }

  /** Split one owning cell into equal-width columns, retaining its content in the first. */
  public void splitCell(final int block, final int row, final int column, final int columns) {
    transact(
        s -> {
          leaveCell();
          Table table = (Table) document.blocks.get(block);
          Table.Merge owner = table.span(row, column);
          long[] id = {document.nextId};
          Table next = TableSplitting.columns(table, row, column, columns, id);
          document = document.blocks(document.blocks.set(block, next), id[0]);
          enterCell(block, owner.row, owner.column);
        });
  }

  /** Start a second table at row, separated by a paragraph that receives the caret. */
  public void splitTable(final int block, final int row) {
    transact(
        s -> {
          leaveCell();
          Table table = (Table) document.blocks.get(block);
          if (row <= 0 || row >= table.rows())
            throw new IllegalArgumentException("Split Table requires a row after the first row");
          long id = document.nextId;
          Table head = TableSplitting.rows(table, 0, row, table.id());
          Paragraph separator = new Paragraph("p" + id++, new RichText());
          Table tail = TableSplitting.rows(table, row, table.rows(), "t" + id++);
          document =
              document.blocks(
                  document
                      .blocks
                      .set(block, head)
                      .insert(block + 1, separator)
                      .insert(block + 2, tail),
                  id);
          selection = new Selection(block + 1, 0, 0);
        });
  }

  public void sortTable(
      final int block,
      final int column,
      final boolean descending,
      final boolean header,
      final boolean numeric) {
    transact(
        s -> {
          leaveCell();
          Table t = (Table) document.blocks.get(block);
          document =
              document.blocks(
                  document.blocks.set(block, t.sorted(column, descending, header, numeric)),
                  document.nextId);
        });
  }

  public void tableToText(final int block, final String separator) {
    transact(
        s -> {
          leaveCell();
          Table t = (Table) document.blocks.get(block);
          Sequence<Block> blocks = document.blocks.remove(block);
          long id = document.nextId;
          for (int r = 0; r < t.rows(); r++) {
            RichText text = new RichText();
            for (int c = 0; c < t.columns(); c++) {
              if (c > 0) text = text.concat(RichText.of(separator));
              int n = 0;
              for (Paragraph p : t.cell(r, c)) {
                if (n++ > 0) text = text.concat(RichText.of("\n"));
                text = text.concat(p.text);
              }
            }
            blocks = blocks.insert(block + r, new Paragraph("p" + id++, text));
          }
          document = document.blocks(blocks, id);
          selection = new Selection(block, 0, 0);
        });
  }

  public void deleteTable(final int block) {
    transact(
        s -> {
          leaveCell();
          if (!(document.blocks.get(block) instanceof Table))
            throw new IllegalArgumentException("Select a table");
          Sequence<Block> blocks = document.blocks.remove(block);
          long id = document.nextId;
          if (blocks.size() == 0) blocks = blocks.append(new Paragraph("p" + id++, new RichText()));
          document = document.blocks(blocks, id);
          selectFirst();
        });
  }

  /** A rich clipboard fragment. Blocks remain immutable; paste allocates fresh IDs. */
  public Document copySelection() {
    if (cells != null) {
      Table table = (Table) document().blocks.get(cells.block);
      return new Document(
              new Sequence<Block>().append(table.crop(cells)),
              document.page,
              "Clipboard",
              document().nextId)
          .styles(document.styles);
    }
    if (selection.collapsed()) return null;
    Sequence<Block> blocks = new Sequence<Block>();
    for (int i = selection.startBlock(); i <= selection.endBlock(); i++) {
      Block b = document.blocks.get(i);
      if (b instanceof Paragraph) {
        Paragraph p = (Paragraph) b;
        b =
            p.text(
                p.text.slice(
                    i == selection.startBlock() ? selection.start() : 0,
                    i == selection.endBlock() ? selection.end() : p.text.length()));
      }
      blocks = blocks.append(b);
    }
    Map<String, Section> sections = new LinkedHashMap<String, Section>();
    for (int i = selection.startBlock(); i < selection.endBlock(); i++) {
      String id = document.blocks.get(i).id();
      if (document.sections.containsKey(id)) sections.put(id, document.sectionAt(i).resolved);
    }
    Document fragment = new Document(blocks, document.page, "Clipboard", document.nextId);
    return fragment.sections(sections, fragment.lastSection);
  }

  private void trackedPaste(Document fragment) {
    for (Block block : fragment.blocks)
      if (!(block instanceof Paragraph))
        throw new IllegalArgumentException("Tracked paste currently requires paragraph content");
    if (!fragment.sections.isEmpty())
      throw new IllegalArgumentException("Tracked section paste is not supported yet");
    trackedReplace("");
    Document accepted = Revisions.resolve(fragment, null, true);
    final Revision mark = newRevision("insert");
    int block = selection.block, at = selection.caret;
    Paragraph original = document.paragraph(block);
    RichText prefix = original.text.slice(0, at),
        suffix = original.text.slice(at, original.text.length());
    Sequence<Block> blocks = document.blocks;
    long next = document.nextId;
    int caret = at;
    for (int i = 0; i < accepted.blocks.size(); i++) {
      Paragraph source = (Paragraph) accepted.blocks.get(i);
      boolean last = i == accepted.blocks.size() - 1;
      RichText text = source.text.mapStyles(style -> style.revision(mark));
      if (i == 0) text = prefix.concat(text);
      caret = text.length();
      if (last) text = text.concat(suffix);
      Paragraph inserted =
          (i == 0 ? original : source.copy("p" + next++, text))
              .text(text)
              .breakRevision(last ? original.breakRevision : mark);
      if (i == 0) blocks = blocks.set(block, inserted);
      else blocks = blocks.insert(block + i, inserted);
    }
    Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
    Section boundary = sections.remove(original.id);
    int last = block + accepted.blocks.size() - 1;
    if (boundary != null) sections.put(blocks.get(last).id(), boundary);
    document = document.blocks(blocks, next).sections(sections, document.lastSection);
    selection = new Selection(last, caret, caret);
    insertionStyle = null;
  }

  /** Clipboard TSV fills a table rectangle; ordinary typing continues to insert literal text. */
  public void pasteText(String text) {
    if (!inCell() || text.indexOf('\t') < 0) {
      replace(text);
      return;
    }
    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
    if (normalized.endsWith("\n")) normalized = normalized.substring(0, normalized.length() - 1);
    String[] lines = TextUtil.split(normalized, '\n');
    int columns = TextUtil.split(lines[0], '\t').length;
    List<List<Sequence<Paragraph>>> grid = new ArrayList<List<Sequence<Paragraph>>>();
    long id = 1;
    for (String line : lines) {
      String[] values = TextUtil.split(line, '\t');
      if (values.length != columns)
        throw new IllegalArgumentException("Pasted rows need equal column counts");
      List<Sequence<Paragraph>> row = new ArrayList<Sequence<Paragraph>>();
      for (String value : values)
        row.add(new Sequence<Paragraph>().append(new Paragraph("p" + id++, RichText.of(value))));
      grid.add(row);
    }
    pasteCells(new Table("t" + id, grid));
  }

  private void pasteCells(final Table source) {
    final int block = cellBlock();
    final int row = cells == null ? cellRow() : cells.firstRow;
    final int column = cells == null ? cellColumn() : cells.firstColumn;
    Table target = (Table) document().blocks.get(block);
    if (row + source.rows() > target.rows()
        || column + source.columns() > target.columns()
        || cells != null
            && (source.rows() != cells.lastRow - cells.firstRow + 1
                || source.columns() != cells.lastColumn - cells.firstColumn + 1))
      throw new IllegalArgumentException("Pasted table must fit the selected cells");
    // Validate the entire grid before editing any cell. Never split an existing merged cell.
    for (int r = 0; r < source.rows(); r++)
      for (int c = 0; c < source.columns(); c++) {
        Table.Merge a = source.span(r, c), b = target.span(row + r, column + c);
        if (a.row + row != b.row
            || a.column + column != b.column
            || a.rows != b.rows
            || a.columns != b.columns)
          throw new IllegalArgumentException(
              "Pasted and destination merged-cell shapes must match");
      }
    transact(
        s -> {
          for (int r = 0; r < source.rows(); r++)
            for (int c = 0; c < source.columns(); c++) {
              if (source.covered(r, c)) continue;
              enterCell(block, row + r, column + c);
              selectWholeCell();
              Sequence<Block> paragraphs = new Sequence<Block>();
              for (Paragraph paragraph : source.cell(r, c))
                paragraphs = paragraphs.append(paragraph);
              paste(new Document(paragraphs, document.page, "Clipboard", document.nextId));
            }
          selectCells(block, row, column, row + source.rows() - 1, column + source.columns() - 1);
        });
  }

  /**
   * Moves the selection to a block/offset outside it as one undoable step (drag inside the
   * document). The target is given in pre-move coordinates.
   */
  public void moveSelectionTo(final int block, final int offset, final Document fragment) {
    final Selection from = selection;
    transact(
        s -> {
          int sb = from.startBlock(), eb = from.endBlock(), start = from.start(), end = from.end();
          replace("");
          int targetBlock = block, targetOffset = offset;
          boolean after = block > eb || (block == eb && offset >= end);
          if (after && block == eb) {
            targetBlock = sb;
            targetOffset = start + (offset - end);
          } else if (after) targetBlock = block - (eb - sb);
          select(targetBlock, targetOffset, targetOffset);
          paste(fragment);
        });
  }

  public void paste(final Document fragment) {
    if (fragment == null) return;
    if (inCell() && fragment.blocks.size() == 1 && fragment.blocks.get(0) instanceof Table) {
      pasteCells((Table) fragment.blocks.get(0));
      return;
    }
    if (cells != null) {
      // Validate first; transaction rollback also preserves the original cell rectangle.
      for (Block block : fragment.blocks)
        if (!(block instanceof Paragraph))
          throw new IllegalArgumentException("Cannot paste nested tables or pictures into cells");
      transact(
          s -> {
            editCells(x -> x.replace(""), false);
            paste(fragment);
          });
      return;
    }
    if (document.trackChanges) {
      transact(s -> trackedPaste(fragment));
      return;
    }
    if (inCell() && !fragment.sections.isEmpty())
      throw new IllegalArgumentException("Section breaks belong in the document body");
    if (inCell())
      for (Block b : fragment.blocks)
        if (!(b instanceof Paragraph))
          throw new IllegalArgumentException("Nested tables are not supported yet");
    transact(
        s -> {
          Document original = document;
          Selection replaced = selection;
          int first = selection.startBlock(), start = selection.start();
          Paragraph before = document.paragraph(first),
              after = document.paragraph(selection.endBlock());
          RichText prefix = before.text.slice(0, start),
              suffix = after.text.slice(selection.end(), after.text.length());
          int last = selection.endBlock();
          Sequence<Block> blocks = document.blocks;
          for (int i = last; i >= first; i--) blocks = blocks.remove(i);
          long id = document.nextId;
          List<Block> inserted = new ArrayList<Block>();
          Map<String, Section> pastedSections = new LinkedHashMap<String, Section>();
          for (Block source : fragment.blocks) {
            if (source instanceof Paragraph) {
              Paragraph p = (Paragraph) source;
              Paragraph copy =
                  p.copy("p" + id++, p.text).with("listStart", String.valueOf(p.listStart));
              inserted.add(copy);
              Section section = fragment.sections.get(p.id);
              if (section != null) {
                PageFurniture furniture = section.furniture;
                for (String key : PageFurniture.KEYS)
                  if (furniture.stories.containsKey(key)) {
                    Sequence<Paragraph> story = new Sequence<Paragraph>();
                    for (Paragraph para : furniture.get(key))
                      story = story.append(para.copy("p" + id++, para.text));
                    furniture = furniture.story(key, story);
                  }
                pastedSections.put(copy.id, section.furniture(furniture));
              }
            } else if (source instanceof Table) {
              Table table = (Table) source;
              List<List<Sequence<Paragraph>>> cells = new ArrayList<List<Sequence<Paragraph>>>();
              for (int r = 0; r < table.rows(); r++) {
                List<Sequence<Paragraph>> row = new ArrayList<Sequence<Paragraph>>();
                for (int c = 0; c < table.columns(); c++) {
                  Sequence<Paragraph> cell = new Sequence<Paragraph>();
                  for (Paragraph p : table.cell(r, c))
                    cell = cell.append(p.copy("p" + id++, p.text));
                  row.add(cell);
                }
                cells.add(row);
              }
              inserted.add(new Table("t" + id++, cells, table.merges, table.format));
            } else if (source instanceof Picture) inserted.add(((Picture) source).copy("i" + id++));
          }
          if (inserted.get(0) instanceof Paragraph) {
            Paragraph p = (Paragraph) inserted.get(0);
            inserted.set(0, (start > 0 ? before : p).text(prefix.concat(p.text)));
            if (start > 0 && pastedSections.containsKey(p.id))
              pastedSections.put(before.id, pastedSections.remove(p.id));
          } else inserted.add(0, before.text(prefix));
          int caretBlock = first + inserted.size() - 1, caret;
          if (inserted.get(inserted.size() - 1) instanceof Paragraph) {
            Paragraph p = (Paragraph) inserted.get(inserted.size() - 1);
            caret = p.text.length();
            inserted.set(inserted.size() - 1, p.text(p.text.concat(suffix)));
          } else {
            inserted.add(after.copy("p" + id++, suffix));
            caretBlock++;
            caret = 0;
          }
          for (int i = 0; i < inserted.size(); i++)
            blocks = blocks.insert(first + i, inserted.get(i));
          document = document.blocks(blocks, id);
          Map<String, Section> sections = new LinkedHashMap<String, Section>(document.sections);
          for (int i = first; i <= last; i++) sections.remove(original.blocks.get(i).id());
          sections.putAll(pastedSections);
          Section tail = original.sections.get(after.id);
          if (tail != null) sections.put(blocks.get(caretBlock).id(), tail);
          document = document.sections(sections, document.lastSection);
          selection = new Selection(caretBlock, caret, caret);
          if (!original.comments.isEmpty())
            document =
                document.comments(ReviewComment.reanchor(original, document, replaced, selection));
          if (!original.bookmarks.isEmpty())
            document =
                document.bookmarks(Bookmark.reanchor(original, document, replaced, selection));
          insertionStyle = null;
        });
  }

  /**
   * Interactive Enter: continue lists, exit empty list items, and follow headings with body text.
   */
  public void enterParagraph() {
    if (cells != null) {
      replaceCells("\n");
      return;
    }
    transact(
        s -> {
          Paragraph p = document.paragraph(selection.block);
          if (selection.collapsed() && p.text.length() == 0 && !p.list.isEmpty()) {
            if (p.listLevel > 0) paragraph("listLevel", String.valueOf(p.listLevel - 1));
            else {
              paragraph("list", "");
              paragraph("listStart", "0");
            }
            return;
          }
          boolean follow =
              selection.collapsed()
                  && selection.caret == p.text.length()
                  && p.style.startsWith("Heading");
          replace("\n");
          if (follow) {
            Paragraph next =
                document
                    .paragraph(selection.block)
                    .with("style", "Normal")
                    .with("keepNext", "false");
            next = next.text(RichText.of("", TextStyle.DEFAULT));
            document = document.blocks(document.blocks.set(selection.block, next), document.nextId);
            insertionStyle = null;
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
    breakTypingGroup();
    finishComposition();
    if (transactionDepth > 0) throw new IllegalStateException("Undo inside transaction");
    if (undo.isEmpty()) return;
    redo.add(state());
    State s = undo.remove(undo.size() - 1);
    restore(s);
    revision++;
  }

  public void redo() {
    breakTypingGroup();
    finishComposition();
    if (transactionDepth > 0) throw new IllegalStateException("Redo inside transaction");
    if (redo.isEmpty()) return;
    undo.add(state());
    State s = redo.remove(redo.size() - 1);
    restore(s);
    revision++;
  }

  public void applyPreview(final Session preview, long expectedRevision) {
    if (revision != expectedRevision)
      throw new IllegalStateException("Document changed since preview");
    transact(
        new Edit() {
          public void apply(Session s) {
            document = preview.document;
            mergeData = preview.mergeData;
            selection = preview.selection;
            insertionStyle = preview.insertionStyle;
            cell = preview.cell;
            cells = preview.cells;
            integratedSource = null;
          }
        });
  }
}
