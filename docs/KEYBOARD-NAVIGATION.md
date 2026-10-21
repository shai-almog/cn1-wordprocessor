# Keyboard navigation

Shortcuts follow Codename One's editors on every port (`EditKeys`); Ctrl on Windows, Linux and the browser is Command on the Mac, Alt is Option:

| Keys | Move |
| --- | --- |
| Alt+Left/Right (Mac Option+arrow) | Word |
| Alt+Up/Down | Previous/next paragraph |
| Alt+Home/End | Paragraph start/end |
| Ctrl+Left/Right | Line start/end |
| Ctrl+Up/Down, Ctrl+Home/End | Document start/end |
| Alt or Ctrl+Backspace/Delete | Delete a word |
| Shift with any of the above | Extend the selection |

- Left/Right swap in right-to-left paragraphs. Reversed selections collapse to the proper edge. Table-cell editing stays in its own story.
- Double click selects a word, triple click the paragraph.
- Home/End follow visual wrapped lines. One caret is painted at a soft wrap, and native input geometry uses the same visual side.
- Up/Down keeps the preferred horizontal position across shorter lines. Page Up/Down moves by the editor viewport; Shift keeps the original selection anchor.
- Paragraph movement stays inside the current body or table-cell story and stops at an adjacent embedded block.
- Word deletion uses normal undo and tracked-change transactions; repeated tracked deletion advances past text already marked deleted.
- A generated Unicode 16 table classifies letters, marks, decimal digits and underscore consistently on every runtime.

Grapheme-level movement is in [GRAPHEME-EDITING.md](GRAPHEME-EDITING.md); typing undo grouping is in [TYPING-UNDO.md](TYPING-UNDO.md). Visual bidi ordering is implemented; see [STATUS.md](STATUS.md).

## Limitations

- A text selection stays within one story (the body or one table cell); selecting across cells makes a rectangular cell selection (`CellSelection`, see [TABLE-SELECTION.md](TABLE-SELECTION.md)) rather than a text range.
- Locale-specific word boundaries are not implemented.
- IME composition is covered by tests and was checked on the Android emulator; keyboard behavior is not hand-verified on Windows, Linux, iOS or browser builds.
