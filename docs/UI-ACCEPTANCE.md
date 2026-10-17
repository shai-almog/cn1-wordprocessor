# Title and ribbon acceptance — 2026-10-13

Benchmarking is paused. This is a partial UI parity pass, not certification of the Wordcraft feature catalog.

## Implemented

- Built-in CN1 custom desktop title integration with native traffic-light controls, native OS menus, quick Save/Undo/Redo, centered document name/recovery state, and command search.
- Compact Home ribbon with two-row font/paragraph controls, consistent font sizing, active formatting/style indication, Format Painter, case conversion, six styles and a More styles selector.
- Ribbon collapse and restore on tab selection; hidden navigation pane by default.
- File backstage with New/Open/Info/Save/Export, real document thumbnails and return to the editor.
- Native menu publication for visible custom toolbars, propagation of the desktop title-bar Maven hint, and native dialog input focus delayed until its owner window exists.

## Local verification

Java 8 Maven application reactor passed. Maven mac-source plus Xcode Release/ParparVM build passed. Framework ToolbarTest, DesktopChromeTest, WindowTest and NativeWindowDialogTest passed: 276 tests. The new native-dialog regression failed before the focus fix. Build-engine title-bar tests passed separately.

Native AppKit/Metal review bundles 5–7 were exercised with real UI input:

- OS File menu contains New, Open, Save, DOCX/PDF export and Rename.
- Search accepts `heading`, shows only Heading 1/2, and applies Heading 1.
- More styles exposes six styles; Subtitle and Normal apply.
- Collapse hides ribbon groups; selecting Insert restores the appropriate groups.
- Rename accepts `Ribbon Review` and updates the native window title.
- Screenshots confirm title alignment, readable font/size controls, active heading/bold highlights and the revised dialog frame.
- Earlier revision 4 verified File backstage thumbnails; revision 3 verified creating and typing in a blank disposable document.

The application shares CN1 desktop recovery storage across review bundle IDs. These tests used the explicitly approved disposable document.

## Still open

- Full catalog parity is not achieved. Insert, Layout, Review and other workflows remain much smaller than the reference.
- Destination-selecting desktop Save As, print, paragraph justification, advanced styles and document workflows remain missing.
- Format Painter and case conversion are implemented; full native interaction acceptance is still pending. Case conversion has a library regression for formatting, paragraph boundaries, selection, comment anchors and undo.
- The first focused Normal button in the styles dialog shows clipped leading glyphs; other choices remain visible and the button action works. Static prompt dialogs still inherit different native styling.
- General narrow-window ribbon overflow, keyboard/accessibility coverage and platform validation remain incomplete.
- Existing benchmark/source-size artifacts predate this UI; do not present them as current results or as evidence of parity.

## Building after framework changes

MacPort.jar embeds the core classes. After changing core, install core and rebuild/install the `maven/mac` bundle into the same Maven repository before running the application native build. Updating only codenameone-core leaves the generated native executable using the older bundled classes.


## Native mobile and paragraph batch (2026-10-14, still in progress)

- Built the real iOS ParparVM application with Maven `ios-source`, followed by local Xcode Debug simulator compilation. iPhone 17 Pro / iOS 26.3 boots and restores the test document.
- Fixed an observed low-level iOS input deadlock: UIKit selection queries called `callSeriallyAndWait` while the EDT waited on synchronous main-queue rendering. Core now provides immutable `TextInputGeometry` snapshots, captured on the EDT; native geometry queries never touch the live editor or wait on the EDT. Two geometry regression tests pass. The app enumerates visible insertion positions in its provider.
- Exercised native iPhone hardware typing, Select All, bold, undo, rotation, and actual software keyboard Return/letter keys. Software keyboard entry changed the word count. Compact category chooser, collapsed ribbon, document reflow, safe-area insets and native keyboard avoidance are implemented. Landscape keeps document/controls above the keyboard. Android, device IME and browser interaction remain unverified.
- The software-keyboard test exposed double application of scroll offsets in the document canvas. The correction is in source but awaits the next native interaction test. Do not count mobile acceptance as complete.
- Desktop Review 8, built through Maven `mac-source`, exercised native NSSavePanel: wrote `/private/tmp/word-native-acceptance.cn1doc`, edited text and saved again to the same path; inspected the ZIP XML to verify the changed text. Cmd-Shift-S opens format selection; Cancel returns to the editor. Atomic native writes use NSDataWritingAtomic. The doubled suggested filename extension observed in the chooser is fixed in source, pending rebuild.
- Paragraph justification/distribution, hanging/right indents, periodic tabs, keep-lines and widow controls now have layout tests and native/DOCX round-trip tests. Their native Paragraph dialog and rendered scenarios still need acceptance.
- PDF export now explicitly uses print layout even when the phone is in reflow mode.
- Static prompts now use the same light document-dialog frame with bounded scrolling. Pending native rebuild/inspection.

Benchmarking remains paused. No catalog row is certified by these partial tests.


## 2026-10-14 rich table editing batch

Maven/ParparVM macOS Review 10 was built from the application reactor and the native mac-source target (`/private/tmp/word-inline-native.log`). In the native UI, inserted a 3-by-3 table, typed `Alpha beta` directly into its first cell, used Tab to reach the second, applied bold with Cmd+B, inserted paragraph breaks, typed `New paragraph` and `More rich text`, and used Shift+Tab to return to the first cell. The table layout dialog merged the first two cells and retained all paragraph/run formatting. Saved `/private/tmp/word-rich-table-acceptance.cn1doc` through NSSavePanel; inspected its XML for the 1-by-2 merge and the expected plain/bold runs. The save suggestion now has one extension. Font dialog dropdowns and checked/unchecked boxes have readable contrast; mixed-size superscript is visibly raised.

The layout check exposed different spacing between ordinary and merged cells. Both now use the same paragraph-aware layout, including alignment, indents, spacing and list markers; library tests pass, the latest rendering change awaits a rebuilt native check. Ribbon overflow now has explicit scroll arrows; Font uses a group launcher. These latter UI changes also await native acceptance.

CellEditingTest covers canonical-root identity, body/cell shared undo/redo, rich clipboard, paragraph breaks, composition commit/cancel, merged follower traversal, Tab appending an undoable row, rollback and preview. AutomationTest covers search/replace in merged multi-paragraph cells and single-operation undo. Search now addresses table text. Cross-cell range selection and nested table editing remain incomplete.

The earlier iOS keyboard/scroll fix passed the follow-up landscape check: software-keyboard letters and Return updated the document with the active line and caret visible. The rich-table iOS build is in progress; desktop interaction evidence does not certify mobile cell editing.

New/Open and template creation now offer Save, Don't Save and Cancel, and defer replacement until save succeeds and the document still matches the saved snapshot. Native cancellation/error and replacement acceptance are pending. No benchmark or catalog-parity claims are added by this batch.


## 2026-10-14 pictures, styles and file workflow batch

Review 11 verified that Home groups fit the 1024-point desktop window, with Font's bottom-right launcher and a two-tile gallery plus More. New-document Cancel and Save followed by chooser Cancel preserve the test document. Successful Save wrote `/private/tmp/word-save-before-new.cn1doc`; the subsequent blank-document transition could not be directly observed because the UI tool timed out. Thread samples show idle AppKit/EDT loops, not a demonstrated deadlock.

Review 13 built through Maven/ParparVM (`/private/tmp/word-picture-native.log`). Real native typing and Insert ribbon actions worked; the accessibility tree exposes visible editable paragraphs. The Pictures control opened NSOpenPanel and selected the generated `/private/tmp/word-picture-test.png`. Closing the panel reproduced the UI-tool timeout. Image painting/editing and save round trips in the native UI are not yet certified. The Mac open/save implementations now use document-owned asynchronous sheets and restore the owner window; this fix awaits the next native build and interaction check.

The full Java 8 reactor passes 104 tests (`/private/tmp/word-pictures-styles-reactor.log`): rich picture insertion/undo, exact native/DOCX bytes and geometry, PNG row filters/palette/transparency, JPEG direct embedding, pagination/PDF object references, global style updates including cells, preservation of direct formatting, cell selection through undo, and native/DOCX style definitions. These library results do not certify rendered Word interoperability. Desktop Styles pane and mobile style dialogs are implemented but awaiting native acceptance.

Android debug APK compilation and emulator launch passed with no crash-buffer entries. The UI tool cannot bind the unbundled emulator process, so Android interaction remains unverified. iOS native typing/keyboard/rotation evidence above predates the picture/style batch; latest mobile builds and interactions remain pending. No benchmarks were run and no catalog rows certified.

Review 14 passes the picture workflow after switching to document-owned sheets: selected `/private/tmp/word-picture-test.png`, confirmed with Return, observed the red/transparent-blue image on the page, opened its accessible Picture action, set width 120 pt with proportions locked, centered it and changed its description. Native Save created `/private/tmp/word-pictures-styles-native.cn1doc`; XML inspection confirms 120-by-60 pt, centered alignment, the description and byte-for-byte original PNG data.

The desktop Styles pane created `Acceptance heading`, applied bold/20 pt, then modified it to 24 pt. Saved XML preserves both the definition and the paragraph using it. Native testing found an initial-family mismatch (the style dialog used generic names against resolved family names) and stale pane entries after New; both are fixed in source, with a font-alias regression test. Save-before-New also passes: the final edit was written to the explicit test file before the UI became a zero-word blank document. CUA coordinate clicks still report `noWindowsAvailable` on some native sheets/dialogs; accessibility actions and keyboard confirmation work.

File/Print and Cmd+P now use Codename One's existing `Display.print` API with a background-generated PDF and native completion/cancel/error status. Native print-dialog acceptance remains pending.

## 2026-10-14 font and table acceptance in progress

Review 15 Cmd+P opened the real macOS Print dialog with picture/text preview; Escape returned to the editor with `Print cancelled`. No print job was sent. The preview exposed Helvetica substitution, now replaced in source by embedded bundled fonts. `/private/tmp/word-fonts-acceptance.pdf` renders all twelve faces in Poppler; pdffonts confirms embedded CID TrueType faces with Unicode maps and pdftotext retains Latin accents, Greek and Cyrillic. Native acceptance of the replacement follows in the next build.

Table properties library checks pass (`/private/tmp/word-tableformat-tests3.log`): selection/undo, sorted appearance, merged-owner deletion, native/DOCX/HTML/PDF retention, relative widths and vertical geometry, repeated headers without missing or duplicate body rows. No catalog certification or benchmark measurement is implied.


## 2026-10-14 native file types, table properties, and mobile controls

Review 16 was rebuilt through Maven mac-source/ParparVM and iOS through Maven ios-source plus the simulator Xcode build. The framework custom file-type UTI now conforms to `public.data`; `.cn1doc` is selectable and opens in the native document-owned panel. Before this fix its dynamically generated UTI differed from the file's actual identifier and Open stayed disabled.

The disposable `/private/tmp/word-table-properties-acceptance.cn1doc` opened with unequal column widths, alternating shading and two-page pagination. Through Insert > Table layout > Table properties, row 2 column 2 was changed to `fff2cc`, bottom alignment and 64pt minimum row height. The page updated, native Save succeeded, and the saved XML contains those exact values. Native print preview showed both pages and the repeated header; preview was cancelled without submitting a print job. The prior picture fixture remains preserved at `/private/tmp/word-review16-preserved.cn1doc`; the explicitly approved accidental duplicate was deleted after byte comparison.

On iPhone 17 Pro / iOS 26.3 simulator, accessibility activation now expands the ribbon, switches Home to Insert, inserts a 3x3 table, focuses a cell, accepts `Cell` using the iOS onscreen keyboard, and hides the keyboard while preserving the edit. Button activation previously relied on a hardware GAME_FIRE mapping absent on touch-only ports; list activation previously changed selection without dispatching the action. Focused framework regression tests pass (43 tests). This is simulator interaction evidence, not device or all-platform certification.

Tab-stop acceptance: the native Paragraph > Tabs dialog loads an existing stop, changes it from left/dot to right/hyphen, applies through the parent Paragraph dialog, and saves to `/private/tmp/word-tabs-acceptance.cn1doc`. Initial native rendering exposed zoom-dependent leader overlap and a desktop-title-mode configuration regression; both are being corrected and require another native check.


Follow-up: native zoom-aware screen leaders now fit their gaps at 126%; the title controls are restored with `desktop.titleBar=custom` using the checkout's existing macOS stub-generator fix. The previously installed build-engine artifact omitted the property. Thirteen focused build-engine tests passed, the installed generator now emits the property before `Main.init()`, and the rebuilt native app displays the centered document title, Save/Undo/Redo and command search while retaining OS menus. On iOS, setting a 72pt right/dot stop through the nested Tabs dialog, applying Paragraph, and reopening Tabs preserves all settings. The parent and child dialogs remain cancellable.

Four additional paired headless probes pass against pinned Wordcraft: explicit left, center, right and decimal tab positions, dotted leaders, preserved text and actual DOCX tab characters. Together with the prior eleven probes, fifteen pass for both applications. Artifacts and hashes are under `/private/tmp/word-tabs-paired-probes`; this is command/DOCX evidence, not full visual or catalog parity. The native PDF preview exposed rounded screen metrics affecting leaders; PDF output now independently fits leader counts to embedded font advances, with regression coverage.


### 2026-10-14 fields (Review 16 and iPhone simulator)

- Standard Maven mac-source/ParparVM build succeeded (`/private/tmp/word-fields-native.log`); full reactor 130 tests (`/private/tmp/word-fields-full.log`).
- Opened synthetic `/private/tmp/word-fields-acceptance.cn1doc` through native chooser. Update fields showed PAGE 1 and 2 on separate pages, NUMPAGES 2 and current TITLE; locked PAGE stayed `Keep me`.
- Update-all undo/redo, Edit field code display/apply/undo and Date & time insertion passed. Saved package XML independently confirmed instructions/results/lock values. Native two-page print preview matched and was cancelled without a print job.
- Maven ios-source and simulator Xcode build succeeded (`/private/tmp/word-fields-ios.log`). iPhone Insert > Date & time produced `2026-10-14`; Edit field reopened matching instruction and result; Lock field and Apply passed.
- Native command-end/document-end and wheel scrolling did not move in this harness sequence; accessibility document Increment did scroll to page two. Full keyboard/trackpad acceptance is still required. Native title accessibility label remained the previous document title while the visible custom title and window title updated correctly.
- No performance benchmark or full catalog-parity claim was made.


### 2026-10-14 headers/footers (Review 16)

- Full reactor 137 tests (`/private/tmp/word-furniture-full3.log`). Standard Maven/ParparVM native builds pass; follow-up native/iOS integration checks remain in progress.
- Synthetic `/private/tmp/word-furniture-acceptance.cn1doc` has three body pages, first/even/default headers and centered PAGE/NUMPAGES footers. Native first header appeared in italic at its configured top distance.
- Insert > Header > First page: native Cmd+A typing replaced the text with `Quarterly report — first page`; Center and Apply changed the rendered header. One Cmd+Z reverted it; redo and save succeeded. Saved XML independently confirmed the changed first header and unchanged other variants.
- Initial small-editor preview clipped right-aligned text; `setReflow(true)` fixed its width and the rebuilt native dialog visibly showed the complete line.
- Insert > Footer > Even pages: native typing plus Page number inserted `Even page ` followed by an atomic PAGE field. This later test edit is currently in Review16 recovery, not the explicit saved fixture.
- Native print preview showed three pages, correct first/even/default headers, and page totals; cancelled without printing. Independent Poppler extraction/rendering of `/private/tmp/word-furniture-reference.pdf` confirmed page 1/2/3 of 3 and even header placement.
- Mobile header/footer editing, oversized margin content, section linkage and full on-page editing are still pending.


### Header/footer integrated build follow-up

Standard Maven/ParparVM macOS (`/private/tmp/word-furniture-native4.log`) and iOS simulator (`/private/tmp/word-furniture-ios.log`) builds pass. The native Command–Down/Up viewport defect is resolved in Review 16; the caret becomes visible on the last/first body page. iPhone portrait header TITLE insertion/centering/apply visibly renders Document 1. Footer PAGE insertion/centering/apply/reopen preserves result 1. The dialog controls fit at portrait width. Physical touch scrolling and the footer on the page remain unverified: CUA drag/scroll returned `noWindowsAvailable`; this is not evidence of an application scrolling failure.

## 2026-10-14 native title-bar behavior and review cleanup

The Mac port now receives the custom Toolbar's laid-out title band and interactive-control exclusions. Blank title space starts AppKit window dragging; double-click uses the macOS title-bar action (zoom/fill, minimize or no action). Native traffic-light controls remain native. Buttons, disabled buttons and custom pointer controls are excluded from window drag hit testing. Regions refresh when geometry changes and clear when the toolbar is removed. The Mac app delegate also restores the existing minimized main window on a Finder/Dock reopen event; merely activating through Cmd-Tab remains separate.

Validation: 17 DesktopChromeTest cases pass, including new control-exclusion, resize and cleanup regressions. The full application reactor passes 151 tests; Maven mac-source and Xcode Release/ParparVM build pass using the isolated snapshot repository. In the native Review 16 build, blank-title double-click expanded and restored the window, title dragging was exercised, edge resizing changed the window height, clicking the title opened Rename, Cancel returned to the document, minimize hid the window, and reopening the same bundle restored the same four-page/31-word document. Zoom/restore was repeated after reopening. The native close control exited the process. Other global double-click preference values are implemented but were not changed or individually exercised on the user's machine. Multi-monitor snapping remains unverified.

Seven obsolete macOS review processes and the old iPhone test process were stopped. Review 16 was replaced in place for this work and closed after acceptance. Process and simulator checks found no remaining CN1 review/test instances. The preserved document at `/private/tmp/word-review16-preserved.cn1doc` remains intact. No additional numbered review bundles were created for this repair.

All app Maven scripts now select a project-specific repository through `scripts/maven-env.sh`, including the CLI dependency-classpath step. The default is project-local `.local/maven-repository`; external repositories must have unique task-specific names. The active external path is kept in ignored `.local/maven-repo-path`. Shared `~/.m2` is rejected. The old common temporary repository was left untouched because it may have other owners, and is no longer used by these builds.

This fixes native window behavior; it does not certify the remaining Wordcraft feature catalog or refresh benchmarks.


## 2026-10-14 tracked review acceptance (Review 16)

The app now has Track Changes, Track formatting/author options, a live docked Reviewing Pane, accept/reject/all and previous/next navigation. Ribbon action widths accommodate the full captions. Text revisions render blue underline/red strike, with margin change bars; formatting and paragraph-break revisions also appear in review.

Native Maven/ParparVM checks: imported a tracked replacement, rejected inserted and deleted text separately, restored via Undo, typed a new tracked insertion, applied bold and centered paragraph formatting, rejected/restored paragraph formatting, saved `.cn1doc`, reopened it, and exported DOCX. The reopened pane retained four revisions with their authors/dates. ZIP inspection independently confirmed one insertion, one deletion, one character-format revision and one paragraph-format revision in the exported DOCX. Test files live under the unique task directory (`native-review-saved.cn1doc`, `native-review-export.docx`). The prior user-approved preserved document is untouched.

Native Save uses AppKit's modal save panel on its main thread with the Java worker waiting for completion; Save and New Folder are enabled and the chosen path is honored. Open remains the framework document-owned chooser; its accessibility state reported disabled for Open while the visible native button was enabled and successfully opened the selected file. No speculative Open-panel change was made.

The Maven/ParparVM iPhone simulator build passed. Through the real onscreen keyboard, a tracked Q insertion appeared, the mobile Reviewing Pane showed its author/date, Reject restored the original text, and Undo restored the revision. The test app was terminated afterward. This proves those mobile review interactions, not full mobile parity.

Follow-up native selection rejection passed: keyboard-selected the last four characters of a tracked caption, rejected only those characters, and restored them with Undo. Track formatting was disabled, reopened as unchecked and restored independently of text tracking. Named-style review and formatting-option behavior still need broader native acceptance. The current full reactor passes 192 tests, 59 paired CLI/DOCX scenarios pass in both apps, and 20 cross-import review decisions plus 10 caption cross-import edits pass. Those checks do not certify rendering or the full catalog. Native review processes are closed between builds, with Review 16 replaced in place.


## 2026-10-14 native caption acceptance

Review 16 launched after the user unlocked the desktop; no new startup failure was established. The title's native zoom control restored the undersized prior test window. References > Insert Caption produced `Figure I: Native caption test`; Undo removed it and Redo restored it. Inserting an earlier caption above it produced `Figure 1: Earlier caption` followed by `Figure II: Native caption test`, preserving each field's requested format. Saved with the native panel to the unique task-directory `native-caption-acceptance.cn1doc`; independent ZIP inspection confirms both SEQ instructions and results. No user document was overwritten. The app was closed and its process absence checked.

The Maven/ParparVM iPhone simulator build inserted `Figure I: Q` from the actual onscreen keyboard and restored the prior document with Undo. Initial testing found keyboard overlap and a Done action with no dismissal handler. App single-line fields now release focus/hide the keyboard on Done, and mobile dialogs enable bottom keyboard padding. The rebuilt dialog visibly keeps Insert/Cancel above the keyboard; before the final Done check, both Finder and Simulator became unavailable (`cgWindowNotFound`) while the app process remained alive and no new crash report appeared. This last interaction remains pending. The exact simulator app was terminated; no desktop review app remained running.


The subsequent case-conversion build adds all five Home > Change case choices and passes both native toolchains. Actual native interaction with those new choices is pending desktop availability; neither compilation nor the 59 paired headless probes certifies it. The mobile Done handler likewise still needs its final native check. No new performance claim accompanies these changes.


## 2026-10-14 resumed desktop and live native MCP

The unlocked desktop allowed the final iPhone caption keyboard check: Done dismisses the keyboard from both the label and caption fields, retains entered caption text, and reveals all dialog controls. Cancel preserved the test document.

The real Maven/ParparVM macOS and iPhone apps now expose Codename One's built-in MCP in the opt-in `agent-debug` profile. Native tool discovery, UI snapshots, live command schemas, document/selection inspection, stale-state rejection, shared undo and exact XML restoration pass. Normal Maven jars exclude the opt-in resource even after a profile switch without cleaning.

Live testing exposed framework MCP modal blocking and main-form-only snapshots. The local snapshot now lets modal activation return pending, offers `ui_windows` plus explicit `windowId` targeting, and rejects input to a blocked parent window. Seventeen framework MCP tests pass, including modal open/close, focused/native/explicit-window targeting and blocked-parent rejection. Actual macOS dialog discovery, parent-edit rejection and Cancel through MCP pass. Native focus can remain on the main form during background automation; explicit dialog targeting is used and verified.

`scripts/live-ui-smoke.py` drives all five actual Home > Change case choices on both macOS and iPhone, including list navigation to the fifth choice, per-mode undo and exact original model restoration. On iPhone it dismisses the keyboard, expands the compact ribbon and uses the ribbon overflow controls. The visible macOS uppercase text and restored iPhone paragraph/table were checked separately through native UI tooling. Results are retained in `live-mcp-acceptance.json`. These checks do not certify complex IME, physical touch input, other platforms or catalog-wide parity. The simulator test app was terminated after acceptance.


## 2026-10-14 native bookmarks/references through built-in MCP

The final Maven/ParparVM macOS and iPhone builds pass the eight checks in `live-reference-acceptance.json`. The script prepares a disposable document with a heading and body insertion point using the live session, then operates the actual ribbon and native dialogs through CN1 accessibility. It checks the resulting document/selection and normal undo history. Desktop insertion and mobile overflow controls were also inspected with native UI screenshots. The iPhone run initially failed because the References row clipped Update fields; switching to the shared scrollable groups fixed the actual UI.

Each successful run restores the XML captured at that run's start and waits for Recovery saved. The simulator's restored XML also survives termination/relaunch. Earlier failed runs retained their disposable test state for diagnosis; an immediate simulator termination before the recovery debounce had previously restored an older test state. The successful script now verifies persistence before teardown. No user document is used for these scenarios. Bookmark cells/margin stories, full field-switch semantics and all reference edge cases remain outside this acceptance scope.

After the final run, the exact desktop starting XML was checked again, Review 16 was stopped, and both desktop/simulator MCP ports were confirmed closed. No extra review instance was left running.


### 2026-10-15 contents, reflow and mobile dialogs

The References TOC dialog was exercised in native Maven/ParparVM macOS and iPhone 17 Pro / iOS 26.3 builds using the framework merged in PR #6019. The reusable `live-toc-smoke.py` creates headings in an explicitly disposable document, configures title/levels/leader through the actual ribbon dialog, follows a generated heading link, edits and updates the source, removes the TOC, and restores it in one undo. Every successful run restores the exact initial XML and waits for Recovery saved. Evidence: `toc-acceptance.json` and `artifacts/parity-20261015-toc`.

Visual inspection found desktop-width tabs wrapping page numbers in mobile reflow. The transient view now scales tab stops/indents while native storage and print layout retain original geometry. The corrected phone capture shows entries, leaders and page numbers on the same line. It also found the centered option dialog's body collapsing when the keyboard opened. Mobile option dialogs now fill the safe viewport; the title clears the notch and Insert/Cancel remain above the keyboard. A real onscreen Q key produced QContents; Done hid the keyboard; Cancel preserved the document.

These checks establish the listed workflows, not full Wordcraft or mobile parity. TOC custom styles, explicit outline levels and TC entries remain gaps. Native Windows/Linux, Android and browser interaction are still unverified for this batch.


### 2026-10-15 final caption lists and page-reference consistency

The final macOS Release and iPhone 17 Pro / iOS 26.3 Debug simulator builds use Maven/ParparVM against merged framework `008f9ece62526d807c2064887ad8f8641d6195a4`. `live-figures-smoke.py` passes the native ribbon/dialog workflow: title/label/leader insertion, generated-link navigation, caption edit/update, removal and one-step undo. `live-toc-smoke.py` also passes again on both final builds. The screenshots show the document fonts, generated labels, leaders and page numbers; the mobile ribbon still requires horizontal navigation for wide groups.

`live-pagination-smoke.py` compares a multi-page TOC created through the ribbon with MCP Update fields and a subsequent ribbon update. The Mac fixture consistently reports page 5. The different iPhone fixture includes existing cross-reference fields in a heading; its final result consistently reports page 7. Before the fix, the initial TOC copied the old heading text and incorrectly retained page 5 until the next update. A library regression and the final native run cover that defect. These are different documents, not a cross-platform pagination or speed comparison.

All final runs restore their starting XML and verify Recovery saved. Native UI inspection confirms the restored text/table; both agent-owned apps are terminated, with no review Mac process and neither MCP port left open. Evidence and source/binary hashes are in `artifacts/parity-20261015-references`; summaries are `toc-acceptance.json`, `figures-acceptance.json` and `pagination-acceptance.json`. The full reactor passes 230 tests. No full catalog, physical-device, other-platform or performance certification follows from these scoped checks.


## 2026-10-15 notes and reference regressions

The final Maven/ParparVM macOS Release and iPhone simulator Debug builds pass note insertion, rich story editing, custom marks, next-reference navigation, deletion and undo through the built-in MCP UI controls. TOC and figure-list insert/update/navigation/remove/undo regressions also pass on both platforms. macOS native Insert-menu access, native keyboard/select-all/bold formatting and post-reference baseline typing pass. Direct iPhone software-keyboard taps insert note text and a paragraph break, then apply successfully.

Every scenario restores its exact starting document XML and waits for Recovery saved. Both review apps were stopped, and ports 18765/18766 were checked closed. Source archive/fingerprints, binary hashes, independent PDF rendering and scoped checks are recorded in `notes-acceptance.json` and `artifacts/parity-20261015-notes`. These checks do not certify full note options, in-page note editing, all Wordcraft features or untested platforms.


## 2026-10-15 advanced TOC sources

The References ribbon now exposes Add Text outline levels, marked contents entries, and custom style mappings in the TOC dialog. Heading styles, explicit outline levels, mapped styles and TC fields can be combined or enabled independently. Entries preserve source text, distinct link anchors, level/category filtering and normal undo. Direct versus inherited outline levels survive native/DOCX storage and tracked-format rejection. Native dropdown labels now have readable colors on the editor’s light surface.

The final source passes 249 Java 8 reactor tests and 24 paired/two-way DOCX source checks. Maven/ParparVM builds and advanced-source/note-regression workflows pass on macOS and iPhone simulator. Native keyboard input and level selection pass; nested-dialog Apply followed by parent Cancel leaves the document unchanged. Review documents were restored byte-for-byte at the XML level, recovery completed, and both review apps were stopped. See `toc-sources-acceptance.json` for source hashes and retained evidence.

This does not complete the TOC field specification or application parity: arbitrary imported level ranges/switches, custom generated-entry formatting, multiple TOCs and remaining platform checks are outstanding. Notes options, citations/bibliography, index/authorities and broader object/review workflows still require implementation and acceptance. Performance comparisons remain paused.


## 2026-10-15 Android and native input sessions

The current source passes 250 Java 8 reactor tests and local macOS/iOS ParparVM builds, an Android debug APK build, and the ParparVM JavaScript build. Each of macOS, iPhone simulator and Android 14 ARM64 emulator passes 15 built-in MCP native UI checks covering caption lists, advanced TOC sources and rich notes. Every workflow restored its exact original XML and waited for recovery persistence. Real native macOS typing and iPhone software-keyboard typing also pass. Review apps and the agent-owned Android emulator were stopped. See `input-platform-acceptance.json`.

Android testing exposed keyboard composition callbacks changing a selection between automation commands. The editor now invalidates closed native input sessions and prevents programmatic commands from reopening the keyboard. Focused regression coverage verifies that callbacks from an old session cannot mutate its successor. The previously failing caption edit now passes. One later MCP connection closed while the app remained alive, with an empty Android crash buffer; reconnection and all workflows passed. This is recorded separately from app-crash evidence.

The Android build script uses Java 8 for Maven, a separate modern JDK for Gradle, the isolated Maven snapshot repository and a project-local or explicitly chosen Gradle cache. The browser build script rebuilds current editor dependencies and removes stale opt-in debug configuration. Browser interaction remains unverified because the browser tool could not load its request-header policy. Android physical-device, native keyboard/touch and complex IME acceptance, Windows/Linux checks and the outstanding document features remain incomplete. No final performance comparison is claimed.


## 2026-10-15 note settings and mobile numbering

The Footnote and Endnote dialog now controls eleven number styles, starting numbers, continuous/section/page restarts, and page-bottom/below-text or section-end/document-end placement. Settings support document defaults and sparse overrides for selected sections, native/DOCX persistence, selection preservation and one-step undo. Mobile reflow keeps page-restarted labels anchored to print pages. Focused dropdown text remains readable on desktop and mobile; the starting-number field requests a numeric keyboard.

The exact reviewed source passes 258 Java 8 reactor tests. Twenty paired and edited two-way DOCX checks pass against the pinned Wordcraft revision. Independent PDF rendering/text extraction verifies below-text placement and page/section restart labels. Local Maven builds pass for macOS, iPhone simulator, Android and browser. The reviewed note-settings ribbon workflow passes on all three native targets; Android also reruns rich-note regression. Native macOS/iPhone screenshots and iPhone software-keyboard number entry were checked separately. Every native workflow restored its exact original XML and waited for recovery persistence. All review apps and the Android test emulator are stopped. See `note-options-acceptance.json`; reviewed app archives and source hashes are retained in `artifacts/parity-20261015-note-options`. These debug-enabled artifacts are for acceptance, not performance comparison.

This remains partial parity. The format limits in `FORMATS.md`, direct note-story editing/objects, citations/bibliography, index/authorities, remaining review/object workflows, complex-script typography and unverified platforms remain outstanding. No final benchmark or language/framework superiority claim is made.


## 2026-10-15 index workflows

Mac, iPhone simulator and Android emulator each pass six built-in MCP UI checks: mark/subentry/bold/Mark All/See text, run-in index with right-aligned hyphen leaders, atomic undo/redo, update with new source entry, cancel without mutation, and remove while preserving marks. All three restore exact original XML and wait for Recovery saved. The real Mac dialog accepts keyboard text; iPhone Subentry accepts an actual software-keyboard F, Done hides the keyboard, and Cancel preserves the document. Generated native and PDF pages were inspected. Android physical input remains unverified.

Chrome now starts the application, displays the ribbon, accepts body typing and paragraph insertion, and opens References > Mark Index Entry. Startup/UI updates were visibly slow; the dialog title was present in accessibility but absent from the painted dialog. Browser control detached during dialog input, so full workflow, persistence and responsive acceptance remain open. Native review processes, Android emulator and local web server are stopped; the disconnected browser review tab could not be explicitly closed through the tool and is left to automatic session cleanup. See `index-acceptance.json`.


## 2026-10-15 authority workflows

Mac, iPhone simulator and Android emulator pass citation marking without deleting the selection, title/leader settings, later-citation update without duplicates, atomic undo/redo, cancel without changes and removal retaining marks. Index regression checks also pass on all three. Native and independent PDF rendering were inspected; original XML was restored and recovery completed before stopping every review app and the disposable emulator. Browser changes compile but were not accepted after the browser tool detached. See `authorities-acceptance.json`.


## 2026-10-15 citations and source management

Mac, iPhone simulator and Android emulator each pass seven citation checks: create source plus page-range citation, generate bibliography, edit source and refresh, switch citation style, atomic undo/redo, cancel source creation, and remove bibliography retaining sources/citations. Twelve authority/index regressions also pass on each. The Mac source Author field accepts real keyboard input; cancel is verified against exact document XML. Screenshots and independent PDF rendering were inspected. All review fixtures restore exact XML and recovery completes before app shutdown.

Current browser source compiles. The old preview origin recovered its earlier disposable document but showed an older cached ribbon. Served JS and the current ZIP match; a prior tab's active service worker is the likely version mismatch. A fresh local origin reached the splash, then browser control timed out before feature checks. Current browser editing, dialog rendering, responsiveness and persistence remain unverified. Native apps/emulator and servers are stopped; disconnected ephemeral browser tabs await tool cleanup. See `citations-acceptance.json`.


## 2026-10-16 table-cell comments

Comments now belong to body paragraphs or a range within one owning table cell. Stable paragraph IDs preserve their position across row insertion, merged-cell operations and paragraph splitting. Editing text updates the anchors; deleting the owning cell removes its comments, and undo restores them. The editor paints cell highlights from a cached paragraph index for the immutable document.

The Review ribbon supports Add, Reply, Resolve/Reopen, Go to and Delete for these ranges. Go to enters the correct cell and selects its range. Native XML and DOCX retain authors, range markers, replies and resolution. Twelve paired DOCX checks create plain, replied and resolved cell comments in each implementation and reopen/rewrite them with the other reader. They check anchor offsets, thread relationships and resolution, not rendered layout.

The final Java 8 reactor passes 350 tests with no failures, errors or skips. Ten new model/format tests cover anchor editing, row/merge changes, undo, replies, native/DOCX persistence and rejection of cross-cell ranges. The 59 paired document cases and 20 existing review interchange checks also pass.

The final native batch passes 185 checks: Linux 121, macOS 32 and iPhone simulator 32. Each platform runs the same 12 cell-comment ribbon/dialog checks. Mac and iPhone also run seven comparison and thirteen Mailings checks; Linux additionally reruns keyboard, dialog input, navigation, typing and grapheme regressions. Cell highlights were visually inspected on Mac and iPhone. These are local Maven/ParparVM builds with the built-in MCP; Linux uses Xvfb/XTest. Physical devices, Windows and current-source Android/browser acceptance are not certified here.

During the first iPhone Mailings run, an agent cleanup command terminated the app before the runner completed. This was an automation error, not an observed application crash. The interrupted recovery slots were backed up locally, the preserved original document was restored with a valid recovery checksum, and the running app verified exact original XML and empty recipient state. All thirteen Mailings checks were then rerun successfully; their runner exited before cleanup. The interrupted report is retained separately and excluded from passing counts.

All final native runs restored their original XML and recipient state and awaited recovery. Task-owned native apps and Linux containers were stopped. Exact source/artifact hashes, build logs, final reports and failure/restoration evidence are in `artifacts/parity-20261016-cell-comments`; see `cell-comments-acceptance.json`. The iOS artifact is a Debug simulator app, Linux is a review build, and Mac includes opt-in debugging. Their byte counts are artifact provenance, not matched release benchmarks.

Remaining comment work includes editing existing comment/reply text, next/previous ribbon navigation, cross-cell/story ranges and broader Word UI/rendering fidelity. Full parity and final startup, memory, source-size and performance comparisons remain incomplete.


## 2026-10-16 comment editing and navigation

The Comments dialog now edits threads and replies; Review includes Previous/Next comment. Twenty-two comment workflows pass on each of Mac, Linux and iPhone simulator. Additional phone screenshots verify the action row and edit-reply sheet. See [comment-controls acceptance](COMMENT-CONTROLS.md) for exact final counts, restoration, scoped comparison and retained runner failures.


## 2026-10-16 recipient management

Mailings > Edit Recipient List now provides five-row source paging, include/exclude controls, one filter, a stable text sort and recipient-count preview. Changes are staged until Apply and Cancel preserves the original state. Nine new workflows plus thirteen existing Mailings checks pass on each of Mac and iPhone simulator. Desktop and narrow-screen screenshots were visually inspected; this is MCP interaction evidence, not physical-device keyboard/touch certification. A first visual review caught missing text styling after paging; the corrected final build renders the row labels. See [recipient-management acceptance](RECIPIENT-MANAGEMENT.md).
