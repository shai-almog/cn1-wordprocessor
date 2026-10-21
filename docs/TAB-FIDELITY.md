# Bar tabs and tab stops

Bar tabs are supported in the model, the paragraph dialog, shared layout, screen painting and PDF output. A bar draws without a tab character and stays at its paragraph-relative position through wrapping, first-line indentation and alignment. DOCX keeps cleared inherited stops and reads/writes the document-wide default tab interval. Additive and inherited tab behavior follows OOXML tab semantics.

## Limitations

- Paragraphs with bar tabs and differing intervals need explicit DOCX conversion.
- Right-to-left paragraphs are laid out mirrored, as Wordcraft para.rs `break_lines`: tab stops, indents (start indent on the right) and alignment are measured from the right edge, each tab segment moves to its mirrored extent before bidi reordering, and bar tabs mirror with the line. Table-cell line placement and lines split around floating objects (and drop-cap lines) are not mirrored, and list labels stay at the left. Locale-specific decimal separators are not implemented.
- A hanging first line has an implicit left stop at the paragraph indent ahead of the default stops, as Wordcraft (`crates/layout/src/para.rs` `next_tab`).
- HTML export writes a tab as a tab character in white-space-preserving text, so browsers use their own stops rather than the paragraph's (as in Wordcraft, `crates/formats/src/html.rs`).
- Some other DOCX readers truncate a paragraph's tab list to 64 entries, which can drop a bar that follows 64 cleared inherited stops. This app's own DOCX round trip keeps it.
