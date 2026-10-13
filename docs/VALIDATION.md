# Local validation

See `validation.json` for the current machine-readable record. Generated artifacts and raw logs live under ignored `artifacts/` and Maven `target/` directories.

The Java 8 reactor tests cover persistent-tree differential edits, Unicode boundaries, session history/rollback/composition, selected paragraph properties, cached layout, split table rows, XML/DOCX/native serialization, preservation/conversion gates, PDF structure, macros/templates/previews, recovery corruption/order, and stdio MCP/path boundaries. They do not establish Wordcraft parity.

The desktop UI smoke launches the real CN1 form, invokes editor callbacks on the CN1 event thread, verifies typed text/selection/undo/composition, paginates a large document, checks caret scrolling, and captures the rendered form. Physical key events, OS composition candidates, assistive technology, mobile touch, and browser interaction remain untested.

A generated 1,000-paragraph, 25,000-word DOCX was opened by the pinned Wordcraft CLI and text-compared. This proves that particular textual fixture interchanges; it does not prove formatting or pagination equivalence. The two layouts have different metrics and page counts. The small macro fixture was exported to DOCX and PDF, and PDF text was independently extracted with Poppler.

The headless model benchmark uses fixed synthetic character widths and warmed JVM edits. Its `inputToPresentMeasured` and `competitorCompared` fields are false. `compare-cli.py` separately measures complete process invocation for equivalent DOCX-to-text extraction, alternating apps and verifying identical output; this includes JVM startup and excludes rendering. Neither measurement settles the performance release gate.

The JavaScript target compiled locally. Browser automation failed before interacting with the page (browser tool request-policy/unavailable errors). No Android device/emulator was connected. No Android/iOS build, Windows/Linux execution, CI run, cloud build or published release is claimed.

The common CLI behavior probes use each app's corresponding commands and inspect output DOCX semantics for paragraphs, selected bold text, replace-all and a rectangular table. The APIs differ: Wordcraft's `text.insert` maps newline characters to soft breaks, so a paragraph probe must use its `text.newParagraph` command. A command-name difference is not recorded as an implementation failure. These four probes certify zero complete catalog rows.

A separate warm-import benchmark links the unmodified Wordcraft DOCX release libraries and calls each engine's actual DOCX reader plus plain-text extractor on the same in-memory fixture. Three alternating process rounds each discard 50 operations and measure 100. Extracted UTF-8 output is compared byte-for-byte; startup, file I/O, layout and rendering are outside the timed region. This isolates one simple import workload, not overall format fidelity or application performance.

The local GraalVM CLI build passes 18 process-level checks against the JVM implementation:
help/command descriptors, macro preview/apply, inspect/text, native/DOCX/TXT/HTML/Markdown/RTF/AsciiDoc
round trips, byte-identical PDF output, invalid input, overwrite protection, unsupported PDF glyphs,
and MCP edit/save/root confinement. Portable PDF font metrics and XML fast-path validation have
regression tests. These are CLI checks; the GUI still uses JavaSE. Native compilation and checks
are local only. See [startup evidence](STARTUP.md) and [runtime checks](native-validation.json).
