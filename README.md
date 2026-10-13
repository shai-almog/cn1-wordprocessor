# CN1 Word Processor

A Codename One application with reusable Java document, layout, format and automation
libraries. The desktop application uses **ParparVM and the native AppKit/Metal port**.
There is no custom Swing host or GraalVM application build.

This is an initial editor implementation. Full Wordcraft feature parity remains unfinished;
see [STATUS](docs/STATUS.md). Earlier GraalVM CLI results are withdrawn as evidence for
this application's native performance.

## Build and run the native macOS application

Use JDK 8 for Maven, Xcode, and current Codename One `8.0-SNAPSHOT` artifacts. The native
runtime must include the shared ZIP API. If using a local framework checkout, refresh its
ParparVM bundle with `mvn -f maven/parparvm/pom.xml install -DskipTests` when it is stale.

```sh
scripts/build-native.sh
scripts/run-desktop.sh
```

The script installs/tests the shared libraries, then uses the existing Codename One target:

```sh
mvn -B -f app/pom.xml -Dcodename1.platform=ios -Dcodename1.buildTarget=mac-source package
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

## Other targets and measurements

`app/common` is shared with Codename One's browser, Android and iOS targets.
`scripts/build-web.sh` builds the JavaScript target. Mobile device builds and Windows/Linux
native execution remain unverified. No cloud builds or public release have been submitted.

[COMPARISON](docs/COMPARISON.md) records source LOC/tokens and the limitations of existing
measurements. Source tokens use pinned `tiktoken` / `o200k_base`, not agent usage.

```sh
python3 -m venv artifacts/metrics-venv
artifacts/metrics-venv/bin/pip install -r scripts/metrics-requirements.txt
artifacts/metrics-venv/bin/python scripts/source-metrics.py /path/to/wordcraft --out artifacts/source-metrics.json
scripts/benchmark.sh 1000 30 artifacts/1000-paragraphs.docx
python3 scripts/parity-probes.py /path/to/wordcraft-cli --out artifacts/parity-probes-new
```

The [502-entry catalog](benchmarks/baseline.json) is pending verification. Fewer application
lines alone does not establish parity or a language advantage. A project license has not
yet been selected; see [third-party notices](third-party/README.md).
