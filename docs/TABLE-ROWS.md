# Table row height and pagination

Table Layout > Cell Size > Row height edits an exact or minimum height in points. Table Properties offers Automatic, At least and Exactly, plus Allow row to break across pages. Controls apply to the caret's row; Cancel preserves the document; Apply is one undo step and keeps the cell selection.

Exact heights constrain screen and PDF geometry: overflowing text stays in the document, but lines below the row are omitted and a partly visible last line is clipped. Returning to Automatic reveals it. Line spacing multiples below single are accepted (minimum 0.06) and clip to the cell; exact/at-least paragraph spacing is in [LINE-SPACING.md](LINE-SPACING.md). Cell vertical alignment applies within the available height. Merged contents can enlarge a flexible row in their span but not rows whose heights are all exact.

Rows allowing breaks use available page space. Rows with breaks disabled move to a fresh page if they fit. Oversized rows still split so pagination finishes; repeated headers reduce space on later pages.

Height rules and break policy survive native storage, DOCX, row insert/delete/reorder, cropping, cell/table splitting and undo. Insert Above/Below inherits the source row's settings; Distribute Rows keeps each row's rule and policy. Existing native files keep minimum heights and allow breaks. DOCX uses `trHeight` with `hRule` and `cantSplit` (explicit false read on import); heights round to twips and a positive exact height is at least one twip. New DOCX exports declare compatibility mode 15 (border-at-margin table placement); imported settings are preserved.

## Commands

`table.rowHeight {"height":36,"rule":"exact"}` (default rule `atLeast`), and `table.properties` options `rowHeight`, `rowHeightRule`, `allowBreak`. `rowHeight:0` or `rowHeightRule:"auto"` clears the height. Invalid input rolls back atomically.

## Limitations

- HTML expresses row height only as a minimum and does not reproduce exact clipping.
- Word's row/paragraph/footnote keep constraints are not fully implemented.
- Legacy DOCX compatibility modes are not fully modeled and can render differently after import.
- Some Word-style renderers add row insets to a minimum height; here the minimum is the whole row height.
- No null-to-clear or properties getter in the table properties API.
- Minimum usable desktop window sizing is a broader UI gap.
