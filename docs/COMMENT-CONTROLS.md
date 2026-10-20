# Comment controls

Review > Previous comment / Next comment move through comments in paragraph and table-cell order (not creation order), include resolved threads and wrap at either end. Navigation places the caret at the comment start and leaves content, revisions and undo history unchanged. Go to in the Comments dialog still selects the whole anchored range.

The Comments dialog can edit the thread text or any reply. Saving preserves author, range and resolved state; Cancel keeps the original text. Each saved edit is undoable. Reply controls have distinct accessible labels and the action row wraps on narrow layouts. DOCX import and export keep leading, interior and trailing blank paragraphs in comment text.

Anchoring of comments inside table cells is described in [CELL-COMMENTS.md](CELL-COMMENTS.md).

## Limitations

- Cross-cell and note/margin-story comment ranges are not supported.
- No rich formatting in comment bodies, no timestamps or mentions.
- Full Word UI and rendering fidelity for comments is incomplete.
