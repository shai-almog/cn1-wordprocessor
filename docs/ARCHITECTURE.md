# Architecture

`Document` is an immutable root containing an indexed persistent `Sequence<Block>`, a `PageSpec`, a title, and an ID allocator. Paragraphs contain a persistent `RichText` rope of styled chunks. Rectangular table cells contain paragraph sequences. Document and layout modules have no platform UI dependency.

`Session` is confined to one editing thread. Edits replace immutable roots; transaction failure restores document, selection, insertion style and revision. A successful transaction contributes one undo snapshot. The bounded history retains 200 roots; this is a count limit, not a byte budget. Composition is a temporary draft rooted at its original selection and commits to one undo operation. `loadDocument` clears file-specific history while monotonically advancing the revision. `replaceDocument` is undoable and used for document properties.

`LayoutEngine.Metrics` separates font measurement from layout. Paragraph caches are keyed by immutable paragraph identity and available width. Edits to one paragraph remeasure that paragraph. Pagination currently rebuilds placements for the whole document; the engine is not a fully incremental page tree. Wrapping uses code-point measurements, not a complete shaped-text layout implementation. CN1 paints only visible pages, but the full layout remains in memory.

`EditorSurface` implements CN1 `TextInputClient` and uses the public editor-host bridge from `RichTextArea` for platform input. It owns its canvas, document model, selection and rendering. Native input receives the current paragraph as surrounding text. The app does not wrap a browser editor or use Swing text editing. The macOS application uses the standard ParparVM AppKit/Metal port and CN1 file APIs. Separate JVM CLI tools have no GUI host.

The `formats` module owns format boundaries. `.cn1doc` packages the editable native XML and, when available, the original DOCX. Unedited DOCX re-export retains original part contents. Edited conversion rebuilds the supported document body; unsupported features require an explicit opt-in. Preserving unknown ZIP entries is a preservation mechanism, not a general fidelity guarantee.

`Commands` is the shared automation registry. A compiled extension registers typed command metadata and a handler; mutating handlers normally run transactionally. `Macros` operates on a fork for preview, then applies only to its originating session at the captured revision. UI, CLI and MCP use this API. Compiled extensions are trusted code; the portable macro language does not evaluate JavaScript/Java or load arbitrary JARs.

Recovery serializes immutable snapshots off the UI thread, writes one of two checksummed slots, reads it back and verifies it. A later revision prevents a delayed older write from replacing it. Startup chooses the highest valid generation. Application stop commits composition and saves synchronously; very large shutdown serialization remains a performance concern.

The native lifecycle wrapper in `common` extends CN1 `Lifecycle`, delegates to `WordProcessor`, and supplies platform file selection/download/share adapters. It is generated from the current CN1 Maven archetype. UI construction occurs in init(), after Display initialization. Native macOS launch/render and ZIP recovery were exercised; mobile device behavior remains unverified.
