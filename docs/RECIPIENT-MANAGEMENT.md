# Recipient management

Mailings > Edit Recipient List keeps every imported row. Include/exclude checkboxes use original source order and show five recipients per page. Include all/Exclude all apply to the whole source list, including other pages and filtered-out rows. Filters and exclusions combine; clearing a filter does not re-include an excluded recipient. Preview recipient count shows how many rows will merge. Apply commits one undo step; Cancel discards all dialog changes.

Sort by one field uses case-insensitive text order, stable for ties; Descending reverses it. Filters: Contains, Equals, Does not equal, Less/Greater than, At most/least, Is blank, Is not blank. Text comparisons ignore case; inequalities compare finite numbers when both values parse, otherwise text. Equality keeps leading-zero identifiers distinct. Blank tests ignore surrounding whitespace.

Preview, previous/next, Find Recipient, label recipients, NEXT/NEXTIF and Finish & Merge use the selected order; record numbers and the inclusive Finish range refer to that order. Changing the view keeps the current source recipient when still selected, else selects the first result. An empty result turns preview off and restores placeholders; Finish rejects an empty list. Restoring all recipients and clearing filter/sort recovers import order. Source rows, mappings and settings are session-owned and never saved in a template or recovery data.

## Commands

`mailings.manageRecipients` exposes these operations to macros and MCP. `mailings.inspect` includes `sourceIndices`, original `sourceRecords`, exclusions and criteria. `mailings.editRecipients` without arguments returns selected `rows`, original `sourceRows` and the source-index mapping. API indices are zero-based; UI source-row labels and merge range/record numbers are one-based.

## Limitations

- One filter and one sort field only; no compound AND/OR (Wordcraft has no recipient filtering or sorting at all, crates/engine/src/cmd/mailings.rs).
- Sorting is text order, not numeric or locale-aware.
- No persistent recipient links or saved recipient state.
