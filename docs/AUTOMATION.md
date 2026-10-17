# Automation API v1

The command registry is shared by the UI, macros, CLI and MCP. `scripts/wordprocessor commands` prints current JSON descriptors and schemas. Indices are zero-based; text offsets are UTF-16 code units and must not split a surrogate pair. Paragraph-range selection can use `endBlock`.

## Java SDK

Depend on `com.codenameone.wordprocessor:automation:0.1.0-SNAPSHOT` after building/installing the reactor. The API is provisional.

```java
Session session = new Session(Document.empty());
Commands commands = new Commands();
commands.execute(session, "text.insert", Json.object("text", "Hello, {{name}}"));
commands.execute(session, "template.fill",
    Json.object("values", Json.object("name", "Ada")));
session.undo(); // Undo the template transaction.
```

A compiled extension implements `Commands.Extension`:

```java
public final class GreetingExtension implements Commands.Extension {
    public void register(Commands commands) {
        commands.register("greeting.insert", "Insert a greeting", "name:string", true,
            (session, args) -> {
                session.replace("Hello, " + Commands.str(args, "name"));
                return null;
            });
    }
}
// new GreetingExtension().register(commands);
```

The two included extensions are `TemplateExtension` and `QualityExtension`. These are compiled with the app. Dynamic JAR loading is not implemented. Extensions are trusted Java; marking a handler transactional supplies document rollback, not a sandbox for external side effects. Keep filesystem/network side effects outside portable commands.

## Macros

`examples/welcome-macro.json` is runnable with the README command. Macro JSON uses `version: 1` and a `steps` array containing `command` and `args`. `${parameter}` substitution is distinct from document template fields `{{field}}`. At most 1,000 steps execute; cancellation or command failure rolls back the transaction. Undo/redo commands cannot be nested in a macro.

```java
Macros.Preview preview = new Macros(commands).preview(
    session, macro, parameters, () -> false);
// preview.before, preview.after and preview.changes are available for review.
preview.apply(session); // Rejects another session or a changed document revision.
```

`run ... --preview` prints the proposed text and structural changes without writing output. A successfully applied macro is one undo step. Writing checks are basic heuristics (repeated spaces, long paragraphs, vague links), not spelling/grammar services.

## MCP over stdio

```sh
scripts/wordprocessor mcp --root /absolute/existing/workspace
```

The server implements the `2025-06-18` initialization lifecycle and tools interface. It does not open a network port. Standard output is JSON-RPC only. Command dots map to underscores (`text.insert` becomes `text_insert`). Additional tools are `open_document`, `save_document`, `preview_macro`, and `apply_preview`. `tools/list` supplies schemas.

Paths resolve inside the configured root; traversal and symlinks escaping it are rejected. Saving uses a new path and does not overwrite existing files. The host grants access to the entire chosen root, so choose it deliberately. An MCP session edits its own in-memory document; it is not a bridge into the currently open desktop window. Built-in commands are available; SDK hosts can register additional compiled extensions in their registry.

Preview tools return before/after text, a structural change summary and a revision-bound token. A direct mutating command executes immediately; clients that require review should use preview/apply. Preview storage is bounded to 16 entries. Macro parameters through MCP are not implemented; pass expanded values in its JSON.


## Reference lists and pagination

`references.toc`, `references.updateToc` and `references.removeToc` operate on the contents story. TOC source options are `headingStyles`, `outlineLevels`, `tcFields`, `styleLevels` (style ID/display name to levels 1–9), and `tcIdentifier` (blank accepts every TC category). `references.addText` sets the selected paragraphs’ `level` (0 excludes them); `paragraph.set` accepts `outlineLevel=-1` to restore inheritance. `references.markToc` inserts a hidden TC field after the selected text, retaining that text, with optional `text`, `level` and `identifier`. This is separate from Wordcraft’s index-marking command. `references.tableOfFigures` accepts a caption `label` (default `Figure`) plus the same title/page-number/alignment/leader/hyperlink options. `references.updateFigures` updates every represented caption category; `references.removeFigures` removes the requested category. Each action shares normal transactional undo. `references.updateFields` refreshes both kinds of generated story as well as supported document fields.

A host can construct `Commands(LayoutEngine)` or bind `setLayoutEngine` before command execution. Use the host's print engine, not its transient phone reflow dimensions. The native app does this when creating its editor after CN1 initialization. CLI commands use `PdfExporter.layoutEngine` with the bundled font provider, matching CLI PDF output. The no-argument SDK constructor retains Helvetica metrics for compatibility. These APIs, like the session itself, are confined to the host's editing thread.


## Note stories

- `references.footnote` / `references.endnote`: optional `text` and `mark`, inserted at the selection.
- `references.insertNote`: explicit `kind`, `text`, optional `mark`.
- `references.notes`: stable IDs, kind, visible label, custom mark and story text.
- `references.selectNote` / `references.deleteNote`: reference ID; body and cell references are supported.
- `references.nextFootnote`: next reference in document order, wrapping at the end.
- `references.updateNote`: ID plus optional text, kind and mark. Text editing participates in tracked changes.
- `references.convertNotes`: `to` is `footnote`, `endnote` or `swap`; the conversion is one undo transaction.

Rich story editing uses `Note.paragraphs` and `Session.updateNote`. Nested notes are rejected. Numbering/placement options are not implemented. The References ribbon's note editor and these commands operate on the same immutable note model.


`references.noteOptions` gets the effective settings with no arguments, or changes `kind` (`footnote`/`endnote`), `scope` (`document`/`section`), `numFmt`, `numStart`, `numRestart` and `pos`. Document changes clear only the matching section overrides, so unrelated section choices remain. Section changes cover the selected sections, retaining body or cell selection. All changes share one undo transaction. `references.notes` returns labels resolved against the host's print layout. See `scripts/note-options-cross-import.py` and `scripts/live-note-options-smoke.py` for interchange and native ribbon checks.


### Index

- `references.markEntry`: optional entry (defaults to selection), subentry, crossReference, bookmark, bold, italic, all, text. Inserts after the selection without deleting it. Mark All adds the first same-case whole-word match in each body/table paragraph without an identical mark; returns entry and marked count. Cross-reference and bookmark range are mutually exclusive.
- `references.index`: optional title, type (`indented`/`runIn`), headings, rightAlign, tabLeader, columns (currently only 1). Inserts at the caret or replaces the existing index settings.
- `references.updateIndex`: regenerate the existing unlocked index, preserving body/cell selection.
- `references.removeIndex`: remove the generated story, retaining source XE marks.
- `references.updateFields` includes index regeneration in its transaction.

Reproduce native acceptance with `python3 scripts/live-index-smoke.py --port PORT --allow-disposable --output NEW.json` on an explicitly disposable document. The script restores the original XML through undo. Run `scripts/index-cross-import.py WORDCRAFT_CLI --out NEW_DIRECTORY` for the matched comparison; it currently exits nonzero for two documented Wordcraft INDEX-range update failures.


### Table of authorities

- `references.markCitation`: optional `entry`, defaulting to the selected text; inserts a TA mark after the selection.
- `references.tableOfAuthorities`: optional `title` and `tabLeader`; inserts or replaces table settings.
- `references.updateAuthorities` and `references.removeAuthorities`: regenerate or remove the table while preserving source marks.
- `references.updateFields` also regenerates the authority table.

Reproduce native acceptance with `scripts/live-authorities-smoke.py --port PORT --allow-disposable --output NEW.json`; it restores the original document. `scripts/authorities-cross-import.py WORDCRAFT_CLI --out NEW_DIRECTORY` retains two known reference reverse-update failures and exits nonzero.


### Citations and bibliography

- `references.sources`: optional `add` source object and/or `remove` tag; returns the document's sources. Adding an existing tag edits that source and refreshes results. Fields: tag, kind, author, editor, title, year, month, day, city, publisher, journal, edition, volume, issue, pages, url, accessed, doi (strings). Authors use `Last, First; Last, First`.
- `references.citation`: existing `tag` and/or new `source`, optional `pages`; inserts an atomic CITATION field at the selection and refreshes numbering.
- `references.citationStyle`: `style` (the twelve displayed names or recognized Word stylesheet aliases); refreshes in one transaction.
- `references.bibliography`: optional `title`; inserts or replaces the bibliography.
- `references.updateBibliography` and `references.removeBibliography`: refresh results or remove generated paragraphs while retaining sources/citations.
- `references.updateFields` now includes citations and bibliography.

Reproduce native acceptance with `python3 scripts/live-citations-smoke.py --port PORT --allow-disposable --output NEW.json`. It restores original XML through undo and waits for recovery. Run `python3 scripts/citations-cross-import.py WORDCRAFT_CLI --out NEW_DIRECTORY` for the twelve-style comparison. It currently exits nonzero for twelve documented Wordcraft reverse-update failures.


Comment controls: `comment.edit` accepts `id`, `text`, and optional zero-based `reply` (omitted or -1 edits the root thread). Authors, anchors and resolved state remain unchanged. `comment.next` and `comment.previous` return the navigated thread ID, or null for no comments, and place a caret at its start in body/table-cell order with wraparound. They do not mutate the document or undo history.


Recipient management: `mailings.manageRecipients` accepts optional `sortField` (empty clears), `descending`, `filterField` (empty clears), `filterOperator` (`contains`, `=`, `<>`, `<`, `>`, `<=`, `>=`, `blank`, `notBlank`), `filterValue`, and `excluded` (the complete array of zero-based original source indices to exclude). Omitted values preserve their current setting. Apply changes are atomic and undoable, with preview caches refreshed. `mailings.inspect` returns the settings, original count and source-index mapping. `mailings.editRecipients` without arguments returns selected rows and original source rows without changing the list. Original rows are never deleted by management operations. Merge ranges and record navigation remain one-based within the selected order. See [Mailings](MAILINGS.md) for sorting and comparison semantics.
