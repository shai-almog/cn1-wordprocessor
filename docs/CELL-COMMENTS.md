# Table-cell comments

Comments can anchor to a body paragraph or to a range within one table cell. Stable paragraph IDs keep the anchor in place across row insertion, merged-cell operations and paragraph splitting. Editing text updates the anchors; deleting the owning cell removes its comments, and undo restores them. The editor paints cell highlights from a cached paragraph index.

The Review ribbon supports Add, Reply, Resolve/Reopen, Go to and Delete for these ranges. Go to enters the cell and selects the range. Native XML and DOCX keep authors, range markers, replies and resolution. See [COMMENT-CONTROLS.md](COMMENT-CONTROLS.md) for editing and next/previous navigation.

## Limitations

- A comment range cannot span cells (cross-cell ranges are rejected).
- Cross-story ranges and note/margin-story comments are not supported.
- Rendering and Word UI fidelity for comments is incomplete.
