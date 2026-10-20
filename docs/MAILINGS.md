# Mail merge

The Mailings ribbon uses the same Java command engine as macros and native MCP. **Select Recipients** pastes a list or opens CSV, TSV, XLSX, XLSM or ODS. Spreadsheets offer a worksheet selector and a first-row-header option. **Edit Recipient List** manages inclusion, filter and sort (see [RECIPIENT-MANAGEMENT.md](RECIPIENT-MANAGEMENT.md)); use **Select Recipients > Type or paste a list** to edit the original CSV; **Match Fields** overrides address-column mappings. Recipient data is session-owned, shares undo/redo and macro previews, and is cleared when another document opens. Save the template and keep the recipient file separately: recipient data is not stored in `.cn1doc`, DOCX or recovery storage.

Insert a merge field, address block, greeting or rule. **Preview Results**, previous/next recipient and **Find Recipient** resolve fields in the body, tables, headers/footers and notes. Shading is a view setting and is not printed. **Check for Errors** reports missing recipient columns. **Finish & Merge** creates an editable document for an inclusive record range; Undo restores the template and recipient state. Letters get separate sections/pages and personalized headers/footers; directory output uses continuous sections. Email mode creates a document and does not send messages.

Rules: IF, NEXT, NEXTIF, SKIPIF and MERGEREC. Comparisons support `=`, `<>`, `<`, `>`, `<=`, `>=`, numeric when both values parse as numbers. The IF grammar takes one nested MERGEFIELD and literal true/false text. Fields are data: no instruction, spreadsheet formula or workbook macro executes. Locked and unrelated fields keep cached results.

**Envelopes** creates one of eight common envelope sizes, as a separate document or a section before the letter. **Labels** supports nine Letter/A4 sheet layouts, repeated text, one recipient per label, or a specified row/column. Sheet geometry is fixed per product (table widths, gaps, row heights). Generation rejects text taller than a label rather than shifting later labels. **One recipient per label** continues through all selected recipients in selected order, one section per sheet with the product's paper geometry; the last sheet leaves unused labels empty.

The spreadsheet reader handles shared/inline strings, cached formula values, booleans, ISO dates/times, 1900/1904 date systems, sparse cells and ODS row groups. Limits: 64 MiB compressed/inflated package data, 32 MiB per part, 1,000 sheets/columns, 100,000 recipients, 1,000,000 cells, 16 million characters. Malformed or oversized input fails before replacing the current list. CSV supports quoted delimiters, doubled quotes, embedded newlines, comma/semicolon/tab detection, UTF-8 and file-picker UTF-16. Exported field instructions and cached results round-trip through DOCX.

## Limitations

- Formulas are not recalculated, arbitrary Excel number formats and workbook formatting are not applied, and external data sources are not supported.
- Atomic multiline fields show line breaks as spaces in preview; Finish & Merge emits real line breaks.
- Not implemented: full nested-field evaluation, advanced switches, number/date format switches, persistent recipient links, compound filters, multiple sort keys, email delivery, printer-feed calibration.
- Workbook file-picker interaction and physical mobile keyboard/touch are not verified.
- DOCX field round-trip is not complete Word field compatibility.
