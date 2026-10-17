# Native GUI benchmarks

Final local ribbon/native-menu build, 2026-10-13. Feature parity is incomplete; these measurements do not establish language or agentic-coding superiority.

## Startup and idle memory

Ten fresh processes per application/document, alternating launch order, warm filesystem cache. Each process stays open for five seconds after the first completed CPU document-paint marker. The marker includes loading, layout and issuing document draw commands, but excludes GPU completion and display presentation. Neither true cold-boot startup nor input-to-photon latency is measured.

| Paragraphs | App | CPU-ready median / p95 (ms) | Idle RSS min / median / max (MiB) | Highest kernel RSS peak (MiB) | Successful launches |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | cn1 | 104.52 / 115.58 | 95.61 / 100.17 / 100.56 | 100.72 | 10/10 |
| 1 | wordcraft | 299.75 / 467.14 | 172.67 / 176.62 / 177.77 | 191.62 | 10/10 |
| 1,000 | cn1 | 389.66 / 395.78 | 160.41 / 160.72 / 161.45 | 161.64 | 10/10 |
| 1,000 | wordcraft | 332.51 / 364.68 | 225.61 / 228.73 / 232.25 | 243.56 | 10/10 |
| 10,000 | cn1 | 1,036.40 / 1,046.17 | 177.62 / 180.81 / 183.80 | 184.47 | 10/10 |
| 10,000 | wordcraft | 522.58 / 543.26 | 321.95 / 323.48 / 324.28 | 338.56 | 10/10 |

RSS sampled every ~10 ms; idle samples start 0.5 seconds after the observed ready event. Kernel peak comes from macOS wait4/ru_maxrss and covers the whole process lifetime. The raw process minimum includes startup before the app is usable and is reported in JSON separately. All values are resident memory, not virtual address-space reservations.

## Editing and file operations

Three fresh process runs per app on the same 1,000-paragraph DOCX. Each performs 50 insert/undo pairs at the start, a 10,000-character paste/undo, five DOCX saves, five reopens and three PDF exports. Autosave is disabled in both instrumentation copies; normal production recovery is isolated. Each saved DOCX is CRC checked and its paragraph text compared with the input. Undo must restore the original text.

| Operation | CN1 operation median / p95 (ms) | Wordcraft operation median / p95 (ms) | CN1 command-to-CPU-paint median / p95 (ms) | Wordcraft command-to-CPU-paint median / p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| exportPdf | 21.05 / 26.18 | 341.63 / 350.10 | 22.52 / 27.69 | 342.01 / 350.51 |
| insert | 3.34 / 7.86 | 0.16 / 0.36 | 17.86 / 18.95 | 16.23 / 17.47 |
| paste10000 | 17.20 / 24.95 | 0.15 / 0.18 | 18.90 / 26.70 | 23.55 / 24.53 |
| reopenDocx | 258.89 / 268.05 | 9.74 / 16.18 | 260.41 / 269.67 | 66.99 / 78.74 |
| saveDocx | 9.93 / 14.48 | 11.72 / 16.21 | 15.78 / 17.76 | 12.53 / 17.16 |
| undo | 3.26 / 7.74 | 0.06 / 0.09 | 17.64 / 19.18 | 16.05 / 17.14 |
| undoPaste | 3.35 / 3.52 | 0.08 / 0.09 | 18.14 / 18.35 | 16.54 / 16.54 |

| Whole scripted process | CN1 | Wordcraft |
| --- | ---: | ---: |
| Maximum kernel RSS peak (MiB) | 399.84 | 291.08 |
| Median CPU seconds | 3.48 | 10.99 |
| Median elapsed seconds | 20.78 | 18.40 |

Whole-process CPU/wall includes the five-second idle period, operation pacing and two seconds after completion. CN1 uses a 25 ms timer; Wordcraft requests repaint checks at 20 ms during the scripted workload. These harness overheads differ, so CPU/wall are diagnostic rather than an isolated engine comparison. Operation timings use each app’s normal command/model path; command-to-paint includes that app’s resulting UI refresh.

DOCX validation checks plain text, not complete formatting equivalence. CN1 PDF currently has a much smaller typography/feature scope. Page counts and font metrics differ. Export timings therefore do not establish equal-fidelity throughput.

## Production size and source

| Metric | CN1 + Java | Wordcraft Rust |
| --- | ---: | ---: |
| Native executable bytes | 21,715,368 | 57,402,384 |
| Unsigned app bundle bytes | 26,628,653 | 57,515,260 |
| Application code lines (explicit tests pruned) | 6,150 | 118,659 |
| Application source tokens (o200k_base, explicit tests pruned) | 56,689 | 1,646,847 |

Sizes use uninstrumented ARM64 release executables. CN1 bundle includes resources and bundled OFL fonts. Wordcraft bundle uses its upstream packaging recipe without distribution signing/notarization or optional craft-fonts. These are logical byte totals, not compressed download or allocated disk sizes. Framework/dependency code, fonts and generated output are excluded from application LOC/tokens. Source counts are not a matched-feature comparison.

## Environment and evidence

Mac16,5, 16 logical CPUs, 64 GiB RAM, macOS-26.6.2-arm64-arm-64bit-Mach-O. Maven/ParparVM/Xcode Release versus Cargo release. Wordcraft revision `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`; CN1 framework `d519cd4e6f`.

macOS is the only platform on which the current native applications were exercised. CN1 Windows/Linux/Android/iOS/browser application behavior remains unverified; see [platform matrix](platforms.json).

Reproduce with `scripts/native-bench/README.md`. Raw per-process events, RSS samples, output files, fixture paths and hashes are under `artifacts/native-bench/final/`. The [summary](native-benchmarks.json) includes distributions and production executable hashes. Instrumentation is confined to disposable copies; application source remains uninstrumented.
