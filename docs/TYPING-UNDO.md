# Typing and paragraph editing acceptance

The 2026-10-16 typing checkpoint passes **333 Java 8 application tests, 125 native checks and nine desktop browser checks**. Its source, binaries, framework patches and individual results are recorded in [typing-acceptance.json](typing-acceptance.json) and `artifacts/parity-20261016-typing`. This is a scoped editing checkpoint, not full Wordcraft parity.

## Behavior

Adjacent native single-code-point commits undo as words. Whitespace joins the preceding word; the following word starts another group. A pause longer than 1.5 seconds, selection movement, formatting, a command, composition, paste or component focus loss ends the group. Groups are capped at 256 UTF-16 units. A supplementary character stays whole. Every committed keystroke still advances the recovery revision; grouping only removes redundant undo snapshots. Undo/redo, history limits, tracked insertion and table-cell editing are covered by model tests.

Repeated native selection notifications at the same position preserve insertion formatting and the typing group. Changing the review author ends a group. Pasting even one character is a separate undo step from typing on either side.

Ctrl-Up/Down moves among paragraph starts and Shift retains the selection anchor. Mac Option-Up/Down follows AppKit paragraph behavior, including its forward/backward selector sequences; Option-Shift-Up/Down extends to paragraph boundaries. Movement stays inside the current body or table-cell story and stops at an adjacent embedded block. Wrapped visual Home/End remains separate from paragraph boundaries.

## Evidence

| Platform | Current checks | Boundary |
| --- | ---: | --- |
| macOS ARM64 | 32 | Twelve native paragraph/typing/formatting/paste checks and twenty comparison/Mailings regressions; Maven mac-source, ParparVM and Xcode Release. |
| Linux ARM64 | 73 | Fourteen paragraph/typing checks, twenty-two navigation checks and thirty-seven existing keyboard/dialog/comparison/Mailings regressions; actual X11 key events through GTK under local Xvfb. |
| Android ARM64 emulator | 20 | Seven comparison and thirteen Mailings workflows through built-in MCP. These do not certify physical typing, touch or typing-group behavior on Android. |
| Desktop Chrome | 9 | Real typing, grouped undo/redo, selection replacement, multiline entry, paragraph movement/selection and recovery across reload. No developer warnings/errors were reported. |

All native workflows restored their original document XML; Mailings restored its recipient state. Recovery completed before stopping the apps. The disposable Android emulator, Linux containers, browser review tab and preview server were stopped. The browser fixture was cleared and recovery saved. Unrelated applications were preserved.

The current libraries also pass all 59 paired CLI/DOCX cases and 20 two-way review-resolution cases against Wordcraft at `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. These assert document semantics, not equivalent page rendering.

Native acceptance found two framework gaps. [PR #6027](https://github.com/codenameone/CodenameOne/pull/6027) routes Mac paragraph selectors through the sequenced editor-command dispatcher; sixteen focused framework tests, Maven verification/static analysis and guide lint pass. [PR #6026](https://github.com/codenameone/CodenameOne/pull/6026) now activates GTK menu accelerators before the canvas handler consumes keys. The failing Ctrl-B case passes after that repair, including immediately following typing and undo. Both native overlays retain the merged `008f9ece` application baseline; CI and merge state are separate from local validation.

The Android runner records one explicit stale-selection retry after the IME closed. Retries are bounded and allowed only when XML, revision, cell and review state are unchanged. A separate debug-client interruption was retained as a failed attempt and rerun with exclusive MCP access; the application did not crash.

## Measurement corrections and remaining work

The retained Maven logs show 318 tests in the preceding navigation checkpoint and 308 in the comparison checkpoint. Their original reports included three stale tests from the removed desktop module and reported 321/311. Current metadata corrects those counts; original archives remain unchanged. The obsolete generated wrapper output was removed. The new count comes from the current reactor log.

The source-metrics tool includes the generated Unicode table in application totals and reports it separately as `generatedApplication`; four measurement-boundary tests pass. No new comparative source-token or performance result is claimed.

The subsequent [Unicode checkpoint](GRAPHEME-EDITING.md) adds logical grapheme editing and wrapping. Remaining work includes visual bidi navigation, broad IME acceptance, cross-cell selection, current-source iOS and Windows checks, physical mobile input, browser file/offline/mobile acceptance, and the larger format/workflow gaps in [STATUS.md](STATUS.md). Linux's RelWithDebInfo binary, opt-in MCP builds and unsigned Android APK are review artifacts, not comparable release-size or performance baselines. Full catalog certification and matched startup/RSS/size/token measurements remain incomplete.
