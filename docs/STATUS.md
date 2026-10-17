# Implementation status

The approved target is a standalone application and reusable Java libraries, targeting desktop, mobile, and web, matching Wordcraft's documented feature subset with fewer application lines and no material performance regression. Automation includes compiled Java extensions, portable command macros, and desktop CLI/MCP access. **That target is not achieved by this implementation.**

## Latest verified scope

**Codename One 7.0.278 baseline (2026-10-17).** The app now builds against the released 7.0.278 framework instead of the isolated snapshot; all reactor tests pass and the Maven `mac-source` ParparVM build runs natively. `.mvn/maven.config` works around Surefire 3.0.0-M5 resolving the CN1 test provider only from Central.

**Native integration (Mac native build, verified through the app MCP and the real macOS pasteboard):**

- Copy and cut put model XML, HTML, RTF, Markdown and plain text on the system pasteboard. Paste and drop read the richest offered format, then images (PNG/JPEG/GIF) and files (.docx is handed to the host; .html/.rtf/.md/.txt are inserted). Verified: RTF placed on `NSPasteboard` by another process pastes with its bold run; a copy is read back by another process in all five formats. Limitation: exported RTF carries bold/italic but not text color (CN1 `RichTextImporter` RTF writer).
- OS drag and drop: the document is a native drop target that places the caret under the pointer. Dragging a selection out exports the same formats; dragging inside moves it as one undo step (`Session.moveSelectionTo`, `DragMoveTest`). Not yet verified with a physical Finder/Safari drag.
- Dark and high-contrast appearance follow the OS live (500 ms poll; CN1 has no change callback). Chrome colors map from their light originals, icons redraw, and the page stays paper. Verified visually in OS dark mode.
- Accessibility: ribbon tabs expose TAB/TAB_LIST roles with selection, heading paragraphs expose heading levels, and the zoom slider is labeled; every interactive node in the main form now has a label. Not yet verified with a live VoiceOver session.


The integrated Font workflow now covers small/all caps, hidden text, spacing/scale/position, double strike, 18 underline choices with independent color, and outline/shadow/emboss/engrave. Word-at-caret targeting, Font dialog editing, Format Painter/clear, style overrides, tracked history, native/DOCX persistence and canvas/PDF output use the same model. **519 Java 8 tests**, **98 native Mac/iPhone checks**, **102 paired character checks**, 13 PDF pixel/geometry checks, 24 paired spacing regressions and 59 paired document cases pass. Original XML/recipient state was restored and the review apps stopped. See [character formatting](CHARACTER-FORMATTING.md) and [current evidence](text-effects-acceptance.json). Typography, advanced objects/tables/sections, broader workflows, release-baseline integration and all-platform/full parity remain incomplete.

Paragraph spacing now supports Multiple, At least and Exactly through the ribbon, Paragraph and style dialogs, shared screen/PDF layout, native storage and DOCX. Named-style inheritance and tracked-format rejection preserve the rule and value. **478 Java 8 tests**, **72 native Mac/iPhone checks**, **24 paired spacing checks**, 24 paired row regressions and 59 paired document cases pass. Both original documents and recipient lists were restored and both review apps stopped. See [line spacing](LINE-SPACING.md). The 24-point rendered line advances match, but baseline positions differ; tight-line clipping is deliberately retained. Advanced tables, typography, broader workflows, release-baseline integration and all-platform/full parity remain incomplete.

Table rows now have Automatic/At least/Exactly height rules, a direct Row height ribbon control and an Allow row to break across pages option. Screen/PDF clip exact-row overflow without deleting text; tighter multiple line spacing is supported and retained by native/DOCX storage. New DOCX exports declare modern compatibility mode to prevent legacy table-placement shifts. **462 Java 8 tests**, **170 native Mac/iPhone checks**, **24 paired row/spacing checks**, 174 table regressions and 59 paired document cases pass. See [table row sizing and pagination](TABLE-ROWS.md). The strict PDF border comparison retains a one-pixel mismatch; minimum-height insets, legacy layout, advanced workflows, current release-baseline integration and all-platform/full parity remain incomplete.

Table Layout now supports AutoFit Contents/Window, fixed widths, point/percentage preferred widths, table alignment and indentation. Ordinary table insertion splits at the caret or replaces a selection, focuses the first cell, and restores a valid body caret when leaving it; Quick Tables share the caret repair. **447 Java 8 tests**, **140 native Mac/iPhone checks**, **51 paired sizing/insertion checks**, 123 table-layout/formula/Quick Table regression checks and 59 paired document cases pass. Native documents and Mailings state were restored and both task-owned apps stopped. See [table sizing and insertion](TABLE-SIZING.md). Advanced tables, typography, wider workflows, current release-baseline integration and all-platform acceptance remain incomplete; this is not full parity.

Table Layout now inserts six aggregate formula fields and recalculates them through Update Formulas or Update Fields, including dependencies, locks and explicit cycle errors. Insert > Quick Tables adds editable tabular, matrix and calendar previews with selection replacement and one-step undo. **427 Java 8 tests**, **92 native Mac/iPhone checks**, **63 paired new-feature/DOCX checks**, 60 table-layout regressions and 59 paired document cases pass. See [table formulas and Quick Tables](TABLE-DATA.md). Language-specific number parsing, named/conditional table styles, broader layout/object features, release-baseline integration and full parity remain incomplete.

Table Layout now splits ordinary/merged cells into equal-width columns and divides tables at the focused row, with one-step undo/redo and preserved comment anchors. Table Properties edits accessible titles/descriptions, retained by native storage, DOCX, clipboard and structural edits. Track Changes now keeps table focus/selection. **409 Java 8 tests**, **68 native Mac/iPhone checks**, **40 paired splitting/metadata checks**, 60 table-layout regressions and 59 existing paired document cases pass. See [table splitting and alternative text](TABLE-SPLITTING.md). Font fallback (including the observed missing emoji glyph), broader table features, current release-baseline integration and full parity remain incomplete.


The contextual Table Layout ribbon now offers direct insertion above/below/left/right, equal column widths/minimum row heights, and all nine horizontal/vertical cell alignments. Inserted rows inherit cell appearance and minimum height. **392 Java 8 tests**, **34 native Mac/iPhone workflows**, **60 paired table-layout/DOCX checks**, 48 table-selection regressions and 59 existing paired document cases pass. See [table layout controls](TABLE-LAYOUT.md). The latest native bundles retain the isolated framework baseline documented in the acceptance record; full parity and release-baseline integration remain incomplete.

Bar tabs, inherited-tab clearing and document default tab intervals now pass **384 Java 8 tests** and **16 native Mac/iPhone dialog checks**. The bars share screen/PDF geometry and do not move typed text. Fifteen of sixteen paired bar-tab DOCX checks pass; Wordcraft drops one bar after 64 clear entries. The 59 existing paired document cases and 48 table/DOCX regressions pass. See [tab fidelity](TAB-FIDELITY.md). Full parity remains incomplete.

Rectangular table selection and multi-sheet recipient labels now pass **374 Java 8 tests**, **40 native Mac/iPhone UI workflow checks**, desktop pointer/keyboard/clipboard checks, **48 paired table/DOCX checks**, 59 existing document cases and thirteen Mailings regressions. The contextual Table Layout ribbon supports selection, formatting, clearing, merging and structural deletion with undo. See [table selection and label sheets](TABLE-SELECTION.md). The additional Mac input fix is a local framework patch; current upstream/release-baseline and full feature parity remain unverified.

Mailings recipient management adds reversible sorting, filtering and source-row inclusion/exclusion, with five-row paging on desktop and phone. The final Java 8 reactor passes **361 tests**; **44 native Mac/iPhone-simulator checks** and **13 paired Wordcraft/interchange regressions** pass for this checkpoint. Source-list readback is available through built-in MCP. See [recipient management](RECIPIENT-MANAGEMENT.md) and `recipient-management-acceptance.json`. Full parity remains incomplete; current-source Linux, Android, browser and Windows acceptance is not established by this checkpoint.

Comment/reply editing and Previous/Next comment controls now pass **356 Java 8 tests and 215 native checks (131 Linux, 42 Mac, 42 iPhone simulator)**. Editing preserves author, range and resolution; navigation follows body/cell order and wraps. Twelve edited-comment DOCX checks, twenty navigation artifact checks, 59 existing paired cases and 20 review interchange checks pass. See [comment-controls acceptance](COMMENT-CONTROLS.md) and `comment-controls-acceptance.json`. Original documents/recipients were restored and task-owned apps stopped. Full parity remains incomplete.

Table-cell comments now pass **350 Java 8 tests and 185 native checks (121 Linux, 32 Mac, 32 iPhone simulator)**. Add, reply, resolve/reopen, Go to, delete and undo work through the ribbon; anchors follow cell edits, paragraph splits and row changes. Twelve paired DOCX cell-comment checks pass in both directions, alongside the 59 paired document cases and 20 review interchange regressions. See [cell-comment acceptance](CELL-COMMENTS.md) and `cell-comments-acceptance.json`. This checkpoint does not certify full Review parity or current Android/browser behavior.

The Unicode character-editing checkpoint passes **340 Java 8 tests, including all 1,093 Unicode 16 grapheme cases; 136 native checks (109 Linux and 27 Mac); and seven desktop Chrome checks**. Logical arrows/selection, deletion, hit testing and wrapping keep combining sequences and joined emoji together. All 59 paired document cases and 20 review cross-import checks pass. See [Unicode editing acceptance](GRAPHEME-EDITING.md) and `grapheme-acceptance.json` for source hashes, artifact provenance, the PWA update caveat and platform limits. This does not certify visual bidi, shaping or full parity.

The typing checkpoint passes **333 Java 8 application tests, 125 native checks (73 Linux, 32 Mac, 20 Android) and nine desktop Chrome checks**. Word-sized typing undo, paragraph shortcuts, insertion-format preservation and native Linux menu accelerators are implemented and exercised within the platform boundaries in [typing acceptance](TYPING-UNDO.md). All 59 paired document cases and 20 review cross-import checks pass. Original native documents and recipient state were restored; task-owned apps, emulator, containers and browser/server were stopped. Full parity and final matched benchmarks remain incomplete. Exact source/artifact hashes are in `typing-acceptance.json`.

The navigation checkpoint passes **318 Java 8 application tests and 91 native checks**: 59 Linux and 32 Mac. It adds word movement/deletion, visual wrapped-line Home/End, preferred-column Up/Down, page movement, and reversed selection handling. Native Mac testing also produced a framework repair in [PR #6027](https://github.com/codenameone/CodenameOne/pull/6027); Linux uses [PR #6026](https://github.com/codenameone/CodenameOne/pull/6026). Both original documents were restored exactly. See [keyboard acceptance](KEYBOARD-NAVIGATION.md) and `navigation-acceptance.json` for artifact hashes and explicit limits. Full parity and final matched benchmarks remain incomplete.

The preceding comparison checkpoint passed 308 Java 8 reactor tests. Review > Compare documents now creates editable, authored text and paragraph-break revisions from pasted text or an imported document. Word/character comparison, ignored case/whitespace, matching-paragraph formatting and existing table-cell text are supported. Accept/reject, one-step undo/redo, native storage and DOCX resolution are tested. Seven comparison workflows and thirteen Mailings regressions pass on each of native Mac, iPhone simulator and Android emulator (60 native checks), with exact original XML restored. Linux adds 37 comparison, Mailings, keyboard and dialog-input checks, bringing this batch to 97 native checks. See `comparison-acceptance.json` and `REVIEW-COMPARISON.md` for limits.

Paired comparison checks pass 42 of 48 cases. All 24 cases from this app's generated comparisons resolve correctly in both readers. The six failures originate in Wordcraft output and also reproduce with its own reader: paragraph insertion/deletion lacks tracked paragraph-break metadata. These retained failures and narrow text assertions do not establish full comparison or rendering parity.

The current browser build passes real multiline entry, comparison, accept, undo, reject and recovery across reload; developer warnings/errors were empty. This extends the prior Mailings browser checks. Full browser acceptance, mobile browser input and advanced file workflows remain incomplete. No browser-performance claim follows from it.

Linux additionally uses the isolated Linux socket, GTK input-method and dialog-pointer patch documented in `LINUX.md` and `comparison-acceptance.json`. The merged framework baseline is `008f9ece62526d807c2064887ad8f8641d6195a4`, built in a unique isolated Maven repository. Desktop uses Maven/ParparVM with native window controls and menus. Native macOS, iPhone simulator and Android emulator acceptance are distinguished from partial browser runtime evidence in `platforms.json`. No full-catalog or performance superiority claim is supported yet. The dated sections below retain the progression of earlier checkpoints and their then-current limitations.

## UI parity work in progress

Benchmarking is paused while the title area, ribbon and workflows are corrected against the reference application. Existing performance and source-size reports describe an earlier build; they are not measurements of the current UI and do not establish parity.

The current revision replaces the separate caption layout with Codename One's built-in custom desktop title integration (retaining native window controls and OS menus), compacts the ribbon, and adds File backstage, command search, format painter and case conversion. Custom-title dragging, double-click zoom/restore, native edge resizing, minimize/reopen and close have now been exercised after extending the Mac port. The checked native interactions and remaining visual defects are recorded in [UI acceptance](UI-ACCEPTANCE.md). Native Save As and the paragraph layout controls are implemented and under acceptance testing. Remaining gaps include native acceptance of narrow ribbon overflow and style management, advanced formatting, and the larger workflow gaps below. Mobile work and newly discovered defects are tracked in UI acceptance.

## Implemented and locally exercised

- Persistent AVL styled-text rope and block sequence; multi-paragraph selection, insertion/deletion, formatting, paragraph properties, bounded undo/redo, transactional rollback, IME composition callbacks.
- Paragraph measurement cache and paginated rendering; rectangular and merged tables, row splitting, basic page geometry, outline, zoom, word counts, find/replace in body and table paragraphs.
- Word-style grouped File/Home/Insert/Layout/References/Mailings/Review/View/Automate ribbon, platform-native application menus, styles gallery, navigation search, draggable margin ruler and zoom/status bar. Bundled Source Sans 3, Source Serif 4 and JetBrains Mono supply real font choices.
- Nine-level bullet/numbered lists with restart values and decimal, Roman and letter labels; DOCX list round trips. Interactive Enter continues/exits lists and returns headings to body style. Periodic custom tabs, justification/distribution, hanging/right indents, keep-lines, configurable widow control and keep-with-next pagination; semantic tests pass and native rendering acceptance is in progress.
- Styled clipboard fragments (including tables) through a private format plus HTML/plain-text interchange; inline rich cell typing/formatting/paragraph breaks, shared body/cell undo, Tab traversal, rectangular merge/split, table row/column insertion/deletion, stable text/numeric sorting of unmerged tables, and conversion to text.
- Range-anchored comments with authors, resolve/reopen/delete, undo and native/DOCX persistence; highlights and comment navigation in the native UI. Replies persist through native/DOCX round trips and support undo; reply and table-cell comment ribbon acceptance passes on Mac, Linux and iPhone simulator. Cross-cell and note-story comment ranges remain unsupported.
- Named paragraph styles: create/apply/modify/update-to-match/delete custom definitions; document-wide updates include table cells and preserve differing direct formatting. Native/DOCX definitions round-trip. Desktop create/apply/modify and save acceptance passes; mobile controls still await interaction acceptance.
- Embedded PNG/JPEG pictures: caret insertion, size/aspect/alignment/alt text/delete, undo, native/DOCX round trips, HTML data images and PDF image output with PNG alpha. Native insert/resize/align/alt-text/save acceptance passes using document-owned sheets. Picture corrections (brightness/contrast), color modes (grayscale, washout, black and white) and transparency render on the canvas and round-trip through native XML and DOCX blip effects; PDF export does not apply these adjustments yet.
- Native `.cn1doc` project storage; original DOCX package retained; explicit conversion gate for known unsupported DOCX content; inherited paragraph/character styles, direct run formatting, external hyperlink relationships and basic table interchange.
- TXT/HTML/Markdown/RTF/AsciiDoc adapters through CN1; selectable-text PDF output. Native export/print embeds the same twelve bundled TrueType faces used by the editor; independent PDF rendering and Latin/Greek/Cyrillic extraction pass. Complex-script shaping and fallback remain incomplete.
- Two-slot checksummed recovery, damaged-write fallback, and revision ordering against delayed autosaves.
- Validated command registry, compiled Java extension interface, parameterized macros with rollback/cancellation and stale-preview rejection; template filling and writing checks; stdio MCP lifecycle and rooted file access.
- Standard Maven/ParparVM macOS AppKit/Metal application, native launch/render and ZIP recovery verified. The custom Swing host and GraalVM build path were removed. See [native startup status](STARTUP.md).
- Shared desktop/mobile/web application code; previously compiled JavaScript bundle. The CLI remains separate JVM tooling; the opt-in live MCP integration also runs inside the native app (see LIVE-MCP.md). Native typing across paragraph breaks, style changes, undo/redo, DOCX export/reopen with the native file chooser, and PDF export were exercised on macOS. Complex IME composition and other ports remain unverified.

## Remaining work required by the plan

| Area | Remaining work |
| --- | --- |
| Wordcraft parity | Verify all 502 catalog entries against actual workflows and implement the documented working subset; refresh the moving baseline before release. Current catalog rows remain pending or partial; none are certified complete. |
| DOCX | Custom/composite numbering labels, theme fonts/colors, multi-column and advanced section layout, advanced header/footer content, advanced fields, bookmark ranges outside body paragraphs, floating/cropped/rotated pictures, anchored objects, nested tables and advanced table properties and real-world round-trip fidelity. Current import often approximates layout. |
| Typography | Shaped layout and caret geometry for Arabic/Hebrew/Indic scripts, bidi navigation, font fallback and complex-script PDF shaping, locale/RTL tab behavior and broader tab-layout fidelity, line-break rules and pagination fidelity. |
| Editing | Typing/paragraph keyboard acceptance on the remaining platforms; mixed body/table selection, nested table editing, inline/cell pictures, shapes/charts/equations, advanced lists and tabs, style inheritance and character-style management, broader external clipboard interoperability, native Save As overwrite/error acceptance, mobile print acceptance, touch selection/scrolling acceptance, accessibility and reliable native IME on every port. |
| Word workflows | Remaining tracked-change display/structural/move workflows, composite citations/advanced source roles and bibliography filters, index columns/collation/advanced fields, authority categories/short forms, advanced TOC and note-story fidelity, remaining Mailings options, proofing, templates, forms/content controls, structural document comparison/Combine, protection and remaining catalog workflows. |
| Performance | True incremental pagination after the changed suffix, large-document memory/undo accounting, GUI startup and input-to-present latency, equivalent cold/warm layout/import/export benchmarks with matched fonts and output checks. |
| Platforms | Complete browser file/input/offline acceptance; Android physical-device, native keyboard/touch and complex IME checks (15 built-in MCP emulator UI checks pass), iOS device tests and completion of simulator acceptance; Windows execution and Linux window-manager/physical-input acceptance; offline/file/share behavior; signing and installable releases. |
| Extensibility | Broader SDK surface for advanced document nodes, versioned compatibility policy, sample external extension artifact, UI extension points, comprehensive macro capability/resource controls and host integrations. |
| Release | Reproducible dependency lock/version, complete test corpus, external format interoperability beyond the tested plain-text fixture, public license choice, distribution packaging and release documentation. |

## Current feature-batch evidence

See [feature acceptance and performance](FEATURE-VALIDATION.md) for the current tests and exact measurement boundaries. Fifty-nine paired CLI/DOCX probes, twenty cross-import review checks and ten cross-import caption checks pass. The latest Java 8 reactor passes 478 tests; see the dated acceptance records below for source and artifact evidence. These narrow scenarios do not certify the full catalog rows or rendered parity. The previous source-size comparison remains unmatched in scope.

## Acceptance gates

1. A catalog row needs a reproducible scenario, expected result, actual artifact and platform evidence for both apps before it can count toward parity.
2. Format tests need semantic assertions and rendered comparison on original, round-tripped and externally reopened files. Unknown package parts being retained does not imply they remain semantically usable after editing.
3. Performance gates use the same hardware, documents, fonts, operations and timing boundaries, median/p95 and peak memory, with cold/warm separation. The working threshold in the baseline is at most 5% regression; success is not inferred from a synthetic benchmark.
4. Fewer lines is evaluated at matched feature scope, with production/test/generated/runtime/dependency code reported separately. Framework reuse is disclosed.
5. Desktop, browser, Android and iOS must pass real interaction workflows. Compiling a bundle or invoking input callbacks does not satisfy that gate.
6. Native/GraalVM tests run locally only; do not create or dispatch native CI jobs.

## 2026-10-14 fonts and table properties

Table column proportions, minimum row heights, cell shading/solid borders, symmetric padding, vertical alignment and repeated leading header rows now share the screen/PDF layout. Formatting follows row sorting, structural edits and merged-owner deletion, with transactional cell-aware undo. Native and DOCX round trips and HTML output pass library tests; macOS edit/save/print-preview and iOS table insert/cell-typing acceptance now pass for the tested fixtures. Asymmetric/decorative borders, nested tables, exact row heights, floating/absolute table positioning and mixed body/table selection remain gaps. Oversized header blocks consuming more than half a page are not repeated.

Print preview/cancel passed on native Review 15 without submitting a print job. The embedded-font path passed independent Poppler rendering and text extraction for all twelve font faces; Review 16 native preview also shows the correct bold serif face and embedded image. Font files are embedded whole with compression, not subsetted. CLI default PDF still uses Helvetica. Benchmarks remain paused.


## 2026-10-14 aligned tabs

Explicit left/center/right/decimal tab stops and dot/hyphen/underscore/middle-dot leaders are implemented in paragraph formatting, native storage, DOCX, screen and PDF output. Paragraph > Tabs edits stops with parent-dialog apply/cancel and undo. The completed tab batch passed 123 reactor tests, including PDF leader-rounding and malformed-table regressions. Native dialog edit/save and iOS set/apply/reopen checks pass. Zoom-dependent screen leader fitting and the Maven desktop title-mode path were verified in the follow-up native build; PDF leader fitting now uses embedded-font advances and has a separate regression test. Bar tabs, locale-specific decimal separators, inherited cleared stops, RTL tab semantics and HTML tab-layout fidelity remain incomplete. Paragraph-specific nondefault DOCX fallback intervals are materialized as up to 64 stops rather than a per-paragraph default interval (DOCX uses a document-wide default).


## 2026-10-14 inline fields

Atomic inline fields preserve instruction, cached result, lock state and character formatting through editing, case conversion, rich clipboard, undo, native storage and DOCX simple/complex-field import. PAGE, NUMPAGES, TITLE, date/time and filename update from the host; explicit merge/property values are supported by the resolver. Insert > Field, Date & time, Edit field and Update fields are available. Unknown instructions retain cached results and are never executed.

The completed field batch passed 130 reactor tests and the standard Maven/ParparVM macOS and iOS simulator builds. Native update-all/undo/redo, code display, date insertion, locked-result preservation, save and two-page print-preview/cancel pass. The saved test package contains distinct PAGE results 1 and 2 and NUMPAGES 2. iPhone date insertion and field property editing/apply pass. Sixteen paired command/DOCX probes pass on both pinned applications; this includes basic field instruction/cached-result preservation, not the full field catalog. Nested/range fields, mixed formatting within one result, multiline field layout, automatic update policy and unsupported switches remain gaps. Import warns when result formatting or multiline display is flattened.

Basic paragraph headers/footers now have the native acceptance evidence below.


## 2026-10-14 paragraph headers and footers

Default, first-page and even-page paragraph headers/footers now persist in the native document and DOCX parts, including scoped hyperlinks, formatting, alignment and atomic fields. Per-page PAGE/NUMPAGES/TITLE results are shared by the native canvas and PDF output. Body space expands for resolved margin content, including page-specific fields. Oversized stories remain editable with a layout warning; PDF export rejects the overflow instead of silently producing truncated output. Sections, linked sections, tables/pictures in margin stories, direct on-page margin editing, a full header/footer formatting ribbon and automatic field update policy remain incomplete.

The full app reactor passed 137 tests. Eighteen paired CLI/DOCX probes pass on both pinned applications; the added scenarios verify default-header/default-footer text and package references only. Native first-page header typing/alignment/apply/undo/redo/save, even-footer page-field insertion and three-page print-preview/cancel pass. The editor preview clipping defect was corrected and visually verified. The integrated build adds caret reveal after native selection changes, paper pagination for mobile field updates and document-wide style changes reaching margin stories; the Maven/ParparVM macOS and iOS builds pass. Native Command–Down/Up now reveals the caret. iPhone portrait header TITLE insertion/centering/apply and footer PAGE insertion/apply/reopen pass. Touch scrolling and physically viewing the mobile footer remain unverified because the simulator gesture tool returned noWindowsAvailable.


## 2026-10-14 section layout

Sections now carry their own paper size/margins, next/continuous/odd/even start and page-number restart. Paragraph boundaries survive split/join, rich paste and undo; absent header/footer stories link to the previous section, and explicit empty stories unlink. The Layout ribbon has section-break and section-settings dialogs; margins, orientation, paper size and ruler edits target the active section. Header/footer dialogs expose linking, and editing inherited text unlinks that story. Native storage and DOCX preserve section boundaries/properties and owned parts. Screen/input geometry, mobile reflow and PDF use each page's dimensions.

The full reactor passes 151 tests. Twenty-two paired command/DOCX probes pass against the pinned Wordcraft CLI, including four section-start cases; those probes do not compare rendering. Independent PDF inspection verifies a four-page portrait/landscape/landscape/portrait fixture with linked headers and logical numbers 1/7/8/9 (page 8 pads an odd-page section break). The integrated Maven/ParparVM macOS and iOS builds pass. Native section settings, logical numbering, orientation changes and unlink-on-header-edit were exercised; broader section UI and mobile interaction acceptance remain incomplete.

Remaining section gaps include columns, mirrored margins/gutters, vertical text/alignment, line numbering, per-section page-number formats and full Word behavior for continuous sections sharing a page with different header/footer settings or numbering restarts. Complex imported section settings still trigger conversion warnings. Basic paragraph stories do not cover tables/pictures or direct on-page margin editing.


## 2026-10-14 tracked changes and review

Text insertion/deletion, paragraph breaks, character/paragraph formatting and named-style application now retain author/date history. Accept/reject works for all changes, individual revision IDs, a caret's change, or the selected characters across paragraphs. Selection-level review leaves changes outside the range intact. Own unaccepted insertions cancel when deleted; deleting another author's insertion retains its history. Rich paragraph paste, atomic fields, cell text, margin-story editing, composition, undo, case conversion and repeated forward/backward deletion participate in this model. Track formatting can be disabled independently of text tracking.

Native storage and DOCX preserve these revisions and original formatting. Imported paragraph style revisions restore inherited character defaults on rejection. Native macOS review-pane navigation, text/character/paragraph review, undo, saved-file reopen and DOCX export passed. iPhone simulator insertion/rejection/undo passed. The follow-up selected-range/style/formatting-option native acceptance is in progress; compilation and library tests alone do not certify it.

Evidence: 179 reactor tests; 41 paired probes in `parity-probes.json`; 20 cross-import checks in `review-cross-import.json`. These inspect text and OOXML semantics, not equivalent page rendering. Cross-import checks explicitly permit conversion of unrelated unsupported Wordcraft defaults and record their import warnings. No catalog rows are certified and no performance measurements were taken.

Remaining review gaps: Final/Original/Simple Markup display and markup customization, revision balloons, tracked table/image/section structure and moves, comparison/combine/protection, style-definition history, and independent overlapping paragraph-style/character-format histories. The reviewing pane is docked on desktop and modal on mobile. Whole-document review includes owned header/footer stories, but direct navigation to a specific first/even margin range still needs work. This is not full Review-tab or Wordcraft parity.


## 2026-10-14 captions and sequence fields

The References ribbon and native Insert menu now insert captions above/below the selected block (outside the whole table when invoked inside a cell). Labels, descriptions, label exclusion, five number formats and heading-based chapter prefixes are supported. SEQ fields update in document order with independent identifiers, reset/repeat/hidden-result switches and heading restarts. STYLEREF resolves preceding heading text/numbers. Fields preserve instructions and cached results through native/DOCX storage; quoted arguments remain data even when they resemble switches. Captions use a named style, one-step undo and existing text/paragraph tracking.

Evidence: 186 full-reactor tests; 49 paired CLI/DOCX probes; 10 caption cross-import checks in `caption-cross-import.json`. Each cross-import opens the other app's caption DOCX, inserts an earlier caption, updates fields and checks all three sequence instructions/results in the saved artifact. Native macOS Roman caption insertion, above-item insertion/renumbering, undo/redo, selected-character rejection/undo and Save As passed. The saved native fixture retains both sequence instructions and the I-to-II renumbering. iPhone Roman caption insertion and undo passed. The keyboard-aware dialog correction visibly keeps its action buttons above the keyboard; the resumed iPhone simulator check confirms Done dismisses the keyboard from both the label and caption fields, retains entered caption text, and leaves all dialog actions visible.

Remaining caption limits: no automatic object/caption attachment, automatic update-on-edit policy, margin-story sequences, arbitrary named-style STYLEREF search or complete field-switch semantics. At the end of that caption batch, the References tab still lacked TOC, notes, citations/bibliography, bookmarks and cross-references; the bookmark/reference follow-up below supersedes the latter two gaps. This batch does not certify complete catalog rows or resume benchmarking.


## 2026-10-14 case-conversion follow-up

Home > Change case now exposes sentence, lower, upper, title and toggle modes. The command also supports omitted-mode cycling and whole-word targeting when a caret is inside a word. Context crosses formatting-run boundaries; fields and existing deleted text remain intact, comments follow expanded text, and tracking/undo work in body and table-cell text. Existing deleted text does not consume sentence capitalization. Tests include German sharp-s expansion and Greek final sigma; full locale/grapheme behavior on all ports is not certified.

The Java 8 reactor passes 192 tests. All 59 paired CLI/DOCX scenarios pass, including the five case modes, three selected-text cycle stages, a caret-word conversion and the tested Unicode expansion/lowering. Maven/ParparVM macOS and iOS simulator builds pass. The resumed iPhone simulator Done acceptance passes. All five native case-dialog choices now pass on macOS and iPhone through the live MCP integration, including undo and exact original model restoration.

A separate observation is retained in `case-caret-difference.json`: after an initial conversion with the caret inside `hello`, this app keeps the word selected and subsequent cycle commands produce `Hello` then `hello`; the pinned Wordcraft implementation moves the caret to the word end and remains at `HELLO`. Full-selection cycling passes in both. This is a narrow implementation difference, not evidence that one language/framework is generally superior. Shift-F3 keyboard mapping is not yet implemented here.


## 2026-10-14 live native debugging

The opt-in `agent-debug` Maven profile integrates Codename One's built-in MCP server directly into the native app. Live editor commands share the UI session and undo history and reject stale state or document edits while a dialog is open. Built-in UI tools now support modal workflows and explicit native-window targeting in the local framework snapshot. Both Maven/ParparVM Apple builds and 195 application tests pass; the focused framework MCP suite passes 17 tests. Normal editor jars exclude the debug listener configuration after a profile switch without cleaning. See [LIVE-MCP.md](LIVE-MCP.md) for commands and [UI-ACCEPTANCE.md](UI-ACCEPTANCE.md) for native evidence. The app still has the feature and platform gaps listed above; no benchmarks were run.


## 2026-10-14 bookmarks and cross-references

Added named body ranges, Add/Go To/Delete dialogs, heading/caption/bookmark references, reference navigation, text/page/relative field updates, native/DOCX persistence and shared undo. Ranges follow split/join, precise repeated-text edits, Unicode case expansion and tracked replacement acceptance/rejection. Cyclic references resolve to bounded errors; locked fields remain unchanged. See the explicit remaining format limits in [FORMATS.md](FORMATS.md).

The normal Java 8 reactor passes **212 tests**. The native Maven/ParparVM macOS and iPhone simulator builds pass. Eight actual native UI checks pass on each: bookmark add, navigation, deletion/undo, named text reference, follow reference, heading page reference, above/below reference, and update after source editing. Both runs restore their exact starting XML and wait for recovery persistence; the iPhone recovery also matches after a process relaunch. See `live-reference-acceptance.json` and `scripts/live-reference-smoke.py`.

Native mobile acceptance exposed a clipped References ribbon; it now uses the same scrollable groups and accessible overflow controls as the other tabs. Rebuilding initially exposed a stale snapshot Maven plugin that ignored changed editor dependencies. The isolated repository now contains the checkout's current plugin; its dependency-change regression test passes, and the regenerated iOS source includes the fix. No global Maven cache was used.

Eight paired/cross-import bookmark/reference artifacts pass the reference assertions (`reference-cross-import.json`), with unrelated DOCX formatting warnings retained. These are behavior checks, not certification of entire catalog rows. TOC, notes, citations/bibliography, advanced fields/objects/tables, remaining review workflows and platform QA still prevent full Wordcraft parity. Performance and source-size comparisons remain paused.

Regression check: all **59** existing paired CLI/DOCX scenarios pass after these changes; `parity-probes.json` contains the current artifacts and assertions. The eight bookmark/reference interchange cases are additional. Both agent-owned native test processes were stopped after acceptance; Review 16 remains available as the single reusable macOS bundle.


## 2026-10-15 merged framework and contents workflow

The local native builds use merged framework commit `008f9ece62526d807c2064887ad8f8641d6195a4` (PR #6019), installed as `8.0-SNAPSHOT` in a unique isolated Maven repository. This is the merged source baseline, not a claim that release 7.0.277 has been published. The app title strip now relies on the framework's measured native-control insets instead of adding a hardcoded Mac inset.

References now offers Table of Contents, Update Table and Remove Table, plus the native Insert menu entry. Settings cover a custom title, heading levels 1–9, page numbers, right alignment, five tab leaders and heading links. Generated content participates in normal undo and native/DOCX persistence; updates preserve body or table-cell selection and honor a locked TOC. DOCX import/export supports the standard contents content control and complex field spanning multiple paragraphs, including nested page-reference fields. Ordinary heading-based contents round trips between Wordcraft and this app and can be updated or removed in the other app.

Nine paired option scenarios (18 checks) and four cross-import update/remove checks pass. Both macOS and iPhone simulator ribbon workflows pass through the built-in MCP, with exact original XML restoration. Real iPhone keyboard typing, Done dismissal and Cancel were checked separately. Mobile option dialogs use the safe viewport with scrolling content and action buttons above the keyboard. Phone reflow scales tab stops and indents without changing saved/printed geometry. See [TOC acceptance evidence](toc-acceptance.json).

TOC bookmark creation is batched and PAGEREF resolution uses the paragraph layout index rather than scanning the entire document for each field. No new performance comparison is claimed. CLI PDF export now embeds the same bundled font families as the native editor and measures with those font advances, loading each face once per export. The 12-face Unicode/font-loading regression and independent PDF font inspection/rendering pass. The latest full Java 8 reactor passes 230 tests including the application module.

Remaining TOC work includes direct outline levels, custom style-to-level maps, TC fields, arbitrary complex-field ranges and broader real-world Word fidelity. Footnotes/endnotes, citations/bibliography, index/authorities and other catalog workflows remain incomplete. Caption lists now have the scoped acceptance below. The full 502-row catalog and all-platform parity are still uncertified; benchmarks remain paused.


## 2026-10-15 caption lists and consistent pagination

References now includes Table of Figures, Update figures and Remove figures, with a native Insert-menu entry. Figure, Table, Equation and custom caption labels use the same generated-story machinery as the TOC. Lists for different caption categories and a heading TOC coexist; captions with hidden visible labels are matched by their SEQ identifier. Options cover title, page numbers, right alignment, five leaders and links. Updates and removal share ordinary undo, native storage and DOCX complex-field ranges.

Final Maven/ParparVM macOS and iPhone simulator builds pass insertion, generated-link navigation, caption editing/update, removal and undo through the actual ribbon dialogs. The TOC workflow also passes on both final builds. Every run restores its exact starting document XML and waits for Recovery saved. Final test processes are stopped and ports 18765/18766 are closed. The clean framework baseline is the merged PR #6019 commit; no additional framework changes are required by this batch.

The caption-list comparison has 9 passing and 3 failing checks. Both apps create the three standard caption lists; our app imports and updates all three Wordcraft outputs. Wordcraft imports our standard complex figure fields and edits source captions, but its Update figures leaves cached entries stale. Those reverse-direction failures remain explicit in [figure acceptance](figures-acceptance.json). The 22 TOC paired/cross-import checks pass after the final changes.

Native automation now uses the editor's print-layout engine instead of Helvetica; CLI automation uses the same bundled-font advances as CLI PDF export. A long-document native test exposed stale copied TOC labels when headings contained live REF/PAGEREF fields. Generated labels now refresh as pagination settles. The corrected test passes on both Mac and iPhone, including the original field-bearing mobile fixture. [Pagination evidence](pagination-acceptance.json) records the separate documents and results. Native and headless typography are not claimed identical.

Remaining acceptance includes long-label and narrow-ribbon polish, advanced TOC options, footnotes/endnotes, citations/bibliography, index/authorities, broader DOCX fidelity and the untested platforms. Five relevant catalog rows now link to scoped evidence and remain partial. The 502-row catalog is not certified. Performance/source-size comparisons remain paused; current agent-debug builds are not benchmark artifacts.


## 2026-10-15 footnotes and endnotes

Notes are atomic references owning immutable rich paragraph stories. The References ribbon and native Insert menu insert footnotes/endnotes; Show Notes edits or converts a selected note, and Next Note follows document order through body paragraphs and table cells. Automation also supports bulk conversion. Reference deletion, copying, renumbering, case conversion, character formatting, undo and tracked text edits preserve note ownership. New note content is covered by its tracked reference insertion; edits to existing note stories retain individual revisions.

Footnotes reserve page-bottom space and continue across pages; endnotes follow the document body. Both use the shared native/PDF layout, including table references, separator rules, note paragraph alignment and lists. Field updates include note stories. Layout reuses unchanged measured note paragraphs. Typing after a reference resumes normal baseline text. Native XML and standard DOCX note parts retain the stories, custom marks and scoped hyperlink relationships. HTML includes linked note sections; plain-text export includes the note text.

The Java 8 reactor passes 241 tests. Sixteen paired/cross-import DOCX assertions pass against Wordcraft at `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. An independently rendered PDF preserves all 80 lines of a long footnote across two pages. Native ribbon and software/native keyboard checks passed on macOS and iPhone; final-build acceptance and source fingerprints are recorded in `notes-acceptance.json`.

This is not full note or application parity. Remaining note work includes configurable numbering/start/restarts/placement by document and section, direct in-page note editing, notes containing tables/pictures, comments/bookmarks in note stories, custom continuation separators, and broader complex-table/section layout acceptance. Unsupported explicit DOCX note settings produce conversion warnings. Advanced TOC options, citations/bibliography, index/authorities, remaining review/object workflows and untested platforms still prevent full Wordcraft parity. Performance/source-size comparisons remain paused.


## 2026-10-15 advanced TOC sources

The References ribbon now exposes Add Text outline levels, marked contents entries, and custom style mappings in the TOC dialog. Heading styles, explicit outline levels, mapped styles and TC fields can be combined or enabled independently. Entries preserve source text, distinct link anchors, level/category filtering and normal undo. Direct versus inherited outline levels survive native/DOCX storage and tracked-format rejection. Native dropdown labels now have readable colors on the editor’s light surface.

The final source passes 249 Java 8 reactor tests and 24 paired/two-way DOCX source checks. Maven/ParparVM builds and advanced-source/note-regression workflows pass on macOS and iPhone simulator. Native keyboard input and level selection pass; nested-dialog Apply followed by parent Cancel leaves the document unchanged. Review documents were restored byte-for-byte at the XML level, recovery completed, and both review apps were stopped. See `toc-sources-acceptance.json` for source hashes and retained evidence.

This does not complete the TOC field specification or application parity: arbitrary imported level ranges/switches, custom generated-entry formatting, multiple TOCs and remaining platform checks are outstanding. Notes options, citations/bibliography, index/authorities and broader object/review workflows still require implementation and acceptance. Performance comparisons remain paused.


## 2026-10-15 Android and native input sessions

The current source passes 250 Java 8 reactor tests and local macOS/iOS ParparVM builds, an Android debug APK build, and the ParparVM JavaScript build. Each of macOS, iPhone simulator and Android 14 ARM64 emulator passes 15 built-in MCP native UI checks covering caption lists, advanced TOC sources and rich notes. Every workflow restored its exact original XML and waited for recovery persistence. Real native macOS typing and iPhone software-keyboard typing also pass. Review apps and the agent-owned Android emulator were stopped. See `input-platform-acceptance.json`.

Android testing exposed keyboard composition callbacks changing a selection between automation commands. The editor now invalidates closed native input sessions and prevents programmatic commands from reopening the keyboard. Focused regression coverage verifies that callbacks from an old session cannot mutate its successor. The previously failing caption edit now passes. One later MCP connection closed while the app remained alive, with an empty Android crash buffer; reconnection and all workflows passed. This is recorded separately from app-crash evidence.

The Android build script uses Java 8 for Maven, a separate modern JDK for Gradle, the isolated Maven snapshot repository and a project-local or explicitly chosen Gradle cache. The browser build script rebuilds current editor dependencies and removes stale opt-in debug configuration. Browser interaction remains unverified because the browser tool could not load its request-header policy. Android physical-device, native keyboard/touch and complex IME acceptance, Windows/Linux checks and the outstanding document features remain incomplete. No final performance comparison is claimed.


## 2026-10-15 note settings and mobile numbering

The Footnote and Endnote dialog now controls eleven number styles, starting numbers, continuous/section/page restarts, and page-bottom/below-text or section-end/document-end placement. Settings support document defaults and sparse overrides for selected sections, native/DOCX persistence, selection preservation and one-step undo. Mobile reflow keeps page-restarted labels anchored to print pages. Focused dropdown text remains readable on desktop and mobile; the starting-number field requests a numeric keyboard.

The exact reviewed source passes 258 Java 8 reactor tests. Twenty paired and edited two-way DOCX checks pass against the pinned Wordcraft revision. Independent PDF rendering/text extraction verifies below-text placement and page/section restart labels. Local Maven builds pass for macOS, iPhone simulator, Android and browser. The reviewed note-settings ribbon workflow passes on all three native targets; Android also reruns rich-note regression. Native macOS/iPhone screenshots and iPhone software-keyboard number entry were checked separately. Every native workflow restored its exact original XML and waited for recovery persistence. All review apps and the Android test emulator are stopped. See `note-options-acceptance.json`; reviewed app archives and source hashes are retained in `artifacts/parity-20261015-note-options`. These debug-enabled artifacts are for acceptance, not performance comparison.

This remains partial parity. The format limits in `FORMATS.md`, direct note-story editing/objects, citations/bibliography, index/authorities, remaining review/object workflows, complex-script typography and unverified platforms remain outstanding. No final benchmark or language/framework superiority claim is made.


## 2026-10-15 indexes

References > Index now exposes Mark Index Entry, Insert Index, Update Index and Remove Index. XE marks preserve the source selection and support nested subentries, See text, bookmark page ranges, bold/italic references and Mark All (first same-case whole-word match per paragraph). Body and table paragraphs participate. Index generation groups and de-duplicates references, supports indented or run-in entries, letter headings, right-aligned references and leaders, and iterates pagination to include the index's own page footprint. Failed generation rolls back rather than retaining unresolved page numbers. Update preserves a body/cell selection, and insert/update/remove participate in undo.

Native storage and standard multi-paragraph DOCX INDEX fields preserve editable generated paragraphs. Empty/locked indexes and coexistence with TOCs are covered. The reference's exported INDEX fields import and regenerate here; the reverse regeneration exposes a limitation in the pinned reference's range-field reader. No compatibility flattening was added. The generated index, real Mac keyboard input and iPhone software-keyboard input were visually checked; cancel restores the document. Android's six checks use built-in MCP UI actions, not physical keyboard/touch acceptance.

Multiple columns, locale-specific sorting, multiple indexes, custom heading/separator switches, and dedicated mark management remain gaps. No final performance or source-size comparison was rerun. Source, app bundles, screenshots, PDFs, comparison failures and cleanup evidence are in `artifacts/parity-20261015-index`.


## 2026-10-15 tables of authorities

References > Table of Authorities provides Mark Citation, Table of Authorities, Update Authorities and Remove Authorities. Standard TA marks preserve selected body or cell text. The shared index engine collects and sorts whole citations (including colons), deduplicates page references, includes section numbering and the generated table footprint, and supports custom title and tab leaders. Insert/update/remove are undoable; native and DOCX storage retain editable standard TOA ranges alongside indexes and TOCs. Locked tables remain unchanged; unsupported TOA switches reject regeneration.

Six native UI checks pass on each of Mac, iPhone simulator and Android emulator, with six index checks rerun on each platform. All restore the exact fixture XML and wait for recovery. Native/PDF pages were visually reviewed. Six paired import/export checks pass; two reverse-direction Wordcraft updates duplicate cached entries. Categories, passim, short forms, locale collation, multiple authority tables and joint convergence across multiple generated tables remain incomplete. Artifacts and cleanup evidence: `artifacts/parity-20261015-authorities`.


## 2026-10-15 citations and bibliography

Document-owned sources record tag, type, author/editor names, title, publication date, city/publisher, journal, edition/volume/issue/pages, URL, access date and DOI. Create/edit/delete source, insert citation, page range and style controls are available in References. APA, Chicago, GB/T 7714, both GOST sorts, Harvard (Anglia), IEEE, both ISO 690 variants, MLA, SIST02 and Turabian render the reference's tested book/article subset. Numbered styles use first-citation or sorted order as appropriate. Source edits and style changes refresh citations and the editable bibliography in one undo step; cell selection is preserved. Deleting a source produces an explicit missing-source result and can be undone. Generated source tags avoid silent overwrites in the UI.

Native recovery and standard DOCX source custom-XML parts retain the library and style. Export keeps unrelated package stores, reuses existing properties parts and resolves filename collisions. Bibliography output uses a standard complex field enclosing editable paragraphs, with hanging indents for author/date styles. Source/field constraints, locked fields, unsupported composite switches, empty libraries, body/table editing, native/DOCX persistence and package collisions are regression tested.

Seven citation ribbon checks and twelve authority/index regressions pass per native platform. This does not certify physical mobile input, all citation-source categories, style-guide completeness, global/shared sources, Zotero, complex citation fields or language-specific collation. Twenty-four paired checks pass and twelve reverse Wordcraft updates fail; failures are retained. All native review apps, the disposable emulator and preview servers were stopped. The browser tool could not explicitly close its disconnected ephemeral tabs. Source/build/evidence archive: `artifacts/parity-20261015-citations`. Benchmarking remains paused pending parity.


## 2026-10-15 Mailings and current browser acceptance

The Mailings command engine shares session history, macros, CLI and native MCP. Recipient import handles quoted CSV/TSV and bounded XLSX/XLSM/ODS worksheets, cached formula results, sparse cells and Excel dates. Preview preserves atomic fields and selection; Finish creates editable rich copies with separate letter sections, personalized headers, remapped bookmarks/comments/notes and internal references. Invalid rules, missing columns, oversized input and label overflow fail atomically. Envelope and label geometry is shared by screen/DOCX/PDF. Mobile ribbon groups now scroll, making Preview and Finish reachable on narrow screens.

All four local platform builds pass from the same source. Native MCP acceptance and citation regressions pass on Mac/iPhone/Android; Mac actual multiline keyboard entry was also checked. Paired comparisons cover supported text and field results. Independent PDF renders verify the simple letter/envelope/label fixtures, not printer calibration. Browser UI checks now verify recipient entry, preview, finish, undo/redo and recovered merged text on reload. All review apps, the disposable Android emulator, the test browser tab and preview server were stopped after acceptance.

Remaining Mailings gaps include persistent recipient links, compound recipient filters/multiple sort keys, multiline atomic preview, advanced field switches and nested rules, additional label sheets and printer-feed calibration. Native workbook import was exercised through MCP; file-picker interactions and physical mobile input still need acceptance. Broader review/object/proofing/typography workflows, Windows/Linux and final matched measurements remain incomplete. See `MAILINGS.md` and `artifacts/parity-20261015-mailings` for reproducible scope and artifacts.


## 2026-10-16 document comparison and Linux native validation

Review comparison is integrated into the ribbon, shared command engine, CLI and native MCP. The comparison engine retains paragraph break revisions, supports direct formatting on unchanged paragraphs and compares text in existing table cells without changing table geometry. Unsupported structures fail before replacing the document. Bounded diffing avoids an unbounded quadratic matrix and reports coarse replacements. UI comparison detaches the result from the input file; raw automation commands do not implement that host Save As policy.

The final source passes 311 reactor tests and the Mac/iPhone/Android comparison plus Mailings regression checks. The browser passed actual keyboard entry, comparison, accept/undo/reject and recovery after reload. Paired DOCX resolution is 42/48; all CN1-origin cases pass, while six Wordcraft-origin paragraph-break cases reproduce in both readers. The remaining gaps in `REVIEW-COMPARISON.md` are material, so the catalog row remains partial.

Linux ARM64 builds through the standard local Maven/ParparVM target in an isolated Ubuntu 22.04 container. The review binary uses RelWithDebInfo without release LTO and opt-in loopback MCP; it is not a performance/release-size baseline. Thirty-seven Linux checks pass: seven comparison, thirteen Mailings, thirteen XTest document keyboard/Unicode/clipboard checks and four dialog input/focus/field-isolation checks. Exact original XML and Mailings recipient state were restored. Xvfb without a window manager does not certify title controls, physical input, printing or native file dialogs.

All agent-owned review applications and containers, the disposable Android emulator, browser test tab and preview server were stopped after validation. The archive `artifacts/parity-20261016-comparison` contains source hashes, platform bundles, native screenshots, logs and paired results. Full Wordcraft parity, physical-device/Windows acceptance and final matched performance/size/source-token numbers remain unfinished.

## Newspaper columns

Sections carry 1-12 columns, with spacing, an optional separator rule and unequal widths. Paragraphs can start with a column break. These round-trip through native XML and DOCX (`w:cols`, `w:br w:type="column"`) and are covered by `ColumnsFormatTest` and `ColumnsLayoutTest`. Columns are set from Layout > Columns or with `layout.columns` / `layout.columnBreak`. Limitations:
- Columns are not balanced before a continuous section break.
- A column break in the middle of a DOCX paragraph moves to the paragraph start, with a warning.
- The native visual check is still pending, so the matrix rows remain unverified.

## Design tab page decoration

`design.pageColor`, `design.watermark` and `design.pageBorders` store a per-section `PageDecor`
(native XML `<decor>`), painted by the editor and PDF export. DOCX maps page borders to
`w:pgBorders` (`offsetFrom="page"`) and page color to `w:background` plus
`displayBackgroundShape`. Limitations: the DOCX watermark (a VML header shape) is neither read nor
written; Word's page color is document-wide, so the last section's color is the one saved; art
page borders and borders measured from text are approximated; the watermark is fixed 54pt diagonal
text, and the PDF lightens it instead of using transparency. Header/footer commands (`insert.header`/`insert.footer`, `layout.linkToPrevious`,
`hf.position`) edit section furniture directly.

## File: properties, protection, inspector and checkers

- `file.properties` keeps title, author, subject, keywords, description, category, company and
  manager. They round-trip through `docProps/core.xml` and `docProps/app.xml` and native XML.
- `file.protect` sets `readOnly`, `comments` or `trackedChanges`. `Session.transact` rejects
  disallowed edits, and the editor drops text input under read-only or comments-only protection.
  DOCX uses `w:documentProtection` with enforcement. **Limitation:** there are no passwords.
  Changing the mode is always allowed, and a protection hash read from Word is dropped (with a
  warning).
- `file.inspect` reports comments, revisions, hidden text, properties, headers/footers, bookmarks
  and watermark. It can remove comments, hidden text and properties. Revisions are reported, not
  removed.
- `file.accessibility` checks for:
  - pictures without alt text;
  - skipped heading levels and empty headings;
  - tables without a header row;
  - text contrast below WCAG 4.5:1 against the page or shading color;
  - a missing title;
  - vague link text;
  - repeated blanks used for layout.
- `file.compatibility` writes and re-reads DOCX and lists the import warnings, plus a note when
  a watermark would be dropped.
- `file.exportPdf` returns the PDF as base64.
- **Not yet implemented:** PNG export, version history, encryption, autosave/recover commands
  and templates.

## Page-number format and line numbers

`layout.pageNumberFormat` sets the section's PAGE format (roman or letters) and an optional restart.
Body, header and footer PAGE fields without a `\*` switch use it. `layout.lineNumbers` numbers every
Nth body line in the margin; numbering restarts per page, per section or never. These are painted
in the editor and the PDF, and map to DOCX `w:pgNumType w:fmt` and `w:lnNumType`.

**Limitations:**
- Table cell lines are not numbered.
- `w:lnNumType w:start` and `w:distance` are not kept.
- Per-paragraph suppression (`w:suppressLineNumbers`) is not supported.
- Page vertical alignment (`w:vAlign`) is not implemented.

## Multilevel and custom lists

`listFormat` is `base[|template]`. The base is a number format, `outline` (levels cycle 1/a/i),
`legal` (1.1.1.) or `bullet`. The template is Word `lvlText` (`%1)`, `(%1)`, `%1.%2.`) or a bullet
symbol. Commands: `para.multilevel`, `para.defineNumber`, `para.defineBullet`, `list.define`.
DOCX writes the numFmt and lvlText for each level and reads custom lvlText back as a template.

**Limitations:**
- Per-level indents, fonts and `w:suff` are not modelled.
- A template's ancestor counters use the formats seen at those levels.
- Picture bullets are not supported.

## Review markup display

`review.markup {mode}` switches the canvas between all markup, simple/no markup (every revision previewed as accepted) and original (every revision previewed as rejected); `review.showMarkup` toggles all/none. Previews are read-only on the canvas. Simple markup does not yet differ from no markup (Word adds change bars only). `review.restrict` is the same as `file.protect`. `insert.editHeader`/`insert.editFooter` open the header/footer editor dialog; there is no in-page header editing mode yet. Dark page mode inverts lightness while preserving hue.

## Drawn objects

The following are model-level objects with full undo:
- Insert shape (21 geometries), text box (with linking), WordArt (styles, fill, outline, transforms), icon, signature line, embedded-object placeholder and canvas.
- Charts (column, bar, line, pie and area; data, title, legend and labels).
- SmartArt (list, process, cycle, hierarchy, pyramid and matrix; text pane, add, promote/demote, colors and reset).
- Equations (linear and professional, symbols, structures, display/inline, justification and numbering).
- Ink (pen, pencil and highlighter, pen presets, eraser, lasso, ink-to-shape and replay).
- Arrange: align, wrap, z-order, selection pane, group/ungroup.

They render identically on the canvas, in PDF and in HTML from shared geometry.

Limits:
- Objects are block-level. Wrapping narrows whole paragraphs, and there is no drag positioning.
- Ink strokes are entered as point lists through commands; there is no freehand pointer capture on the canvas yet.
- Equation structures are entered and shown in linear format with Unicode symbol and script substitution, not a 2-D math layout.
- Chart data is edited as CSV text.
