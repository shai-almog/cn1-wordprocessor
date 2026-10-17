# Tab fidelity

Implemented bar tabs in the model, paragraph dialog, shared layout, screen painting and PDF output. The bar draws without a tab character and stays at its paragraph-relative position through wrapping, first-line indentation and alignment. DOCX now preserves cleared inherited stops and reads/writes the document-wide default interval.

Validation: 384 Java 8 tests; eight native dialog workflows on each of Mac and iPhone simulator, including nested-dialog cancel, apply, clear and undo/redo; 15/16 paired bar-tab interchange checks; 59 existing paired document cases and 48 table/DOCX checks. The Mac PDF fixture was rendered with Poppler and inspected alongside both native screenshots. The original documents and recipient state were restored, and both task-owned apps were stopped.

The one external failure is retained: the pinned Wordcraft reader truncates each tabs property to 64 entries, dropping a bar following 64 inherited-tab clears. Our own DOCX roundtrip retains it. See [reference reader](https://github.com/storytold/wordcraft/blob/159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0/crates/docx/src/read/props.rs#L285). The additive/inherited behavior follows [OOXML tab semantics](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tabs?view=openxml-3.0.1).

Native builds use the same isolated framework baseline and recorded local Mac patch as the table-selection checkpoint. This does not certify the latest upstream framework, release baseline, physical mobile touch, other platforms or full Wordcraft parity. Differing bar-bearing paragraph intervals still need explicit DOCX conversion; locale/RTL tabs, hanging-indent implicit stops and HTML tab rendering remain gaps.

Evidence: `tab-fidelity-acceptance.json` and `artifacts/parity-20261016-tabs/`, including source hashes, exact native bundles, build logs, screenshots, independently rendered PDF and both-reader output files. The scripts are `live-bar-tabs-smoke.py` and `bar-tabs-probes.py` under `scripts/`.
