# Character formatting workflow

Home > Font edits the font family/size, bold/italic, baseline, strike/double strike, small/all caps, hidden text, character spacing, horizontal scale and raised/lowered text. Underline choices include None, Single, Words only, Double, Thick, dotted/dashed/dash-dot variants, long dashes, waves and double waves, with an independent color or automatic text color. Outline, Shadow, Emboss and Engrave combine with these settings. The scrollable dialog keeps its actions available on phones and uses readable choice labels.

Apply is one undo step and Cancel preserves the document. The underline checkbox, double-underline checkbox, dropdown and ribbon state agree; the ribbon toggles any underline mode off. Format Painter, Clear Formatting, named paragraph-style defaults, direct overrides, tracked formatting and accept/reject share these properties. Formatting with a collapsed caret inside a word targets that word without moving the caret; at word boundaries it changes subsequent typing. Word targeting includes apostrophes, underscores and combining marks.

## Model and automation

`TextStyle.characters` is immutable. `format.set` and `styles.modify` accept `doubleUnderline`, `doubleStrike`, `smallCaps`, `allCaps`, `hidden`, `characterSpacing`, `characterScale`, `characterPosition`, `underlineStyle`, `underlineColor`, `outline`, `shadow`, `emboss` and `engrave`. `underlineStyle` uses the standard OOXML names listed in `CharacterFormat.UNDERLINES`; `underlineColor` accepts six-digit hexadecimal RGB or `auto`. The legacy `underline` and `doubleUnderline` properties remain supported and select a single canonical underline mode.

Spacing accepts -100..100 points, scale 1..600 percent and position -1584..1584 points. Positive position raises text. Invalid geometry, underline modes and colors are rejected. `Session.transact` groups related edits atomically and rolls back the group if any property is invalid.

Case effects preserve original text and UTF-16 selection positions. Small caps synthesize uppercase lowercase glyphs at 80% size; they do not select OpenType small-cap glyphs. All caps takes precedence when both are enabled. Hidden text stays in the editable model while contributing no glyphs or advance in the canvas/PDF. There is no separate Show Hidden Text preview mode yet.

## Rendering and persistence

Canvas and PDF share transformed measurements, wrapping, glyph-baseline alignment and underline paths. Horizontal scale stretches glyphs as well as advances. Spacing operates at grapheme boundaries, with negative advance clamped to zero. Selection geometry uses the same character measurements. Inline fields render their cached result while preserving atomic source positions; scaled/spaced tab leaders fit their allotted width. Hidden tabs/manual line breaks do not advance or split lines.

Word-only underlines leave whitespace unpainted. Patterned, heavy and wavy lines fit the measured text advance and can use a color independent of the glyphs. The normal canvas path avoids image/transform work for ordinary text. Outline masks preserve transparent interiors, including over highlighting; their cache is bounded to 262,144 pixels and 64 entries, and unusually wide runs are tiled. PDF uses stroked text for outline and separate offset passes for shadow/bevel effects. These effects do not implement WordArt, customizable blur/lighting, OpenType typography or complete complex-script shaping.

Native XML and DOCX preserve the properties, named-style/direct values and tracked previous formatting. DOCX uses `w:u` mode/color, `dstrike`, `smallCaps`, `caps`, `vanish`, `outline`, `shadow`, `emboss`, `imprint`, `spacing` in twips, `w` in integer percent and `position` in half-points. Finer fractions round on export. Legacy native files retain their single/double underlines and default missing properties. HTML exports presentation CSS; its patterns/effects are approximations, and HTML/Markdown/RTF/AsciiDoc imports do not promise reciprocal fidelity.

## Acceptance and limits

The integrated batch passes **519 Java 8 tests**, **98 native checks** (26 underline/effect checks and 23 character regressions on each of Mac and iPhone), **102 paired character edit/DOCX checks**, six independent PDF pixel checks and seven character-geometry checks. The paired probes use Wordcraft revision `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. The shared underline modes and four effects survive reciprocal DOCX reads; the additional heavy/long variants have local format, geometry and native checks. Saved rasters retain the rendering differences; they are not an equivalence certificate.

See `text-effects-acceptance.json` for current hashes and evidence, and `character-formatting-acceptance.json` for the earlier eight-property checkpoint. Scripts are `character-format-probes.py`, `render-character-probe.py`, `render-text-effects.py`, `live-character-format-smoke.py` and `live-text-effects-smoke.py`. Each native run restores its original XML and recipient state. The final review apps are stopped. The iPhone dialog/canvas and Mac canvas were visually inspected; the separate native Mac dialog could not be captured through the desktop tool (`cgWindowNotFound`). Its controls were exercised through the built-in MCP.

The framework remains the isolated 008f9ece-based snapshot plus recorded Mac fixes. Character borders/enclosures/phonetic guides, full shaping/font fallback, release-baseline integration and Windows/Linux/Android/browser/physical-device acceptance remain open, alongside the broader application gaps in [STATUS.md](STATUS.md). Full Wordcraft parity is not achieved.
