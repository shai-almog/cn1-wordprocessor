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
| Formats Wordcraft has | WMF/EMF pictures render as bitmaps without their text records (Wordcraft also skips metafile text). Zotero integration: see the Zotero row. ODT carries one page style and no comments or tracked changes (Wordcraft drops those too). `.doc` import skips comments, bookmarks, tracked changes, merged cells and floating shapes. |
| Zotero | References > Zotero (Add/Edit Citation, Add/Edit Bibliography, Refresh, Document Preferences, Unlink Citations) speaks Zotero's word-processor wire protocol on 127.0.0.1:23116 through Codename One sockets. Verified only against a scripted fake Zotero in unit tests, not yet against a running Zotero desktop app or in the native app; ports where Codename One sockets are unsupported (browser) report an error. Citations are atomic plain-text fields in top-level body paragraphs: no citations in table cells, footnotes or endnotes, so footnote/endnote (note) citation styles are refused; rich (RTF/HTML) results lose formatting; the bibliography is one field in one paragraph with entries shown space-separated; Document_setBibliographyStyle is accepted but not applied; placeholder conversion, Add Note and document import/export transfer are unsupported. Each Zotero edit is its own undo step. |
| Typography | Glyph shaping on screen comes from the platform text renderer. Bidi ordering covers strong types, numbers and neutrals but not explicit embeddings or bracket pairing; lines with tabs keep logical order. Decimal tabs align on a period only. |
| PDF | Hebrew/Arabic fallback, RTL, Arabic presentation forms, kerning, links, outline and basic tags are exported; no Indic/complex OpenType shaping, system font fallback or font subsetting. See [FORMATS](FORMATS.md). |
| Proofing languages | Runs are proofed in their w:lang language (Review > Language sets it): English uses the bundled word list; Polish and Portuguese use a system-installed Hunspell `pl_PL`/`pt_BR`/`pt_PT` pair (none bundled, as in Wordcraft) with Polish/Portuguese grammar rules (repeated word, spacing, doubled punctuation, sentence capital, Polish missing comma before `że`/`który`); other languages get no marks. The Hunspell reader handles PFX/SFX, cross product, AF, MAP/REP/TRY but not twofold suffixes, compounding or ICONV, so real SJP/VERO dictionaries will over-flag some forms; `we`/`ze` and Wordcraft's fuller Polish/Portuguese suggestion tuning are missing; it has only been exercised with synthetic test dictionaries, not real installed ones or on device. Bulgarian (hyphenation only in Wordcraft) is unproofed. |
| Editing | Mixed body/table selections, nested table editing, cross-cell and note-story comment ranges, and table-cell proofing are unsupported. The layout engine re-paginates the whole document after an edit rather than only the changed suffix. Undo history is bounded by count (200), not by memory. |
| Accessibility | The document is exposed to screen readers through Codename One accessibility, but this has not been verified in a live VoiceOver, Narrator or Orca session. |
| Platforms | Linux and Windows builds are produced and benchmarked by CI but have had little hands-on desktop use. Android and iOS were exercised on the emulator and simulator, not physical devices. The browser build has no live debugging channel. |
| Charts and SmartArt | Imported charts keep only cached data, type, title, legend and value labels (no stacking, styles, axis formats or combination groups); edited charts are saved without an embedded workbook. Imported SmartArt is drawn from Word's saved drawing until edited, then re-laid out with a basic layout and saved as shapes, not SmartArt. |
| Equations | Office Math is imported, drawn built-up and round-tripped, but equations are block objects: inline `m:oMath` moves to its own line, editing is through linear text (no in-place structure editing, gallery or ink math), LaTeX import keeps math as text, and fine OMML formatting survives only while the equation is unedited. See FORMATS.md. |
| Recovery | Crash recovery is stored unencrypted, so password-protected documents get no recovery copy. |
| Releases | macOS builds are signed with Developer ID; notarization can lag, in which case the DMG is not stapled. Windows and Linux packages are unsigned. |
| Automation | The SDK and macro API are provisional (`0.1.0-SNAPSHOT`) with no compatibility policy yet. |
