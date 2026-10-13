# Local validation

See `validation.json` for the current machine-readable record. Generated artifacts and raw logs live under ignored `artifacts/` and Maven `target/` directories.

The Java 8 reactor tests cover persistent-tree differential edits, Unicode boundaries, session history/rollback/composition, selected paragraph properties, cached layout, split table rows, XML/DOCX/native serialization, preservation/conversion gates, PDF structure, macros/templates/previews, recovery corruption/order, and stdio MCP/path boundaries. They do not establish Wordcraft parity.

The macOS application was built through Maven's `mac-source` target and Xcode Release,
then launched as a ParparVM AppKit/Metal executable. The rendered sample document and
File tab were inspected through native UI automation. Its recovery file was independently
validated for CRC32, ZIP integrity and XML contents, exercising the master ZIP APIs.
The original pre-Display initialization crash is fixed and covered by a regression test.

Further interaction checks were interrupted by native automation errors
(`cgWindowNotFound`, then `windowNotFoundAtPosition`). A debugger confirmed a live
CN1MacWindow; a subsequent screenshot showed the recovered document rendered. No new
crash report was produced. Physical typing, undo, IME and save/open dialogs are not
certified by this run. The old callback smoke is retained as a test helper only; it is
not native interaction evidence.

A generated 1,000-paragraph, 25,000-word DOCX was opened by the pinned Wordcraft CLI and text-compared. This proves that particular textual fixture interchanges; it does not prove formatting or pagination equivalence. The two layouts have different metrics and page counts. The small macro fixture was exported to DOCX and PDF, and PDF text was independently extracted with Poppler.

The headless model benchmark uses fixed synthetic character widths and warmed JVM edits. Its `inputToPresentMeasured` and `competitorCompared` fields are false. `compare-cli.py` separately measures complete process invocation for equivalent DOCX-to-text extraction, alternating apps and verifying identical output; this includes JVM startup and excludes rendering. Neither measurement settles the performance release gate.

The JavaScript target compiled locally. Browser automation failed before interacting with the page (browser tool request-policy/unavailable errors). No Android device/emulator was connected. No Android/iOS build, Windows/Linux execution, CI run, cloud build or published release is claimed.

The common CLI behavior probes use each app's corresponding commands and inspect output DOCX semantics for paragraphs, selected bold text, replace-all and a rectangular table. The APIs differ: Wordcraft's `text.insert` maps newline characters to soft breaks, so a paragraph probe must use its `text.newParagraph` command. A command-name difference is not recorded as an implementation failure. These four probes certify zero complete catalog rows.

A separate warm-import benchmark links the unmodified Wordcraft DOCX release libraries and calls each engine's actual DOCX reader plus plain-text extractor on the same in-memory fixture. Three alternating process rounds each discard 50 operations and measure 100. Extracted UTF-8 output is compared byte-for-byte; startup, file I/O, layout and rendering are outside the timed region. This isolates one simple import workload, not overall format fidelity or application performance.

The GraalVM CLI experiment is retired. Historical results are retained under
[history/graalvm-cli](history/graalvm-cli/README.md), and are not evidence for the
ParparVM GUI. See [native startup status](STARTUP.md).
