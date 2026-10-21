# Table splitting and alternative text

The Table Layout ribbon provides **Split Cells...** and **Split Table**. Split Cells divides the focused cell (merged or not) into an entered number of equal-width columns, refining the grid only where needed; other rows keep their extents and content. Existing merged cells keep their vertical span. Original content stays in the first part, new parts are blank and inherit appearance and paragraph formatting (without the original's tracked revision). The 100-column grid limit is checked first.

Split Table starts a second table at the focused row, inserts an editable paragraph between them and places the caret there. Both tables keep column widths, row heights, leading repeated-header rows and alternative text. A vertical merge crossing the boundary is divided; its text stays in the upper table. Paragraph IDs are stable, so comments follow their text. Each action has one-step undo/redo that restores the cell context. Toggling Track Changes keeps the focused cell, selection and insertion formatting.

Table Properties edits a table title and description, independently or cleared. Native storage, DOCX `tblCaption`/`tblDescription`, structural edits, splitting and rich clipboard copies keep them. HTML exports accessible labels. Table paragraph accessibility nodes expose title, row/column location and description. Each field is capped at 10,000 Unicode code points; XML attribute whitespace is encoded so multiline descriptions survive.

## Commands

- `table.split {"columns":3}` splits the focused cell (default two). Explicit `block`, `row`, `column` must be given together; explicit coordinates without `columns` keep the older unmerge operation.
- `table.splitTable {}` splits before the focused (or first selected) row; splitting before the first row is rejected atomically.
- `table.altText {"title":"Quarterly sales","text":"Revenue by region"}`; omitted fields are kept, empty or whitespace-only clears a field.

## Limitations

- Splits are horizontal on the focused cell only: no arbitrary row subdivision or splitting every cell of a selection (as in Wordcraft, whose `Table::split_cell` in crates/doc/src/table.rs splits one cell into columns).
- A single-column split does not unmerge; use the legacy unmerge in Rows and Columns.
- Splitting a multi-column span honors equal physical widths, which can differ from Word's old grid lines.
- Accessibility semantics are exposed but full screen-reader navigation is not verified; some emoji lack glyphs (font fallback is incomplete).
- Text rotation, asymmetric borders and advanced table styles are not implemented.
