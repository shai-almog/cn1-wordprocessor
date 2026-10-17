package com.codenameone.wordprocessor;

import java.util.*;

/** Produces editable tracked text changes without changing either input document. */
public final class DocumentComparison {
  private DocumentComparison() {}

  public static final class Options {
    public boolean characters, ignoreCase, ignoreWhiteSpace;
    public boolean formatting = true;
    public String author = "Reviewer";
  }

  public static final class Result {
    public final Document document;
    public final int changes;
    public final boolean coarse;

    Result(Document document, int changes, boolean coarse) {
      this.document = document;
      this.changes = changes;
      this.coarse = coarse;
    }
  }

  private static void supported(Document d) {
    if (!d.sections.isEmpty())
      throw new IllegalArgumentException("Comparison of section breaks is not supported yet");
    if (!d.comments.isEmpty() || !d.bookmarks.isEmpty())
      throw new IllegalArgumentException(
          "Comparison of comment or bookmark ranges is not supported yet");
    if (!Revisions.list(d).isEmpty())
      throw new IllegalArgumentException(
          "Accept or reject existing changes before comparing documents");
    for (Block b : d.blocks) {
      if (!(b instanceof Paragraph))
        throw new IllegalArgumentException(
            "Comparison currently requires paragraphs; tables and pictures are not"
                + " supported yet");
      Paragraph p = (Paragraph) b;
      for (int i = 0; i < p.text.length(); i++)
        if (p.text.objectAt(i))
          throw new IllegalArgumentException(
              "Comparison of inline fields or notes is not supported yet");
    }
  }

  private static int[] position(Document d, int offset) {
    for (int i = 0; i < d.blocks.size(); i++) {
      int length = d.paragraph(i).text.length();
      if (offset <= length) return new int[] {i, offset};
      offset -= length + 1;
    }
    throw new IllegalArgumentException("Comparison offset is outside document");
  }

  private static Document fragment(Document d, int from, int to) {
    int[] a = position(d, from), b = position(d, to);
    Sequence<Block> blocks = new Sequence<Block>();
    for (int i = a[0]; i <= b[0]; i++) {
      Paragraph p = d.paragraph(i);
      blocks =
          blocks.append(
              p.text(p.text.slice(i == a[0] ? a[1] : 0, i == b[0] ? b[1] : p.text.length())));
    }
    return new Document(blocks, d.page, "Comparison fragment", d.nextId);
  }

  private static String date() {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    return TextUtil.padded(c.get(Calendar.YEAR), 10, 4)
        + "-"
        + TextUtil.padded(c.get(Calendar.MONTH) + 1, 10, 2)
        + "-"
        + TextUtil.padded(c.get(Calendar.DAY_OF_MONTH), 10, 2)
        + "T"
        + TextUtil.padded(c.get(Calendar.HOUR_OF_DAY), 10, 2)
        + ":"
        + TextUtil.padded(c.get(Calendar.MINUTE), 10, 2)
        + ":"
        + TextUtil.padded(c.get(Calendar.SECOND), 10, 2)
        + "Z";
  }

  private static Document formatting(
      Document original,
      Document revised,
      TextComparison.Result diff,
      Options options,
      int[] count) {
    if (diff.edits.isEmpty() && original.plainText().isEmpty() && revised.plainText().isEmpty())
      diff =
          new TextComparison.Result(
              Arrays.asList(new TextComparison.Edit(0, 0, 0, 0, true)), false);
    Sequence<Block> blocks = original.blocks;
    long next = original.nextId;
    int offset = 0, edit = 0;
    for (int block = 0; block < blocks.size(); block++) {
      Paragraph old = original.paragraph(block);
      int end = offset + old.text.length();
      while (edit < diff.edits.size() && diff.edits.get(edit).to < end) edit++;
      if (edit < diff.edits.size()) {
        TextComparison.Edit e = diff.edits.get(edit);
        if (e.same
            && e.from <= offset
            && e.to >= end
            && e.to - e.from == e.revisedTo - e.revisedFrom) {
          int[] mapped = position(revised, e.revisedFrom + offset - e.from);
          Paragraph fresh = revised.paragraph(mapped[0]);
          if (mapped[1] == 0 && fresh.text.toString().equals(old.text.toString())) {
            final Revision rev =
                new Revision(Long.toString(next), "format", options.author.trim(), date());
            final RichText[] text = {old.text};
            fresh.text.visit(
                (at, value, style) -> {
                  RichText part = old.text.slice(at, at + value.length());
                  text[0] =
                      text[0].replace(
                          at,
                          at + value.length(),
                          part.mapStyles(
                              previous ->
                                  options.formatting
                                      ? style.clean().trackedProperties(previous, rev)
                                      : style.clean()));
                });
            Paragraph updated = fresh.copy(old.id, text[0]).breakRevision(old.breakRevision);
            if (options.formatting) updated = updated.trackedProperties(old, rev);
            if (updated.formatRevision != null || hasFormatting(updated.text)) {
              next++;
              count[0]++;
            }
            blocks = blocks.set(block, updated);
          }
        }
      }
      offset = end + 1;
    }
    return original.blocks(blocks, next);
  }

  private static boolean hasFormatting(RichText text) {
    final boolean[] found = {false};
    text.visit(
        (at, value, style) -> {
          if (style.formatRevision != null) found[0] = true;
        });
    return found[0];
  }

  private static List<Sequence<Block>> stories(Document d, List<Table> tables) {
    List<Sequence<Block>> result = new ArrayList<Sequence<Block>>();
    Sequence<Block> current = new Sequence<Block>();
    for (Block b : d.blocks) {
      if (b instanceof Paragraph) current = current.append(b);
      else if (b instanceof Table) {
        result.add(current);
        current = new Sequence<Block>();
        tables.add((Table) b);
      } else throw new IllegalArgumentException("Comparison of pictures is not supported yet");
    }
    result.add(current);
    return result;
  }

  private static boolean sameCell(TableFormat.Cell a, TableFormat.Cell b) {
    return a.background == b.background
        && a.borderColor == b.borderColor
        && a.borderWidth == b.borderWidth
        && a.horizontalPadding == b.horizontalPadding
        && a.verticalPadding == b.verticalPadding
        && a.vertical.equals(b.vertical);
  }

  private static void tableGeometry(Table a, Table b) {
    if (a.rows() != b.rows()
        || a.columns() != b.columns()
        || a.merges.size() != b.merges.size()
        || a.format.headerRows != b.format.headerRows)
      throw new IllegalArgumentException(
          "Comparison of table structure changes is not supported yet");
    for (int i = 0; i < a.merges.size(); i++) {
      Table.Merge x = a.merges.get(i), y = b.merges.get(i);
      if (x.row != y.row || x.column != y.column || x.rows != y.rows || x.columns != y.columns)
        throw new IllegalArgumentException(
            "Comparison of merged cell changes is not supported yet");
    }
    for (int row = 0; row < a.rows(); row++) {
      if (a.format.minHeight(row) != b.format.minHeight(row)
          || !a.format.row(row).rule.equals(b.format.row(row).rule)
          || a.format.row(row).allowBreak != b.format.row(row).allowBreak)
        throw new IllegalArgumentException("Comparison of table row heights is not supported yet");
      for (int col = 0; col < a.columns(); col++)
        if (a.format.column(col) != b.format.column(col)
            || !sameCell(a.format.cell(row, col), b.format.cell(row, col)))
          throw new IllegalArgumentException(
              "Comparison of table geometry or cell appearance is not supported yet");
    }
  }

  private static Sequence<Block> blocks(Sequence<Paragraph> story) {
    Sequence<Block> result = new Sequence<Block>();
    for (Paragraph p : story) result = result.append(p);
    return result;
  }

  private static Document story(Document owner, Sequence<Block> blocks, long next) {
    return new Document(blocks, owner.page, "Comparison story", next).styles(owner.styles);
  }

  public static Result compare(Document original, Document revised, Options options) {
    if (original.plainText().length() > 2000000 || revised.plainText().length() > 2000000)
      throw new IllegalArgumentException(
          "Comparison is limited to two million characters per document");
    List<Table> oldTables = new ArrayList<Table>(), newTables = new ArrayList<Table>();
    List<Sequence<Block>> oldStories = stories(original, oldTables),
        newStories = stories(revised, newTables);
    if (oldTables.size() != newTables.size())
      throw new IllegalArgumentException(
          "Comparison of inserted or deleted tables is not supported yet");
    if (oldTables.isEmpty()) return paragraphs(original, revised, options);
    if (!original.sections.isEmpty()
        || !revised.sections.isEmpty()
        || !original.comments.isEmpty()
        || !revised.comments.isEmpty()
        || !original.bookmarks.isEmpty()
        || !revised.bookmarks.isEmpty()
        || !Revisions.list(original).isEmpty()
        || !Revisions.list(revised).isEmpty())
      throw new IllegalArgumentException(
          "Resolve changes and remove section, comment or bookmark ranges before"
              + " comparing tables");
    Sequence<Block> output = new Sequence<Block>();
    long next = original.nextId;
    int changes = 0;
    boolean coarse = false;
    for (int i = 0; i < oldStories.size(); i++) {
      Sequence<Block> a = oldStories.get(i), b = newStories.get(i);
      if (a.size() == 0 || b.size() == 0) {
        if (a.size() != b.size())
          throw new IllegalArgumentException(
              "Comparison of paragraph insertion at a table boundary is not supported" + " yet");
      } else {
        Result result =
            paragraphs(story(original, a, next), story(revised, b, revised.nextId), options);
        for (Block block : result.document.blocks) output = output.append(block);
        next = result.document.nextId;
        changes += result.changes;
        coarse |= result.coarse;
      }
      if (i < oldTables.size()) {
        Table old = oldTables.get(i), fresh = newTables.get(i);
        tableGeometry(old, fresh);
        Table table = old;
        for (int row = 0; row < old.rows(); row++)
          for (int col = 0; col < old.columns(); col++)
            if (!old.covered(row, col)) {
              Result result =
                  paragraphs(
                      story(original, blocks(old.cell(row, col)), next),
                      story(revised, blocks(fresh.cell(row, col)), revised.nextId),
                      options);
              Sequence<Paragraph> cell = new Sequence<Paragraph>();
              for (Block block : result.document.blocks) cell = cell.append((Paragraph) block);
              table = table.setCell(row, col, cell);
              next = result.document.nextId;
              changes += result.changes;
              coarse |= result.coarse;
            }
        output = output.append(table);
      }
    }
    return new Result(original.blocks(output, next), changes, coarse);
  }

  private static Result paragraphs(Document original, Document revised, Options options) {
    supported(original);
    supported(revised);
    if (options.author == null || options.author.trim().isEmpty())
      throw new IllegalArgumentException("Enter a comparison author");
    String before = original.plainText(), after = revised.plainText();
    TextComparison.Result diff =
        TextComparison.compare(
            before, after, options.characters, !options.ignoreCase, !options.ignoreWhiteSpace);
    final int[] formatted = {0};
    Document baseline = formatting(original, revised, diff, options, formatted);
    Session draft = new Session(baseline);
    draft.reviewAuthor(options.author);
    draft.trackChanges(true);
    final int[] changes = {formatted[0]};
    draft.transact(
        ignored -> {
          // Apply backwards: the untouched prefix retains its original offsets even when
          // deleted text remains visible.
          for (int i = diff.edits.size() - 1; i >= 0; i--) {
            TextComparison.Edit e = diff.edits.get(i);
            if (e.same
                && before
                    .substring(e.from, e.to)
                    .equals(after.substring(e.revisedFrom, e.revisedTo))) continue;
            int[] a = position(draft.document(), e.from), b = position(draft.document(), e.to);
            draft.selectRange(a[0], a[1], b[0], b[1]);
            if (e.same) draft.trackChanges(false);
            if (e.revisedFrom == e.revisedTo) draft.replace("");
            else draft.paste(fragment(revised, e.revisedFrom, e.revisedTo));
            if (e.same) draft.trackChanges(true);
            else changes[0]++;
          }
        });
    return new Result(
        draft.document().trackChanges(original.trackChanges), changes[0], diff.coarse);
  }
}
