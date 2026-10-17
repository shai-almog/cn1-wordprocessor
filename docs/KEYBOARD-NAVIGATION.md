# Keyboard navigation acceptance

The 2026-10-16 navigation checkpoint passes 318 application tests and 91 native checks: 59 on Linux and 32 on macOS. This closes a specific editing gap; it does not establish full Wordcraft parity. Reproducible reports, source hashes, native binaries and framework patches are recorded in [navigation-acceptance.json](navigation-acceptance.json) and `artifacts/parity-20261016-navigation`.

## Implemented behavior

- Ctrl/Option word movement and deletion, Shift extension, reversed selections, and selection collapse to the appropriate edge.
- Ctrl-Home/End story boundaries; Mac Command-Up/Down reaches the document boundaries through AppKit's native selectors. Table-cell editing stays in its own story.
- Home/End follows visual wrapped lines. One caret is painted at a soft wrap, and native input geometry uses the same visual side.
- Up/Down keeps the preferred horizontal position across shorter lines. Page Up/Down moves by the editor viewport; Shift retains the original selection anchor.
- Word deletion uses normal undo and tracked-change transactions. Repeated tracked deletion advances past text already marked deleted.
- A generated Unicode 16 table classifies letters, marks, decimal digits and underscore consistently on every runtime. The generator pins its Unicode data version. It does not implement locale-specific word segmentation, grapheme clusters or bidi visual navigation.

## Native evidence

Linux's 22 new checks send XTest key events through GTK and inspect the actual document with built-in MCP. They cover word movement/deletion, selection, undo, Unicode categories and surrogate preservation, wrapped lines, preferred column and multi-page movement. Seven comparison, thirteen Mailings, thirteen basic keyboard and four dialog regressions also pass. The isolated Xvfb runtime is not physical-keyboard or window-manager certification. Its ARM64 RelWithDebInfo binary is a review artifact, not a release-performance control.

Mac's twelve navigation checks use native key events and MCP observations. They cover mixed word/character selection, reversed replacement, deletion/undo, document boundaries, preferred column, wrapped-line affinity, Page Up/Down and Shift-Page Down. Seven comparison and thirteen Mailings regressions also pass. Original XML and recipient state were restored, recovery was saved, and the review app was stopped.

The Mac checks found and repaired swallowed AppKit selectors and loss of reversed selection direction. The repair uses the framework's existing sequenced command dispatcher so subsequent native edits wait for the editor's acknowledged state. The developer guide and focused framework tests accompany [Codename One PR #6027](https://github.com/codenameone/CodenameOne/pull/6027). Linux uses the socket/input bridge from [PR #6026](https://github.com/codenameone/CodenameOne/pull/6026). Both are overlays on the merged `008f9ece62526d807c2064887ad8f8641d6195a4` application baseline; hosted CI and merge status are separate from these local results.

The framework's full local suite ran 7,814 tests with one analytics setup timeout. Its eight-test class passed on rerun, and Maven verify with static analysis then passed. Fifteen focused editor tests also passed against current master. The original full-suite timeout is retained in the evidence; this is not reported as a clean full-suite pass.

## Remaining gaps

The subsequent [typing checkpoint](TYPING-UNDO.md) implements paragraph shortcuts and typing undo grouping with scoped desktop acceptance. The subsequent [Unicode checkpoint](GRAPHEME-EDITING.md) adds logical grapheme editing and wrapping. Cross-cell selection, visual bidi navigation, locale-specific boundaries and broad input-method acceptance remain unfinished. New keyboard behavior has not been certified on Windows, mobile or browser builds. Existing platform results apply to their dated source/artifact checkpoints.

The 502-row feature catalog still needs equivalent workflow evidence. Performance and source-size comparisons remain paused until the matching workflows and rendering are established.

Test-count correction: the original archive reported 321 tests by including three stale reports from the removed desktop module. The retained reactor log ran 318 tests. The archive is preserved; the current acceptance metadata records this correction.
