# Implementation status

The approved target is a standalone application and reusable Java libraries, targeting desktop, mobile, and web, matching Wordcraft's documented feature subset with fewer application lines and no material performance regression. Automation includes compiled Java extensions, portable command macros, and desktop CLI/MCP access. **That target is not achieved by this implementation.**

## Implemented and locally exercised

- Persistent AVL styled-text rope and block sequence; multi-paragraph selection, insertion/deletion, formatting, paragraph properties, bounded undo/redo, transactional rollback, IME composition callbacks.
- Paragraph measurement cache and paginated rendering; rectangular tables, row splitting, basic page geometry, outline, zoom, word counts, find/replace in top-level paragraphs.
- Native `.cn1doc` project storage; original DOCX package retained; explicit conversion gate for known unsupported DOCX content; basic direct run formatting and table interchange.
- TXT/HTML/Markdown/RTF/AsciiDoc adapters through CN1; limited selectable-text PDF output.
- Two-slot checksummed recovery, damaged-write fallback, and revision ordering against delayed autosaves.
- Validated command registry, compiled Java extension interface, parameterized macros with rollback/cancellation and stale-preview rejection; template filling and writing checks; stdio MCP lifecycle and rooted file access.
- Standard Maven/ParparVM macOS AppKit/Metal application, native launch/render and ZIP recovery verified. The custom Swing host and GraalVM build path were removed. See [native startup status](STARTUP.md).
- Shared desktop/mobile/web application code; previously compiled JavaScript bundle. CLI/MCP remain separate JVM tooling. Native keyboard/IME and file chooser workflows still require validation.

## Remaining work required by the plan

| Area | Remaining work |
| --- | --- |
| Wordcraft parity | Verify all 502 catalog entries against actual workflows and implement the documented working subset; refresh the moving baseline before release. Current catalog entries are pending. |
| DOCX | Resolve inherited paragraph/character styles, numbering, themes, sections, headers/footers, fields, links, images, anchored objects, nested/merged tables and real-world round-trip fidelity. Current import often approximates layout. |
| Typography | Shaped layout and caret geometry for Arabic/Hebrew/Indic scripts, grapheme-aware editing, bidi navigation, font fallback/embedding, tabs, justification, line-break rules, widow/orphan handling, keep-with-next and pagination fidelity. |
| Editing | Rich table selection/editing, images/shapes/charts/equations, list UI/numbering, tabs/rulers, styles pane, rich clipboard, search in tables, print integration, touch handles, accessibility and reliable native IME on every port. |
| Word workflows | Comments, tracked changes/review, references/citations/bibliography/TOC, mail merge, proofing, templates, forms/content controls, document comparison, protection and remaining catalog workflows. |
| Performance | True incremental pagination after the changed suffix, large-document memory/undo accounting, GUI startup and input-to-present latency, equivalent cold/warm layout/import/export benchmarks with matched fonts and output checks. |
| Platforms | Browser runtime and persistence verification; Android and iOS builds and device tests; Windows/Linux desktop execution; offline/file/share behavior; signing and installable releases. |
| Extensibility | Broader SDK surface for advanced document nodes, versioned compatibility policy, sample external extension artifact, UI extension points, comprehensive macro capability/resource controls and host integrations. |
| Release | Reproducible dependency lock/version, complete test corpus, external format interoperability beyond the tested plain-text fixture, public license choice, distribution packaging and release documentation. |

## Acceptance gates

1. A catalog row needs a reproducible scenario, expected result, actual artifact and platform evidence for both apps before it can count toward parity.
2. Format tests need semantic assertions and rendered comparison on original, round-tripped and externally reopened files. Unknown package parts being retained does not imply they remain semantically usable after editing.
3. Performance gates use the same hardware, documents, fonts, operations and timing boundaries, median/p95 and peak memory, with cold/warm separation. The working threshold in the baseline is at most 5% regression; success is not inferred from a synthetic benchmark.
4. Fewer lines is evaluated at matched feature scope, with production/test/generated/runtime/dependency code reported separately. Framework reuse is disclosed.
5. Desktop, browser, Android and iOS must pass real interaction workflows. Compiling a bundle or invoking input callbacks does not satisfy that gate.
6. Native/GraalVM tests run locally only; do not create or dispatch native CI jobs.
