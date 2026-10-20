# Character formatting

Home > Font edits the font family/size, bold/italic, baseline, strike/double strike, small/all caps, hidden text, character spacing, horizontal scale and raised/lowered text. Underline choices include None, Single, Words only, Double, Thick, dotted/dashed/dash-dot variants, long dashes, waves and double waves, with an independent color or automatic text color. Outline, Shadow, Emboss and Engrave combine with these settings. The scrollable dialog keeps its actions available on phones.

Apply is one undo step and Cancel preserves the document. The underline checkbox, double-underline checkbox, dropdown and ribbon state agree; the ribbon toggles any underline mode off. Format Painter, Clear Formatting, named paragraph-style defaults, direct overrides, tracked formatting and accept/reject share these properties. With a collapsed caret inside a word, formatting targets that word without moving the caret; at word boundaries it changes subsequent typing. Word targeting includes apostrophes, underscores and combining marks.

Case effects preserve original text and UTF-16 selection positions. Small caps synthesize uppercase glyphs for lowercase letters at 80% size (no OpenType small-cap glyphs). All caps takes precedence when both are enabled. Hidden text stays in the model but contributes no glyphs or advance in the canvas and PDF.

## Commands

`TextStyle.characters` is immutable. `format.set` and `styles.modify` accept `doubleUnderline`, `doubleStrike`, `smallCaps`, `allCaps`, `hidden`, `characterSpacing`, `characterScale`, `characterPosition`, `underlineStyle`, `underlineColor`, `outline`, `shadow`, `emboss` and `engrave`. `underlineStyle` uses the OOXML names listed in `CharacterFormat.UNDERLINES`; `underlineColor` is six-digit hex RGB or `auto`. The legacy `underline` and `doubleUnderline` properties still select a single canonical underline mode.

Spacing accepts -100..100 points, scale 1..600 percent and position -1584..1584 points (positive raises text). Invalid values are rejected. `Session.transact` groups edits atomically and rolls back if any property is invalid.

## Rendering and persistence

Canvas and PDF share measurements, wrapping, baseline alignment and underline paths. Horizontal scale stretches glyphs as well as advances. Spacing operates at grapheme boundaries with negative advance clamped to zero. Inline fields render their cached result; hidden tabs and manual line breaks do not advance or split lines. Word-only underlines skip whitespace; patterned, heavy and wavy lines fit the text advance and may have their own color. Outline uses a bounded mask cache (262,144 pixels, 64 entries; wide runs are tiled). PDF uses stroked text for outline and offset passes for shadow/bevel.

Native XML and DOCX keep the properties, named-style and direct values, and tracked previous formatting. DOCX uses `w:u` mode/color, `dstrike`, `smallCaps`, `caps`, `vanish`, `outline`, `shadow`, `emboss`, `imprint`, `spacing` (twips), `w` (integer percent) and `position` (half-points); finer fractions round on export. Legacy native files keep their single/double underlines. HTML exports presentation CSS as an approximation.

## Limitations

- No separate Show Hidden Text preview mode.
- Effects are not WordArt: no customizable blur/lighting, no OpenType typography features, and complex-script shaping is incomplete.
- Character borders/enclosures and phonetic guides are not implemented.
- HTML, Markdown, RTF and AsciiDoc imports do not round-trip these properties reliably.
