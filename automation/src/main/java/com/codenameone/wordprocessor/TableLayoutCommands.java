package com.codenameone.wordprocessor;

/** Contextual table operations shared by the ribbon, macros, CLI and live MCP. */
public final class TableLayoutCommands implements Commands.Extension {
  private static Table table(Session s) {
    if (!s.inCell()) throw new IllegalArgumentException("Click inside a table first");
    return (Table) s.document().blocks.get(s.cellBlock());
  }

  public void register(Commands commands) {
    commands.register(
        "table.rowHeight",
        "Set the current row height",
        "height:number,rule?:string",
        true,
        (s, a) -> {
          Table t = table(s);
          String rule = a.containsKey("rule") ? Commands.str(a, "rule") : "atLeast";
          if (!("atLeast".equals(rule) || "exact".equals(rule)))
            throw new IllegalArgumentException("Use atLeast or exact row height");
          s.formatTable(
              s.cellBlock(),
              t.format.row(
                  s.cellRow(),
                  new TableFormat.Row(
                      ((Number) a.get("height")).floatValue(),
                      rule,
                      t.format.row(s.cellRow()).allowBreak)));
          return null;
        });
    commands.register(
        "table.properties",
        "Set preferred table width and placement",
        "width?:number,widthPct?:number,align?:string,indent?:number,rowHeight?:number,rowHeightRule?:string,allowBreak?:boolean",
        true,
        (s, a) -> {
          Table t = table(s);
          TableFormat.Sizing size = t.format.sizing;
          String unit = size.unit;
          float width = size.width;
          if (a.containsKey("width")) {
            unit = "points";
            width = ((Number) a.get("width")).floatValue();
          }
          if (a.containsKey("widthPct")) {
            unit = "percent";
            width = ((Number) a.get("widthPct")).floatValue();
          }
          TableFormat.Row row = t.format.row(s.cellRow());
          float height =
              a.containsKey("rowHeight") ? ((Number) a.get("rowHeight")).floatValue() : row.height;
          String rule =
              a.containsKey("rowHeightRule")
                  ? Commands.str(a, "rowHeightRule")
                  : a.containsKey("rowHeight") ? (height == 0 ? "auto" : "atLeast") : row.rule;
          TableFormat format =
              t.format.row(
                  s.cellRow(),
                  new TableFormat.Row(
                      height,
                      rule,
                      a.containsKey("allowBreak")
                          ? (Boolean) a.get("allowBreak")
                          : row.allowBreak));
          s.formatTable(
              s.cellBlock(),
              format.sizing(
                  new TableFormat.Sizing(
                      unit,
                      width,
                      a.containsKey("align") ? Commands.str(a, "align") : size.align,
                      a.containsKey("indent")
                          ? ((Number) a.get("indent")).floatValue()
                          : size.indent,
                      size.fixed)));
          return null;
        });
    commands.register(
        "table.autofit",
        "Fit table to contents or page, or freeze current widths",
        "mode?:string",
        true,
        (s, a) -> {
          Table t = table(s);
          TableFormat f = t.format;
          TableFormat.Sizing size = f.sizing;
          String mode = a.containsKey("mode") ? Commands.str(a, "mode") : "window";
          if ("window".equals(mode)) {
            f =
                f.distributeColumns()
                    .sizing(new TableFormat.Sizing("percent", 100, size.align, size.indent, false));
          } else if ("contents".equals(mode) || "fixed".equals(mode)) {
            if ("contents".equals(mode))
              f = f.sizing(new TableFormat.Sizing("auto", 0, size.align, size.indent, false));
            float[] widths =
                commands
                    .layoutEngine()
                    .tableWidths(t.format(f), s.activeSection().resolved.page.contentWidth());
            float total = 0;
            for (int c = 0; c < widths.length; c++) {
              f = f.column(c, widths[c]);
              total += widths[c];
            }
            if ("fixed".equals(mode))
              f = f.sizing(new TableFormat.Sizing("points", total, size.align, size.indent, true));
          } else throw new IllegalArgumentException("Use contents, window or fixed AutoFit");
          s.formatTable(s.cellBlock(), f);
          return null;
        });
    commands.register(
        "table.quick",
        "Insert an editable quick table",
        "kind?:string",
        true,
        (s, a) -> {
          s.insertQuickTable(a.containsKey("kind") ? Commands.str(a, "kind") : "tabular");
          return null;
        });
    commands.register(
        "table.formula",
        "Insert a calculated table formula field",
        "formula?:string",
        true,
        (s, a) -> {
          Table table = table(s);
          String instruction =
              a.containsKey("formula") ? Commands.str(a, "formula") : "=SUM(ABOVE)";
          String result = TableFormulas.calculate(table, s.cellRow(), s.cellColumn(), instruction);
          if (s.cellSelection() != null)
            throw new IllegalArgumentException(
                "Place the caret in one cell before inserting a formula");
          s.insertField(new InlineField(instruction.trim(), result, false, false));
          return Json.object("result", result);
        });
    commands.register(
        "table.altText",
        "Set table accessibility title or description",
        "title?:string,text?:string",
        true,
        (s, a) -> {
          Table table = table(s);
          if (!a.containsKey("title") && !a.containsKey("text"))
            throw new IllegalArgumentException("Supply a title or description");
          s.formatTable(
              s.cellBlock(),
              table.format.altText(
                  a.containsKey("title") ? Commands.str(a, "title") : table.format.title,
                  a.containsKey("text") ? Commands.str(a, "text") : table.format.description));
          return null;
        });
    commands.register(
        "table.splitTable",
        "Split table before the current row",
        "",
        true,
        (s, a) -> {
          table(s);
          s.splitTable(
              s.cellBlock(), s.cellSelection() == null ? s.cellRow() : s.cellSelection().firstRow);
          return null;
        });
    for (final String direction :
        new String[] {"RowAbove", "RowBelow", "ColumnLeft", "ColumnRight"})
      commands.register(
          "table.insert" + direction,
          "Insert " + direction,
          "",
          true,
          (s, a) -> {
            Table original = table(s);
            int block = s.cellBlock(), row = s.cellRow(), col = s.cellColumn();
            CellSelection range = s.cellSelection();
            boolean rows = direction.startsWith("Row"),
                after = direction.endsWith("Below") || direction.endsWith("Right");
            Table.Merge span = original.span(row, col);
            int first = rows ? (range == null ? row : range.firstRow) : span.column;
            int last =
                rows
                    ? (range == null ? span.row + span.rows - 1 : range.lastRow)
                    : span.column + span.columns - 1;
            int at = after ? last + 1 : first;
            int count = rows && range != null ? range.lastRow - range.firstRow + 1 : 1;
            if ((rows ? original.rows() : original.columns()) + count > (rows ? 1000 : 100))
              throw new IllegalArgumentException("Table size limit reached");
            for (int i = 0; i < count; i++)
              s.tableStructure(block, rows ? "insertRow" : "insertColumn", at + i);
            if (rows) {
              TableFormat format = ((Table) s.document().blocks.get(block)).format;
              for (int i = 0; i < count; i++) {
                format = format.row(at + i, original.format.row(row));
                for (int c = 0; c < original.columns(); c++)
                  format = format.cell(at + i, c, original.format.cell(row, c));
              }
              s.formatTable(block, format);
            }
            s.enterCell(block, rows ? at : row, rows ? col : at);
            return null;
          });
    for (final boolean rows : new boolean[] {true, false})
      commands.register(
          rows ? "table.distributeRows" : "table.distributeColumns",
          "Distribute " + (rows ? "rows" : "columns"),
          "",
          true,
          (s, a) -> {
            Table table = table(s);
            s.formatTable(
                s.cellBlock(),
                rows ? table.format.distributeRows() : table.format.distributeColumns());
            return null;
          });
    commands.register(
        "table.cellAlign",
        "Set horizontal and vertical cell alignment",
        "value:string",
        true,
        (s, a) -> {
          Table table = table(s);
          String value = Commands.str(a, "value"), vertical, horizontal;
          if ("center".equals(value)) {
            vertical = "center";
            horizontal = "center";
          } else {
            String[] names = {
              "topLeft",
              "topCenter",
              "topRight",
              "centerLeft",
              "centerRight",
              "bottomLeft",
              "bottomCenter",
              "bottomRight"
            };
            boolean valid = false;
            for (String name : names) valid |= name.equals(value);
            if (!valid) throw new IllegalArgumentException("Unknown cell alignment");
            vertical =
                value.startsWith("top") ? "top" : value.startsWith("bottom") ? "bottom" : "center";
            horizontal =
                value.endsWith("Left") ? "left" : value.endsWith("Right") ? "right" : "center";
          }
          int block = s.cellBlock(), row = s.cellRow(), column = s.cellColumn();
          Selection caret = s.selection();
          CellSelection range = s.cellSelection();
          boolean textSelection = range == null;
          if (range == null) {
            s.selectCells(block, row, column, row, column);
            range = s.cellSelection();
          }
          TableFormat format = table.format;
          for (int r = range.firstRow; r <= range.lastRow; r++)
            for (int c = range.firstColumn; c <= range.lastColumn; c++) {
              TableFormat.Cell cell = format.cell(r, c);
              format =
                  format.cell(
                      r,
                      c,
                      new TableFormat.Cell(
                          cell.background,
                          cell.borderColor,
                          cell.borderWidth,
                          cell.horizontalPadding,
                          cell.verticalPadding,
                          vertical));
            }
          s.formatTable(block, format);
          s.paragraph("align", horizontal);
          // A caret invocation formats the full cell but retains the user's text
          // selection.
          if (textSelection)
            s.selectRange(caret.anchorBlock, caret.anchor, caret.block, caret.caret);
          return null;
        });
  }
}
