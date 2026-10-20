# Keyboard navigation

- Ctrl/Option word movement and deletion, Shift extension, reversed selections, and selection collapse to the proper edge.
- Ctrl-Home/End go to story boundaries; Mac Command-Up/Down reaches document boundaries. Table-cell editing stays in its own story.
- Home/End follow visual wrapped lines. One caret is painted at a soft wrap, and native input geometry uses the same visual side.
- Up/Down keeps the preferred horizontal position across shorter lines. Page Up/Down moves by the editor viewport; Shift keeps the original selection anchor.
- Ctrl-Up/Down moves among paragraph starts (Shift extends). Mac Option-Up/Down follows AppKit paragraph behavior and Option-Shift-Up/Down extends to paragraph boundaries. Movement stays inside the current body or table-cell story and stops at an adjacent embedded block.
- Word deletion uses normal undo and tracked-change transactions; repeated tracked deletion advances past text already marked deleted.
- A generated Unicode 16 table classifies letters, marks, decimal digits and underscore consistently on every runtime.

Grapheme-level movement is in [GRAPHEME-EDITING.md](GRAPHEME-EDITING.md); typing undo grouping is in [TYPING-UNDO.md](TYPING-UNDO.md). Visual bidi ordering is implemented; see [STATUS.md](STATUS.md).

## Limitations

- Cross-cell selection is not supported.
- Locale-specific word boundaries are not implemented.
- Broad input-method (IME) behavior is not verified, and new keyboard behavior is not certified on Windows, mobile or browser builds.
