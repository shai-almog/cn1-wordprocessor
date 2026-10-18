# Table selection and label sheets

The contextual **Table Layout** ribbon now appears while editing a table. Select Cell, Row, Column, Table and Select range operate on a shared rectangular selection. The same selection drives cell highlighting, character and paragraph formatting, style/case changes, typing, clearing, rich/TSV clipboard operations, merging, row/column deletion, composition and undo. Intersected merged cells expand the selection to include the entire span. Mac drag and Shift-arrow selection use the native input session; phones expose the range dialog and contextual ribbon picker.

Rich copy keeps the rectangular grid, paragraph content, fields, formatting and merged geometry. In-table paste replaces matching destination cells in one undo step. TSV paste requires a rectangular grid that fits the destination; incompatible dimensions or merge patterns leave the document and selection unchanged. Cut and Delete clear cell contents while preserving the table. Explicit row/column deletion removes structure, including the entire table when its whole extent is selected.

**Mailings > Labels > One recipient per label** now continues through all selected recipients. Every sheet gets a separate section with the selected product's paper geometry. The last sheet keeps unused labels empty. Sorting/filtering/exclusion order is respected; single-label placement remains a single label. Output limits and label-height validation remain in effect.

## Verified scope

- The Java 8 Maven reactor passes **374 tests**, including thirteen new tests for table selection and multi-sheet labels. Tests cover merged-cell normalization, selection-only state, atomic failures, undo/redo, rich/TSV paste, composition, tracked deletion, stale MCP tokens, structural commands, all nine label products, full/partial sheets and native/DOCX round trips.
- Standard Maven/ParparVM Mac and iPhone simulator builds pass **40 native UI workflow checks** (14 table and six label checks per platform). Both original document XML and recipient state are restored. Mobile checks use built-in MCP accessibility actions; they do not certify physical touch or keyboard behavior.
- Mac mouse/keyboard and clipboard interaction evidence covers rectangular drag/Shift-arrow selection, typing, undo restoration, rich rectangle paste, TSV paste, and Backspace/cut/copy when the focused cell is empty. The dedicated Objective-C command-order contract check also passes.
- **48 paired table checks** verify row/column/whole-table formatting and replacement plus cross-reader DOCX reopening against Wordcraft `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. The existing 59 paired document cases and thirteen Mailings/interchange cases pass. These checks do not certify all Wordcraft commands or visual fidelity.

Artifacts, screenshots, source hashes, native bundles, earlier failed runs and final reports are in `artifacts/parity-20261016-tables`. `table-selection-acceptance.json` identifies the accepted reports. Reproduce with `scripts/table-selection-probes.py`, `scripts/live-table-selection-smoke.py` and `scripts/live-label-sheets-smoke.py`. Native scripts require an explicitly disposable review session and restore its original XML/recipient state through undo.

## Framework dependency

The app remains a normal Codename One application built through Maven and ParparVM. The isolated baseline is the previously recorded `008f9ece` snapshot plus its prior Mac input fixes, with the additional local patch in `docs/framework-patches/mac-composite-selection.patch`. It forwards horizontal movement and deletion to the rich editor and allows the editor to decide whether an empty active text story nevertheless represents a cell/object selection for Copy/Cut. Existing clipboard restrictions and window ownership guards remain. The native command harness adds empty-focus cut, blocked clipboard and inactive-session checks.

The patch is local and has **not** been published as a PR or applied to upstream master. The tested snapshot and cache are uniquely named under `/private/tmp/cn1-word-tables-20261016-xtUzBOG9`; the build does not use `~/.m2`. Current upstream/release-baseline acceptance is a separate outstanding task.

## Remaining gaps

This closes rectangular selection within one top-level table. Mixed body/table selections, nested tables, cell pictures, resize handles, arbitrary merged-grid paste, automatic destination expansion, physical mobile selection gestures, and current-source Windows/Linux/Android/browser acceptance remain open. Label printer-feed calibration and advanced Mailings fields remain open. Broader document fidelity and matched-scope performance gates remain incomplete; no full-parity or performance claim follows from this checkpoint.
