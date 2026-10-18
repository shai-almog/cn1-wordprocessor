# Functional parity and native integration

Compared against Wordcraft at `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. Wordcraft
statements come from its source at that revision (paths given); ours from this repository
and the evidence files named.

## Catalog parity

Wordcraft publishes a 502-row feature catalog (`crates/engine/src/catalog.rs`). 487 rows are
implemented there; the other 15 are stubs in Wordcraft itself. **All 487 implemented rows are
certified here** against the native Mac app ([acceptance matrix](acceptance-matrix.json),
[certification evidence](native-certification.json); method and limits in [STATUS](STATUS.md)).
Certification proves the native app reproduces this app's JVM behavior for each command and
that every control has an observable effect; it is not a row-by-row byte comparison with
Wordcraft's output.

## Where we go further

| Capability | This app (Java / Codename One) | Wordcraft (Rust / egui) |
| --- | --- | --- |
| macOS menu bar | Native NSMenu File, Edit, Insert, Format and Tools menus (about 40 commands, with shortcuts) (`WordProcessor.installMenu`, `Command.setDesktopMenu`) | In-window ribbon only; no NSMenu (`apps/wordcraft/src/main.rs` uses AppKit only to terminate and to receive open-file events) |
| Rich copy **out** | Copy/cut place model XML, HTML, RTF, Markdown and plain text on the system pasteboard, read back by other apps in all five formats | Copies plain text (`ctx.copy_text`, `crates/ui-egui/src/keys.rs`) |
| Rich paste **in** | HTML, RTF, Markdown, images, files | HTML, RTF, images (`apps/wordcraft/src/clipboard.rs`) |
| Drag **out** of the document | Selection drags out as rich text to other apps; drags inside move as one undo step | Not supported (no drag source) |
| Drag **in** | Files and text, dropped at the caret under the pointer | Files (`raw.dropped_files`) |
| High-contrast appearance | Follows the OS setting live | Not supported |
| Dark mode | Follows the OS live; page stays paper, optional dark page | Follows the desktop theme (`crates/ui-egui/src/desktop_theme.rs`) |
| Text input | AppKit `NSTextInputClient` marked text through the Codename One Mac port | winit/egui IME events |
| iPhone and iPad | Same source builds a native iOS app; exercised through native UI and MCP ([platforms](platforms.json)) | No mobile target |
| Android | Same source builds an Android app; 15 native UI checks | No mobile target |
| Browser | JavaScript build, exercised | WASM target |

## Where Wordcraft is at least level, or ahead

- **Screen readers.** Wordcraft describes the document to AccessKit as paragraphs, headings,
  lists, tables and text runs with caret tracking (`crates/ui-egui/src/a11y.rs`). This app
  exposes roles, labels, heading levels and the document text through Codename One
  accessibility, but has **not been verified in a live VoiceOver session**. Treat this as
  unproven parity, not a win.
- **Native file panels.** Both use NSOpenPanel/NSSavePanel on macOS (Wordcraft through `rfd`).
- **FreeBSD.** Wordcraft builds for FreeBSD; this app does not target it.
- **Windows.** Neither app has been exercised on Windows here.
- **Physical drag tests.** Our drag in/out is verified through the pasteboard and the app's
  drop handler, not yet with a physical Finder/Safari drag.
- **Typography and PDF fidelity.** Wordcraft's PDF exporter and shaping cover more typography;
  our PDF export is faster but narrower (see [FORMATS](FORMATS.md)).
