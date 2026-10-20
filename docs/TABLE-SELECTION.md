# Table selection and clipboard

The contextual **Table Layout** ribbon appears while editing a table. Select Cell, Row, Column, Table and Select range operate on one rectangular selection that drives cell highlighting, character/paragraph formatting, style and case changes, typing, clearing, rich/TSV clipboard, merging, row/column deletion, composition and undo. Selections touching a merged cell expand to its whole span. On Mac, drag and Shift-arrow use the native input session; phones use the range dialog and the contextual ribbon picker.

Rich copy keeps the grid, paragraphs, fields, formatting and merged geometry. In-table paste replaces matching destination cells in one undo step. TSV paste needs a rectangular grid that fits the destination; incompatible dimensions or merge patterns change nothing. Cut and Delete clear cell contents and keep the table. Explicit row/column deletion removes structure, including the whole table if its full extent is selected.

Mailings > Labels > One recipient per label is described in [MAILINGS.md](MAILINGS.md).

## Limitations

- Selection is limited to one top-level table: no mixed body/table selections or nested tables.
- Not implemented: cell pictures, resize handles, arbitrary merged-grid paste, automatic destination expansion.
- Physical mobile selection gestures are not verified.
