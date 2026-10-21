# Table formulas and Quick Tables

## Formulas

Table Layout > Formula inserts a calculated, editable field. SUM, AVERAGE, COUNT, MIN, MAX and PRODUCT accept ABOVE, BELOW, LEFT, RIGHT, A1 references, ranges such as A1:B3, and numeric constants, comma-separated. Merged cells count once within a range; explicitly repeated arguments count again. Deleted text is excluded. Keywords are case-insensitive regardless of system language.

Table Layout > Update Formulas and Update Fields recalculate results. Forward dependencies resolve in one pass; cycles give `!Circular reference`. Locked fields keep cached results; unknown functions and unsupported number-format switches keep their cached values. Updates preserve the active cell, cell selection and insertion formatting, and are undoable. Results are cached fields, not live formulas: editing a source cell does not recalculate dependents.

The parser treats instructions as data and bounds them: 8,192 characters, 100 arguments, dependency depth 128, references within the table grid. `\* MERGEFORMAT` is accepted. Each referenced cell is read in its own language, the `w:lang` of its first non-blank text (Wordcraft `cell_lang`): decimal-comma languages (Polish, German, French and the rest of Wordcraft's locale_number list, minus point regions such as de-CH and es-MX) read `3,50`, `1 250,00`, `1.250,00` and `1\u00a0250,50 z\u0142` as numbers, a lone `1.5` as a decimal and `1.` as a whole number; other cells use English decimal points, validated grouping and common currency/percent notation; a percent is read as its displayed number (12% is 12). Non-integral results show two decimals, with the decimal comma when the formula cell (or, when it is empty, the first number read) is in a decimal-comma language (`5,75`). There is no document default language yet, so unmarked cells are English.

## Quick Tables

Insert > Quick Tables (also in the native Insert menu) previews Tabular list, Matrix and Calendar. They are ordinary editable tables with a repeated header row. Insertion replaces the selected text, keeps styled prefix/suffix paragraphs and their comment anchors, and places the caret in the first cell. Cancel changes nothing; insertion is one undo step. Calendar is a reusable 31-day sample, not computed from the current date. Tabular/matrix use a blue header with white text, alternating light-blue rows and a bold first column; Calendar uses a light grid and bold headings (materialized character/cell properties).

## Commands

- `table.formula {"formula":"=SUM(ABOVE)"}`; default SUM(ABOVE).
- `references.updateFields {}` updates supported formula and reference fields.
- `table.quick {"kind":"tabular"}`; kinds are tabular (default), matrix, calendar; others are rejected.

Native storage and DOCX keep formula instructions/results and the materialized template content and appearance.

## Limitations

- Not supported: nested functions, arithmetic expressions, bookmarks, IF and `\#` number-format switches (as in Wordcraft, whose `table.formula` in crates/engine/src/cmd/table.rs takes only SUM/AVERAGE/COUNT/MAX/MIN/PRODUCT over a direction); per-cell language properties and comma-decimal locales (Wordcraft formats the result in the cell's language).
- Empty MIN/MAX return 0; rounding at exact ties and very large-number formatting may differ from Word.
- Named table styles follow Wordcraft's table styles: definitions with based-on chains and conditional regions (whole table, firstRow, lastRow, firstCol, lastCol, band1Horz/band1Vert with shading, borders, bold and header text color) read from and written to DOCX styles.xml and kept in the native format; Table > Table Styles is the gallery with the style options (w:tblLook). Styles are materialized into cell formatting when applied (direct DOCX cell formatting wins), so structural edits do not re-derive them and there is no theme-aware recoloring; the style's paragraph/run properties beyond bold and header color are not applied.
- Nested quick tables are rejected. Text replacement is tracked by Track Changes but the inserted table structure is not (tracked structural edits are a broader gap).
