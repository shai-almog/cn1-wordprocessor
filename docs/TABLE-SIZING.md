# Table sizing and insertion

Table Layout offers AutoFit Contents, AutoFit Window and Fixed Column Width. Table Properties adds preferred width (points or percent), left/center/right alignment and left indentation. These are table properties separate from cell alignment and relative column widths.

Contents mode measures styled text, fields, tabs, indents and merged owners, assigns room between longest words and full lines, and updates after edits. Window mode starts with equal columns across the text area and redistributes for long words. Fixed freezes current widths as an absolute preferred width and keeps proportions when the preferred width changes. Content-driven tables wrap oversized unbreakable text at grapheme boundaries. Left alignment uses the stored indent; center/right align against the section text area and keep the indent for switching back. Sizing survives row/column edits, sorting, cell and table splitting, clipboard and undo, and feeds rendering, hit testing and PDF. Phone reflow scales widths in a temporary display document; the saved document and print geometry are unchanged.

Native storage keeps the sizing model; older native files keep their full-width fixed layout. DOCX uses `tblW` (dxa/pct/auto), `jc`, `tblInd` and `tblLayout`, with grid/cell widths for proportions. HTML exports preferred sizing and automatic margins.

Insert Table splits at the caret or replaces the selected text (including selections spanning paragraphs), keeps the rich prefix/suffix paragraphs (an empty prefix paragraph is retained) and comment anchors, and focuses the first cell. Cancel is inert; insertion is one undo step. Limits: 100 rows by 30 columns. Nested insertion is rejected.

## Commands

- `table.properties {"width":180,"align":"center","indent":18}` (points) or `{"widthPct":60}`; omitted properties stay unchanged; unsupported properties are rejected.
- `table.autofit {"mode":"contents"}`, `window` (default) or `fixed`.
- `table.insert {"rows":2,"columns":3}`.

## Limitations

- Per-cell preferred widths and conditional styles are not represented, so imported content-driven tables may autofit differently than in Word.
- Floating tables (`w:tblpPr`, the table "float" option) round-trip through DOCX and native and are placed by their anchors (column/margin/page, text/margin/page), offsets or alignments, taking no room in the flow with following text wrapping around them plus their distances from text. A floating table that does not fit on its page splits like a flow table, and overlap avoidance between floating tables (`w:tblOverlap`) is not implemented.
- Not implemented: rotated cell text drawing (btLr/tbRl round-trips but is drawn horizontally), asymmetric borders/margins, full complex-script shaping.
- Text replacement is tracked by Track Changes; the table structure itself is not.
- Row heights and break controls: see [TABLE-ROWS.md](TABLE-ROWS.md).
