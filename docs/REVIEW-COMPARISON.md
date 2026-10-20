# Tracked document comparison

Review > Compare documents compares the current document with pasted text or a DOCX, CN1DOC or text file. Choose word or character level, an author label, and whether case, whitespace and direct formatting differences count. The result is ordinary editable revisions: with all text differences enabled, Accept All yields the revised text and Reject All restores the original, including paragraph boundaries. Comparison is one undo step. In the UI the result requires Save As, so neither input file is overwritten.

## Command

`review.compare` accepts revised `text` or native document `xml`, optional `originalXml`, `author`, `level` (`word` or `character`), `caseChanges`, `whiteSpace` and `formatting`. File access stays in the host. Macro previews use the same engine and leave the original untouched until committed. It returns `changes`, `author`, `coarse` and `notHonoured`.

## Behavior

Paragraph insertion/deletion and text inside existing table cells are supported; table dimensions, merges, column widths, row heights and cell appearance must match. Direct paragraph/run formatting is tracked for unchanged paragraphs. Ignored case/whitespace differences adopt the revised spelling without marks. Native storage and DOCX keep the generated revisions with author/date.

Limits: two million characters and 200,000 tokens per story. UTF-16 surrogate pairs are atomic at character level. Prefix/suffix matching avoids quadratic work; the unmatched LCS table is capped at four million cells, and larger unmatched ranges become whole replacements with `coarse: true`. No text is truncated.

## Limitations

- Comparison is rejected when the document has unresolved revisions, section boundaries, comment/bookmark ranges, pictures, inline fields/notes, inserted/deleted tables, changed table geometry, or insertion at previously empty table boundaries (so nothing is silently discarded).
- Headers/footers, page geometry and style definitions stay from the original and are reported as not compared.
- Formatting inside changed paragraphs is not fully compared.
- Character level is not grapheme- or language-aware.
- Combine, moves, structural revisions and full document comparison are not implemented.
