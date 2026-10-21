package com.codenameone.wordprocessor;

import java.util.ArrayList;
import java.util.List;

/** Convert text to a table, delete cells with shifting, and set a column's width in points. */
final class TableTextCommands implements Commands.Extension {
  public void register(final Commands c) {
    c.edit("table.fromText", "separator?:string",
        "Convert the selected paragraphs to a table, splitting at tab, comma or semicolon",
        (s, a) -> {
          String kind = Commands.str(a, "separator", "tab");
          char sep =
              "comma".equals(kind) ? ',' : "semicolon".equals(kind) ? ';' : "tab".equals(kind) ? '\t' : 0;
          if (sep == 0) throw new IllegalArgumentException("Separator must be tab, comma or semicolon");
          // In a cell this converts the cell's paragraphs into a nested table, as Wordcraft does.
          var d = s.editingDocument();
          var x = s.selection();
          int from = x.startBlock(), to = x.endBlock();
          for (int i = from; i <= to; i++)
            if (!(d.blocks.get(i) instanceof Paragraph))
              throw new IllegalArgumentException("Select paragraphs only");
          long id = d.nextId;
          var rows = new ArrayList<List<Sequence<Paragraph>>>();
          int columns = 1;
          for (int i = from; i <= to; i++) {
            var p = d.paragraph(i);
            var text = p.text.plainText();
            var row = new ArrayList<Sequence<Paragraph>>();
            int start = 0;
            for (int k = 0; k <= text.length(); k++)
              if (k == text.length() || text.charAt(k) == sep) {
                row.add(
                    new Sequence<Paragraph>()
                        .append(new Paragraph("p" + id++, p.text.slice(start, k))));
                start = k + 1;
              }
            columns = Math.max(columns, row.size());
            rows.add(row);
          }
          if (columns > 30 || rows.size() > 100)
            throw new IllegalArgumentException("Table size outside 1..100 by 1..30");
          for (List<Sequence<Paragraph>> row : rows)
            while (row.size() < columns)
              row.add(new Sequence<Paragraph>().append(new Paragraph("p" + id++, new RichText())));
          Sequence<Block> blocks = d.blocks;
          for (int i = to; i >= from; i--) blocks = blocks.remove(i);
          blocks = blocks.insert(from, new Table("t" + id++, rows));
          // A table cannot end the document; keep a paragraph after it.
          if (from + 1 >= blocks.size() || !(blocks.get(from + 1) instanceof Paragraph))
            blocks = blocks.insert(from + 1, new Paragraph("p" + id++, new RichText()));
          s.replaceStory(d.blocks(blocks, id));
          s.enterNestedCell(from, 0, 0);
          return Json.object("rows", rows.size(), "columns", columns);
        });
    c.edit("table.deleteCells", "shift:string",
        "Delete the caret cell: shift left, shift up, or delete the whole row or column",
        (s, a) -> {
          if (!s.inCell()) throw new IllegalArgumentException("Place the caret in a table");
          String shift = Commands.str(a, "shift");
          int block = s.cellBlock(), row = s.cellRow(), col = s.cellColumn();
          if ("row".equals(shift)) return c.execute(s, "table.deleteRow");
          if ("column".equals(shift)) return c.execute(s, "table.deleteColumn");
          var t = (Table) s.document().blocks.get(block);
          if (!t.merges.isEmpty())
            throw new IllegalArgumentException("Unmerge cells before shifting");
          long id = s.document().nextId;
          if ("left".equals(shift)) {
            for (int k = col; k + 1 < t.columns(); k++) t = t.setCell(row, k, t.cell(row, k + 1));
            t = t.setCell(row, t.columns() - 1, empty("p" + id++));
          } else if ("up".equals(shift)) {
            for (int k = row; k + 1 < t.rows(); k++) t = t.setCell(k, col, t.cell(k + 1, col));
            t = t.setCell(t.rows() - 1, col, empty("p" + id++));
          } else throw new IllegalArgumentException("Shift must be left, up, row or column");
          s.leaveCell();
          s.replaceDocument(s.document().blocks(s.document().blocks.set(block, t), id));
          s.enterCell(block, row, col);
          return null;
        });
    c.edit("table.columnWidth", "width:number",
        "Set the caret column's width in points (other columns share the rest)", (s, a) -> {
          if (!s.inCell()) throw new IllegalArgumentException("Place the caret in a table");
          int block = s.cellBlock(), col = s.cellColumn();
          var t = (Table) s.document().blocks.get(block);
          float total = s.activeSection().resolved.page.contentWidth();
          float fraction = ((Number) a.get("width")).floatValue() / total;
          var f = t.format.columnFraction(col, Math.max(.001f, Math.min(.99f, fraction)));
          s.formatTable(block, f);
          return Math.round(f.columnFraction(col) * total);
        });
  }

  private static Sequence<Paragraph> empty(String id) {
    return new Sequence<Paragraph>().append(new Paragraph(id, new RichText()));
  }
}
