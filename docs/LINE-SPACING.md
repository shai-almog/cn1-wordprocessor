# Paragraph line spacing

Home > Line spacing and Paragraph offer Multiple, At least and Exactly. Multiple uses a line multiplier; the other rules use points. The style editor exposes the same rule. Layout > Single, 1.5 lines and Double reset the rule to Multiple. Applying a rule/value is one undo step, works in table cells and preserves the selection; Cancel leaves the document unchanged.

Layout uses the exact height for line advance and pagination and clips to the containing table cell. Short exact lines bottom-align and clip text from the top; excess height centers the text. At-least spacing grows to fit taller runs. List markers follow the text baseline. Screen and PDF share the geometry (positioning follows the OOXML line-spacing definition).

Native files and DOCX keep the rule and value, including named styles and tracked paragraph-format history. Before/after spacing overrides preserve inherited line settings, and style updates keep a direct rule/value as a pair so points cannot become a multiple. Legacy native files without a rule keep their multiplier. DOCX stores multiples in 240ths of a line and points in twips.

## Commands

`paragraph.spacing {"rule":"exact","value":18}`; rules are `auto`, `atLeast` and `exact`. `paragraph.set` and `styles.modify` also accept `lineRule`; their `spacing` property edits the number in the current rule's units.

## Limitations

- Values below 0.06 are unsupported; DOCX input is clamped to that minimum.
- HTML and text interchange do not keep all spacing behavior.
- Baseline positions and clipping of tight exact lines can differ from Word-style renderers.
- Not every style-editor workflow is verified natively.
