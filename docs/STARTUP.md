# Startup diagnosis and native CLI measurements

The earlier 135.03 ms number measured a fresh CLI process opening a 1,000-paragraph DOCX,
extracting its text, writing stdout and exiting. It did **not** measure GUI readiness.
The pinned Wordcraft release CLI took 8.78 ms in that original run.

## Why the gap existed

Wordcraft's CLI is an ahead-of-time compiled native Rust executable. Its release profile uses
optimization level 3 and thin LTO. The `text` command opens its document/session and extracts
body text without starting its GUI. The first Java implementation instead launched a JVM,
searched a GUI-heavy classpath, and constructed the entire command registry before handling
`text`. It also constructed an editing session that text extraction never used.

Before changing the code, 30 fresh-process samples per arm on this host gave these medians:

| Diagnostic | Median |
| --- | ---: |
| Empty Java 8 main | 35.75 ms |
| Empty Java 21 main | 31.53 ms |
| Java 8 CLI help, direct main | 39.08 ms |
| Java 8 extraction, direct main | 121.97 ms |
| Java 21 extraction, direct main | 79.88 ms |
| Java 8 extraction through original shell launcher | 131.68 ms |
| Wordcraft extraction | 8.61 ms |

[Diagnostic samples](startup-before.json) identify the pre-change commit `8794bd4`.
Each diagnostic discards one first invocation and uses warm filesystem caches. These are
separate process measurements; subtracting them is only an approximate attribution, not a
CPU profile. An empty JVM already exceeded the entire Rust operation. A newer JVM reduced
the gap but did not close it.

## Changes

- Compile the same complete CLI locally with GraalVM Native Image, `-O3`, generic armv8-a
  on this host, normal serial GC. Three immutable application classes initialize at build
  time. There is no daemon, cached output, benchmark-specific parser, native C/Rust rewrite,
  profile-guided training, or host-specific CPU flag.
- Initialize command descriptors only for commands that need them; extract text directly
  from the parsed document. Restrict the CLI classpath to the app libraries and CN1 core.
- Avoid redundant XML attribute sets, avoid copying text without entities, and use an ASCII
  name-scanning fast path with the existing Unicode fallback and validation. Construct a
  DOCX run style once instead of rebuilding and stringify/parsing it for every property.
- Use portable Helvetica advances for CLI PDF output. The same format features remain
  available in the native binary, including PDF, macros and MCP. CLI PDF pagination now
  matches its emitted font metrics; it can differ from the old AWT-based output.

## Final matched comparison

Every cell below is **median / p95 milliseconds**, from 100 timed fresh processes per arm,
five discarded warmups, rotating/reversed execution order and byte-identical extracted
output on every invocation. The original formatted fixture is the 1,000-paragraph row;
the other two are separate, simple paragraph-only fixtures, not a linear scaling series.

| Fixture | Native Java executable | Wordcraft Rust executable | Java development shell launcher | JVM fallback |
| --- | ---: | ---: | ---: | ---: |
| 1-paragraphs | 5.98 / 7.03 | 4.63 / 5.09 | 10.20 / 11.30 | 73.84 / 78.65 |
| 1000-paragraphs | 12.25 / 13.14 | 12.17 / 12.92 | 16.15 / 17.09 | 118.59 / 123.38 |
| 10000-paragraphs | 18.90 / 20.72 | 22.14 / 23.26 | 22.73 / 24.47 | 130.19 / 135.41 |

The original workload is within 0.7% at the median and within 5% at p95, meeting
the project's 5% threshold **for this operation and fixture**. Native Java is faster on
the larger fixture. Wordcraft remains faster on the one-paragraph fixture, where runtime
startup dominates. No overall performance gate or GUI-startup win is claimed.

These final samples were taken on macOS arm64 (Mac16,5, 16 CPUs, 64 GiB RAM), Java 8
Azul 1.8.0_372 for the fallback and Oracle GraalVM 21.0.11 for native compilation.
Other applications were active: system load averages went from
`[11.099609375, 8.79736328125, 7.23583984375]` to `[8.0078125, 8.2587890625, 7.1142578125]`. No task build/profiler ran during
timing. Earlier, less-loaded iterations were around 9–10 ms for both native executables;
absolute values vary with system load. Do not subtract the historical 135 ms result from
the final timings as a controlled before/after experiment. The simultaneous four-arm
comparison is the current evidence.

[Raw samples, toolchains, fixture/output hashes and binary hashes](startup-comparison.json)
are retained. Native size is 25,055,288 bytes; this is a CLI artifact,
not the JavaSE GUI distribution. Both applications' full feature scopes remain different.

## Validation and use

51 Java tests pass. The final native executable passes 18 process-level checks against
its JVM build, including all command families, supported format adapters, byte-identical
PDF export, macro preview/apply, invalid inputs, overwrite protection and MCP boundaries.
All four common Wordcraft CLI behavior probes pass with the native binary. A generated
PDF was independently text-extracted and rendered with Poppler.

- [Runtime validation](native-validation.json)
- [Native shared behavior probes](native-parity-probes.json)
- [Current source LOC/token manifest](source-metrics.json), including PDF width tables

```sh
# JAVA_HOME must select JDK 8 for Maven; GRAALVM_HOME selects the native compiler.
GRAALVM_HOME=/path/to/graalvm scripts/build-native.sh
# Invoke the production native artifact directly, avoiding shell startup.
desktop/target/native/wordprocessor text document.docx
python3 scripts/benchmark-startup.py /path/to/wordcraft-cli \
  artifacts/1000-paragraphs.docx --iterations 100 --out artifacts/startup-final/results.json
```

The development wrapper `scripts/wordprocessor` chooses native automatically when built,
or accepts `WORDPROCESSOR_RUNTIME=jvm` to force the JVM. Its separate shell overhead is
reported above. `scripts/build.sh` invalidates older native executables. Rebuild native
after source changes. Native builds/tests are strictly local; no CI jobs were added or run.
The GUI still uses JavaSE; Windows/Linux native execution and GUI startup remain unmeasured.
