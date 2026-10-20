# Status and known limitations

The app builds from one Java source for macOS, Linux, Windows, iOS, Android and the browser.
It implements the 487 working rows of Wordcraft's 502-row feature catalog (the other 15 are
stubs in Wordcraft too); each row was replayed in the native Mac app ([catalog matrix](acceptance-matrix.json),
[native check results](native-certification.json)). That check proves the native app matches
this app's JVM behavior and that every control has an observable effect. It is not a
byte-for-byte comparison with Wordcraft's output.

Performance numbers come from CI only (`.github/workflows/metrics.yml`); see the README.
Format coverage and its limits are in [FORMATS](FORMATS.md).

## Known limitations

| Area | Limitation |
| --- | --- |
| DOCX | Custom/composite numbering labels, theme fonts and colors, advanced section, header/footer and field content, floating/cropped/rotated pictures, anchored objects and nested tables are approximated or unsupported. Real-world round trips can lose layout detail. |
| Formats Wordcraft has | No WMF/EMF pictures, ODT or LaTeX yet, and no Zotero integration. `.doc` import skips comments, bookmarks, tracked changes, merged cells and floating shapes. |
| Typography | Glyph shaping on screen comes from the platform text renderer. Bidi ordering covers strong types, numbers and neutrals but not explicit embeddings or bracket pairing; lines with tabs keep logical order. Decimal tabs align on a period only. |
| PDF | Hebrew/Arabic fallback, RTL, Arabic presentation forms, kerning, links, outline and basic tags are exported; no Indic/complex OpenType shaping, system font fallback or font subsetting. See [FORMATS](FORMATS.md). |
| Editing | Mixed body/table selections, nested table editing, cross-cell and note-story comment ranges, and table-cell proofing are unsupported. The layout engine re-paginates the whole document after an edit rather than only the changed suffix. Undo history is bounded by count (200), not by memory. |
| Accessibility | The document is exposed to screen readers through Codename One accessibility, but this has not been verified in a live VoiceOver, Narrator or Orca session. |
| Platforms | Linux and Windows builds are produced and benchmarked by CI but have had little hands-on desktop use. Android and iOS were exercised on the emulator and simulator, not physical devices. The browser build has no live debugging channel. |
| Recovery | Crash recovery is stored unencrypted, so password-protected documents get no recovery copy. |
| Releases | macOS builds are signed with Developer ID; notarization can lag, in which case the DMG is not stapled. Windows and Linux packages are unsigned. |
| Automation | The SDK and macro API are provisional (`0.1.0-SNAPSHOT`) with no compatibility policy yet. |
