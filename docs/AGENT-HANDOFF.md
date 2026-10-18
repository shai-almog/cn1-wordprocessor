# Word processor handoff

Prepared 2026-10-17 for the next agent, at the user's explicit request to stop this agent's implementation and summarize everything. This is a continuation guide, not a completion claim.

## Read this first

**Full parity has not been achieved.** The current Wordcraft catalog has **502 entries: 431 pending, 71 partial, zero certified complete**. These are bookkeeping states, not a reliable count of missing implementations: some statuses lag code. They nevertheless provide no basis for claiming full parity. Latest acceptance explicitly records `fullParity: false` and `renderedEquivalence: false`. Performance superiority is also unproven.

The user wants a recognizable Word application built properly with Codename One and Java, matching or exceeding Wordcraft in functionality, usability, performance, and supported platforms. They are frustrated that repeated requests to finish led to isolated feature batches and test-count updates rather than completion of the whole product. The next agent should organize and finish large, coherent user workflows against a concrete acceptance matrix, not repeat that pattern.

**Preserve the working tree.** Most application implementation is uncommitted or untracked. Cloning its HEAD does not reproduce this work. Ignored artifacts contain much of the provenance. Do not reset, clean, or replace either repository casually.

No application was launched or implementation changed while preparing this handoff. No new PR, PR comment, or commit was made. The only intended addition is this document.

## 1. User requirements and operating constraints

- Use a normal Codename One application lifecycle and Maven native compilation through ParparVM. Earlier Swing/custom-launcher work was rejected. Do not revive it, manually construct the UI before initialization, call `Display.init()` from the app, or substitute a GraalVM app executable.
- Deliver Word-like ribbon, title/document area, native desktop menus, navigation, status and editing behaviors. A generic editor with underlying commands is insufficient.
- Keep real native window drag, resize, close, minimize, maximize and platform appearance. Native title integration is preferred to pretending painted controls provide native behavior. Windows and Linux also need their appropriate title controls. Mobile web popup/window APIs are unnecessary.
- Scope includes standalone application, reusable document/editor libraries, Java SDK/automation and portable macros; desktop, mobile and browser behavior matters.
- Finish functional and visual parity before presenting performance numbers as a fair comparison. Measure actual resulting-source tokens, LOC, supported versus tested platforms, startup, latency, binary/bundle size and minimum/peak RSS over meaningful workloads.
- Do not manufacture evidence that Java or Codename One is generally superior for agentic coding. Separate measured outcomes from hypotheses and disclose framework/runtime contributions.
- **Do not create another PR without explicit authorization. Do not post PR comments.** The user specifically objected to unauthorized PRs and subsequently prohibited comments.
- Use JDK 17 for Maven. Custom framework snapshots must use an isolated, uniquely named cache, never `~/.m2` or generic temporary paths such as `/tmp/local-cn1`.
- Native/GraalVM tests are local only. Never dispatch, rerun, enable or recreate native CI jobs, equivalent workflows or the banned `native-test` label. The framework's AGENTS.md still applies.
- Keep at most one task-owned native review app running. Stop the exact owned process when finished; preserve unrelated applications. Preserve original document XML and Mailings recipient state, restore both, wait for recovery to save, then stop.
- Built-in application MCP debugging is explicitly authorized. Use real native interaction for keyboard/touch/IME, window chrome, file pickers and OS integration acceptance where app-level commands cannot prove behavior.
- Do not put credentials, user/recovery documents, generated binaries or operational logs into source control. Preserve unrelated dirty and untracked files.
- `gh` is the authoritative local GitHub CLI. A sandbox authentication failure must be retried with appropriate network access before diagnosing broken authentication. If a future PR is authorized, honor `~/.codex/config.toml` PR settings.

## 2. Repositories and exact state

| Item | Location / revision | Meaning |
| --- | --- | --- |
| Actual app | `/Users/shai/dev/cn1-wordprocessor` | Work here after preserving the working tree. |
| App branch / HEAD | `main`, `3ac33836cddb3a247c984baa341862ee4c097182` | Does not include most current work. |
| App dirty state before handoff | 52 modified tracked entries, 317 untracked entries | 369 entries before this document; do not confuse entries with Java source files. |
| Framework workspace | `/Users/shai/dev/cn4/CodenameOne` | Current agent cwd; many existing unrelated and relevant modifications. |
| Framework branch / HEAD | `master`, `d519cd4e6fb061bb120877acb36aa3f4c87aa971` | Not the framework snapshot used for latest acceptance. |
| Tested framework | Isolated `008f9ece62526d807c2064887ad8f8641d6195a4`-based snapshot plus recorded Mac fixes | Includes `framework-patches/mac-composite-selection.patch`; release integration remains open. |
| Wordcraft checkout | `/private/tmp/wordcraft-comparison` | Local pinned comparison tree. |
| Wordcraft HEAD | `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0` | Verified during handoff; preserve pin for repeatability. |
| Wordcraft executables | `target/release/wordcraft-cli`, expected GUI `target/release/wordcraft` under that checkout | Inspect availability before running. |
| Wordcraft catalog | `crates/engine/src/catalog.rs` | Compare actual behavior as well as declarations. |

The application POM is `0.1.0-SNAPSHOT`, uses `cn1.version=8.0-SNAPSHOT`, and targets Java 17 (records, text blocks, switch expressions; Android needs the `java.version=17` build hint). The user's original desire for Codename One 7.0.277 as a released baseline has **not** been satisfied by calling this isolated snapshot a release.

Framework dirty files span core implementation/MCP/Dialog/List/Toolbar/Window/accessibility, Linux native and Java ports, Mac AppKit text input/window/chrome/Metal and Java bridges, iOS input, builder tests, core tests and developer documentation. Untracked work includes TextInputGeometry, Linux text input files/tests, initializr-related files and generated backend content. These were not modified by the latest font batches. Determine provenance before touching them; do not assume they are all this app's work or all already upstream.

PR history: #6019 was explicitly requested and the user confirmed merging it. #6026/#6027 were created without approval, drew objections, and subsequently received review/CI/conflict work. Current remote state and merge coverage were not queried for this handoff. The conversation is not proof that every dirty local framework change is in a released artifact.

## 3. Staging, transfer integrity and evidence locations

Latest workroot (`W` in the commands below):

`/private/tmp/cn1-word-tables-20261016-xtUzBOG9`

- Staged build source: `W/app`.
- Isolated Maven repository: `W/maven-cache`.
- Latest staged source **was transferred into the actual app repo**. All **338** entries in `artifacts/parity-20261017-text-effects/source-sha256.json` were freshly verified against the actual repo during this handoff.
- Manifest SHA256: `a3a88ccf570f29ca037209e1983083764b7cc4f4c5e90026e7fd96aa2e554c8d`, also recorded in `docs/text-effects-acceptance.json`.
- Transfer receipts: `W/sync-characters-result.json` (30 files) and `W/sync-effects-result.json` (25 files), with **37 distinct changed paths** across both batches. Expanded transfer verified 417 artifact hashes.
- Backups: `W/characters-before/`, `characters-before-sha256.json`, `characters-workspace-before/`, and the corresponding `effects-before/`, `effects-before-sha256.json`, `effects-workspace-before/`.
- Archiving helpers: `W/archive-characters.py`, `W/archive-effects.py`. Old archive/sync/implementation helpers may require original baselines and new destination directories. **Do not blindly rerun them.**
- Actual repo ignored evidence: `artifacts/parity-20261017-character-formatting/` and `artifacts/parity-20261017-text-effects/`. Each contains source/artifact manifests, a `changes.patch`, logs, native reports/screenshots and paired document/PDF outputs.
- Temporary workroots may disappear or be unavailable on another machine. Preserve the needed evidence deliberately; these paths are not a portable dependency installation strategy.

Fresh handoff verification is `W/handoff-verification.json`, timestamp `2026-10-17T06:25:39.629257+00:00`: 338 source hashes verified, no matching review Mac process found, no listener on port 18765. It is a targeted check, not an inventory of every running application.

## 4. Latest acceptance and its limits

The authoritative latest record is [text-effects-acceptance.json](text-effects-acceptance.json). The earlier [character-formatting-acceptance.json](character-formatting-acceptance.json) is a superseded checkpoint for current font source.

| Check | Latest result | Scope |
| --- | --- | --- |
| Java 8 reactor | 519 tests; zero failures, errors or skips | Local tests. |
| Mac native | 26 effects + 23 character regressions | 49 scoped checks. |
| iPhone simulator native | 26 effects + 23 character regressions | 49 scoped checks. |
| Paired character edits and reciprocal DOCX | 102 checks | Compared against pinned Wordcraft. |
| Paired line-spacing regressions | 24 | Scoped regression corpus. |
| Paired document cases | 59 | Does not establish whole-product parity. |
| PDF effects pixels | 6 checks | Outline ink/interior, shadow, emboss, engrave, colored underlines. |
| PDF character geometry | 7 checks | Hidden text, scaling, spacing, caps/small caps display. |
| Rendered equivalence | **False** | Reference and app outputs still differ. |
| Full parity | **False** | Catalog closure, workflows and platforms remain open. |

Exact source manifest hash: `a3a88ccf570f29ca037209e1983083764b7cc4f4c5e90026e7fd96aa2e554c8d`.

Do not add historical test totals as though they represent independent feature coverage. Passing checks establish only their asserted cases. The 519-test suite is not evidence that every Wordcraft command is complete.

Latest local logs in `W`: `effects-final-reactor.log`, `effects-final-mac-build.log`, `effects-final-ios-source.log`, `effects-final-ios-build.log`; reports `effects-final-mac-live.json`, `effects-final-mac-characters.json`, `effects-final-ios-live.json`, `effects-final-ios-characters.json`, `effects-final-mac-binary.json`, `effects-final-ios-binary.json`, and `effects-cleanup.json`. Probe output directories include `effects-paired`, `effects-render`, `effects-character-render`, `effects-spacing-regression`, and `effects-document-regression`. Copies are archived under the latest ignored artifact directory.

### Native artifacts and cleanup

**Mac review bundle:** `/private/tmp/cn1-word-navigation-20261016-wnZ8j9YN/MacNavigation.app`, bundle ID `com.codenameone.wordprocessor.app.mac`. Reuse this review bundle instead of leaving more copies running. Built from `W/app/app/common/target/mac-native/Build/Products/Release/Main.app`. Executable SHA256: `0e7023726c2ff5ffdcff337a8d20e539ce3d174a77ae5ecf9b2147f6ceb5238a`. Last owned PID 47708 was stopped. Its document/recipient state was restored; window geometry and 214% zoom were left unchanged during the expanded batch.

**iPhone simulator:** iPhone 17 Pro, iOS 26.3, UDID `0E472C6A-9375-4774-9EF0-B2CAA3ECC9FB`, app ID `com.codenameone.wordprocessor.app`. Debug simulator executable is `W/app/app/common/target/ios-native/Build/Products/Debug-iphonesimulator/Main.app/Main`, SHA256 `b1c4bf0b2fa6977a00d6c2e818855a0b3b4c004210b6fb00e6335e5a4525b4e9`. Last owned PID 52057 was stopped. The simulator itself may remain booted. Installed path is recorded in the acceptance JSON; it is installation-specific.

Both apps reused port 18765 **sequentially**. Original XML and recipients were restored and recovery saved before shutdown. Do not kill a recorded PID without checking identity now; PIDs can be reused.

The native desktop tool returned `cgWindowNotFound` during latest Mac capture attempts. Application MCP and native controls continued to work. This is not proof of a crash. Mac `cn1://screen.png` captured the main canvas, not the separate native Font dialog, so there is no fresh separate-dialog Mac screenshot acceptance. iPhone dialog and canvas screenshots were visually inspected. App accessibility actions are not physical touch/IME certification.

## 5. Architecture and source map

Start with [ARCHITECTURE.md](ARCHITECTURE.md), but apply the stale-documentation warnings below. All Java package paths below are `src/main/java/com/codenameone/wordprocessor/` under the named module.

- `document`: immutable document roots, persistent `Sequence<Block>`, rich-text rope/stable IDs. `Session` owns editing, selection/insertion style, transactions/rollback, composition and undo. Undo keeps up to 200 roots, **not a byte budget**.
- `layout`: platform-independent `Metrics`, `LayoutEngine`, `MeasurementCache`, `CharacterMetrics`, `TextDecorations`. Measurement reuse exists, but placements are repaginated for the document; this is **not a fully incremental page tree**. No complete shaping/bidi engine.
- `editor`: CN1 canvas `EditorSurface`, `WordProcessor` UI and `TextEffects`. Uses `TextInputClient`/editor-host bridge; surrounding native text is scoped to the current paragraph.
- `formats`: native XML, DOCX, PDF and adapters. Key files: `DocumentXml`, `Docx`, `DocxStyles`, `RevisionFormats`, `TextFormats`, `PdfExporter`, `PdfMetrics`. Original DOCX parts may be preserved; retaining parts is not proof of semantic fidelity after editing.
- `automation`: shared `Commands` routes UI, CLI and MCP. Compiled Java extensions are trusted code. Portable macros use fork/preview, revision checks and rollback.
- `cli`: headless model/format/layout entry points. `benchmarks`: pinned reference and measurement scaffolding.
- `app/common`: normal CN1 `Main` lifecycle; UI created in lifecycle initialization after framework display initialization. Platform projects/build scripts produce native apps.
- Recovery uses two checksummed slots and generation ordering, with delayed write protection. Large synchronous shutdown serialization is still a performance concern.

### Implemented breadth, with scoped acceptance only

The following exists in code/evidence; none of it should be read as complete Word fidelity:

- Word-style File/Home/Insert/Layout/References/Mailings/Review/View/Automate tabs, File backstage, command search, native menus/title integration, ruler, zoom/status, styles gallery and navigation.
- Grapheme-aware editing, word movement/deletion, visual Home/End, vertical/page navigation and grouped typing undo. Physical platform input coverage varies.
- Paragraph alignment/justify/distribute, multiple/at-least/exact line spacing, indentation, tabs/leaders/bar stops, keep-lines/keep-next/widow controls and basic multilevel lists.
- Named paragraph-style create/apply/modify/update-to-match/delete, preserving direct overrides.
- Rich clipboard and cell paragraphs, rectangular table selection, merge/split cells, split table, row/column insert/delete, sorting and text conversion.
- Table alignment, padding, shading/basic borders, repeating headers, autofit/preferred width/indent, row automatic/minimum/exact height and row-break policy, alt text.
- Six aggregate table formulas with update, cycle errors and locks; Quick Tables including tabular/matrix/calendar cases.
- Block PNG/JPEG pictures with size/aspect/alignment/alt text and native/DOCX/PDF handling; not full anchored object layout.
- Basic section geometry and next/continuous/odd/even starts, page-number restart, linked paragraph headers/footers with first/even/default variants.
- Inline fields, captions with SEQ/STYLEREF, body bookmarks and cross-references.
- TOC/table of figures with outline/style-map/TC sources, notes/settings, citations/bibliography, index and authorities in supported subsets.
- Comments/replies, resolution/edit/navigation and range anchors in body/cells; tracked text, paragraph breaks and character/paragraph formatting; accept/reject globally, by ID or selection.
- Comparison for text, matched paragraph formatting and cell text; not full structural Combine.
- Mail Merge CSV/XLSX recipients, sorting/filtering/inclusion, preview/output and label-sheet subsets.
- Native editable storage, DOCX, format adapters, PDF with 12 bundled faces, recovery, Java SDK/macros and MCP.

## 6. Latest implementation: character formatting and effects

The final implementation work comprised two font batches, whose distinct path list is appended below. No framework files were changed for these batches.

`CharacterFormat` is immutable and held by `TextStyle.characters`. It adds double underline/strike, small/all caps, hidden text, spacing (-100..100 points), scaling (1..600 percent), vertical position (-1584..1584 points; positive raises), 18 underline choices including None, underline color (RGB/auto), outline, shadow, emboss and engrave.

There is one canonical underline mode; old underline/double-underline APIs remain compatible. The Font UI uses friendly labels, supports scrolling on mobile, restores current values when reopened, groups application into one undo step and supports cancel. Commands `format.set` and `styles.modify`, Format Painter and Clear Formatting share fields. Style updates preserve direct overrides. Tracked previous formatting survives native XML/DOCX and accept/reject.

At a caret inside a word, formatting applies to the word without moving the caret; boundaries change typing style. Word handling includes apostrophes, underscore and combining marks. Case effects change display while preserving original text and UTF-16 positions. Small caps are **synthesized at 80% glyph size**, not OpenType small caps.

`CharacterMetrics` measures per grapheme, applies case/scale/spacing, hides invisible advances and prevents negative advances. Ascent-aware measurement fixes mixed-size baselines. Fields keep `rawDisplay` and draw field results while maintaining atomic source offsets. Hidden tabs/newlines consume no width/break; scaled/spaced tab leaders fit allocated width.

`TextDecorations` shares screen/PDF decoration paths, including words-only, dotted, dashed, long/heavy and wave modes. Words-only skips whitespace. `TextEffects` uses transparent outline masks bounded to 262144 pixels and 64 entries, tiling wide runs. Hollow glyph interiors preserve highlights/backgrounds. PDF uses stroked outline text and offset shadow/bevel passes with scoped graphics state.

DOCX maps `u` value/color, `dstrike`, `smallCaps`, `caps`, `vanish`, `outline`, `shadow`, `emboss`, `imprint`, `spacing` (twips), `w` (integer percent), and `position` (half-points). Native XML remains backward-compatible and includes tracked previous fields. HTML is approximate CSS; reciprocal HTML fidelity is not established.

Eleven common underline choices including None and four effects have paired reference checks. Seven extra heavy/long variants have local format/geometry/native checks; the reference normalizes some modes. PDF baselines/decorations differ, and the reference specimen rendered some effects as solid text. There is deliberately no whole-render equivalence claim.

### Debugging traps already resolved or observed

- ParparVM lacks `Locale.ROOT`; use the compatible `new Locale("", "")` pattern already in the code.
- `GridLayout(0, 2)` is invalid; explicit rows were required, and generic two-column preferred sizing did not solve the Font dialog.
- `formRow` overwrites accessibility labels; tests must use the resulting UI label.
- Small-cap baselines must use ascent, not font-box bottom. Fields must draw cached result text, not object placeholders.
- Underline modes must not leave contradictory legacy flags latent.
- Repeatedly focusing a long MCP choice list resets scroll; focus once, then scroll.
- `lsof -Fp` includes `f...` records as well as `pPID`; filter only PID records.
- `open` returns before MCP is ready; wait for listener/readiness before testing.
- A smoke report may show zero checks or false restoration before the script's `finally` completes. Wait for script exit before interpreting final state.

## 7. Remaining work: do not call this an exhaustive completed audit

Reconcile these with the 502-row catalog and actual reference behavior. Some catalog entries may be reference stubs; some app capabilities may merely have stale status. Completion requires a concrete user-visible acceptance decision for each applicable row.

| Area | Important unresolved work |
| --- | --- |
| Pagination/sections | Newspaper columns/balancing/column breaks, mirrored margins/gutters, vertical alignment/text, line numbering, page-number formats, complex continuous sections, rich header/footer objects and fidelity. |
| Typography | Shaping, bidi layout/caret order, Arabic/Hebrew/Indic, fallback fonts and missing emoji, line-breaking fidelity, advanced OpenType/kerning/ligatures, character borders/enclosures/phonetics, hidden-text viewing. |
| Objects | Floating/anchored/inline/cell pictures; crop/rotate/wrap; shapes, charts, equations and WordArt. |
| Tables | Nested/RTL tables, asymmetric/decorative borders, rotation, conditional named styles, advanced cell widths, mixed body/table selections and real rendered fidelity. |
| Lists/styles | Composite/custom numbering, advanced definitions, full character-style management and inheritance. |
| Review | Final/Original/Simple Markup/balloons/customization, tracked structure/moves, Combine/protection, style-definition history, cross-cell/note comments. |
| References | Composite citations/roles/filters, index columns/collation/fields, authority categories/short forms, complete TOC switches/ranges, rich note stories and direct editing/bookmarks/comments. |
| Workflows | Remaining Mailings, proofing, templates, forms/content controls, accessibility and SDK UI extension surfaces. |
| Files/OS | External clipboard interoperability, Save As overwrite/error handling, mobile print/share, browser file/offline behavior. |
| Performance | True incremental pagination, memory-bounded undo, large-document shutdown, real input-to-present and matched full-workload measurements. |
| Delivery | Current released framework integration, current platform builds/acceptance, packaging/signing/licenses. |

## 8. Platform coverage: supported does not mean accepted

| Platform | Existing evidence | Still unproven |
| --- | --- | --- |
| Mac ARM64 | Maven/ParparVM AppKit/Metal release; latest 49 font checks | Fresh native dialog capture, full workflows and release-baseline acceptance. |
| iPhone simulator | iPhone 17 Pro/iOS 26.3 Debug; latest 49 font checks and dialog/canvas images | Physical device, real touch/IME and complete mobile workflows. |
| Linux ARM64 | Earlier Ubuntu 22.04 container/Xvfb checkpoint: 131 checks, GTK/XTest/MCP | Latest font source not rebuilt/accepted; no window manager, so title behavior, real file pickers/printing/physical input not certified. |
| Android ARM64 | Earlier Android 14 emulator Mailings/comparison/typing/MCP checkpoints | Current font/table source acceptance; physical touch/IME/device. |
| Browser | Earlier ParparVM JavaScript Chrome typing/grapheme/comparison/recovery evidence | Current font source acceptance, mobile browsers, complete file/offline/print workflows. |
| Windows | Declared target | No local build/interaction acceptance established. |

Wordcraft Mac GUI/CLI are locally available; other reference-platform claims were not all verified. Do not count a framework's theoretical ports as this application's tested coverage. A declared reference BSD target is not a Codename One advantage.

## 9. Build and debug recipes

These paths are local and were used for the tested artifacts. Check their existence first. Rebuilds from the actual source are appropriate, but its old `target` outputs may be stale. `W/app` is the source/build tree that produced the latest recorded binaries. Never run overlapping reactors with identical coordinates into the same cache.

```bash
export JAVA_HOME=/Users/shai/Library/Java/JavaVirtualMachines/azul-1.8.0_372/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export WORDPROCESSOR_MAVEN_REPO=/private/tmp/cn1-word-tables-20261016-xtUzBOG9/maven-cache
cd /Users/shai/dev/cn1-wordprocessor
bash scripts/build.sh
bash scripts/build-native.sh -Pagent-debug -DskipTests
```

`build.sh` installs the reactor and produces the CLI classpath. `build-native.sh` builds dependencies, requests Maven `mac-source`, and builds Xcode Release. `agent-debug` enables MCP port 18765. Ordinary builds exclude that debug configuration. **Do not benchmark/distribute the agent-debug artifact as the final release.**

For iOS, from the app repository root after dependencies are installed:

```bash
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -ntp -f app/pom.xml \
  -Pagent-debug -DskipTests -Dcodename1.platform=ios \
  -Dcodename1.buildTarget=ios-source package
xcodebuild -project app/common/target/app-common-0.1.0-SNAPSHOT-ios-source/Main.xcodeproj \
  -scheme Main -configuration Debug -derivedDataPath app/common/target/ios-native \
  -destination 'platform=iOS Simulator,id=0E472C6A-9375-4774-9EF0-B2CAA3ECC9FB' \
  CODE_SIGNING_ALLOWED=NO build
```

Verify the simulator ID still exists, stop the other owned review app, then use `xcrun simctl install`, `launch`, and `terminate` for bundle ID `com.codenameone.wordprocessor.app`. Do not reset or delete simulator user data.

Other builds: `scripts/build-android.sh` uses JDK 17 Maven for source generation, `JAVA17_HOME` for Gradle, `ANDROID_HOME`, and a project-local Gradle cache by default. `scripts/build-web.sh` and `scripts/serve-web.sh` build/serve the JS port. Use a fresh origin and close old PWA clients to avoid testing cached code. `scripts/build-linux.sh` is for Linux with JDK 17 and an isolated cache; see [LINUX.md](LINUX.md) for dependencies. Clean generated Linux target content appropriately because the translator can index old generated C; do not hide that problem with a larger heap. Container `--init` helps Xvfb process cleanup. The previous Linux debug RelWithDebInfo/no-LTO build is not a release performance baseline.

Formatter used: JBR Java17 at `/Users/shai/Library/Java/JavaVirtualMachines/jbr-17.0.7/Contents/Home/bin/java`, with `/private/tmp/cn1-word-mailings-20261015-H6Rey6wk/google-java-format-1.17.0.jar`. Do not accidentally switch Maven to Java17.

Native Java tests that initialize images may require local desktop access; an earlier sandbox abort was environmental. Inspect errors and retry authorized local access before diagnosing a source defect.

### MCP and acceptance probes

[scripts/live-mcp.py](../scripts/live-mcp.py) speaks newline-delimited JSON-RPC to localhost. Check `--help` and discover the live tool schema rather than relying on old documentation:

```bash
python3 scripts/live-mcp.py --port 18765 tools
python3 scripts/live-mcp.py --port 18765 word_snapshot '{"xml":true}'
python3 scripts/live-mcp.py --port 18765 ui_windows
python3 scripts/live-mcp.py --port 18765 word_layout
```

App tools include `word_commands`, `word_snapshot`, `word_command`, `word_layout`. Mutations require a fresh `stateId`. Do not blindly retry stale-state changes: existing probes retry only explicit pre-execution stale-selection cases after checking that XML/review/cells/revision did not change. UI tools include find/snapshot/activate/set-text/perform-action; use window IDs from the live window list. A modal action can return `pending: true`; inspect the resulting dialog instead of interpreting that as failure.

Examples after readiness, with fresh output paths:

```bash
python3 scripts/live-text-effects-smoke.py --port 18765 --allow-disposable --output /unique/new-effects.json
python3 scripts/live-character-format-smoke.py --port 18765 --allow-disposable --output /unique/new-characters.json
```

`/unique/...` is a placeholder: use a real unique task-owned directory. Inspect preservation/restoration behavior before any test; the scripts restore XML/recipients in `finally`. On failure, inspect restoration before stopping the app. Wait for recovery saved. Do not use broad kill commands. Also avoid `cua.getApp` merely to inspect stopped status: it can launch an application.

Paired probes take the reference CLI path as the first positional argument; use fresh output directories and the configured Java/cache environment:

```bash
WC=/private/tmp/wordcraft-comparison/target/release/wordcraft-cli
python3 scripts/character-format-probes.py "$WC" --out /unique/characters
python3 scripts/parity-probes.py "$WC" --out /unique/documents
python3 scripts/line-spacing-probes.py "$WC" --out /unique/spacing
python3 scripts/render-character-probe.py "$WC" --out /unique/character-render
python3 scripts/render-text-effects.py "$WC" --out /unique/effects-render
```

Raster checks require Poppler (`pdftoppm`, `pdftotext`) and Pillow. Many topic-specific `live-*-smoke.py`, `*-probes.py` and `*-cross-import.py` scripts exist. Inspect their assertions before treating their names as broad acceptance guarantees.

## 10. Documentation and known evidence caveats

Read [STATUS.md](STATUS.md), [FEATURE-VALIDATION.md](FEATURE-VALIDATION.md), [UI-ACCEPTANCE.md](UI-ACCEPTANCE.md), [VALIDATION.md](VALIDATION.md), [FORMATS.md](FORMATS.md), [LIVE-MCP.md](LIVE-MCP.md), [AUTOMATION.md](AUTOMATION.md), and the topic documents/acceptance JSONs. Machine records and source-specific manifests are preferable to historical prose where they disagree.

- STATUS's latest top-level 519-test section is current; a later historical feature section still says 478. Older sections claim exact rows/bar stops/note settings are missing even though later batches added them.
- ARCHITECTURE's code-point wrapping description predates grapheme work. Conversely, grapheme support does not imply complete shaping/bidi.
- Historical Helvetica-only CLI PDF descriptions are obsolete: current CLI/native PDF use bundled families.
- LIVE-MCP calls the app tools a set of three in one place, but `word_layout` also exists. Discover live tools.
- Some old instructions suggest simultaneous ports 18765/18766. The later user requirement to avoid multiple running versions takes precedence.
- `platforms.json` contains checkpoint evidence, not fresh acceptance of today's source on all platforms. LINUX's old PR-head references are historical.
- Known figure-field reverse-direction reference failures remain in the record: reference imports complex figure fields but failed to regenerate three reverse outputs.
- Earlier comparison checkpoint was 42/48: six reference-origin paragraph-break revision failures reproduced in both readers; all 24 CN1-origin cases passed. Do not silently erase these failures.
- Bar tabs were 15/16: reference dropped one bar after 64 clears.
- Row PDF strict border check differed by one pixel; minimum-height inset semantics differ. Matching 24-point line advances did not mean matching baselines.
- Latest independent effect/geometry raster checks are not Word/Wordcraft rendered equivalence.

## 11. Performance, source size and final reporting

There are **no defensible final current full-parity performance numbers**. Existing [COMPARISON.md](COMPARISON.md), [source-metrics.json](source-metrics.json), [NATIVE-BENCHMARKS.md](NATIVE-BENCHMARKS.md), [native-benchmarks.json](native-benchmarks.json), [STARTUP.md](STARTUP.md) and history artifacts are earlier measurements with limited scope. Do not copy them into a final claim about current functionality. The user explicitly challenged benchmarking unequal applications and asked to close features first.

`benchmarks/baseline.json` has a performance gate of at most 5% timing regression with equivalent documents/fonts/operations; its status remains `not-evaluated`.

Source counting: `scripts/source-metrics.py`, `test-source-metrics.py`, and `metrics-requirements.txt` use `cloc` and pinned tiktoken `o200k_base`. Count UTF-8 source content per file including comments/whitespace, without chat/file framing or minification. Report production code, platform glue and generated application code; keep tests/examples/benchmarks/tooling separate. Checked-in generated Unicode application tables count; dependencies, generated build outputs, assets and documentation do not. Rust inline-test pruning is an estimate and parse failures must be disclosed. Report runtime/framework contribution transparently. The user selected **tokens in resulting source**, not agent token usage.

Native measurements: inspect [scripts/native-bench/README.md](../scripts/native-bench/README.md), `prepare.py`, `run.py`, and `report.py`. Preparation patches disposable copies and is not idempotent. Preserve uninstrumented release artifacts/hashes for size; use equivalent instrumented copies for workload timing. Existing protocol alternates fresh native processes, samples RSS with `proc_pidinfo`, and records kernel peak/CPU using `wait4`; historical repetitions were 10 launch runs and 3 workload runs.

The existing startup marker is a **CPU document-paint callback**, not proof of first presented frame or input-to-photon. Filesystem caches were warm, not flushed. Command-driven edits are not physical keystrokes. Equal text alone does not prove formatting/rendering parity. Before publishing results, define startup boundaries, cold/warm conditions, idle and workload RSS minimum/peak, binary versus bundle bytes, input latency/long-document operations, build settings, CPU/OS/fonts, platform acceptance and exact source/artifact hashes.

[EXPERIMENT.md](EXPERIMENT.md) discusses a controlled agent comparison. One application comparison does not prove a general Java-versus-Rust agentic coding result.

## 12. Suggested continuation strategy

These are recommendations for the successor, not additional user approvals or new product requirements.

1. Preserve the dirty app/framework trees and ignored evidence, verify current source hashes and read actual code before reusing historical claims. Do not spend another iteration rebuilding context without a concrete completion plan.
2. Reconcile all 502 pinned catalog entries into a practical acceptance matrix: reference behavior, app behavior, missing UI/model/layout/IO/undo pieces, reciprocal fixtures, native interaction and evidence. Distinguish reference stubs and stale bookkeeping from actual gaps.
3. Resolve the released framework baseline and necessary port patches once. Continuing indefinitely on an old isolated snapshot prevents meaningful release/platform acceptance.
4. Work in large, complete workflows. A section/object/table batch must finish UI, editing, layout, selection, serialization, undo/review interaction, interoperability and representative native checks together. Apply the same discipline to review/styles/references and remaining workflows.
5. Rebuild the same source across required platforms, exercise native input/window/files/mobile flows, use a real document corpus and inspect rendered output. Do not substitute an ever-growing unit-test count for acceptance.
6. Only after meaningful functional/visual equivalence, rerun matched release benchmarks and source/platform measurements. Report failures and unsupported cases explicitly.
7. Complete packaging, licenses and a reproducible released dependency baseline. Do not open PRs or post comments unless the user authorizes those actions.

The previous approach failed to deliver the requested outcome because it repeatedly completed narrow islands of functionality, left the overall acceptance catalog unresolved, accumulated stale status prose and continued on a historical framework snapshot. This handoff must not be mistaken for completion of those obligations.

## Appendix: exact paths changed by the final two font batches

This list comes from the two transfer receipts, not an estimate of all project changes.

```text
automation/src/main/java/com/codenameone/wordprocessor/Commands.java
automation/src/test/java/com/codenameone/wordprocessor/CharacterCommandsTest.java
benchmarks/baseline.json
docs/CHARACTER-FORMATTING.md
docs/FORMATS.md
docs/LIVE-MCP.md
docs/STATUS.md
docs/character-formatting-acceptance.json
docs/text-effects-acceptance.json
document/src/main/java/com/codenameone/wordprocessor/CharacterFormat.java
document/src/main/java/com/codenameone/wordprocessor/ParagraphStyle.java
document/src/main/java/com/codenameone/wordprocessor/Session.java
document/src/main/java/com/codenameone/wordprocessor/TextStyle.java
editor/src/main/java/com/codenameone/wordprocessor/EditorSurface.java
editor/src/main/java/com/codenameone/wordprocessor/TextEffects.java
editor/src/main/java/com/codenameone/wordprocessor/WordProcessor.java
formats/src/main/java/com/codenameone/wordprocessor/DocumentXml.java
formats/src/main/java/com/codenameone/wordprocessor/Docx.java
formats/src/main/java/com/codenameone/wordprocessor/DocxStyles.java
formats/src/main/java/com/codenameone/wordprocessor/PdfExporter.java
formats/src/main/java/com/codenameone/wordprocessor/PdfMetrics.java
formats/src/main/java/com/codenameone/wordprocessor/RevisionFormats.java
formats/src/main/java/com/codenameone/wordprocessor/TextFormats.java
formats/src/test/java/com/codenameone/wordprocessor/CharacterFormatTest.java
formats/src/test/java/com/codenameone/wordprocessor/TableContentsFormatTest.java
formats/src/test/java/com/codenameone/wordprocessor/TextEffectsFormatTest.java
layout/src/main/java/com/codenameone/wordprocessor/CharacterMetrics.java
layout/src/main/java/com/codenameone/wordprocessor/LayoutEngine.java
layout/src/main/java/com/codenameone/wordprocessor/MeasurementCache.java
layout/src/main/java/com/codenameone/wordprocessor/TextDecorations.java
layout/src/test/java/com/codenameone/wordprocessor/CharacterLayoutTest.java
layout/src/test/java/com/codenameone/wordprocessor/TextDecorationsTest.java
scripts/character-format-probes.py
scripts/live-character-format-smoke.py
scripts/live-text-effects-smoke.py
scripts/render-character-probe.py
scripts/render-text-effects.py
```
