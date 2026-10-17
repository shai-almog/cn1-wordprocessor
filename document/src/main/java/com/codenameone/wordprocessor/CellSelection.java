package com.codenameone.wordprocessor;

/** Immutable rectangular cell selection. Merged cells are always selected whole. */
public final class CellSelection {
  public final int block, anchorRow, anchorColumn, focusRow, focusColumn;
  public final int firstRow, lastRow, firstColumn, lastColumn;

  public CellSelection(int block, Table table, int ar, int ac, int fr, int fc) {
    if (block < 0
        || ar < 0
        || ar >= table.rows()
        || fr < 0
        || fr >= table.rows()
        || ac < 0
        || ac >= table.columns()
        || fc < 0
        || fc >= table.columns())
      throw new IllegalArgumentException("Cell selection is outside the table");
    this.block = block;
    anchorRow = ar;
    anchorColumn = ac;
    focusRow = fr;
    focusColumn = fc;
    int top = Math.min(ar, fr), bottom = Math.max(ar, fr);
    int left = Math.min(ac, fc), right = Math.max(ac, fc);
    boolean changed;
    do {
      changed = false;
      for (Table.Merge span : table.merges) {
        if (span.row <= bottom
            && span.row + span.rows > top
            && span.column <= right
            && span.column + span.columns > left) {
          int nt = Math.min(top, span.row), nb = Math.max(bottom, span.row + span.rows - 1);
          int nl = Math.min(left, span.column),
              nr = Math.max(right, span.column + span.columns - 1);
          changed |= nt != top || nb != bottom || nl != left || nr != right;
          top = nt;
          bottom = nb;
          left = nl;
          right = nr;
        }
      }
    } while (changed);
    firstRow = top;
    lastRow = bottom;
    firstColumn = left;
    lastColumn = right;
  }

  public boolean contains(int block, int row, int column) {
    return this.block == block
        && row >= firstRow
        && row <= lastRow
        && column >= firstColumn
        && column <= lastColumn;
  }

  public String key() {
    return block + ":" + anchorRow + ":" + anchorColumn + ":" + focusRow + ":" + focusColumn;
  }
}
