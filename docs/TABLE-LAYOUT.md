# Contextual table layout controls

Table Layout now has Insert Above/Below/Left/Right, Distribute Rows/Columns and a 3-by-3 alignment grid. The grid has distinct top/middle/bottom and left/center/right drawings and accessible labels. The same operations are available to CLI commands, macros and the opt-in native MCP server.

Inserted rows inherit the focused row's cell appearance and minimum height. A selected row range inserts the same number of rows in one undo step. Insertion beside a merged cell uses its outer edge. Reaching the maximum column count no longer prevents inserting a row; rejected operations leave the document and selection unchanged.

Alignment applies to every paragraph in each selected cell, preserves unrelated appearance and restores text or cell selection. All structural and formatting operations are undoable as one transaction. Row distribution equalizes the table's minimum heights, using at least 18 points; overflowing content can still make a row taller.

Validation: 392 Java 8 tests, 17 native workflows on each of Mac and iPhone simulator, 60 paired table-layout/DOCX checks, 48 table-selection regressions and 59 existing paired document cases. The paired fixtures check content, horizontal/vertical alignment, shading, minimum heights and grid proportions. Native screenshots verify the alignment controls and resulting cell placement. The earlier blank-icon build is retained as superseded evidence. Original documents and recipients were restored and task-owned apps were stopped.

Native bundles use the same isolated framework snapshot and local Mac patch as the table-selection checkpoint. Other platforms, latest upstream/release-baseline integration, exact row heights, auto-fit modes, arbitrary cell/table splitting, nested tables and advanced border/merge workflows remain incomplete. This is not full Wordcraft parity. The separate tab checkpoint retains a Wordcraft reader truncation failure.

Evidence: `table-layout-acceptance.json` and `artifacts/parity-20261016-table-layout/`, including source hashes, native bundles, build logs, screenshots and cross-reader DOCX files. Reproduce with `scripts/live-table-layout-smoke.py` and `scripts/table-layout-probes.py`.
