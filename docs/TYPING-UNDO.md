# Typing undo and paragraph navigation

## Undo grouping

Adjacent single-code-point typed commits undo as words. Whitespace joins the preceding word and the next word starts a new group. A pause over 1.5 seconds, selection movement, formatting, a command, composition, paste or component focus loss ends a group; so does changing the review author. Groups are capped at 256 UTF-16 units and supplementary characters stay whole. Every keystroke still advances the recovery revision; grouping only removes redundant undo snapshots. Repeated native selection notifications at the same position keep insertion formatting and the group. Pasting, even one character, is its own undo step. Tracked insertion, history limits and table-cell editing follow the same rules.

## Paragraph movement

Ctrl-Up/Down moves among paragraph starts (Shift keeps the anchor). Mac Option-Up/Down follows AppKit paragraph behavior and Option-Shift-Up/Down extends to paragraph boundaries. Movement stays in the current body or table-cell story and stops at an adjacent embedded block. Wrapped visual Home/End is separate from paragraph boundaries. See also [KEYBOARD-NAVIGATION.md](KEYBOARD-NAVIGATION.md) and [GRAPHEME-EDITING.md](GRAPHEME-EDITING.md).

## Limitations

- A text selection stays within one story (the body or one table cell); selecting across cells makes a rectangular cell selection (`CellSelection`, see [TABLE-SELECTION.md](TABLE-SELECTION.md)) rather than a text range.
- Broad IME behavior, physical mobile input and typing-group behavior on mobile are not verified.
- Browser file, offline and mobile behavior is not fully verified. See [STATUS.md](STATUS.md).
