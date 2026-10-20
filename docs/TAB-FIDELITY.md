# Bar tabs and tab stops

Bar tabs are supported in the model, the paragraph dialog, shared layout, screen painting and PDF output. A bar draws without a tab character and stays at its paragraph-relative position through wrapping, first-line indentation and alignment. DOCX keeps cleared inherited stops and reads/writes the document-wide default tab interval. Additive and inherited tab behavior follows OOXML tab semantics.

## Limitations

- Paragraphs with bar tabs and differing intervals need explicit DOCX conversion.
- Locale/RTL tabs, hanging-indent implicit stops and HTML tab rendering are not implemented.
- Some other DOCX readers truncate a paragraph's tab list to 64 entries, which can drop a bar that follows 64 cleared inherited stops. This app's own DOCX round trip keeps it.
