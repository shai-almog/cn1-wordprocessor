# CN1 Word Processor

A standalone Java word processor and reusable document libraries built with Codename One. The working desktop application has paginated editing, basic DOCX interchange, recovery, a command API, transactional macros, and a desktop CLI/MCP server.

**Development implementation: the Wordcraft-parity plan is not complete.** This is not a Microsoft Word replacement yet, and there is no established performance or feature-count win. See [STATUS](docs/STATUS.md) for the remaining release gates and [validation](docs/VALIDATION.md) for what actually ran.

## Run

Prerequisites: JDK 8, Maven, and current Codename One `8.0-SNAPSHOT` artifacts. The implementation was built against a local Codename One checkout at `d519cd4e6fb061bb120877acb36aa3f4c87aa971`; snapshot artifacts can change. It uses the current `TextInputClient`, editor-host bridge, file download API, and text-format adapters. An older published CN1 version may not contain them.

```sh
# On macOS the scripts select an installed JDK 8 when JAVA_HOME is unset.
# On other systems, set JAVA_HOME to a JDK 8 installation first.
scripts/build.sh
scripts/run-desktop.sh
```

The build installs the library modules into the local Maven repository and resolves the runtime classpath. `scripts/build.sh -o` uses cached dependencies. `scripts/build.sh clean` does a clean build. The desktop launcher needs a graphical session. Recovery lives in the platform app-home directory `.cn1-wordprocessor`; it does not replace explicit file export.

```sh
scripts/wordprocessor help
scripts/wordprocessor new example.cn1doc
scripts/wordprocessor run example.cn1doc examples/welcome-macro.json welcome.docx \
  --params '{"recipient":"Ada"}'
scripts/wordprocessor convert welcome.docx welcome.pdf
scripts/wordprocessor mcp --root "$PWD"
```

For a local desktop development ZIP, run `python3 scripts/package-desktop.py` after building. It includes resolved libraries and launch scripts; JDK 8 must be installed separately.

CLI output files must be new paths. DOCX conversion that would lose unsupported features needs an explicit `--allow-conversion`. PDF export currently supports WinAnsi characters with standard Helvetica faces; unsupported text produces an error.

## Platforms

- **Desktop:** `desktop` hosts the shared CN1 UI in a JavaSE window. Tested locally on macOS. Windows/Linux execution and distributable packages remain unverified.
- **Browser:** run `scripts/build-web.sh`, then `scripts/serve-web.sh`, and open `http://127.0.0.1:8768/`. The generated JavaScript bundle compiles; live browser interaction is still unverified. Serve the static bundle, not the generated servlet WAR.
- **Android/iOS:** `app/common` contains the shared lifecycle and native file adapters, with CN1 Maven platform profiles. Device packaging, signing, keyboard behavior, and testing remain outstanding. No cloud builds have been submitted.

## Libraries

| Module | Responsibility |
| --- | --- |
| `document` | Immutable styled text and block trees, selection, transactions, undo/redo, composition |
| `layout` | Paragraph measurement cache, pagination, table rows, platform metrics interface |
| `formats` | Native project/XML, bounded DOCX reader/writer, text adapters, PDF writer |
| `automation` | Command schemas, compiled Java extensions, macro preview/apply, writing/template examples |
| `editor` | Shared CN1 editor canvas, application UI, checksummed recovery |
| `desktop` | JavaSE host, file adapters, headless CLI, stdio MCP |
| `app` | Generated CN1 platform wrapper and shared lifecycle |
| `benchmarks` | Synthetic model/layout measurements and pinned Wordcraft feature inventory |

See the [comparison scorecard and methodology](docs/COMPARISON.md), [architecture](docs/ARCHITECTURE.md), [formats](docs/FORMATS.md), and [automation](docs/AUTOMATION.md).

## Verify and compare

```sh
scripts/verify.sh
scripts/ui-smoke.sh
scripts/benchmark.sh 1000 30 artifacts/1000-paragraphs.docx
scripts/compare-loc.sh /path/to/wordcraft
python3 scripts/compare-cli.py /path/to/wordcraft/target/release/wordcraft-cli \
  artifacts/1000-paragraphs.docx --iterations 10 --out artifacts/cli-comparison.json
```

Create `artifacts/` before writing into it. The UI smoke invokes editor callbacks in the running CN1 application and captures its render; it is not physical keyboard, native IME, or device validation. The model/layout benchmark uses synthetic metrics, so its timings cannot be compared to Wordcraft's font-shaped layout numbers. The CLI comparison measures complete process startup plus DOCX text extraction with identical output; it does not measure typing or rendering. Line counts currently cover different feature scopes.

The 502-entry [baseline](benchmarks/baseline.json) records Wordcraft catalog metadata at a pinned revision. Entries stay pending until equivalent workflows have evidence; catalog presence alone does not prove either implementation works. No remote repository or release has been published. A project license has not been selected; third-party notices remain in [third-party](third-party/README.md).

## Source tokens and platform evidence

Measurement tooling requires Python 3.11+, `cloc`, and the pinned packages below. The optional warm-import adapter also requires the Rust toolchain used to build Wordcraft.

```sh
python3 -m venv artifacts/metrics-venv
artifacts/metrics-venv/bin/pip install -r scripts/metrics-requirements.txt
artifacts/metrics-venv/bin/python scripts/test-source-metrics.py
artifacts/metrics-venv/bin/python scripts/source-metrics.py /path/to/wordcraft \
  --out artifacts/source-metrics.json
python3 scripts/parity-probes.py /path/to/wordcraft/target/release/wordcraft-cli \
  --out artifacts/parity-probes
python3 scripts/compare-warm-import.py /path/to/wordcraft artifacts/1000-paragraphs.docx \
  --out artifacts/warm-import
# Copy artifacts/warm-import/results.json to artifacts/warm-import.json for the scorecard.
python3 scripts/dependency-inventory.py /path/to/wordcraft --out artifacts/dependency-inventory.json
python3 scripts/capture-baseline.py /path/to/wordcraft --out artifacts/new-baseline.json
python3 scripts/comparison-report.py --artifacts artifacts --out docs/COMPARISON.md
```

Source tokens use pinned `tiktoken` and `o200k_base`, including comments and whitespace with each file tokenized separately. The report records hashes and paths for every counted file, module totals, separate test/build/benchmark buckets, and a conservative Rust estimate with explicit test items removed. It does not count agent usage. See [platform evidence](docs/platforms.json) for declared, built and exercised states.
