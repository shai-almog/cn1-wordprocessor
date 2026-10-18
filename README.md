# Java Beats Rust on Every Front

A Word-compatible word processor written in **Java** with [Codename One](https://www.codenameone.com),
compiled ahead of time by ParparVM to a native AppKit/Metal macOS app (no JVM at run time).
It is measured head to head against [Wordcraft](https://github.com/storytold/wordcraft)
(pinned at `159b84f`), a Rust/egui clone of the same 502-row Word feature catalog.

![The editor in light and dark mode](docs/images/editor-light.png)

| Front | Java (this app) | Rust (Wordcraft) | Evidence |
| --- | --- | --- | --- |
| **Faster startup** | 79 / 144 / 217 ms | 279 / 290 / 481 ms | 1 / 1,000 / 10,000 paragraphs, launch to first painted document |
| **Faster editing** | 3.4 ms insert, 5.6 ms save, 7.8 ms PDF | 12.0 ms insert, 9.6 ms save, 255 ms PDF | [native benchmarks](docs/NATIVE-BENCHMARKS.md) |
| **Less RAM** | 137 / 163 / 197 MiB | 175 / 227 / 323 MiB | idle RSS after load, spelling dictionary included |
| **Smaller on disk** | 33 MB app bundle | 57 MB app bundle | Release builds, unsigned |
| **More native** | macOS menu bar, rich copy out, drag out, high contrast, iOS and Android | in-window ribbon, plain-text copy, no drag out, desktop only | [native features](docs/NATIVE-FEATURES.md) |
| **Equally functional** | 487 / 487 implementable catalog rows certified | 487 rows implemented, 15 stubs | [status](docs/STATUS.md) |
| **Easier for LLMs** | 43,644 lines, 471,703 tokens | 118,659 lines, 1,646,847 tokens | application code, no tests, no comments ([metrics](docs/source-metrics.json)) |
| **Cheaper CI** | 8 s cold build + 623 tests | 185 s cold build + 1,921 tests | [CI metrics](docs/ci-metrics.json) |

## Faster

![Startup time](docs/images/startup.svg)

![Command-to-paint latency](docs/images/latency.svg)

Latency is measured from the injected command to the first painted frame that shows its
result, with the 1,000-paragraph document, three launches per side. Java wins every
operation: insert and undo about 3.5x, paste 2.4x, save 1.7x, reopen 1.2x and PDF export
about 33x (our PDF exporter covers less typography than Wordcraft's; see caveats).

## Smaller

![Resident memory](docs/images/memory.svg)

![Disk size](docs/images/size.svg)

## More native, equally functional

![Native look and dark mode](docs/images/editor-dark.png)

Both apps implement the same catalog. Where they differ, the Java app integrates with the
OS more deeply: a real NSMenu menu bar, copy in five rich formats to other apps, dragging
selections out, live high-contrast and dark mode, and the same source building for iPhone,
iPad and Android. Wordcraft is ahead on screen readers (its AccessKit tree is verified;
ours is not yet tested with VoiceOver) and on PDF typography. The full table, with source
references for every Wordcraft claim, is in [NATIVE-FEATURES](docs/NATIVE-FEATURES.md).

## Easier for LLMs and cheaper CI

![Source size](docs/images/source.svg)

Less than half the code and under a third of the tokens (tiktoken `o200k_base`) for the
same catalog: an agent reads the whole application in a fraction of the context.

![CI cost](docs/images/ci.svg)

Java compiles and runs its whole test suite in seconds. Rust must first compile its
dependency tree from source, so every cold CI run costs minutes. The non-native jobs in
[`.github/workflows/metrics.yml`](.github/workflows/metrics.yml) reproduce the source and
CI numbers.

## How the numbers were measured, and what they do not show

- **Native numbers are local.** Startup, latency, RAM and size come from Release builds on one
  Apple Silicon Mac (`scripts/native-bench/`, raw runs under `artifacts/`). Native CI jobs are
  deliberately not run; CI reproduces only the source metrics, JVM tests and Wordcraft's
  `cargo test`.
- **The machine was busy.** Load averages reached 15-36 during the final runs; both apps ran
  interleaved under the same load, but absolute numbers are noisy.
- **Discarded launches.** Codename One does not paint a hidden or fully covered window, so
  some launches never reached first paint (7 at 1 paragraph, 8 at 1,000, none at 10,000) and
  were retried rather than counted. They are kept under `failed/` in the evidence.
- **PDF scope.** Our PDF export is much faster but covers less typography than Wordcraft's;
  the timing is not an equal-fidelity comparison ([FORMATS](docs/FORMATS.md)).
- **Certification is not byte equality.** Catalog rows are certified against this app's own
  JVM behavior and visible effects, not byte-compared with Wordcraft output.
- **Test counts differ.** Wordcraft runs more, finer-grained tests; the CI comparison is
  total wall time, not time per test.

## Build and run the native macOS application

Use JDK 8 for Maven, Xcode, and current Codename One `8.0-SNAPSHOT` artifacts. The
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
mvn -Dmaven.repo.local="$WORDPROCESSOR_MAVEN_REPO" -B -f app/pom.xml \
  -Dcodename1.platform=ios -Dcodename1.buildTarget=mac-source package
```

This generates `app/common/target/app-common-0.1.0-SNAPSHOT-mac-source/Main.xcodeproj`.
The script builds its Release configuration with Xcode into
`app/common/target/mac-native/Build/Products/Release/Main.app` and the run script opens
that bundle. The binary uses AppKit and Metal and needs no JVM. Signing is disabled for
this local development build; native builds/tests are local only, never CI.

The standard CN1 lifecycle is `app/common/src/main/java/com/codenameone/wordprocessor/app/Main.java`.
It uses the shared `editor` application and CN1 platform file APIs.

## Libraries and tools

| Module | Responsibility |
| --- | --- |
| document | Styled text/block trees, selection, transactions, undo and composition |
| layout | Pagination, table layout and platform metrics interface |
| formats | Native project, DOCX, text adapters and PDF export |
| automation | Commands, macros, extensions and previews |
| editor | Codename One editor UI and recovery |
| app/common | Standard Codename One lifecycle and platform integration |
| cli | Separate JVM command-line and MCP development tools; no UI host |
| benchmarks | Model/import measurements and pinned Wordcraft inventory |

```sh
scripts/build.sh
scripts/wordprocessor help
scripts/wordprocessor new document.cn1doc
scripts/wordprocessor mcp --root "$PWD"
scripts/verify.sh
```

CLI files must be new paths. Known lossy DOCX conversion requires `--allow-conversion`.
The CLI is currently JVM tooling; its timings are not native application startup timings.
See [formats](docs/FORMATS.md), [automation](docs/AUTOMATION.md), and
[validation](docs/VALIDATION.md).


## Other targets

`app/common` is shared with Codename One's browser, Android and iOS targets.
`scripts/build-web.sh` builds the JavaScript target; `scripts/build-linux.sh` uses the local
native Linux target. See [Linux](docs/LINUX.md) and [platform evidence](docs/platforms.json).
Windows and physical mobile devices remain unverified. Live native UI debugging uses the
optional [built-in MCP integration](docs/LIVE-MCP.md). A project license has not yet been
selected; see [third-party notices](third-party/README.md).

Regenerate the charts with `python3 scripts/readme-charts.py` after
`scripts/native-bench/report.py` and `scripts/source-metrics.py`.
