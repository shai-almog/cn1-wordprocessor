# Java Outperforms Rust for Word Processing

This vibe-coded clone of [Wordcraft](https://github.com/storytold/wordcraft), written in
**Java** with [Codename One](https://www.codenameone.com), does more than the Rust original.
It has more functionality, deeper native integration and more platforms. It is faster, uses
less RAM and costs less CI time, and it does all of that in about **two thirds of the lines of
code and tokens**.

That should make the "rewrite it in Rust" movement rethink its direction.

The app is compiled ahead of time by ParparVM to native code, with no JVM at run time. One
codebase ships a native macOS app, an iPhone/iPad app, an Android app and a browser app. It
is measured head to head against Wordcraft (pinned at `159b84f`), a Rust/egui clone of the
same 502-row Word feature catalog.

![The macOS app](docs/images/editor-light.png)

<!-- ci-summary:begin -->
| Front | Java (this app) | Rust (Wordcraft) |
| --- | --- | --- |
| **Faster startup** | 370 / 231 / 288 ms | 821 / 246 / 455 ms |
| **Faster editing** | 8.9 / 10.7 / 12.4 ms to type a character | 51.9 / 14.9 / 24.9 ms to type a character |
| **Less RAM** | 112 / 111 / 90 MiB | 145 / 187 / 106 MiB |
| **Smaller executable** | 34.7 / 37.7 / 32.5 MiB | 55.0 / 75.2 / 57.5 MiB |
| **More platforms from one source** | macOS, Linux, Windows, iPhone/iPad, Android, browser | macOS, Linux, Windows, browser |
| **More features, deeper native** | native menu bar, rich copy out, drag out, high contrast | see [below](#more-features-and-deeper-native) |
| **Equally functional** | 487 / 487 implementable catalog rows | 487 rows implemented, 15 stubs |
| **Easier for LLMs** | 76,275 lines, 1,006,395 tokens | 118,659 lines, 1,646,847 tokens |
| **Cheaper CI per test** | 86 ms (1,123 tests in 97 s) | 500 ms (1,922 tests in 961 s) |

Desktop figures are macOS / Linux / Windows, measured on GitHub's runners by CI run [39825464193](https://github.com/shai-almog/cn1-wordprocessor/actions/runs/39825464193) (commit `502edf9`) with the 1,000-paragraph document: launch to first painted document, idle resident memory, and command to painted frame. Code counts are application code without tests or comment lines. Java wins 38 of the 42 desktop measurements; the rest are listed in [Per-OS results](#per-os-results).
<!-- ci-summary:end -->

## How can Java beat Rust on RAM and performance?

Modern Java is fast. Codename One builds on that with ahead-of-time compilation to native
code, which brings fast startup and low overhead, along with portability to more platforms.
The language itself adds little cost: plain classes, arrays and primitives compile to code a
C compiler optimizes like any other, so in the right hands Java runs as fast as native code.

None of this was built for this project. These are advantages the toolchain already has, and
we did not "cheat" with tricks such as profile-guided optimization. For client applications,
Java and Codename One bring several built-in advantages over Rust:

* **Garbage collection.** Allocation is cheap, and reclaiming memory is the collector's job
  on its own thread rather than the application's. There is no ownership bookkeeping in the
  code, and no defensive cloning or reference counting to satisfy it.
* **A simple threading model.** The UI runs on one event thread while work such as saving
  runs in the background, without lifetimes or `Send`/`Sync` bounds to design around. Save,
  for example, compresses large parts on several cores at once.
* **Whole-program optimization.** ParparVM translates the bytecode to C after aggressive tree
  shaking, so only code the app reaches ships. The C compiler then inlines and optimizes
  across what remains.
* **Lean platform layers.** Codename One draws its own UI on each platform's graphics API and
  does not carry the baggage of the OS widget toolkits.

## Can Rust reach Java/Codename One performance?

For size and speed, probably. But it would take a lot of work and deep rethinking of memory
and inlining strategies. That would likely mean far more code and even slower builds.

Rust is a fantastic tool for low-level programming. Its determinism is essential when building
something like a kernel module, and that is where it belongs. A kernel or system service
cannot trade determinism for throughput, but a user application can, whenever resources allow
it to deliver a better result. For this kind of work, Rust is simply not the right tool.

Matching Java here would mean reinventing what Java already provides, inside Rust. That gains
nothing: the Rust code would become even more complex, more verbose and slower to compile.

## Per-OS results

Every number here comes from one CI run on GitHub-hosted runners, with both apps built from
source, run on the same machine and given the same fixtures.

<!-- ci-tables:begin -->
Measured by CI run [39825464193](https://github.com/shai-almog/cn1-wordprocessor/actions/runs/39825464193) on commit `502edf9` (2026-10-22), GitHub-hosted runners, same runner and fixtures for both apps. Medians; startup is 10 fresh launches to the first painted document, latency is command to painted frame. Regenerate with `scripts/ci-readme-tables.py RUN_ID`.

### macOS

[arm64 GitHub macos15 runner, 3 CPUs](https://github.com/shai-almog/cn1-wordprocessor/actions/runs/39825464193/job/119987459213)

| Measure (lower is better) | Codename One | Wordcraft (Rust) | Better |
|---|---:|---:|---|
| Startup, 1 paragraph | 320 ms | 651 ms | CN1 |
| Startup, 1,000 paragraphs | 370 ms | 821 ms | CN1 |
| Startup, 10,000 paragraphs | 917 ms | 1670 ms | CN1 |
| Idle RAM, 1 paragraph | 96 MiB | 106 MiB | CN1 |
| Idle RAM, 1,000 paragraphs | 112 MiB | 145 MiB | CN1 |
| Idle RAM, 10,000 paragraphs | 220 MiB | 364 MiB | CN1 |
| Peak RAM, 1,000-paragraph workload | 230 MiB | 223 MiB | **Wordcraft** |
| Type a character to painted frame | 8.9 ms | 51.9 ms | CN1 |
| Undo to painted frame | 8.9 ms | 52.3 ms | CN1 |
| Paste 10,000 chars to painted frame | 45.4 ms | 76.6 ms | CN1 |
| Save DOCX to painted frame | 7.7 ms | 12.0 ms | CN1 |
| Reopen DOCX to painted frame | 31.6 ms | 132.0 ms | CN1 |
| Export PDF to painted frame | 36.4 ms | 565.3 ms | CN1 |
| Executable size | 34.7 MiB | 55.0 MiB | CN1 |

### Linux

[x86_64 GitHub ubuntu24 runner, 4 CPUs](https://github.com/shai-almog/cn1-wordprocessor/actions/runs/39825464193/job/119987459064)

| Measure (lower is better) | Codename One | Wordcraft (Rust) | Better |
|---|---:|---:|---|
| Startup, 1 paragraph | 170 ms | 166 ms | **Wordcraft** |
| Startup, 1,000 paragraphs | 231 ms | 246 ms | CN1 |
| Startup, 10,000 paragraphs | 838 ms | 973 ms | CN1 |
| Idle RAM, 1 paragraph | 94 MiB | 164 MiB | CN1 |
| Idle RAM, 1,000 paragraphs | 111 MiB | 187 MiB | CN1 |
| Idle RAM, 10,000 paragraphs | 198 MiB | 387 MiB | CN1 |
| Peak RAM, 1,000-paragraph workload | 190 MiB | 241 MiB | CN1 |
| Type a character to painted frame | 10.7 ms | 14.9 ms | CN1 |
| Undo to painted frame | 9.4 ms | 15.1 ms | CN1 |
| Paste 10,000 chars to painted frame | 18.0 ms | 27.0 ms | CN1 |
| Save DOCX to painted frame | 7.7 ms | 5.9 ms | **Wordcraft** |
| Reopen DOCX to painted frame | 25.9 ms | 83.5 ms | CN1 |
| Export PDF to painted frame | 35.4 ms | 563.8 ms | CN1 |
| Executable size | 37.7 MiB | 75.2 MiB | CN1 |

### Windows

[AMD64 GitHub win25-vs2026 runner, 4 CPUs](https://github.com/shai-almog/cn1-wordprocessor/actions/runs/39825464193/job/119987459831)

| Measure (lower is better) | Codename One | Wordcraft (Rust) | Better |
|---|---:|---:|---|
| Startup, 1 paragraph | 189 ms | 343 ms | CN1 |
| Startup, 1,000 paragraphs | 288 ms | 455 ms | CN1 |
| Startup, 10,000 paragraphs | 1203 ms | 1444 ms | CN1 |
| Idle RAM, 1 paragraph | 71 MiB | 82 MiB | CN1 |
| Idle RAM, 1,000 paragraphs | 90 MiB | 106 MiB | CN1 |
| Idle RAM, 10,000 paragraphs | 169 MiB | 308 MiB | CN1 |
| Peak RAM, 1,000-paragraph workload | 183 MiB | 165 MiB | **Wordcraft** |
| Type a character to painted frame | 12.4 ms | 24.9 ms | CN1 |
| Undo to painted frame | 10.5 ms | 25.0 ms | CN1 |
| Paste 10,000 chars to painted frame | 31.1 ms | 40.4 ms | CN1 |
| Save DOCX to painted frame | 7.4 ms | 10.3 ms | CN1 |
| Reopen DOCX to painted frame | 31.5 ms | 261.2 ms | CN1 |
| Export PDF to painted frame | 32.7 ms | 775.2 ms | CN1 |
| Executable size | 32.5 MiB | 57.5 MiB | CN1 |
<!-- ci-tables:end -->

Note on PDF export: our exporter covers less typography than Wordcraft's. It has no complex
shaping, kerning or system font fallback, no links and no tagged accessibility, so the export
time is not an equal-fidelity comparison. See [FORMATS](docs/FORMATS.md).

## Faster

![Startup time](docs/images/startup.svg)

![Command-to-paint latency](docs/images/latency.svg)

Latency is measured from the command to the first painted frame that shows its result,
with the 1,000-paragraph document (macOS charts; see the tables above for Linux and Windows).

## Smaller

![Resident memory](docs/images/memory.svg)

![Disk size](docs/images/size.svg)

## One codebase, every platform

The same Java source, unchanged, builds and runs out of the box on each target:

| iPhone | Android | Browser |
| --- | --- | --- |
| ![iPhone](docs/images/iphone.png) | ![Android](docs/images/android.png) | ![Browser](docs/images/web.png) |

On phones the ribbon collapses to a tab picker, the page reflows to the screen width and a
touch toolbar replaces the status bar. Wordcraft has no mobile target.

## More Features and Deeper Native

Features this app provides that Wordcraft does not (Wordcraft references are to its source at
the pinned revision):

| Feature | This app | Wordcraft |
| --- | --- | --- |
| **macOS menu bar** | Native NSMenu with File, Edit, Insert, Format and Tools menus, about 40 commands with standard shortcuts | No menu bar; commands live only in the in-window ribbon |
| **Rich copy to other apps** | Copy and cut put HTML, RTF, Markdown, plain text and the native model on the system pasteboard, so Pages, Mail or a browser receive formatted text | Plain text only (`ctx.copy_text`) |
| **Drag out to other apps** | Drag a selection into another app as rich text; drags inside the document move text as one undo step | No drag source |
| **Drag in** | Files and text, dropped at the caret under the pointer | Files only |
| **Rich paste in** | HTML, RTF, Markdown, images and files | HTML, RTF and images |
| **High contrast** | Follows the OS accessibility setting live | Not supported |
| **iPhone and iPad** | Native iOS app with touch selection handles and keyboard-aware dialogs | No mobile target |
| **Android** | Native Android app from the same source | No mobile target |

Both apps follow the OS dark mode live:

![Dark mode](docs/images/editor-dark.png)

The complete comparison, including features where the two are level, is in
[NATIVE-FEATURES](docs/NATIVE-FEATURES.md).

## Easier for LLMs

![Source size](docs/images/source.svg)

About two thirds of the code and three fifths of the tokens (tiktoken `o200k_base`) for the
same catalog: an agent reads the whole application in a fraction of the context, and every
edit touches less code.

## Cheaper CI

![CI cost per test](docs/images/ci.svg)

The two suites have different sizes, so the chart divides runner time by the number of tests.
Functional tests run on the JVM against the same Java code that ships natively, with no native
toolchain; Rust must compile its dependency tree and test binaries before running anything.

### Why does Wordcraft have more tests?

Wordcraft has 1,926 `#[test]` functions; this app now has over 1,100 JUnit tests (the CI chart
above counts the suite at its last metrics run). Counted at Wordcraft's pinned revision:

- **About a quarter test plumbing that egui leaves to the application.** `ui-egui` alone has
  371 tests (scroll viewports, popup placement, hover fades, window-state restore, combo-list
  virtualization, file browser, print spooling), plus `fonts`, `render`, `geom` and `xtask`
  (99 more: font fallback per script, rasterization, build tooling). Codename One supplies
  these as framework services, tested once in the framework instead of in every app.
- **The rest cover the same ground as ours**, including `.doc` import, WMF/EMF pictures,
  Zotero and equation editing, which this app now implements too. Part of the difference is
  style (153 are named round trips, which we check inside broader format tests), but Wordcraft
  still tests shared features more densely. Fewer tests is cheaper CI, not proof of equal
  coverage. Editing, shortcuts, IME composition, dark mode and the phone menu are covered by
  headless tests and by tests that drive the app inside the Codename One simulator.

## Proven in CI

[`.github/workflows/metrics.yml`](.github/workflows/metrics.yml) reproduces every number above
for both projects on GitHub's runners:

| Job | Runner | What it proves |
| --- | --- | --- |
| `java` | Ubuntu | Cold build and functional-test time, test count, JVM engine benchmark, source lines and tokens |
| `wordcraft` | Ubuntu | Wordcraft's cold build and `cargo test` time and test count |
| `desktop` | macOS, Ubuntu, Windows | Startup, idle and peak RAM, command-to-paint latency and executable size of both native apps, same fixtures |
| `ios` | macOS | The same source built for the iPhone simulator, launch-to-key-window time, app size and a screenshot |
| `android` | Ubuntu | The same source built as an APK, cold-start time on an emulator, APK size and a screenshot |
| `web` | Ubuntu | The browser build, run headless in Chrome, bundle size and a screenshot |

The job summary prints each comparison table; raw runs and screenshots are uploaded as
workflow artifacts. Local runs use the same scripts (`scripts/native-bench/`).

## How was this built?

I started with Codex because my Claude quota was low. It worked reasonably well, but it
constantly stopped to check in. Switching to Claude Opus 5.5 on low effort got it past the
finish line. Codename One's built-in MCP server let the agents drive and inspect the running
app, which kept the iterations quick.

## Build and run the native macOS application

Use JDK 17 for Maven (the sources use records, text blocks and switch expressions), Xcode,
and current Codename One `8.0-SNAPSHOT` artifacts. The
validated merged framework baseline is `008f9ece62526d807c2064887ad8f8641d6195a4` (PR #6019). The native
runtime must include the shared ZIP API. If using a local framework checkout, refresh its
ParparVM bundle when it is stale. Every framework and application Maven invocation must
use the same isolated repository via `-Dmaven.repo.local=...`; never install custom snapshots
in `~/.m2`. The scripts default to `.local/maven-repository` inside this project. To use a
unique external repository, set `WORDPROCESSOR_MAVEN_REPO` or save its absolute path in the
ignored `.local/maven-repo-path` file. Do not use a shared temporary repository name.

```sh
scripts/build-native.sh
scripts/run-desktop.sh
```

The script installs/tests the shared libraries, then uses the existing Codename One target:

```sh
source scripts/maven-env.sh
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B \
  -Dcodename1.platform=ios -Dcodename1.buildTarget=mac-source package
```

This generates `common/target/app-common-0.1.0-SNAPSHOT-mac-source/Main.xcodeproj`.
The script builds its Release configuration with Xcode into
`common/target/mac-native/Build/Products/Release/Main.app` and the run script opens
that bundle. The binary uses AppKit and Metal and needs no JVM. Signing is disabled for
this local development build.

## Distributables and releases

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs the tests, then builds every
platform from the same source and uploads each as a workflow artifact: macOS `.dmg`, iOS
simulator `.zip` and unsigned `.ipa`, Android `.apk`, web `.zip`, Linux x64/arm64 `.tar.gz` and
Windows x64/arm64 `.zip`. `scripts/dist.sh` packages them into `dist/`. Pushing a `v*` tag runs
[`release.yml`](.github/workflows/release.yml), which reuses that build and attaches every
package to a GitHub release. The macOS app is signed with Developer ID and notarized when the signing secrets are configured (otherwise ad-hoc signed); the iOS `.ipa` is unsigned.

The standard CN1 lifecycle is `common/src/main/java/com/codenameone/wordprocessor/app/Main.java`.
It starts the `WordProcessor` UI and uses the CN1 platform file APIs.

## Project layout

One standard Codename One project. All application code is in `common/src/main/java`, in a
single package:

| Area | Main classes |
| --- | --- |
| Model | `Document`, `RichText`, `Session` (selection, transactions, undo) |
| Layout | `LayoutEngine` (pagination, tables), shared by screen and PDF |
| Formats | `Docx`, `DocumentXml` (`.cn1doc`), `TextFormats`, `PdfExporter`, `OfficeCrypto` |
| Commands | `Commands` and its `*Commands` groups, used by the ribbon, macros and MCP |
| UI | `WordProcessor`, `EditorSurface`, `OfficeTheme`; lifecycle in `app/Main` |

`common/src/test/java` holds the JUnit tests and the JVM tools: the `Cli`/MCP server and the
benchmarks, which run on the JavaSE port. The root directories are the standard ones:

| Directory | Contents |
| --- | --- |
| `common/` | All Java code, resources and tests |
| `ios/`, `android/`, `javascript/`, `javase/`, `linux/`, `win/` | Each platform's native code (the Save dialog) |
| `scripts/` | Build, packaging, verification and benchmark scripts |
| `docs/` | Documentation, evidence, examples, framework patches and third-party notices |

```sh
scripts/build.sh
scripts/wordprocessor help
scripts/wordprocessor new document.cn1doc
scripts/wordprocessor mcp --root "$PWD"
scripts/verify.sh
```

CLI files must be new paths. Known lossy DOCX conversion requires `--allow-conversion`.
The CLI is currently JVM tooling; its timings are not native application startup timings.
See [formats](docs/FORMATS.md) and [automation](docs/AUTOMATION.md).


## Other targets

`common` is shared with Codename One's browser, Android and iOS targets.
`scripts/build-web.sh` builds the JavaScript target; `scripts/build-linux.sh` uses the local
native Linux target. See [Linux](docs/LINUX.md) and [platform evidence](docs/platforms.json).
Linux and Windows packages are built in CI only and have not been run on a desktop yet;
physical mobile devices remain unverified. Live native UI debugging uses the
optional [built-in MCP integration](docs/LIVE-MCP.md). A project license has not yet been
selected; see [third-party notices](docs/third-party/README.md).

Regenerate the charts with `python3 scripts/readme-charts.py` after
`scripts/native-bench/report.py` and `scripts/source-metrics.py`.
