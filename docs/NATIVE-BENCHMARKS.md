# Native GUI benchmarks

> Historical measurement snapshot: the title and ribbon are being revised. These numbers do not measure the current UI or demonstrate feature parity. Benchmarking is paused pending UI and workflow acceptance.

Current local feature-batch build, 2026-10-13. Feature parity is incomplete; these measurements do not establish language or agentic-coding superiority.

## Startup and idle memory

Ten fresh processes per application/document, alternating launch order, warm filesystem cache. Each process stays open for five seconds after the first completed CPU document-paint marker. The marker includes loading, layout and issuing document draw commands, but excludes GPU completion and display presentation. Neither true cold-boot startup nor input-to-photon latency is measured.

| Paragraphs | App | CPU-ready median / p95 (ms) | Idle RSS min / median / max (MiB) | Highest kernel RSS peak (MiB) | Successful launches |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | cn1 | 132.75 / 145.80 | 99.59 / 100.16 / 101.27 | 101.42 | 10/10 |
| 1 | wordcraft | 346.06 / 394.96 | 174.05 / 175.34 / 177.44 | 191.58 | 10/10 |
| 1,000 | cn1 | 162.38 / 252.45 | 130.12 / 130.69 / 131.59 | 131.62 | 10/10 |
| 1,000 | wordcraft | 395.69 / 433.44 | 224.50 / 225.62 / 228.95 | 243.25 | 10/10 |
| 10,000 | cn1 | 237.63 / 275.72 | 153.64 / 154.34 / 154.94 | 155.50 | 10/10 |
| 10,000 | wordcraft | 521.30 / 566.59 | 321.23 / 323.09 / 324.86 | 339.08 | 10/10 |

RSS sampled every ~10 ms; idle samples start 0.5 seconds after the observed ready event. Kernel peak comes from macOS wait4/ru_maxrss and covers the whole process lifetime. The raw process minimum includes startup before the app is usable and is reported in JSON separately. All values are resident memory, not virtual address-space reservations.

## Editing and file operations

Three fresh process runs per app on the same 1,000-paragraph DOCX. Each performs 50 insert/undo pairs at the start, a 10,000-character paste/undo, five DOCX saves, five reopens and three PDF exports. Autosave is disabled in both instrumentation copies; normal production recovery is isolated. Each saved DOCX is CRC checked and its paragraph text compared with the input. Undo must restore the original text.

| Operation | CN1 operation median / p95 (ms) | Wordcraft operation median / p95 (ms) | CN1 command-to-CPU-paint median / p95 (ms) | Wordcraft command-to-CPU-paint median / p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| exportPdf | 14.51 / 16.32 | 253.73 / 259.04 | 15.88 / 17.70 | 254.09 / 259.39 |
| insert | 2.16 / 4.34 | 0.10 / 0.22 | 17.44 / 18.89 | 12.29 / 13.69 |
| paste10000 | 1.90 / 3.21 | 0.10 / 0.12 | 17.45 / 18.94 | 18.40 / 18.76 |
| reopenDocx | 43.09 / 64.89 | 7.01 / 12.28 | 44.48 / 66.30 | 49.43 / 60.79 |
| saveDocx | 6.06 / 16.20 | 8.13 / 12.21 | 16.76 / 18.18 | 8.60 / 12.67 |
| undo | 2.38 / 4.22 | 0.04 / 0.05 | 17.37 / 19.18 | 12.09 / 13.17 |
| undoPaste | 0.90 / 2.47 | 0.03 / 0.05 | 16.48 / 18.57 | 12.06 / 12.29 |

| Whole scripted process | CN1 | Wordcraft |
| --- | ---: | ---: |
| Maximum kernel RSS peak (MiB) | 275.97 | 291.03 |
| Median CPU seconds | 1.28 | 9.52 |
| Median elapsed seconds | 19.32 | 17.07 |

Whole-process CPU/wall includes the five-second idle period, operation pacing and two seconds after completion. CN1 uses a 25 ms timer; Wordcraft requests repaint checks at 20 ms during the scripted workload. These harness overheads differ, so CPU/wall are diagnostic rather than an isolated engine comparison. Operation timings use each app’s normal command/model path; command-to-paint includes that app’s resulting UI refresh.

DOCX validation checks plain text, not complete formatting equivalence. CN1 PDF currently has a much smaller typography/feature scope. Page counts and font metrics differ. Export timings therefore do not establish equal-fidelity throughput.

## Production size and source

| Metric | CN1 + Java | Wordcraft Rust |
| --- | ---: | ---: |
| Native executable bytes | 22,229,912 | 57,402,384 |
| Unsigned app bundle bytes | 27,143,197 | 57,515,260 |
| Application code lines (explicit tests pruned) | 7,837 | 118,659 |
| Application source tokens (o200k_base, explicit tests pruned) | 72,052 | 1,646,847 |

Sizes use uninstrumented ARM64 release executables. CN1 bundle includes resources and bundled OFL fonts. Wordcraft bundle uses its upstream packaging recipe without distribution signing/notarization or optional craft-fonts. These are logical byte totals, not compressed download or allocated disk sizes. Framework/dependency code, fonts and generated output are excluded from application LOC/tokens. Source counts are not a matched-feature comparison.

## Environment and evidence

Mac16,5, 16 logical CPUs, 64 GiB RAM, macOS-26.6.2-arm64-arm-64bit-Mach-O. Maven/ParparVM/Xcode Release versus Cargo release. Wordcraft revision `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`; CN1 framework `d519cd4e6f`.

macOS is the only platform on which the current native applications were exercised. CN1 Windows/Linux/Android/iOS/browser application behavior remains unverified; see [platform matrix](platforms.json).

Reproduce with `scripts/native-bench/README.md`. Raw per-process events, RSS samples, output files, fixture paths and hashes are under `artifacts/native-bench/feature-final/`. The [summary](native-benchmarks.json) includes distributions and production executable hashes. Instrumentation is confined to disposable copies; application source remains uninstrumented.
