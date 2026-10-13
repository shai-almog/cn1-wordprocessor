package com.codenameone.wordprocessor;

import java.util.*;

/** Immutable rectangular table; cells contain paragraph sequences. */
public final class Table implements Block {
  private final String id;
  private final List<List<Sequence<Paragraph>>> cells;

  public Table(String id, List<List<Sequence<Paragraph>>> cells) {
    if (id == null || cells.isEmpty() || cells.get(0).isEmpty())
      throw new IllegalArgumentException("Empty table");
    this.id = id;
    int cols = cells.get(0).size();
    List<List<Sequence<Paragraph>>> c = new ArrayList<List<Sequence<Paragraph>>>();
    for (List<Sequence<Paragraph>> row : cells) {
      if (row.size() != cols) throw new IllegalArgumentException("Ragged table");
      List<Sequence<Paragraph>> copy = new ArrayList<Sequence<Paragraph>>();
      for (Sequence<Paragraph> cell : row) {
        if (cell == null || cell.size() == 0) throw new IllegalArgumentException("Empty cell");
        copy.add(cell);
      }
      c.add(Collections.unmodifiableList(copy));
    }
    this.cells = Collections.unmodifiableList(c);
  }

  public String id() {
    return id;
  }

  public int rows() {
    return cells.size();
  }

  public int columns() {
    return cells.get(0).size();
  }

  public Sequence<Paragraph> cell(int r, int c) {
    return cells.get(r).get(c);
  }

  public Table setCell(int r, int c, Sequence<Paragraph> value) {
    List<List<Sequence<Paragraph>>> copy = new ArrayList<List<Sequence<Paragraph>>>(cells);
    List<Sequence<Paragraph>> row = new ArrayList<Sequence<Paragraph>>(copy.get(r));
    row.set(c, value);
    copy.set(r, row);
    return new Table(id, copy);
  }
}
