# Unicode character editing

The shared editor uses Unicode 16.0.0 default extended grapheme clusters for logical Left/Right movement, Shift selection, collapsed Backspace/Delete, pointer hit testing and native caret geometry. Combining accents, Hebrew marks, Indic conjuncts, Hangul syllable sequences, emoji modifiers/ZWJ sequences and regional-indicator pairs stay together. Wrapping does not split clusters, including clusters that cross text-style or rope-leaf boundaries. Distributed spacing inserts no gap inside a cluster.

The implementation operates directly on `RichText`; it does not flatten a document for each keystroke. UTF-16 remains the storage, selection and IME coordinate system. Explicit selected ranges and IME replacement counts retain their existing semantics. This is logical segmentation, not a claim of bidi visual navigation, complex-script shaping, locale-specific word movement or typographic line-breaking conformance.

## Reproducible evidence

`grapheme-acceptance.json` identifies the exact source and local native/browser artifacts. The Java 8 reactor passes **340 tests**, including all **1,093 official Unicode 16 grapheme test cases** checked in both directions and at every UTF-16 offset. Regression tests also cover deletion/undo, tracked changes, table cells, style/rope boundaries, narrow wrapping and distributed layout. The data are pinned by URL and SHA-256 under `third-party/unicode-16.0.0`; `python3 scripts/generate-grapheme-ranges.py` verifies the inputs and reproduces the checked-in table. Generated source is included in application LOC/token totals, with a separate generated-source category.

**Native Linux: 109 checks**, including 36 new grapheme cases, run through XTest/GTK in local Xvfb. Seven cluster families pass logical movement, reversed Shift selection, Backspace, forward Delete and undo; adjacent regional-indicator pairs are separate. Comparison, Mailings, clipboard, dialog input, word/paragraph movement and typing undo regressions pass. This is native event-path evidence, not physical keyboard or window-manager certification.

**Native macOS: 27 checks**, including seven new grapheme checks, run on the Maven/ParparVM app. Actual native keyboard events pass combining-character and Indic navigation/deletion and joined-emoji reversed selection, forward deletion and undo. Twenty comparison/Mailings regression checks also pass. Built-in MCP records exact document and selection state. Both desktop runs restore their original XML; Mailings also restores recipients.

**Desktop Chrome: seven checks** cover combining Backspace, emoji forward Delete/undo/selection replacement, Indic Backspace, flag-pair deletion and Unicode recovery after reload. Native browser input and accessibility observations supply the evidence; no current Android/iOS/Windows grapheme certification is claimed.

## PWA build acceptance

The generated service worker keeps an existing client on its previous complete bundle until all clients close. A reload alone can continue running the older version. Initial checks on the reused preview origin reproduced the old code-point deletion behavior; after closing the old client and opening a new one, the same combining test passed. These earlier failures are retained as stale-build observations and are excluded from current-build checks. For local acceptance after rebuilding, close every tab/PWA window for the preview origin and reopen it, or use a new local port. Do not infer runtime provenance just from a successful build or a refreshed page.

Full Wordcraft parity, visual bidi navigation, shaping/font fallback, mobile input, all-platform QA and final matched performance measurements remain open. The review binary sizes identify artifacts only; they are not final comparable release measurements.
