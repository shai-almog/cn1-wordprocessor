# Format boundaries

| Format | Current behavior | Limits |
| --- | --- | --- |
| `.cn1doc` | ZIP with native document XML and optional original DOCX | Primary editable storage for implemented model; not a Word format. Undo history is not serialized. |
| DOCX | Paragraphs, inherited paragraph/character styles plus direct formatting, spacing/alignment/indents/periodic tabs/page geometry, nine-level lists with simple labels/restarts, hyperlink relationships, range comments with replies and resolved state, named paragraph style definitions, paragraph-level embedded PNG/JPEG pictures, rectangular table content with horizontal/vertical merges and rich cell paragraphs | Theme fonts/colors, custom/composite list labels, bookmarks outside body paragraphs, floating/cropped/rotated/cell pictures, structural/move revisions, advanced fields/sections and advanced table properties are incomplete. Compatibility warnings accompany unsupported constructs. |
| TXT | UTF-8 text, paragraph breaks, table text, picture descriptions | Formatting is discarded by definition. |
| HTML | CN1 text import; styled HTML export including merged tables, embedded data-image pictures and superscript/subscript | Imported HTML objects/tables/layout are limited to the CN1 text importer. Not a browser document renderer. |
| Markdown / RTF / AsciiDoc | CN1 rich-text adapters | Supported text subset only. Markdown/RTF/AsciiDoc table and embedded-picture export is rejected. AsciiDoc output is CLI-accessible. |
| PDF | Paginated text/table/PNG/JPEG export, colors and decorations; native editor embeds its twelve bundled font faces | CLI also embeds the bundled font families. No complete complex shaping/fallback, links, tagged accessibility or PDF import. |

Unedited DOCX export uses the original XML/asset part bytes; ZIP bytes/metadata may differ. An edited DOCX with compatibility warnings is blocked until the user exports an explicitly converted copy. Saving `.cn1doc` retains the editable representation and source DOCX for later recovery. Original assets remaining in a converted ZIP are not evidence of unchanged document semantics.

Bounds: DOCX has at most 4,096 entries, 32 MiB expanded per entry and 128 MiB total expanded data. XML has a 32 MiB input limit, depth 256 and a node limit; DTD/entity declarations, malformed nesting and duplicate attributes are rejected. Native XML rejects unknown versions and duplicate block IDs. File adapters cap input at 128 MiB.

There is no claim of arbitrary DOCX losslessness or pagination equivalence. Use the compatibility report and keep the source document when testing imports. Font substitution and incomplete styles can change line and page breaks even before editing.

CLI PDF pagination measures the bundled TrueType advances used by the exported PDF. CLI/MCP page-reference commands use those same metrics; native commands use the editor's print-layout engine, independent of mobile reflow. Native platform font metrics can still differ from headless metrics. No kerning or complex shaping equivalence is claimed. The low-level `PdfExporter.write(document)` overload and default standalone `Commands()` retain the explicit Helvetica fallback; application hosts supply their layout engine/font provider. Fonts and licenses are listed in [third-party notices](../third-party/README.md).

The GUI now bundles Source Sans 3, Source Serif 4 and JetBrains Mono (OFL licenses included). Imported family names outside those families are substituted. Native and CLI PDF export use the bundled fonts. Basic numbered lists restart after non-numbered paragraphs. Simple nested levels, start overrides and Roman/letter formats round-trip; composite numbering text such as `%1.%2` remains an explicit approximation. The hollow-circle and square nested bullet markers are exported as vector geometry in PDF.

Comments use standard range markers and `comments.xml`; resolved state uses paragraph IDs and `commentsExtended.xml`. Rewriting regenerates the extended metadata to match the rewritten comment IDs. Replies use linked comment paragraphs and preserve author, text and resolution. Editing thread/reply text preserves that metadata; leading, interior and trailing blank paragraphs survive native/DOCX round trips. Body ranges and ranges within one table cell, including multiple cell paragraphs, are supported. Cross-cell, body-to-cell and note/margin-story ranges remain unsupported. Comment anchors follow explicit edit ranges, including repeated text, and undo restores the previous anchors.

Clipboard copy publishes native document XML, HTML and plain text. Native XML preserves implemented paragraph/run/table properties; macOS returns private MIME data as UTF-8 bytes. The paste path decodes those bytes before using the model. External HTML still uses the limited CN1 text importer and does not establish full Word clipboard interoperability.

Superscript/subscript preserve base point size plus baseline alignment in native XML and DOCX; screen/PDF rendering uses a smaller shifted glyph. Mixed font sizes share a line baseline. Merged cells preserve owning paragraphs and serialize DOCX gridSpan/vMerge and HTML row/column spans. Table column proportions, minimum row heights, solid uniform cell borders, shading, symmetric padding, vertical alignment and repeated leading header rows now persist in native/DOCX and render in screen/PDF. Nested tables, asymmetric/decorative borders and floating positioning remain incomplete; DOCX conversion warnings identify these cases. Periodic fallback tabs serialize a bounded sequence of 64 positions; explicit aligned tabs and leaders are supported as described below.

Pictures retain the original PNG/JPEG bytes in native/DOCX files. Display uses a bounded decoded-image cache and a maximum 2048-pixel preview edge; exports use original bytes. Images are bounded to 20 MiB and 16 megapixels. PNG PDF decoding supports non-interlaced grayscale, RGB, indexed and alpha images (including packed samples and 16-bit samples converted to 8-bit PDF pixels); interlaced PNG PDF export fails explicitly. JPEG PDF export embeds the encoded data without recompression. Floating DOCX placement, crops and rotations produce conversion warnings; picture-in-text import moves the picture after its paragraph and warns. Cell pictures remain unsupported.

Paragraph styles store resolved character and paragraph defaults. Changing a definition updates matching body/cell paragraphs while retaining properties differing from the prior defaults. Inheritance is flattened on edited export; linked character styles, style sets and a full Word style inspector remain incomplete. A direct override equal to its previous default cannot be distinguished from inheritance in the current model.

Native PDF export and print embed the same Source Sans 3, Source Serif 4 and JetBrains Mono faces used by the editor (four variants each), with CID glyph maps, widths and Unicode extraction maps. Missing glyphs fail explicitly. Fonts are compressed whole; shaping, kerning and system fallback are not yet fully preserved. CLI export uses the same embedded bundled fonts and glyph advances.


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

Eight paired/cross-import artifacts pass their reference assertions against the pinned Wordcraft build; see `reference-cross-import.json`. Wordcraft imports still report unrelated character/paragraph/section formatting warnings. These checks do not establish complete DOCX fidelity or complete feature parity.


## Generated contents and caption lists (2026-10-15)

Heading-based contents and caption-based figure/table/equation lists are editable paragraph stories with portable TOC, SEQ and PAGEREF fields. They coexist in one document. DOCX export writes standard content controls and complex fields spanning the entry paragraphs, preserving nested page references. Import handles these ranges plus the pinned Wordcraft exports. Native storage retains the same options and bookmarks. Generated labels are refreshed when source headings contain live fields, before pagination settles.

The comparison retains a reference interoperability failure: Wordcraft reads our complex figure-list fields and can edit the source caption, but its Update figures command leaves the generated entries stale. Its updater recognizes atomic TOC fields, not the imported complex field starts. Our app updates Wordcraft's exported lists. This is recorded as three failed reverse-direction checks, not complete round-trip parity. Other unsupported DOCX features still require explicit conversion consent.

TOCs now accept custom style maps, direct outline levels and marked TC entries, including category/level filters. Direct outline levels remain distinct from inherited style levels through native/DOCX storage and tracked-format rejection. Twenty-four paired and two-way import/update checks pass for these sources. Imported level ranges other than 1 through N, per-level page-number suppression, arbitrary complex-field ranges, duplicate lists for the same caption label, custom generated-entry formatting and full Word fidelity remain incomplete. Lists are main-body workflows; figure/caption anchoring to movable objects is not implemented.


## Footnotes and endnotes (2026-10-15)

Native files store an atomic inline reference with an owned rich paragraph story. DOCX uses standard footnotes/endnotes parts, reference elements, automatic/custom marks and part-scoped hyperlink relationships. Notes work in body paragraphs and table cells; their text, formatting and revisions survive round trips. Copying references creates distinct note identities. PDF includes page-bottom footnotes and document-end endnotes; long notes continue over pages. HTML exports linked note sections, and plain-text output retains their text.

The note model currently uses continuous decimal footnotes and lower-Roman endnotes, with optional custom marks. Other explicit number formats, restart/start/placement settings produce import warnings. Tables, pictures and nested notes inside a note story are not supported; unsupported imported note content is reported or rejected. HTML/text import flattens note semantics. These boundaries and the paired DOCX probes are recorded in `notes-acceptance.json`; they do not establish complete Word fidelity.


Note settings now preserve sparse section overrides and document defaults in native XML, DOCX `settings.xml` and section `footnotePr`/`endnotePr` properties. Supported formats are decimal, lower/upper Roman, lower/upper repeated letters, decimal with a leading zero, numeric ordinal, hexadecimal, Chicago symbols, numbers in dashes and no number. Starts are 1–32767; numbering can continue or restart per section, with page restarts for footnotes. Footnotes can sit at the page bottom or below text; endnotes can end each section or the document. Physical printed pages determine page-restarted labels, including when the phone view reflows. Layout reports a warning if page-dependent numbering cannot settle within eight passes.

Roman rendering currently falls back to decimal above 3999; repeated-letter and Chicago labels are capped at 40 glyphs. Unsupported regional/textual numbering formats still produce DOCX conversion warnings. Direct in-page editing, tables/pictures in note stories, note-story comments/bookmarks, custom continuation separators and broader complex-table/continuous-section fidelity remain incomplete. Document labels stored before pagination use document order; the layout and `references.notes` command expose page-resolved labels. Plain-text/HTML exports do not yet resolve page-dependent labels with print pagination.


### Index fields

The document model stores empty-result XE marks and an INDEX marker with generated paragraphs. Native XML and DOCX retain their instruction data, formatted page references, indents and tabs. DOCX export encloses generated entries in a standard multi-paragraph complex INDEX result; import recognizes Index1–Index9, IndexLetter and Wordcraft ListEntry paragraphs. The first body index is managed; additional instances are not certified. Body/table marks, nested subentries, cross-reference text, bookmark ranges and bold/italic page references are supported. Standard page numbering includes section restarts and the space consumed by the index. A missing range bookmark produces an explicit error entry.

Multi-column indexes and unimplemented INDEX switches are rejected during regeneration. Unicode case folding is used for grouping/sorting; locale-specific collation and supplementary-character letter headings are incomplete. XE marks have no visible result; field-code display can expose their instructions, but a dedicated hidden-mark editing UI is absent. Existing DOCX conversion warnings for unrelated source styles/sections still apply. The pinned Wordcraft reader does not preserve the generated INDEX range correctly when updating it; the comparison keeps those two failures visible in `index-acceptance.json`.


### Table of authorities

TA citation marks and TOA generated ranges round-trip through native XML and standard DOCX complex fields. TableofAuthorities and reference ListEntry result paragraphs are recognized. Citation text is a single key even when it contains colons. One table, title, leaders and page references are supported; categories, short forms, passim and other TOA switches reject regeneration. Both own exports and imported reference exports pass semantic checks. The pinned Wordcraft reader duplicates our cached TOA range when updating it; failures are retained in `authorities-acceptance.json`.


### Citations and bibliography sources

Native XML stores an immutable, document-owned source library and citation style. DOCX uses the standard bibliography Sources custom-XML namespace, Author/Editor NameLists, source metadata and a complex BIBLIOGRAPHY result range. CITATION fields retain tag, page range, cached result and lock state. The source library survives editing, recovery and undo; unrelated package custom-XML stores survive export. Source records are bounded (2,000 sources, 2,000 characters per value, 64-character tags), and duplicate source tags are rejected on import.

Twelve style renderers match paired book/article output fixtures. Author/date bibliography entries have a hanging indent. Composite citations and unimplemented switches retain cached results; filtered bibliographies and unknown styles cannot be regenerated. Corporate author distinctions, broader source roles, note/header citations, locale collation, global libraries and full CSL/style-guide behavior remain incomplete. The pinned Wordcraft reader duplicates our standard multi-paragraph bibliography result on update; the twelve reverse-direction failures are retained in `citations-acceptance.json`. Source metadata alone does not establish Microsoft Word rendered fidelity.


Mail merge fields (`MERGEFIELD`, `ADDRESSBLOCK`, `GREETINGLINE`, supported conditional/record rules) retain instruction/cache/lock metadata in native and DOCX templates. Preview updates cached results; Finish converts supported unlocked merge fields to rich text, cloning table/section/note content and remapping comment/bookmark ranges and internal references. Recipient source lists, filtered/sorted views, exclusions and address mappings are session state, not serialized document or recovery data. Completed merges export only the selected records in their selected order. Envelope and label output uses ordinary sections/tables. CSV/XLSX/XLSM/ODS input is a bounded recipient-data import, not a spreadsheet editor; see [Mailings](MAILINGS.md) for exact limits and unsupported formatting.


Document comparison emits normal tracked run and paragraph-break revisions; generated comparisons survive native/DOCX storage and accept/reject in either implementation for the tested subset. Comparison supports paragraph text and text within unchanged table geometry, with direct-format revisions on unchanged paragraphs. It rejects unsupported structures before replacing the current document. See [comparison scope](REVIEW-COMPARISON.md); Combine and full structured comparison remain incomplete.


## Rectangular clipboard and label sheets (2026-10-16)

Whole-cell rectangular copy preserves a rich table fragment; in-table paste requires matching destination size and merged-cell geometry. TSV paste fills a fitting rectangle atomically. DOCX checks cover selected row/column/table formatting and text replacement across both readers. Recipient label generation now creates additional physical sheets as separate sections and preserves those boundaries through native and DOCX round trips. See [acceptance and remaining limits](TABLE-SELECTION.md).


## Tab fidelity (2026-10-16)

Bar tabs are stored in native files and DOCX and rendered as vertical rules in the shared screen/PDF layout, including empty and wrapped paragraphs, table cells and margin stories. Bar positions do not consume typed tabs or shift with paragraph alignment or first-line indentation. Paragraph > Tabs exposes bar alignment with transactional apply/cancel and undo.

DOCX import resolves additive style tabs and explicit clears before recognizing periodic fallback stops. Export emits clears for removed inherited stops, so clearing a named style's tabs survives reopening. Document `defaultTabStop` is read and written. Export chooses a paragraph interval, preferring a bar-bearing paragraph's interval; differing paragraph intervals remain bounded explicit fallback lists. Multiple bar-bearing paragraphs with different intervals require explicit conversion. Locale-specific decimals, RTL tabs, hanging-indent implicit stops and HTML tab fidelity remain incomplete.

The paired artifact checks preserve fifteen of sixteen bar-tab results across both readers. Wordcraft's pinned reader takes only the first 64 tab entries and drops our bar after 64 inherited-tab clears in the nondefault-interval case. This failure is retained in [tab acceptance](tab-fidelity-acceptance.json). Independent PDF rendering and native canvas screenshots are recorded in `artifacts/parity-20261016-tabs`; cross-reader rendered equivalence is not claimed.


## Contextual table layout (2026-10-16)

Insertion above/below/left/right, table-wide equal column weights and minimum row heights, and the nine horizontal/vertical alignment choices are available through the Table Layout ribbon, CLI, macros and live MCP. Horizontal alignment affects every paragraph in the target cells; vertical alignment retains shading, borders and padding. Text selections or rectangular cell selections survive alignment and undo. Insertion below a merged cell uses its far edge; selected row insertion inserts the selected row count. New rows inherit the focused row's cell appearance and minimum height.

Sixty paired editing/DOCX checks preserve cell text, alignment, vertical alignment, shading, row heights and grid proportions across both readers for the tested unmerged fixtures. Row distribution equalizes minimum heights, with a floor of 18 points; content may make individual rows taller. It is not an exact-row-height implementation. Nested tables, rotation, asymmetric borders and full merged-table insertion fidelity remain incomplete. Later checkpoints below add cell/table splitting and AutoFit. See `table-layout-acceptance.json` for this earlier checkpoint.


## Table splitting and alternative text

Cell subdivision preserves the physical width of neighboring cells by refining the grid and retaining spans. Table splitting clips crossing vertical merges and keeps their text in the first table, with stable paragraph/comment IDs. DOCX and native storage preserve the resulting geometry. Table titles and descriptions now round-trip through native storage and DOCX `tblCaption`/`tblDescription`; HTML export emits accessible metadata. Tabs/newlines/carriage returns in metadata use XML character references. HTML import still uses the limited text importer and does not preserve complete tables. See [table splitting and alternative text](TABLE-SPLITTING.md) for validation and remaining boundaries.


## Table formulas and Quick Tables (2026-10-17)

Table aggregate fields retain their instruction and cached result in native storage and DOCX. Update Fields recalculates the supported aggregates in table cells; locked or unsupported fields keep cached results. The current evaluator uses decimal-point numbers, bounded directional/A1 references and six aggregate functions. Language-specific numeric parsing, arithmetic expressions and custom numeric format switches remain gaps. Quick Tables serialize as ordinary editable tables with repeated headers and materialized cell/character appearance; named/conditional table-style definitions are not preserved as template identities. See [table formulas and Quick Tables](TABLE-DATA.md) for the supported syntax, insertion semantics and validation boundaries.

## Table preferred width and placement

Native/DOCX storage preserves preferred table width (points/percent/auto), alignment, indentation and fixed/AutoFit layout. DOCX grid and cell widths contain stored preferred column proportions; per-cell preferred-width overrides are not modeled. Shared editor/PDF geometry resolves contents widths, while phone reflow adapts absolute sizing without changing storage. HTML exports sizing and alignment margins. See [table sizing](TABLE-SIZING.md) for the verified scope and remaining advanced table properties.

## Table row heights and breaks

Native and DOCX now retain automatic, minimum and exact row heights and the row page-break policy (`trHeight`/`hRule`, `cantSplit`). Screen/PDF clip overflow in exact rows without deleting text. New DOCX files declare compatibility mode 15; imported settings remain preserved. Legacy table placement, Wordcraft minimum-height insets, HTML exact clipping and broader keep/footnote interactions remain incomplete. See [row sizing and pagination](TABLE-ROWS.md) for evidence and limits.


## Paragraph line spacing

Native/DOCX now preserve multiple, exact-point and minimum-point line spacing, named-style inheritance and tracked previous settings. Exact layout clips in screen/PDF output. See [line-spacing acceptance and remaining rendering differences](LINE-SPACING.md). HTML/text exports do not retain all spacing semantics.


Advanced character formatting retains small/all caps, double underline/strike, hidden text, spacing, scale and vertical position through native XML and DOCX, including tracked previous values and named paragraph styles. Canvas and PDF use effect-aware glyph measurements and actual font ascent for baseline alignment. Case effects preserve source text; hidden text is omitted from canvas/PDF glyphs but remains in the editable model. HTML exports CSS without promising reciprocal import. See [character formatting](CHARACTER-FORMATTING.md) for units, bounds and remaining limitations.


Underline modes and color, outline/shadow/emboss/engrave now persist in native XML and DOCX, including prior tracked values. Standard OOXML underline variants share screen/PDF paths; legacy single/double underline values remain compatible. PDF outline uses transparent stroked glyphs and canvas outlines preserve the underlying highlight. HTML effects are approximate CSS. Seven additional heavy/long underline variants are preserved locally; the pinned reference normalizes some of those variants, so they are not claimed as reciprocal equivalence. See [current font acceptance](CHARACTER-FORMATTING.md).

## Drawn objects (2026-10-17)

Shapes, text boxes, WordArt, charts, SmartArt, equations, ink/canvases, icons, signature lines and object placeholders are block-level `Drawing`s carried by pictures.

- Native XML stores the drawing encoding exactly.
- DOCX writes shapes and text boxes as Word shapes (`wps:wsp`, preset geometry, `txbx`). Equations are Office Math paragraphs. All other kinds are a group of custom-geometry shapes, so Word shows the same vector picture. Every drawing also carries its exact encoding in a `wp:docPr` extension, so this app round-trips it unchanged.
- Limits in Word: charts are not native Word charts, SmartArt is not editable as SmartArt, and ink is not Word ink. Equation numbers are kept inside the math zone.
- Importing foreign Word shapes and text boxes reads geometry, fill, outline, text and anchoring. Foreign charts, SmartArt and groups produce conversion warnings.
- Equations read from foreign DOCX keep their text but not their structure.
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
Character shading, border, proofing language, phonetic guide (ruby) and enclosure (circle/square/triangle/diamond) are stored in `CharacterFormat` and round-trip through native XML. DOCX writes shading as `w:shd`, the border as `w:bdr` and the language as `w:lang`; shading reads back as the run background. Limitation: DOCX does not yet write or read phonetic guides (`w:ruby`) or enclosed characters (`EQ \o` fields), and PDF does not paint shading, borders, ruby or enclosures.

### Document settings, direction and section layout
Application settings (design choices, hyphenation, and others) persist in native XML as `<setting>` children of `<properties>`, and in DOCX as `w:docVars/w:docVar` entries named `cn1.<key>`. Paragraph direction and Asian typography map to `w:bidi` and `w:kinsoku`/`w:wordWrap`/`w:overflowPunct`/`w:autoSpaceDE`/`w:autoSpaceDN`. Section vertical alignment, direction, text flow and grid map to `w:vAlign`, `w:bidi`, `w:textDirection` and `w:docGrid`. Themes are applied to the styles part, not to a `theme1.xml` part, so Word's theme gallery does not show the chosen theme.

## Drop caps, picture effects and proofing data (2026-10-17)
- Drop caps are a paragraph property (`dropCap="mode,lines,distance,font"` in native XML). DOCX writes Word's form: a framed `w:framePr w:dropCap` paragraph holding the enlarged letter, followed by the rest of the paragraph. On read, a framed letter paragraph (up to 4 characters) merges back into the paragraph after it. Frame anchoring other than text-relative is not read.
- Picture artistic effects and background removal are stored non-destructively in the native `adjust` attribute (`brightness,contrast,transparency,mode,effect,tolerance`). DOCX does not write them (no `a14:imgEffect` / background-removal mapping), so they are lost when exported to .docx.
- Picture compression rewrites the stored image bytes via the platform image codec (PNG stays PNG; others become JPEG at 0.85).
- Spelling and hyphenation use bundled public-domain Moby word data and CC0 Liang patterns (see `third-party/README.md`).
