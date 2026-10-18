package com.codenameone.wordprocessor;

import java.util.*;

/** Editable built-in quick tables, with appearance materialized as ordinary cell formatting. */
public final class TableTemplates {
  private TableTemplates() {}

  public static Table create(String kind, long firstId) {
    String[][] data;
    if ("matrix".equals(kind)) {
      data =
          new String[][] {
            {"", "Column A", "Column B", "Column C"},
            {"Row 1", "1", "2", "3"},
            {"Row 2", "4", "5", "6"},
            {"Row 3", "7", "8", "9"}
          };
    } else if ("tabular".equals(kind)) {
      data =
          new String[][] {
            {"Item", "Needed"},
            {"Books", "1"},
            {"Magazines", "3"},
            {"Notebooks", "1"},
            {"Paper pads", "1"},
            {"Pens", "3"},
            {"Pencils", "2"}
          };
    } else if ("calendar".equals(kind)) {
      // Matches the reference's reusable 31-day sample, not the current month's calendar.
      data = new String[7][7];
      for (String[] row : data) Arrays.fill(row, "");
      data[0] = new String[] {"M", "T", "W", "T", "F", "S", "S"};
      for (int day = 1; day <= 31; day++)
        data[1 + (day + 1) / 7][(day + 1) % 7] = Integer.toString(day);
    } else throw new IllegalArgumentException("Unknown quick table: " + kind);
    boolean calendar = "calendar".equals(kind);
    long id = firstId + 1;
    var format = new TableFormat(data.length, data[0].length).headerRows(1);
    var cells = new ArrayList<List<Sequence<Paragraph>>>();
    for (int r = 0; r < data.length; r++) {
      var row = new ArrayList<Sequence<Paragraph>>();
      for (int c = 0; c < data[r].length; c++) {
        var text = TextStyle.DEFAULT.with("bold", (r == 0 || c == 0) ? "true" : "false");
        if (!calendar && r == 0) text = text.with("color", "ffffff");
        var paragraph =
            new Paragraph("p" + id++, RichText.of(data[r][c], text))
                .with("after", "0")
                .with("spacing", "1");
        row.add(new Sequence<Paragraph>().append(paragraph));
        int background = calendar ? 0xffffff : r == 0 ? 0x156082 : r % 2 == 1 ? 0xc1e4f5 : 0xffffff;
        format =
            format.cell(
                r,
                c,
                new TableFormat.Cell(background, calendar ? 0x999999 : 0xc1e4f5, .5f, 6, 4, "top"));
      }
      cells.add(row);
    }
    return new Table("t" + firstId, cells, Collections.<Table.Merge>emptyList(), format);
  }
}
