# Tracked document comparison

Review > Compare documents compares the current document with pasted text or a DOCX, CN1DOC or text file. Choose word or character comparison, an author label, and whether case, whitespace and direct formatting differences count. The result contains ordinary editable revisions. With all text differences enabled, Accept All produces the revised text and Reject All restores the original text, including paragraph boundaries. Comparison is one undo step. UI results require Save As, so comparison does not overwrite either input file.

The shared `review.compare` command accepts revised `text` or native document `xml`, optional `originalXml`, `author`, `level` (`word` or `character`), `caseChanges`, `whiteSpace` and `formatting`. File access stays in the host. Macro previews use the same engine and leave the original untouched until committed. The command returns `changes`, `author`, `coarse` and `notHonoured`.

Paragraph insertion/deletion and text inside existing table cells are supported. Table dimensions, merges, column widths, row heights and cell appearance must match. Direct paragraph/run formatting is tracked for unchanged paragraphs; formatting within changed paragraphs is not completely compared. Ignored case/whitespace differences adopt the revised spelling without revision marks. Both native storage and standard DOCX retain the generated revisions and their author/date labels.

Comparison rejects existing unresolved revisions, section boundaries, comment/bookmark ranges, pictures, inline fields/notes, inserted/deleted tables, changed table geometry and insertion at previously empty table boundaries. This prevents unsupported structures from being silently discarded. Headers/footers, page geometry and style definitions remain from the original and are explicitly reported as not compared. Combine, moves, structural revisions and full document comparison remain incomplete.

Comparison is bounded to two million characters and 200,000 tokens per story. UTF-16 surrogate pairs are atomic at character level; this is not full grapheme or language-aware segmentation. Prefix/suffix matching avoids quadratic work on mostly unchanged input. The unmatched LCS table is limited to four million cells; larger unmatched ranges become whole replacements with `coarse: true`. No text is truncated.

Reproduce paired comparison and both-way DOCX resolution checks:

```sh
python3 scripts/comparison-cross-import.py /path/to/wordcraft-cli --out /unique/evidence/directory
```

Against Wordcraft `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`, 42 of 48 text-resolution checks pass. Six failures originate in Wordcraft comparison output and reproduce when Wordcraft reads its own DOCX: whole-paragraph edits omit tracked paragraph-break metadata, leaving empty paragraphs after acceptance/rejection. All 24 checks originating from this app's generated comparison pass, including resolution in Wordcraft. The failures are retained; this is not full feature or rendered parity.

Native UI acceptance uses `scripts/live-comparison-smoke.py --allow-disposable --blank /path/to/blank.cn1doc --output /unique/evidence.json` (add `--port PORT`). Create the blank fixture with `scripts/wordprocessor new /unique/blank.cn1doc`. Only use this on a disposable review session. The script restores exact original XML through undo and waits for recovery persistence. See [acceptance evidence](comparison-acceptance.json).
