# Format boundaries

| Format | Current behavior | Limits |
| --- | --- | --- |
| `.cn1doc` | ZIP with native document XML and optional original DOCX | Primary editable storage for implemented model; not a Word format. Undo history is not serialized. |
| DOCX | Paragraphs, inherited paragraph/character styles plus direct formatting, spacing/alignment/indents/periodic tabs/page geometry, nine-level lists with simple labels/restarts, hyperlink relationships, range comments with replies and resolved state, named paragraph style definitions, paragraph-level embedded PNG/JPEG/GIF/BMP/TIFF/WebP/WMF/EMF pictures, rectangular table content with horizontal/vertical merges and rich cell paragraphs | Theme fonts/colors, custom/composite list labels, bookmarks outside body paragraphs, floating/cropped/rotated/cell pictures, structural/move revisions, advanced fields/sections and advanced table properties are incomplete. Compatibility warnings accompany unsupported constructs. |
| ODT | Import translates `content.xml`/`styles.xml`/`meta.xml` and `Pictures/` into WordprocessingML and reads it with the DOCX reader; export writes the DOCX package and translates it to ODF 1.3 (stored `mimetype` first, manifest, content, styles, meta, pictures). See [OpenDocument Text](#opendocument-text-odt). | First master page only; comments, tracked changes, text boxes, fields, tab stops, borders and multi-section layouts are not carried. Wordcraft also reads one master page and skips notes and annotations. |
| TXT | UTF-8 text, paragraph breaks, table text, picture descriptions | Formatting is discarded by definition. |
| HTML | CN1 text import plus tables (`colspan`/`rowspan`, clamped to 1000x64) and `<img>` PNG/JPEG `data:` URIs plus external `http`/`https`/`file:` and relative-path images (relative to the opened file) fetched through Codename One networking/FileSystemStorage with a 20 MB limit; styled HTML export including merged tables, embedded data-image pictures and superscript/subscript | Cell text uses the CN1 text importer (nested tables flatten to text; table/cell CSS, header rows are not imported). Images that fail to load or aren't PNG/JPEG are left out and listed in a warning when the file is opened; pasted HTML and CLI/macro HTML imports have no source location and skip external images. Fetches block the import (via addToQueueAndWait on the EDT) with a 15 s timeout per image. Not a browser document renderer. |
| Markdown / RTF / AsciiDoc | CN1 rich-text adapters; Markdown GFM pipe tables (column alignment, `<br>` cell breaks) and `![alt](data:)` PNG/JPEG images (external and relative image targets load as for HTML when a file is opened); RTF `\trowd` tables with `\clmgf`/`\clvmgf` merges and `\pict` `\pngblip`/`\jpegblip` pictures, both directions | Supported text subset only. Markdown has no merged cells: as in Wordcraft's markdown writer (`table_md`), a column or row span exports as empty covered cells (`| a |  | b |`), and a bare `||` imports as an empty cell (no MultiMarkdown colspans, as in Wordcraft's reader). RTF cell text keeps only bold/italic/underline/strike/alignment; cell widths, borders, shading and differing per-row `\cellx` grids are not imported. Drawings and AsciiDoc tables/pictures are rejected. AsciiDoc output is CLI-accessible. |
| LaTeX (`.tex`) | Export: standalone `article` (fontenc/inputenc/textcomp, plus ulem/xcolor/multirow/longtable only when used, hyperref) with `\title`/`\author` from document properties, unnumbered `\section*`..`\paragraph*` from Heading1-4+, bold/italic/underline/strike/small caps/monospace/super/subscript/color/font size, center/flushright/quote, nested itemize/enumerate (max 4 levels), `tabular`/`longtable` with `\multicolumn`/`\multirow`, `\href`, `\footnote`, bookmarks as `\label` and REF/PAGEREF fields as `\hyperref`/`\pageref`, escaping of TeX specials and Unicode punctuation. Import: tolerant parser for sectioning, `\title`/`\author`/`\maketitle`, text commands and declarations, sizes, colors, accents, ligatures/quotes, lists/description, quote/center/flush environments, verbatim/`\verb`, `tabular` with `&`/`\\`/`\multicolumn`/`\multirow`, `\footnote`, `\url`/`\href`, `\label`/`\ref`/`\pageref`, captions, `\cite` as bracketed keys; unknown macros keep their braced text | Not a TeX engine: user macros are not expanded and `\input` files are not read (the same in Wordcraft). Equations export as math (`\[ \]`, `$ $` for inline, `equation*` with `\tag` when numbered, `amsmath` added): fractions, scripts, radicals, n-ary operators, `\left`/`\right` delimiters, matrices, accents, bars, functions, limits and braces. Math is still imported as italic linear text, not as an equation object. Pictures export as their alternative text (single-file export, as in Wordcraft); `\includegraphics` imports as a `[Picture: file]` placeholder. Endnotes export as footnotes; bookmarks export as point labels at paragraph start (ranges are lost). Multi-paragraph table cells are joined into one line; column widths, borders and shading are not exported. Non-Latin text needs XeLaTeX/LuaLaTeX. Exported output was not compiled in CI (no TeX installation available). |
| PDF | Paginated text/table/picture export, colors and decorations; embeds the bundled faces, with Hebrew and Arabic falling back to bundled Noto faces; kerning, links, outline, metadata and tags | Shaping covers Latin, Hebrew, Arabic, the nine major Indic scripts, Thai, Lao, Khmer and Myanmar; CFF fonts embed with charstring subsetting (subroutines kept whole), CFF2 fonts embed whole; no PDF import. |

Unedited DOCX export uses the original XML/asset part bytes; ZIP bytes/metadata may differ. An edited DOCX with compatibility warnings is blocked until the user exports an explicitly converted copy. Saving `.cn1doc` retains the editable representation and source DOCX for later recovery. Original assets remaining in a converted ZIP are not evidence of unchanged document semantics.

Bounds: DOCX has at most 4,096 entries, 32 MiB expanded per entry and 128 MiB total expanded data. XML has a 32 MiB input limit, depth 256 and a node limit; DTD/entity declarations, malformed nesting and duplicate attributes are rejected. Native XML rejects unknown versions and duplicate block IDs. File adapters cap input at 128 MiB.

There is no claim of arbitrary DOCX losslessness or pagination equivalence. Use the compatibility report and keep the source document when testing imports. Font substitution and incomplete styles can change line and page breaks even before editing.

CLI PDF pagination measures the bundled TrueType advances used by the exported PDF. CLI/MCP page-reference commands use those same metrics; native commands use the editor's print-layout engine, independent of mobile reflow. Native platform font metrics can still differ from headless metrics. PDF glyph positions apply GPOS/`kern` pair kerning inside each span while keeping the layout span origins, so kerned text can end slightly before the measured span edge. Complex shaping equivalence is not claimed. The low-level `PdfExporter.write(document)` overload and default standalone `Commands()` retain the explicit Helvetica fallback; application hosts supply their layout engine/font provider. Fonts and licenses are listed in [third-party notices](../docs/third-party/README.md).

The GUI now bundles Source Sans 3, Source Serif 4 and JetBrains Mono (OFL licenses included). Imported family names outside those families are substituted. Native and CLI PDF export use the bundled fonts. Basic numbered lists restart after non-numbered paragraphs. Simple nested levels, start overrides and Roman/letter formats round-trip; composite numbering text such as `%1.%2` remains an explicit approximation. The hollow-circle and square nested bullet markers are exported as vector geometry in PDF.

Comments use standard range markers and `comments.xml`; resolved state uses paragraph IDs and `commentsExtended.xml`. Rewriting regenerates the extended metadata to match the rewritten comment IDs. Replies use linked comment paragraphs and preserve author, text and resolution. Editing thread/reply text preserves that metadata; leading, interior and trailing blank paragraphs survive native/DOCX round trips. Body ranges and ranges within one table cell, including multiple cell paragraphs, are supported. Cross-cell, body-to-cell and note/margin-story ranges remain unsupported. Comment anchors follow explicit edit ranges, including repeated text, and undo restores the previous anchors.

Clipboard copy publishes native document XML, HTML and plain text. Native XML preserves implemented paragraph/run/table properties; macOS returns private MIME data as UTF-8 bytes. The paste path decodes those bytes before using the model. External HTML still uses the limited CN1 text importer and does not establish full Word clipboard interoperability.

Superscript/subscript preserve base point size plus baseline alignment in native XML and DOCX; screen/PDF rendering uses a smaller shifted glyph. Mixed font sizes share a line baseline. Merged cells preserve owning paragraphs and serialize DOCX gridSpan/vMerge and HTML row/column spans. Table column proportions, minimum row heights, solid uniform cell borders, shading, symmetric padding, vertical alignment and repeated leading header rows now persist in native/DOCX and render in screen/PDF. Nested tables, asymmetric/decorative borders and floating positioning remain incomplete; DOCX conversion warnings identify these cases. Periodic fallback tabs serialize a bounded sequence of 64 positions; explicit aligned tabs and leaders are supported as described below.

Pictures retain the original PNG/JPEG/GIF/BMP/TIFF/WebP/WMF/EMF bytes in native/DOCX files. WMF (placeable or standard header) and EMF pictures, including `.doc` metafile BLIPs (inflated, with a placeable header restored for WMF), are rasterized in pure Java to a PNG of at most 2048 pixels per edge (144 dpi below that) for display, HTML and PDF, so PDF output is a bitmap rather than vector art. The rasterizer covers the record subset Wordcraft reads: window/viewport/map modes, EMF world transforms, save/restore, pens, solid brushes and stock objects, lines, polylines, polygons, poly-polygons, Beziers, rectangles, ellipses, arcs/chords/pies, EMF path brackets and DIB bitmaps (StretchDIBits, DIB(Stretch)BitBlt, BitBlt/StretchBlt/AlphaBlend). Text records are not drawn and hatch and pattern brushes paint as solid color (both also in Wordcraft, whose PDF output is likewise a bitmap); rounded rectangles are drawn square, and clipping, regions and raster operations other than copy are ignored; unknown or malformed records are skipped and fill work is bounded. GIF (first frame only; animation is dropped), BMP (1/4/8/16/24/32-bit, uncompressed, bitfields, RLE8/RLE4), baseline TIFF (first page; uncompressed, PackBits, LZW, Deflate; bilevel/gray/palette/RGB/RGBA at 1-16 bits, strips or tiles, horizontal predictor) and WebP (lossless VP8L, lossy VP8 key frames, ALPH alpha) are decoded by pure-Java decoders and converted once to PNG for display, HTML and PDF. Animated WebP, planar/CMYK/YCbCr/JPEG-compressed/floating-point TIFF and OS/2 BMP variants beyond the 12-byte core header are rejected with a clear error. Display uses a bounded decoded-image cache and a maximum 2048-pixel preview edge; exports use original bytes. Images are bounded to 20 MiB and 16 megapixels. PNG PDF decoding supports non-interlaced grayscale, RGB, indexed and alpha images (including packed samples and 16-bit samples converted to 8-bit PDF pixels); interlaced PNG PDF export fails explicitly. JPEG PDF export embeds the encoded data without recompression. Floating DOCX placement, crops and rotations produce conversion warnings; picture-in-text import moves the picture after its paragraph and warns. Cell pictures remain unsupported.

Paragraph styles store resolved character and paragraph defaults. Changing a definition updates matching body/cell paragraphs while retaining properties differing from the prior defaults. Inheritance is flattened on edited export; linked character styles, style sets and a full Word style inspector remain incomplete. A direct override equal to its previous default cannot be distinguished from inheritance in the current model.

Native PDF export and print embed the same Source Sans 3, Source Serif 4 and JetBrains Mono faces used by the editor (four variants each), with CID glyph maps, widths and Unicode extraction maps. Each TrueType face is embedded as a subset: glyph ids are kept (identity CIDToGIDMap) but outlines the document does not use are emptied, after closing over composite-glyph components, and layout tables are dropped; the subset carries a format 12 cmap of its kept glyphs and a six-letter subset tag. CFF-outline faces (.otf, CFF .ttc collections) are rewritten as a CID-keyed CFF holding .notdef and the used charstrings in CID order (FontFile3 /CIDFontType0C, no CIDToGIDMap); global and local subroutine INDEXes are copied whole rather than subset, since renumbering them means rewriting charstrings. CFF2 (variable CFF) faces are embedded whole as FontFile3 /OpenType with CID = glyph id, so a glyph reused for different text extracts as its first text. A character the chosen face lacks falls back per character to bundled Noto Sans Hebrew and Noto Naskh Arabic (regular/bold), then to installed fonts: the platform font folders (macOS /System/Library/Fonts, /Library/Fonts, ~/Library/Fonts; Windows Fonts folders; /usr/share/fonts, /usr/local/share/fonts, ~/.fonts, ~/.local/share/fonts) are indexed once by family name through FileSystemStorage and candidates are tried in Wordcraft's per-script order (its preferred families for Devanagari, Bengali, Thai, Lao, Khmer, Myanmar and other Indic scripts, its RTL list for Arabic/Hebrew, then families named after the script, then its general fallback list, at most 24 faces per character). Characters nothing covers print as the face's .notdef box. Text is shaped per face and script run by a built-in OpenType engine: GSUB single, multiple, ligature, context and chaining-context lookups (ccmp, locl, rlig, liga, clig, calt; Arabic isol/fina/medi/init by joining type; Indic nukt, akhn, rphf, rkrf, pref, blwf, abvf, half, pstf, vatu, cjct, pres, abvs, blws, psts, haln with pre-base matra reordering, two-part matra splitting (Bengali, Oriya, Tamil, Malayalam) and reph placement for Devanagari, Bengali, Gurmukhi, Gujarati, Oriya, Tamil, Telugu, Kannada and Malayalam; Thai/Lao sara am decomposed with the nikhahit moved before preceding tone marks; Khmer and Myanmar syllables with pre-base vowels, coeng Ro / medial Ra moved before the base, kinzi after it, and rphf, pref, blwf, abvf, pstf, cfar) and GPOS single, pair, mark-to-base, mark-to-ligature, mark-to-mark, cursive attachment and context lookups (kern, curs, mark, mkmk, dist, abvm, blwm), honoring GDEF lookup flags. Marks and offsets are placed with TJ adjustments and text rise. Faces without Arabic GSUB still use Presentation Forms-B and lam-alef. Right-to-left runs are drawn in visual order with mirrored paired brackets. ToUnicode maps give each cluster's logical text on its first glyph (ligatures, reordered Devanagari syllables), so extraction stays in logical order. Hyperlinks become URI link annotations (http, https, mailto, ftp) and bookmark links become page destinations; headings build the PDF outline; title/author/subject/keywords, creation date and the first run language become document metadata. Output is tagged: paragraphs, headings H1-H6, tables (Table/TR/TD) and figures with alt text are structure elements with marked content and a parent tree; headers and footers are artifacts.

PDF limitations: color-bitmap emoji fonts cannot be embedded and are skipped, so a script only those fonts cover still prints as .notdef; CFF subroutines are not subset; shaping implements Latin, Hebrew, Arabic, the nine Indic scripts, Thai, Lao, Khmer and Myanmar (Hangul jamo and USE scripts get only generic GSUB/GPOS features without reordering); GSUB alternate/reverse-chaining lookups, AAT `morx` tables (macOS Malayalam and Thai system fonts are morx-only, so they get cmap glyphs and hmtx advances only), variable-font instances and vertical text are not supported; Indic reph is placed after the base; Telugu reph (explicit ZWJ) and Malayalam dot reph are not formed; per-script shaping is checked against installed macOS fonts and synthetic tables, not against Wordcraft output and its below-base forms rather than by the font's full positioning class; Arabic shaping does not join across span boundaries and Arabic layout widths are measured unkerned; bracket pairing follows the layout's bidi runs; italic/bold Hebrew and Arabic use the regular or bold Noto face; the Helvetica fallback path still rejects text outside WinAnsi; tags do not yet cover lists (L/LI), notes as Note elements, link annotations as Link elements, or reading order across floating pictures. CLI export uses the same embedded bundled fonts and glyph advances.


Aligned paragraph tabs preserve explicit positions, left/center/right/decimal alignment and dot/hyphen/underscore/middle-dot leaders through native and DOCX files. PDF renders the leaders while the document retains actual tab characters. Decimal alignment currently uses a period; RTL tabs remain incomplete; bar stops and inherited clear overrides are supported. DOCX paragraph-specific fallback intervals are materialized to a bounded set of stops; HTML/plain-text adapters retain tab text but do not reproduce this page geometry.


Inline fields now round-trip native instruction/result/lock/code-view metadata and DOCX simple fields. Complex begin/separate/end fields import as atomic objects with cached text; nested result fields and mixed result styles are flattened with warnings. Field instructions are data, never executable code. Unsupported instructions keep their cached result. HTML/text export uses displayed results; it does not preserve field identity. Page/title resolution and host date/time/filename values are explicit updates. Multiline/tab field results currently display spaces while retaining their original cached value in native/DOCX storage. Cross-paragraph range fields and advanced field evaluation are incomplete.


Paragraph headers/footers support default/first/even variants, distances, different-first/even flags, field objects, direct formatting and per-part hyperlink relationships. Native XML retains all six variants; DOCX exports referenced header/footer parts and settings. Existing generated parts are reused on edited re-export. Tables, images and other non-paragraph margin content generate import warnings. Section ownership and links are supported as described below. Layout expands body margins for active variants, bounded to preserve body space; oversized stories may clip. PDF currently resolves page fields in each rendered margin; DOCX keeps field instructions and cached values for external recalculation.


Section update: native storage and DOCX now preserve paragraph section boundaries, paper geometry, next/continuous/odd/even starts, page-number restarts and inherited versus explicitly empty header/footer stories. PDF uses each page's own MediaBox; resolved PAGE results follow section numbering. Oversized margin content produces layout warnings and PDF export fails rather than silently dropping content. Columns, mirrored margins/gutters, complex continuous-section layout and nondecimal section numbering remain unsupported.


Tracked text insertions/deletions use DOCX `ins`/`del` and `delText`; paragraph marks carry their own revision metadata. Character and paragraph formatting use `rPrChange`/`pPrChange`, with original property values and author/date metadata. Native XML preserves the same history and the track-changes flag. Resolving a selection splits revisions at its boundaries; fields remain atomic. Format-only edits and style changes can be accepted/rejected after native or DOCX reopen. Imported inherited paragraph-style changes also retain their original character defaults.

Supported revision history does not include structural table/image/section edits, moves, style-definition revisions or fully independent overlapping style/run-format histories. The canvas/PDF currently show inline markup; Final/Original/Simple Markup and balloon layouts remain incomplete. Case conversion retains original text when tracking; generic external format adapters do not preserve revision identity. Editing/exporting a DOCX with unsupported content still requires the explicit conversion gate.


Captions use ordinary styled paragraphs containing atomic SEQ fields and optional STYLEREF chapter-number fields. Native XML and DOCX retain instructions/results, including when imported into the other tested implementation and renumbered after an earlier insertion. SEQ supports decimal/Roman/letter output, reset/repeat/hide and heading restarts in body/table order; locked fields retain cached values. Caption placement is a document block operation, not an anchor binding to an image/table. Moving or deleting an object does not automatically move/delete its caption. Margin-story numbering and arbitrary named-style STYLEREF searches remain unsupported; unknown instructions retain cached results.


## Bookmark and cross-reference ranges (2026-10-14)

The native format and DOCX preserve named body-paragraph ranges, including collapsed points, overlapping comment ranges, multiple paragraphs and atomic field boundaries. Bookmarks follow edits, split/join, Unicode case expansion, tracked replacement acceptance/rejection and undo. Bookmark ranges in table cells, header/footer stories or inside field results remain unsupported; DOCX import reports conversion warnings for them.

Cross-references use live REF/PAGEREF instructions and real named or hidden bookmarks. Heading/caption/bookmark text, logical page numbers and above/below results update explicitly. Nested references resolve without relying on stale cached results; cycles and missing targets produce bounded errors. Locked fields retain their results. Complete Word field-switch semantics and bookmark clipboard transfer are not certified.

Wordcraft imports still report unrelated character/paragraph/section formatting warnings. These checks do not establish complete DOCX fidelity or complete feature parity.


## Generated contents and caption lists (2026-10-15)

Heading-based contents and caption-based figure/table/equation lists are editable paragraph stories with portable TOC, SEQ and PAGEREF fields. They coexist in one document. DOCX export writes standard content controls and complex fields spanning the entry paragraphs, preserving nested page references. Import handles these ranges plus the pinned Wordcraft exports. Native storage retains the same options and bookmarks. Generated labels are refreshed when source headings contain live fields, before pagination settles.

The comparison retains a reference interoperability failure: Wordcraft reads our complex figure-list fields and can edit the source caption, but its Update figures command leaves the generated entries stale. Its updater recognizes atomic TOC fields, not the imported complex field starts. Our app updates Wordcraft's exported lists. This is recorded as three failed reverse-direction checks, not complete round-trip parity. Other unsupported DOCX features still require explicit conversion consent.

TOCs now accept custom style maps, direct outline levels and marked TC entries, including category/level filters. Direct outline levels remain distinct from inherited style levels through native/DOCX storage and tracked-format rejection. Twenty-four paired and two-way import/update checks pass for these sources. Imported level ranges other than 1 through N, per-level page-number suppression, arbitrary complex-field ranges, duplicate lists for the same caption label, custom generated-entry formatting and full Word fidelity remain incomplete. Lists are main-body workflows; figure/caption anchoring to movable objects is not implemented.


## Footnotes and endnotes (2026-10-15)

Native files store an atomic inline reference with an owned rich paragraph story. DOCX uses standard footnotes/endnotes parts, reference elements, automatic/custom marks and part-scoped hyperlink relationships. Notes work in body paragraphs and table cells; their text, formatting and revisions survive round trips. Copying references creates distinct note identities. PDF includes page-bottom footnotes and document-end endnotes; long notes continue over pages. HTML exports linked note sections, and plain-text output retains their text.

The note model currently uses continuous decimal footnotes and lower-Roman endnotes, with optional custom marks. Other explicit number formats, restart/start/placement settings produce import warnings. Tables, pictures and nested notes inside a note story are not supported; unsupported imported note content is reported or rejected. HTML/text import flattens note semantics. These boundaries do not establish complete Word fidelity.


Note settings now preserve sparse section overrides and document defaults in native XML, DOCX `settings.xml` and section `footnotePr`/`endnotePr` properties. Supported formats are decimal, lower/upper Roman, lower/upper repeated letters, decimal with a leading zero, numeric ordinal, hexadecimal, Chicago symbols, numbers in dashes and no number. Starts are 1–32767; numbering can continue or restart per section, with page restarts for footnotes. Footnotes can sit at the page bottom or below text; endnotes can end each section or the document. Physical printed pages determine page-restarted labels, including when the phone view reflows. Layout reports a warning if page-dependent numbering cannot settle within eight passes.

Roman rendering currently falls back to decimal above 3999; repeated-letter and Chicago labels are capped at 40 glyphs. Unsupported regional/textual numbering formats still produce DOCX conversion warnings. Direct in-page editing, tables/pictures in note stories, note-story comments/bookmarks, custom continuation separators and broader complex-table/continuous-section fidelity remain incomplete. Document labels stored before pagination use document order; the layout and `references.notes` command expose page-resolved labels. Plain-text/HTML exports do not yet resolve page-dependent labels with print pagination.


### Index fields

The document model stores empty-result XE marks and an INDEX marker with generated paragraphs. Native XML and DOCX retain their instruction data, formatted page references, indents and tabs. DOCX export encloses generated entries in a standard multi-paragraph complex INDEX result; import recognizes Index1–Index9, IndexLetter and Wordcraft ListEntry paragraphs. The first body index is managed; additional instances are not certified. Body/table marks, nested subentries, cross-reference text, bookmark ranges and bold/italic page references are supported. Standard page numbering includes section restarts and the space consumed by the index. A missing range bookmark produces an explicit error entry.

Multi-column indexes and unimplemented INDEX switches are rejected during regeneration. Unicode case folding is used for grouping/sorting; locale-specific collation and supplementary-character letter headings are incomplete. XE marks have no visible result; field-code display can expose their instructions, but a dedicated hidden-mark editing UI is absent. Existing DOCX conversion warnings for unrelated source styles/sections still apply. The pinned Wordcraft reader does not preserve the generated INDEX range correctly when updating it.


### Table of authorities

TA citation marks and TOA generated ranges round-trip through native XML and standard DOCX complex fields. TableofAuthorities and reference ListEntry result paragraphs are recognized. Citation text is a single key even when it contains colons. One table, title, leaders and page references are supported; categories, short forms, passim and other TOA switches reject regeneration. Both own exports and imported reference exports pass semantic checks. The pinned Wordcraft reader duplicates our cached TOA range when updating it.


### Citations and bibliography sources

Native XML stores an immutable, document-owned source library and citation style. DOCX uses the standard bibliography Sources custom-XML namespace, Author/Editor NameLists, source metadata and a complex BIBLIOGRAPHY result range. CITATION fields retain tag, page range, cached result and lock state. The source library survives editing, recovery and undo; unrelated package custom-XML stores survive export. Source records are bounded (2,000 sources, 2,000 characters per value, 64-character tags), and duplicate source tags are rejected on import.

Twelve style renderers match paired book/article output fixtures. Author/date bibliography entries have a hanging indent. Composite citations and unimplemented switches retain cached results; filtered bibliographies and unknown styles cannot be regenerated. Corporate author distinctions, broader source roles, note/header citations, locale collation, global libraries and full CSL/style-guide behavior remain incomplete. The pinned Wordcraft reader duplicates our standard multi-paragraph bibliography result on update. Source metadata alone does not establish Microsoft Word rendered fidelity.


Zotero citations use the Word plugin's layout: fields whose instruction is `ADDIN ZOTERO_ITEM CSL_CITATION {...}` or `ADDIN ZOTERO_BIBL {...} CSL_BIBLIOGRAPHY` (instructions up to 1,000,000 characters, other fields stay at 8,192). DOCX writes them as complex fields and reads them back with instruction, cached result and lock. Zotero document preferences are kept as the `zotero.prefs` document setting and written as `docProps/custom.xml` string properties `ZOTERO_PREF_1..n` in 255-character chunks; other custom properties in that part survive. Field results are stored as plain text (live Zotero updates keep only formatting shared by the whole result), so formatted citations and multi-paragraph bibliographies from Word import flattened (a bibliography field spanning paragraphs is not reassembled). ODT round trip of Zotero fields and preferences is untested.

Mail merge fields (`MERGEFIELD`, `ADDRESSBLOCK`, `GREETINGLINE`, supported conditional/record rules) retain instruction/cache/lock metadata in native and DOCX templates. Preview updates cached results; Finish converts supported unlocked merge fields to rich text, cloning table/section/note content and remapping comment/bookmark ranges and internal references. Recipient source lists, filtered/sorted views, exclusions and address mappings are session state, not serialized document or recovery data. Completed merges export only the selected records in their selected order. Envelope and label output uses ordinary sections/tables. CSV/XLSX/XLSM/ODS input is a bounded recipient-data import, not a spreadsheet editor.


Document comparison emits normal tracked run and paragraph-break revisions; generated comparisons survive native/DOCX storage and accept/reject in either implementation for the tested subset. Comparison supports paragraph text and text within unchanged table geometry, with direct-format revisions on unchanged paragraphs. It rejects unsupported structures before replacing the current document. Combine and full structured comparison remain incomplete.


## Rectangular clipboard and label sheets (2026-10-16)

Whole-cell rectangular copy preserves a rich table fragment; in-table paste requires matching destination size and merged-cell geometry. TSV paste fills a fitting rectangle atomically. DOCX checks cover selected row/column/table formatting and text replacement across both readers. Recipient label generation now creates additional physical sheets as separate sections and preserves those boundaries through native and DOCX round trips.


## Tab fidelity (2026-10-16)

Bar tabs are stored in native files and DOCX and rendered as vertical rules in the shared screen/PDF layout, including empty and wrapped paragraphs, table cells and margin stories. Bar positions do not consume typed tabs or shift with paragraph alignment or first-line indentation. Paragraph > Tabs exposes bar alignment with transactional apply/cancel and undo.

DOCX import resolves additive style tabs and explicit clears before recognizing periodic fallback stops. Export emits clears for removed inherited stops, so clearing a named style's tabs survives reopening. Document `defaultTabStop` is read and written. Export chooses a paragraph interval, preferring a bar-bearing paragraph's interval; differing paragraph intervals remain bounded explicit fallback lists. Multiple bar-bearing paragraphs with different intervals require explicit conversion. Locale-specific decimals, RTL tabs, hanging-indent implicit stops and HTML tab fidelity remain incomplete.

The paired artifact checks preserve fifteen of sixteen bar-tab results across both readers. Wordcraft's pinned reader takes only the first 64 tab entries and drops our bar after 64 inherited-tab clears in the nondefault-interval case. Cross-reader rendered equivalence is not claimed.


## Contextual table layout (2026-10-16)

Insertion above/below/left/right, table-wide equal column weights and minimum row heights, and the nine horizontal/vertical alignment choices are available through the Table Layout ribbon, CLI, macros and live MCP. Horizontal alignment affects every paragraph in the target cells; vertical alignment retains shading, borders and padding. Text selections or rectangular cell selections survive alignment and undo. Insertion below a merged cell uses its far edge; selected row insertion inserts the selected row count. New rows inherit the focused row's cell appearance and minimum height.

Sixty paired editing/DOCX checks preserve cell text, alignment, vertical alignment, shading, row heights and grid proportions across both readers for the tested unmerged fixtures. Row distribution equalizes minimum heights, with a floor of 18 points; content may make individual rows taller. It is not an exact-row-height implementation. Nested tables, rotation, asymmetric borders and full merged-table insertion fidelity remain incomplete. Cell/table splitting and AutoFit are described below.


## Table splitting and alternative text

Cell subdivision preserves the physical width of neighboring cells by refining the grid and retaining spans. Table splitting clips crossing vertical merges and keeps their text in the first table, with stable paragraph/comment IDs. DOCX and native storage preserve the resulting geometry. Table titles and descriptions now round-trip through native storage and DOCX `tblCaption`/`tblDescription`; HTML export emits accessible metadata. Tabs/newlines/carriage returns in metadata use XML character references. HTML import rebuilds table structure and spans but not table formatting or metadata.


## Table formulas and Quick Tables (2026-10-17)

Table aggregate fields retain their instruction and cached result in native storage and DOCX. Update Fields recalculates the supported aggregates in table cells; locked or unsupported fields keep cached results. The current evaluator uses decimal-point numbers, bounded directional/A1 references and six aggregate functions. Language-specific numeric parsing, arithmetic expressions and custom numeric format switches remain gaps. Quick Tables serialize as ordinary editable tables with repeated headers and materialized cell/character appearance; named/conditional table-style definitions are not preserved as template identities.

## Table preferred width and placement

Native/DOCX storage preserves preferred table width (points/percent/auto), alignment, indentation and fixed/AutoFit layout. DOCX grid and cell widths contain stored preferred column proportions; per-cell preferred-width overrides are not modeled. Shared editor/PDF geometry resolves contents widths, while phone reflow adapts absolute sizing without changing storage. HTML exports sizing and alignment margins.

## Table row heights and breaks

Native and DOCX now retain automatic, minimum and exact row heights and the row page-break policy (`trHeight`/`hRule`, `cantSplit`). Screen/PDF clip overflow in exact rows without deleting text. New DOCX files declare compatibility mode 15; imported settings remain preserved. Legacy table placement, Wordcraft minimum-height insets, HTML exact clipping and broader keep/footnote interactions remain incomplete.


## Paragraph line spacing

Native/DOCX now preserve multiple, exact-point and minimum-point line spacing, named-style inheritance and tracked previous settings. Exact layout clips in screen/PDF output. HTML/text exports do not retain all spacing semantics.


Advanced character formatting retains small/all caps, double underline/strike, hidden text, spacing, scale and vertical position through native XML and DOCX, including tracked previous values and named paragraph styles. Canvas and PDF use effect-aware glyph measurements and actual font ascent for baseline alignment. Case effects preserve source text; hidden text is omitted from canvas/PDF glyphs but remains in the editable model. HTML exports CSS without promising reciprocal import.


Underline modes and color, outline/shadow/emboss/engrave now persist in native XML and DOCX, including prior tracked values. Standard OOXML underline variants share screen/PDF paths; legacy single/double underline values remain compatible. PDF outline uses transparent stroked glyphs and canvas outlines preserve the underlying highlight. HTML effects are approximate CSS. Seven additional heavy/long underline variants are preserved locally; the pinned reference normalizes some of those variants, so they are not claimed as reciprocal equivalence.

## Drawn objects (2026-10-17)

Shapes, text boxes, WordArt, charts, SmartArt, display equations, ink/canvases, icons, signature lines and object placeholders are block-level `Drawing`s carried by pictures.

- Native XML stores the drawing encoding exactly.
- DOCX writes shapes and text boxes as Word shapes (`wps:wsp`, preset geometry, `txbx`). Equations are Office Math paragraphs. Charts are native chart parts (`c:chart`, see below). All other kinds are a group of custom-geometry shapes, so Word shows the same vector picture. Every drawing also carries its exact encoding in a `wp:docPr` extension, so this app round-trips it unchanged.
- Limits in Word: SmartArt created or edited here is saved as diagram parts like Wordcraft's, its cached drawing carries the hierarchy and radial connector lines and the cycle ring (the cycle ring is drawn as one circle, where Wordcraft caches curved arrows between nodes), and it has not been verified in Word; ink is not Word ink. Equation numbers are kept inside the math zone.
- Importing foreign Word shapes and text boxes reads geometry, fill, outline, text and anchoring. Foreign groups produce conversion warnings.
- Charts (2026-10-20): `c:chartSpace` parts are read from their cached values into an editable chart (column, bar, line, pie, doughnut, area, scatter; 3-D, stock, radar, surface, bubble and of-pie map to the nearest type with a warning). Read: series names, categories (or scatter x values), values, title, legend on/off and value data labels; capped at 29 series and 199 rows. Not read: stacked/percent grouping, axis scaling and number formats, colors and styles, secondary chart groups of combination charts (warned), trendlines and error bars. The chart is drawn by `DrawingOps`, so the editor and PDF match. An unedited chart is saved by referencing its original chart part (and embedded workbook) unchanged; an edited or inserted chart is written as a new `word/charts/cn1chartN.xml` with literal data (`c:strLit`/`c:numLit`) and no embedded workbook, so Word shows it but "Edit Data in Excel" has no sheet. Insert > Chart and Insert > Edit Chart Data (CSV) edit charts.
- SmartArt (2026-10-20): the diagram data model is read into the SmartArt text outline (nodes and levels from `parOf` connections) and the layout id is mapped to list, process, cycle, hierarchy, pyramid, radial, matrix or venn. While unedited, it is drawn from the `dsp:drawing` Word saves beside the diagram (preset geometry, solid/scheme fills and outlines, text; gradients, effects, rotation and theme tints are not drawn) and saved by referencing the original diagram parts. Without a saved drawing, or after its text/layout/colors are edited, it uses this app's basic layout; created or edited SmartArt is saved as new diagram parts as Wordcraft writes them (`word/diagrams/datacn1N.xml`, layout, quickStyle, colors and a cached `dsp:drawing` of the node shapes, all relationships of the story part, with content-type overrides). The colors part's `uniqueId` is read back into accent1-6/colorful.
- Equations read from DOCX (`m:oMath`/`m:oMathPara`) keep their structure: runs, fractions, sub/superscripts and pre-scripts, radicals, n-ary operators, delimiters, matrices, equation arrays, accents, bars, functions, lower/upper limits, group characters and boxes are laid out and drawn (editor and PDF share the same layout; glyphs are measured with the renderer's metrics). The source math zone is written back verbatim (self-closing tags become open/close pairs) until the equation is edited; edited or typed equations are regenerated as built-up Office Math from their linear form, except numbered or normal-text equations, which are still written as one linear run.
- Limits: math formatting (`m:rPr` styles, `w:rPr` run fonts/colors), alignment points, spacing and break properties, phantom/box options and `m:oMathParaPr` are kept only through the verbatim copy, not modeled. Inline `m:oMath` in a paragraph is an inline equation (one object character on the line) written back as `m:oMath` from its linear text, so its OMML formatting is regenerated rather than copied verbatim; display `m:oMathPara` stays a block. Nesting deeper than 40 levels is flattened to text. Delimiters grow by font size, not with stretched glyph assemblies; PDF uses the base font, so symbols outside WinAnsi may not print. Linear input covers a UnicodeMath subset (`a/b`, `x_1^2`, `sqrt(x)`/`√`, `\sum_(i=1)^n`, `(a&b@c&d)` matrices, `\alpha`-style control words, function names, skewed/linear fractions, floor/ceiling/double-square brackets, `〖〗` grouping, combining accents U+20D0-U+20EF and the circled/big n-ary operators). In-place editing (inline and display) stores its result as linear text, so DOCX/PDF see an ordinary edited equation; empty structure slots are saved as the U+2B1A placeholder (as Word and Wordcraft show them), and Wordcraft's forced stacked/side limit placement for some gallery templates is not kept because linear text cannot carry it. Seven one-sided and vertical-bar bracket templates (`|x|`, `‖x‖`, `├…┤` forms) are not parsed as delimiters and insert as linear text. There is no ink math.
- PDF draws vector paths with base-font text. Partial transparency is blended against white, and characters outside WinAnsi print as "?".
- HTML exports inline SVG.
- Text wrapping is block-granular:
  - Square/tight wrap narrows whole paragraphs that start beside a left- or right-aligned object.
  - Behind/in-front objects take no space.
  - There is no free positioning or per-line contour wrapping.

## Content controls (2026-10-17)

Content controls are atomic fields with a `CONTROL` instruction and survive native XML exactly. DOCX writes run-level `w:sdt` for:
- rich text, plain text, picture, building block, check box (`w14:checkbox`), combo box, drop-down list, date and repeating section (`w15`);
- title, tag, lock and placeholder state.

Limits:
- Block-level content controls are read as their contents.
- Repeating-section items are stored as "; "-separated content.
- Picture controls hold no image.
- Locks are enforced only for value changes made through `developer.properties`.

### Character decorations
Character shading, border, proofing language, phonetic guide (ruby) and enclosure (circle/square/triangle/diamond) are stored in `CharacterFormat` and round-trip through native XML. DOCX writes shading as `w:shd`, the border as `w:bdr` and the language as `w:lang`; shading reads back as the run background. DOCX writes and reads phonetic guides as `w:ruby` (guide text up to 200 characters, `rubyPr` sized from the base run; imported alignment, raise and language are not kept). Limitation: DOCX does not write or read enclosed characters (`EQ \o` fields); saving them requires explicit conversion. `EQ` fields keep their instruction and show their stored result, with a warning. PDF does not paint shading, borders, ruby or enclosures.

### Document settings, direction and section layout
Application settings (design choices, hyphenation, and others) persist in native XML as `<setting>` children of `<properties>`, and in DOCX as `w:docVars/w:docVar` entries named `cn1.<key>`. Paragraph direction and Asian typography map to `w:bidi` and `w:kinsoku`/`w:wordWrap`/`w:overflowPunct`/`w:autoSpaceDE`/`w:autoSpaceDN`. Section vertical alignment, direction, text flow and grid map to `w:vAlign`, `w:bidi`, `w:textDirection` and `w:docGrid`. Themes are applied to the styles part, not to a `theme1.xml` part, so Word's theme gallery does not show the chosen theme.

## Drop caps, picture effects and proofing data (2026-10-17)
- Drop caps are a paragraph property (`dropCap="mode,lines,distance,font"` in native XML). DOCX writes Word's form: a framed `w:framePr w:dropCap` paragraph holding the enlarged letter, followed by the rest of the paragraph. On read, a framed letter paragraph (up to 4 characters) merges back into the paragraph after it. Frame anchoring other than text-relative is not read.
- Picture artistic effects and background removal are stored non-destructively in the native `adjust` attribute (`brightness,contrast,transparency,mode,effect,tolerance`). DOCX does not write them (no `a14:imgEffect` / background-removal mapping), so they are lost when exported to .docx.
- Picture compression rewrites the stored image bytes via the platform image codec (PNG stays PNG; others become JPEG at 0.85).
- Spelling and hyphenation use bundled public-domain Moby word data and CC0 Liang patterns (see `docs/third-party/README.md`).
- Encrypted .docx: agile encryption written as a CFB v3 container (DataSpaces, EncryptionInfo 4.4, EncryptedPackage); read supports CFB v3/v4 and SHA-512 agile only. Hashing, HMAC and AES-CBC come from `com.codename1.security`; encryption and decryption interoperate with `msoffcrypto-tool` on Mac, iOS simulator and Android emulator; the web build is unchecked. Insert Text from File accepts a password for encrypted DOCX.

## Native XML round-trip hardening (2026-10-17)

Native certification loads every recorded before-state through native XML, so the round-trip must be exact:
- Header and footer stories now each get their own id set. Previously a header paragraph could reuse a body id and fail the duplicate-id check on reload.
- Drawing attributes map C0 control characters to U+E000 and up on write and back on read. Raw control characters are illegal in XML 1.0.
- `word_load` loads a document without undo history; history is never serialized (see `.cn1doc` above).

## Word 97-2003 binary import (2026-10-20)

`.doc` files open read-only through `DocBinary`, which translates the binary structures into an in-memory
DOCX package and reuses the DOCX mapping; saving always writes a new file in another format. Supported: text
across 8-bit and UTF-16 pieces, character formatting (bold, italic, underline, strike, caps, hidden, size,
font, colour, highlight, super/subscript), paragraph alignment, indents, spacing and keep options, named
paragraph and character styles, LST/LFO lists, tables (column widths, borders on or off, horizontal merges from
TC80 fFirstMerged/fMerged or sprmTMerge, vertical merges from fVertMerge/fVertRestart or sprmTVertMerge,
nested tables from sprmPItap/sprmPFInnerTtp up to 16 levels), sections and page setup, headers/footers,
footnotes/endnotes, complex fields, inline PNG/JPEG pictures, comments (PlcfandRef/PlcfandTxt with
GrpXstAtnOwners authors, initials and SttbfAtnBkmk ranges), bookmarks (SttbfBkmk/PlcfBkf/PlcfBkl), tracked
insertions and deletions (sprmCFRMarkIns/Del with SttbfRMark authors and DTTM dates) and main-text floating
objects (PlcSpaMom FSPA anchors joined with the OfficeArt drawing: BLIP-store pictures, embedded or at the
FBSE foDelay offset, preset shapes with solid fill/line colours, and text boxes with their PlcftxbxTxt text;
page/margin/column/paragraph offsets and none/square/top-and-bottom/behind wrapping). The editor model then
treats floating DOCX drawings as it does for .docx (a separate paragraph plus a conversion warning). Not yet:
encrypted or Word 6/95 files (rejected), floating objects in headers, grouped shapes, freeforms and other
non-preset geometry (dropped), tight/through wrap (written as square), shape rotation, line widths and
gradients, paragraph-mark and formatting revisions, comment and bookmark boundaries inside table-row end
marks, per-cell borders/shading, and list-style formatting carried by styles. Wordcraft models only one table
level and drops comments and revision marks.

## OpenDocument Text (.odt)

`Odt.read` and `Odt.write` share the DOCX model mapping: import builds an in-memory DOCX
from the ODF parts, export translates the DOCX writer's package, so `read(write(doc))`
round-trips what both directions cover (tested in `OdtTest`).

Covered: paragraphs and `text:h` headings (outline level mapped to `Heading1..9`), common
paragraph/text styles with parent inheritance and the default style, automatic `P`/`T`
styles (bold, italic, underline, single/double strike, size, font, color, background,
super/subscript, caps, small caps, hidden, letter spacing; alignment, indents, spacing,
line spacing, keep-with-next/together, widows, page break before/after, shading,
right-to-left), `text:s`, tabs, line breaks, hyperlinks (external and `#bookmark`),
bookmarks, footnotes and endnotes, `text:list` bullets/numbering with nine levels, prefix,
suffix, start value and continued numbering, tables with column widths, column/row spans
and cell background, `draw:frame`/`draw:image` with size and `svg:desc`/`svg:title` alt
text, page size, orientation and margins, default header and footer of the first master
page, and title/subject/author/description/keywords.

Not carried: `office:annotation` comments and `text:tracked-changes` (dropped on import,
as Wordcraft does; deleted text stays deleted), text boxes and drawn shapes, fields (their
current text is kept), tab stops, paragraph and table borders (export writes a thin cell
border), first/even-page headers, more than one page style or section, list item
`start-value` overrides and `text:list-header` indentation, table header-row repetition,
embedded objects and formulas. LibreOffice acceptance has not been checked locally
(`soffice` is not installed on the development machine).

## Word XML import: Flat OPC and Word 2003 XML (2026-10-20)

`Docx.read` recognizes a single-file XML document by its first bytes and rewrites it as package
parts for the DOCX reader (`WordXml`); the app opens `.xml` files this way and saves them as a
new file. Import only.

- Flat OPC (`pkg:package`, Word's "Word XML Document"): every `pkg:part` becomes a part, from its
  `pkg:xmlData` element or base64 `pkg:binaryData`, so everything the DOCX reader supports is read.
  Part names are checked for traversal and duplicates; at most 10000 parts and 512 MB decoded.
- Word 2003 XML (`w:wordDocument`): body and styles are carried over, with hyphenated 2003 names
  (`b-cs`, `sz-cs`) and `vmerge`/`hmerge` mapped to ECMA-376 names, `wx:` section wrappers and
  smart tags unwrapped, `aml:annotation` bookmarks converted, and tracked insertions/deletions
  accepted with a warning. Converted into the parts the DOCX reader understands: `w:listDef`/
  `w:list` to a numbering part (`w:nfc` codes mapped to `w:numFmt`, `w:listPr`/`w:ilfo` to
  `w:numPr`/`w:numId`); `w:hlink w:dest` to an external hyperlink relationship (bookmark-only
  links stay anchors); `w:binData` + `v:imagedata` to media parts and inline DrawingML pictures
  sized from the VML `style` width/height (pt/in/cm/mm/px; default 100x50 pt, capped at 20000 pt);
  `v:textbox` to an inline DrawingML text box; `w:hdr`/`w:ftr` in `w:sectPr` to header/footer
  parts (`odd` = default, `first`, `even`); inline `w:footnote`/`w:endnote` to notes parts with
  references; `aml:annotation` `Word.Comment.Start/End/Comment` to comment ranges, references and
  a comments part. Limitations: floating VML positioning is not kept (pictures and boxes become
  inline); VML shapes other than pictures and text boxes, linked (non-`binData`) pictures and
  undecodable picture data are dropped with a warning; comment dates are not read. Namespace prefixes
  are matched by the conventional `w:`/`wx:`/`aml:` names, not by resolved URIs.
