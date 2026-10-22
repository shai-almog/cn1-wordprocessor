# Comment controls

Review > Previous comment / Next comment move through comments in paragraph and table-cell order (not creation order), include resolved threads and wrap at either end. Navigation places the caret at the comment start and leaves content, revisions and undo history unchanged. Go to in the Comments dialog still selects the whole anchored range.

The Comments dialog can edit the thread text or any reply. Saving preserves author, range and resolved state; Cancel keeps the original text. Each saved edit is undoable. Reply controls have distinct accessible labels and the action row wraps on narrow layouts. DOCX import and export keep leading, interior and trailing blank paragraphs in comment text.

Comments and replies carry a date (Wordcraft `Comment.date`, ISO 8601): new comments and replies are stamped with the current UTC time, editing or resolving keeps it, the Comments dialog shows it next to the author, `review.comments` and the comment listing commands return it, and it round-trips through the native format (`date` attributes), DOCX `w:date` and Word 2003 XML `aml:createdate` (import). Imported comments without a date stay undated. Word 97-2003 `.doc` comment dates are not read, as in Wordcraft's docbin reader.

Anchoring of comments inside table cells is described in [CELL-COMMENTS.md](CELL-COMMENTS.md).

## Limitations

- Cross-cell and note/margin-story comment ranges are not supported.
- Comment and reply bodies are rich (formatted paragraphs, as Wordcraft's comment part) and keep the author's initials (`w:initials`). Mentions are not supported (Wordcraft has none either).
- Full Word UI and rendering fidelity for comments is incomplete.
