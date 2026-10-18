package com.codenameone.wordprocessor;

import java.util.*;

/** Table subdivision preserving existing cell content, geometry and identifiers. */
final class TableSplitting {
  private TableSplitting() {}

  static Table columns(Table table, int row, int column, int count, long[] nextId) {
    if (row < 0 || row >= table.rows() || column < 0 || column >= table.columns())
      throw new IllegalArgumentException("Cell is outside the table");
    if (count < 1 || count > 100) throw new IllegalArgumentException("Use 1 to 100 columns");
    if (count == 1) return table;
    var selected = table.span(row, column);
    double[] old = new double[table.columns() + 1];
    for (int c = 0; c < table.columns(); c++) old[c + 1] = old[c] + table.format.column(c);
    var boundaries = new ArrayList<Double>();
    for (double value : old) boundaries.add(value);
    double start = old[selected.column], end = old[selected.column + selected.columns];
    double[] cuts = new double[count + 1];
    for (int n = 0; n <= count; n++) {
      double cut = start + (end - start) * n / count;
      // Reuse coincident grid lines without changing any pre-existing column width.
      for (double value : old)
        if (Math.abs(value - cut) <= (end - start) * 1e-8) {
          cut = value;
          break;
        }
      cuts[n] = cut;
      if (!boundaries.contains(cut)) boundaries.add(cut);
    }
    Collections.sort(boundaries);
    int columns = boundaries.size() - 1;
    if (columns > 100)
      throw new IllegalArgumentException("Splitting exceeds the 100-column grid limit");
    int[] mapped = new int[old.length];
    for (int c = 0; c < old.length; c++) mapped[c] = boundaries.indexOf(old[c]);
    int[] parts = new int[cuts.length];
    for (int n = 0; n < cuts.length; n++) parts[n] = boundaries.indexOf(cuts[n]);
    float[] weights = new float[columns];
    for (int c = 0; c < columns; c++) {
      weights[c] = (float) (boundaries.get(c + 1) - boundaries.get(c));
      if (weights[c] < .000001f) throw new IllegalArgumentException("Cell is too narrow to split");
    }
    var cells = new ArrayList<List<Sequence<Paragraph>>>();
    TableFormat.Cell[][] appearance = new TableFormat.Cell[table.rows()][columns];
    TableFormat.Row[] heights = new TableFormat.Row[table.rows()];
    var spans = new ArrayList<Table.Merge>();
    for (int r = 0; r < table.rows(); r++) {
      var line = new ArrayList<Sequence<Paragraph>>();
      heights[r] = table.format.row(r);
      for (int c = 0; c < table.columns(); c++) {
        var owner = table.span(r, c);
        for (int j = mapped[c]; j < mapped[c + 1]; j++) {
          line.add(
              j == mapped[c]
                  ? table.cell(r, c)
                  : blank(table.cell(owner.row, owner.column).get(0), nextId));
          appearance[r][j] =
              j == mapped[c] ? table.format.cell(r, c) : table.format.cell(owner.row, owner.column);
        }
        if (table.covered(r, c)) continue;
        if (r == selected.row && c == selected.column) {
          for (int n = 0; n < count; n++)
            spans.add(new Table.Merge(r, parts[n], selected.rows, parts[n + 1] - parts[n]));
        } else
          spans.add(
              new Table.Merge(r, mapped[c], owner.rows, mapped[c + owner.columns] - mapped[c]));
      }
      if (r == selected.row) {
        var prototype = table.cell(selected.row, selected.column).get(0);
        for (int n = 1; n < count; n++) {
          int at = parts[n];
          appearance[r][at] = table.format.cell(selected.row, selected.column);
          var empty = line.get(at).get(0);
          if (Table.cellText(line.get(at)).isEmpty())
            line.set(at, new Sequence<Paragraph>().append(emptyParagraph(prototype, empty.id())));
        }
      }
      cells.add(line);
    }
    return new Table(
        table.id(),
        cells,
        spans,
        TableFormat.geometry(appearance, weights, heights, table.format.headerRows)
            .altText(table.format.title, table.format.description)
            .sizing(table.format.sizing));
  }

  private static Sequence<Paragraph> blank(Paragraph prototype, long[] id) {
    return new Sequence<Paragraph>().append(emptyParagraph(prototype, "p" + id[0]++));
  }

  private static Paragraph emptyParagraph(Paragraph prototype, String id) {
    return prototype
        .copy(id, RichText.of("", prototype.text.styleAt(0)))
        .breakRevision(null)
        .formatRevision(null, null);
  }

  static Table rows(Table table, int first, int end, String id) {
    if (first < 0 || first >= end || end > table.rows())
      throw new IllegalArgumentException("Invalid table split");
    var cells = new ArrayList<List<Sequence<Paragraph>>>();
    TableFormat.Cell[][] appearance = new TableFormat.Cell[end - first][table.columns()];
    float[] weights = new float[table.columns()];
    TableFormat.Row[] heights = new TableFormat.Row[end - first];
    for (int c = 0; c < table.columns(); c++) weights[c] = table.format.column(c);
    for (int r = first; r < end; r++) {
      var line = new ArrayList<Sequence<Paragraph>>();
      heights[r - first] = table.format.row(r);
      for (int c = 0; c < table.columns(); c++) {
        line.add(table.cell(r, c));
        var owner = table.span(r, c);
        appearance[r - first][c] =
            r == first && owner.row < first && c == owner.column
                ? table.format.cell(owner.row, owner.column)
                : table.format.cell(r, c);
      }
      cells.add(line);
    }
    var spans = new ArrayList<Table.Merge>();
    for (Table.Merge span : table.merges) {
      int top = Math.max(first, span.row), bottom = Math.min(end, span.row + span.rows);
      if (top < bottom)
        spans.add(new Table.Merge(top - first, span.column, bottom - top, span.columns));
    }
    return new Table(
        id,
        cells,
        spans,
        TableFormat.geometry(
                appearance,
                weights,
                heights,
                Math.max(0, Math.min(end, table.format.headerRows) - first))
            .altText(table.format.title, table.format.description)
            .sizing(table.format.sizing));
  }
}
