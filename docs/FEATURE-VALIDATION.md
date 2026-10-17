# Feature acceptance and native performance

> Historical measurement and feature snapshot. For current implementation and test counts, see [STATUS.md](STATUS.md) and [UI-ACCEPTANCE.md](UI-ACCEPTANCE.md). The measurements and missing-feature list below describe the earlier build; they do not measure the current UI or demonstrate feature parity. Benchmarking is paused pending UI and workflow acceptance.

This feature batch does **not** establish Wordcraft parity. The 502-entry catalog remains uncertified; the scenarios below exercise specific behaviors rather than complete catalog entries. Source-size and performance measurements remain unmatched in feature and rendering scope.

## Implemented behavior

- DOCX paragraph/character style inheritance and defaults, direct formatting overrides, and external hyperlink relationships.
- Nine-level lists, restart values and decimal/Roman/letter labels; list continuation, promotion/exit and heading-to-body Enter behavior.
- Styled clipboard fragments, including tables, with private native XML and HTML/plain text representations.
- Table row/column insertion/deletion, stable sorting, deletion and conversion to text, with undo.
- Range comments with author/text, navigation, resolve/reopen/delete, native storage and DOCX range/resolution metadata.
- Fixed half-inch tabs, keep-with-next and basic widow/orphan handling; nested PDF bullet glyphs use vector markers where Helvetica lacks coverage.

## Acceptance evidence

Java 8 reactor: 75 tests, zero failures/errors/skips (plus the two lifecycle-module tests in the native Maven build). Paired command suite: 11 scenarios pass in both CLIs. Reproduce with `scripts/parity-probes.py WORDCRAFT_CLI --out NEW_DIRECTORY`. The script independently checks saved OOXML text, formatting, relationships, dimensions, sort order and comment range/resolution IDs.

Artifacts: `artifacts/feature-parity-20261013-input/results.json`, including SHA-256 hashes of each saved document. Wordcraft also reopened CN1's resolved-comment file and reported the correct author, text, anchor and resolved flag; CN1 imported Wordcraft's corresponding file into native document XML with the same values. Those cross-import outputs are in the same directory.

The native application is built using Codename One Maven `mac-source`, ParparVM and Xcode Release on macOS arm64. No Swing host or JVM is used for the application. CLI tools use Java 8 separately.

Native input testing found and corrected macOS port defects in `Ports/MacPort/nativeSources/METALView.m`: multiline pure editors now receive Tab/Shift-Tab commands, and native keyboard copy/cut/paste now delegates to the editor's rich clipboard handlers. Single-line/legacy field traversal and clipboard blocking/ownership checks remain in place. App search/dialog entry uses CN1 `EditField`; explicit document focus rebinds its native text-input session. Repeated typing exposed a further race in `CN1AppKitTextInput.m`: the Java EDT checked an edit generation and then wrote the native shadow concurrently with AppKit typing. State echoes now check and apply on the main queue and reject replaced sessions. `CN1AppKitWindows.m` restores an eligible visible window when modal dismissal leaves no key window. Deferred app refreshes also respect desktop modal input blocking. A separate `Dialog.resolveHost()` defect capped native dialogs at their initial peer width and clipped controls; native dialogs now measure against their owner. A regression failed before this fix, and all 149 related framework dialog tests passed afterward.

Native UI checks used disposable Maven/ParparVM bundles with recovery reads/writes disabled. They verified:

- Heading-to-body Enter; numbered-list continuation/exit and Tab/Shift-Tab nesting.
- Keyboard rich copy/paste and undo, retaining heading size, body font and nested numbering.
- Exact comment entry and resolve/reopen, including imported author/text.
- Table-cell entry/apply, row insertion and undo, ascending sorting, and nested dialog dismissal.
- Five repeated 66-character native input probes in the final build, each checked against the accessibility value, with no missing or reordered characters.
- Dialog content width/contrast and restoration of the main document after closing. The final repaint check kept both field contents and the dialog background intact.

The macOS main-surface flush also now explicitly clears a stale secondary-window rendering destination. Without that change, a pending main-form repaint could overwrite the dialog background. These framework changes are recorded separately from application source counts: 98 added and 27 removed production lines across five framework files, plus a 25-line regression test.

Native checks use AppKit/Metal and real pointer/keyboard input. They do not certify complex IME, screen-reader navigation, every table edge case or every supported platform. AX-only focus was insufficient for some initial fields; pointer focus and switching fields were exercised. Desktop Save/Export currently writes an app-local file and presents the OS share sheet; a normal destination-selecting Save As workflow remains missing.

Final measurement values are in [the native benchmark report](NATIVE-BENCHMARKS.md) and [its distributions and hashes](native-benchmarks.json).

## Remaining parity gaps

Images/anchored objects, tracked changes, comment replies, merged/rich table editing, sections and headers/footers, fields and references, full styles management, custom tab stops and composite lists, shaped/bidirectional typography, full PDF font coverage, print integration and accessibility remain incomplete. Mobile/browser/Windows/Linux execution has not been certified. See [implementation status](STATUS.md) and [format boundaries](FORMATS.md).


Mailings acceptance (2026-10-15): 300 Java 8 tests; 13 paired Wordcraft text/field probes; 13 Mailings UI workflows plus seven citation regressions on each native Mac/iPhone/Android target; current browser recipient/preview/merge/undo/recovery workflow. This does not certify all Mailings options or platform parity. See [Mailings scope](MAILINGS.md) and [evidence](mailings-acceptance.json).


Document comparison acceptance (2026-10-16): 311 Java 8 tests; 42/48 paired DOCX resolution checks (all 24 CN1-origin checks pass; six Wordcraft-origin paragraph-break failures reproduce in both readers); seven comparison and thirteen Mailings UI checks on each native Mac/iPhone/Android target; current browser comparison/accept/undo/reject/recovery workflow. See [comparison scope](REVIEW-COMPARISON.md) and [evidence](comparison-acceptance.json).
