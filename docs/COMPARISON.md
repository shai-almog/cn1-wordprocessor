# Comparison scorecard

**The app is smaller, but the implementations are not feature-equivalent. No language, agent-coding, platform or overall performance superiority is established.** This report measures the resulting source code, not tokens spent by an agent.

## Source footprint

| Metric | Java + Codename One | Wordcraft Rust |
| --- | ---: | ---: |
| Application files | 33 | 281 |
| Application LOC, before inline-test pruning | 5,085 | 138,785 |
| Application LOC, explicit tests pruned (estimate) | 5,085 | 118,659 |
| Application source tokens, before inline-test pruning | 46,550 | 1,980,112 |
| Application source tokens, explicit tests pruned (estimate) | 46,550 | 1,646,847 |
| Separate test source tokens | 8,976 | 410,496 |

Counts use `cloc` and pinned `tiktoken` `o200k_base`. Source tokens include comments/whitespace, tokenize each file separately, and exclude filename/chat framing. Frameworks, dependencies, assets and generated output are excluded for both sides. Java includes the document libraries, command-line tools and shared platform lifecycle. Tests, benchmarks, examples and build tooling have separate buckets in [the complete file/hash manifest](source-metrics.json).

The Rust estimate removes 266 explicit inline test items and identifies external test modules. The parser cannot fully parse 7 source files; those remain unpruned and are listed in the manifest. This estimate does not evaluate all feature configurations/macros. Separate test-source tokens above do not include tokens removed from inline test items.

The CN1 implementation delegates platform UI/input/storage and several text-format adapters to its framework. Wordcraft uses its own Rust dependency stack. That reuse is part of the proposed stack comparison and must be disclosed; these numbers do not count all code needed to execute either application. [Dependency hashes and artifact sizes](dependency-inventory.json) are recorded separately. The application uses ParparVM. The separate JVM CLI and Wordcraft CLI have different scopes from the native GUI and are not comparable application-size benchmarks.

## Equivalent behavior probes

| Probe | CN1 | Wordcraft |
| --- | --- | --- |
| paragraph_text | Pass | Pass |
| bold_selection | Pass | Pass |
| replace_all | Pass | Pass |
| table_grid | Pass | Pass |

These probes inspect CLI-generated DOCX paragraphs, direct bold formatting, replacement text and table dimensions. They do not cover GUI interaction, shaped rendering or all edge cases, and certify zero full catalog rows. The broader [502-row catalog](../benchmarks/baseline.json) remains pending. [Probe artifact hashes](parity-probes.json) make the exercised outputs identifiable.

## Performance measured so far

Initial JVM baseline: complete fresh-process DOCX text extraction on the same 1,000-paragraph fixture, with 20 timed invocations per app and byte-identical output:

| Metric | CN1 Java CLI | Wordcraft Rust CLI |
| --- | ---: | ---: |
| Median | 135.03 ms | 8.78 ms |
| p95 | 148.02 ms | 11.86 ms |

The process boundary includes JVM/runtime startup and output capture, with warm filesystem caches after one discarded invocation. It measures neither typing nor rendering. Wordcraft was faster in this initial JVM CLI test. These historical JVM measurements do not measure the current ParparVM GUI. Raw samples, environment and document/output hashes are in [the timing record](cli-comparison.json). Runs were collected after local build/UI work finished.


Warmed in-memory DOCX parsing plus text extraction on that same fixture (three alternating process rounds, 50 warmups and 100 measured operations per process):

| Metric | CN1 Java reader | Wordcraft Rust reader |
| --- | ---: | ---: |
| Median | 2.49 ms | 4.37 ms |
| p95 | 3.90 ms | 4.56 ms |

CN1 is faster for this simple, warmed import workload. Both outputs are byte-identical. Startup, disk I/O, layout, rendering and memory measurement are excluded; normal GC/reference destruction applies. The readers support different broader format scopes, so this result does not establish full DOCX performance/fidelity parity. [Raw warmed samples and linked-library hashes](warm-import.json).

The [CN1 model/layout timings](model-layout.json) separately exercise warmed edits with synthetic metrics. They cannot be compared to font-shaped Wordcraft layout timings and do not measure input-to-present latency or peak memory. The overall performance gate is not evaluated.

## Platforms

| Platform | CN1 application evidence | Wordcraft evidence inspected |
| --- | --- | --- |
| macOS | ParparVM AppKit/Metal application; see current native validation | Release CLI built and exercised; GUI untested here |
| Windows | CN1 native target; application execution untested | Declared target and workflow source; execution untested here |
| Linux | CN1 native target; application execution untested | Declared target and workflow source; execution untested here |
| BSD | No app support claimed | Declared target and FreeBSD workflow; execution untested here |
| Browser | JavaScript build passed; live interaction unverified | WebAssembly target exists; not built/tested here |
| Android | CN1 profile/lifecycle present; no device build/test | No advertised app target in inspected README/workspace |
| iOS | CN1 profile/lifecycle present; no device build/test | No advertised app target in inspected README/workspace |

This is not a 6-versus-5 platform-support victory: framework reach, target declaration, compilation and working application behavior are different evidence levels. The [machine-readable platform matrix](platforms.json) records source references. Upstream workflow presence does not verify that a workflow passed, and these local checks are asymmetrical.

## Reproduce and extend

See the root README for the source-metrics, parity-probe, dependency-inventory and CLI comparison commands. The Wordcraft baseline is pinned to `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. Local Java source is identified by per-file hashes in the manifest. Refresh the catalog before any release comparison.

The [experiment protocol](EXPERIMENT.md) defines how to test the narrower, defensible claim: fewer source tokens at equivalent verified behavior and platform coverage, within agreed latency/memory gates. Full application parity and the controlled agent-task experiment are still unfinished.
