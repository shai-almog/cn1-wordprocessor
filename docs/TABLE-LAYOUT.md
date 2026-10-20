# Table layout operations

The Table Layout ribbon provides Insert Above/Below/Left/Right, Distribute Rows/Columns and a 3-by-3 cell alignment grid (distinct top/middle/bottom and left/center/right icons with accessible labels). The same operations are available to CLI commands, macros and MCP.

Inserted rows inherit the focused row's cell appearance and minimum height; a selected row range inserts the same number of rows in one undo step. Insertion beside a merged cell uses its outer edge. Reaching the maximum column count does not block inserting a row; rejected operations leave document and selection unchanged. Alignment applies to every paragraph in each selected cell, preserves other appearance and restores the selection. All operations are one undo transaction. Distribute Rows equalizes minimum heights (at least 18 points); overflowing content can still enlarge a row.

## Limitations

- Auto-fit modes and sizing are in [TABLE-SIZING.md](TABLE-SIZING.md); row heights in [TABLE-ROWS.md](TABLE-ROWS.md).
- Arbitrary cell/table splitting, nested tables and advanced border/merge workflows are not implemented.
