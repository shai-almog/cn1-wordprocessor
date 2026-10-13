# Format boundaries

| Format | Current behavior | Limits |
| --- | --- | --- |
| `.cn1doc` | ZIP with native document XML and optional original DOCX | Primary editable storage for implemented model; not a Word format. Undo history is not serialized. |
| DOCX | Paragraphs, direct run font/size/color/bold/italic/underline/strike/shading, basic spacing/alignment/page geometry, rectangular table content | Inheritance, numbering, links, images, review markup, fields, sections and advanced table properties are incomplete. Compatibility warnings accompany unsupported constructs. |
| TXT | UTF-8 text, paragraph breaks, table text | Formatting is discarded by definition. |
| HTML | CN1 text import; styled HTML export including tables | Imported HTML objects/tables/layout are limited to the CN1 text importer. Not a browser document renderer. |
| Markdown / RTF / AsciiDoc | CN1 rich-text adapters | Supported text subset only. Markdown/RTF/AsciiDoc table export is rejected. AsciiDoc output is CLI-accessible. |
| PDF | Paginated text/table export using standard Helvetica faces, colors and decorations | WinAnsi only; unsupported characters fail explicitly. No embedded source fonts, links, images, complex shaping, tagged accessibility or PDF import. |

Unedited DOCX export uses the original XML/asset part bytes; ZIP bytes/metadata may differ. An edited DOCX with compatibility warnings is blocked until the user exports an explicitly converted copy. Saving `.cn1doc` retains the editable representation and source DOCX for later recovery. Original assets remaining in a converted ZIP are not evidence of unchanged document semantics.

Bounds: DOCX has at most 4,096 entries, 32 MiB expanded per entry and 128 MiB total expanded data. XML has a 32 MiB input limit, depth 256 and a node limit; DTD/entity declarations, malformed nesting and duplicate attributes are rejected. Native XML rejects unknown versions and duplicate block IDs. File adapters cap input at 128 MiB.

There is no claim of arbitrary DOCX losslessness or pagination equivalence. Use the compatibility report and keep the source document when testing imports. Font substitution and incomplete styles can change line and page breaks even before editing.

CLI PDF pagination uses the standard Helvetica/Helvetica-Bold advance widths, also applicable to
their oblique faces, rather than substituting the host's AWT SansSerif metrics. This matches the
fonts emitted by the PDF writer and removes native AWT dependencies. Line height is 1.2 times the
font size before paragraph spacing. No kerning or complex shaping is added; CLI PDF line/page
breaks can differ from earlier builds and from the GUI. The metric data and required Adobe notice
are identified in [third-party notices](../third-party/README.md).
