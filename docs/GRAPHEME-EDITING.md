# Unicode character editing

The editor uses Unicode 16.0.0 default extended grapheme clusters for logical Left/Right movement, Shift selection, collapsed Backspace/Delete, pointer hit testing and native caret geometry. Combining accents, Hebrew marks, Indic conjuncts, Hangul syllable sequences, emoji modifiers/ZWJ sequences and regional-indicator pairs (adjacent pairs are separate flags) stay together. Wrapping never splits a cluster, including across text-style or rope-leaf boundaries, and distributed spacing inserts no gap inside one.

The implementation works directly on `RichText` without flattening the document per keystroke. UTF-16 remains the storage, selection and IME coordinate system; explicit selected ranges and IME replacement counts keep their semantics. The cluster tables are generated from the Unicode data pinned (URL and SHA-256) under `docs/third-party/unicode-16.0.0`.

## Limitations

- This is logical segmentation only: locale-specific word movement, typographic line-breaking conformance and complete complex-script shaping/font fallback are not implemented.
- Mobile input-method behavior is not broadly verified.
- PWA builds: the service worker keeps an existing client on its previous bundle until all tabs close, so after a rebuild close every tab/window for the origin (or use a new port) before testing.
