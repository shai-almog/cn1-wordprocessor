# Native GUI benchmarks

Current local feature-batch build, 2026-10-13. Feature parity is incomplete; these measurements do not establish language or agentic-coding superiority.

## Startup and idle memory

Ten fresh processes per application/document, alternating launch order, warm filesystem cache. Each process stays open for five seconds after the first completed CPU document-paint marker. The marker includes loading, layout and issuing document draw commands, but excludes GPU completion and display presentation. Neither true cold-boot startup nor input-to-photon latency is measured.

| Paragraphs | App | CPU-ready median / p95 (ms) | Idle RSS min / median / max (MiB) | Highest kernel RSS peak (MiB) | Successful launches |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | cn1 | 78.74 / 258.55 | 119.78 / 136.78 / 144.94 | 144.94 | 10/10 |
| 1 | wordcraft | 279.37 / 558.94 | 124.31 / 175.48 / 186.83 | 196.70 | 10/10 |
| 1,000 | cn1 | 144.08 / 174.62 | 151.98 / 162.92 / 166.08 | 166.08 | 10/10 |
| 1,000 | wordcraft | 290.04 / 349.39 | 165.92 / 227.25 / 235.16 | 246.16 | 10/10 |
| 10,000 | cn1 | 217.36 / 365.02 | 137.02 / 196.66 / 209.08 | 210.59 | 10/10 |
| 10,000 | wordcraft | 480.76 / 675.71 | 153.05 / 322.89 / 332.17 | 343.12 | 10/10 |

Launches are collected one at a time and each must show a visible window. Codename One does not paint a window that is hidden or fully covered, so such a launch never reaches ready; those attempts were discarded and retried, never counted as fast results. Discarded CN1 attempts (kept under each `failed/` directory): 7 at 1, 8 at 1,000, 0 at 10,000 paragraphs.

RSS sampled every ~10 ms; idle samples start 0.5 seconds after the observed ready event. Kernel peak comes from macOS wait4/ru_maxrss and covers the whole process lifetime. The raw process minimum includes startup before the app is usable and is reported in JSON separately. All values are resident memory, not virtual address-space reservations.

## Editing and file operations

Three fresh process runs per app on the same 1,000-paragraph DOCX. Each performs 50 insert/undo pairs at the start, a 10,000-character paste/undo, five DOCX saves, five reopens and three PDF exports. Autosave is disabled in both instrumentation copies; normal production recovery is isolated. Each saved DOCX is CRC checked and its paragraph text compared with the input. Undo must restore the original text.

| Operation | CN1 operation median / p95 (ms) | Wordcraft operation median / p95 (ms) | CN1 command-to-CPU-paint median / p95 (ms) | Wordcraft command-to-CPU-paint median / p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| exportPdf | 7.46 / 16.87 | 254.36 / 262.57 | 7.75 / 17.08 | 254.68 / 262.95 |
| insert | 3.10 / 5.80 | 0.11 / 0.27 | 3.38 / 6.71 | 11.99 / 15.60 |
| paste10000 | 7.12 / 7.39 | 0.11 / 0.13 | 7.95 / 8.29 | 18.98 / 21.62 |
| reopenDocx | 41.75 / 45.64 | 7.95 / 12.32 | 42.34 / 46.14 | 51.71 / 70.57 |
| saveDocx | 5.45 / 10.45 | 9.05 / 14.32 | 5.61 / 10.62 | 9.56 / 14.79 |
| undo | 2.92 / 5.33 | 0.04 / 0.06 | 3.10 / 5.68 | 11.46 / 14.92 |
| undoPaste | 3.69 / 4.51 | 0.04 / 0.06 | 4.07 / 4.89 | 13.20 / 14.29 |

| Whole scripted process | CN1 | Wordcraft |
| --- | ---: | ---: |
| Maximum kernel RSS peak (MiB) | 284.95 | 270.80 |
| Median CPU seconds | 2.27 | 9.11 |
| Median elapsed seconds | 17.61 | 17.23 |

Whole-process CPU/wall includes the five-second idle period, operation pacing and two seconds after completion. CN1 uses a 25 ms timer; Wordcraft requests repaint checks at 20 ms during the scripted workload. These harness overheads differ, so CPU/wall are diagnostic rather than an isolated engine comparison. Operation timings use each app’s normal command/model path; command-to-paint includes that app’s resulting UI refresh.

DOCX validation checks plain text, not complete formatting equivalence. CN1 PDF currently has a much smaller typography/feature scope. Page counts and font metrics differ. Export timings therefore do not establish equal-fidelity throughput.

## Production size and source

| Metric | CN1 + Java | Wordcraft Rust |
| --- | ---: | ---: |
| Native executable bytes | 27,675,640 | 57,402,384 |
| Unsigned app bundle bytes | 33,144,966 | 57,515,260 |
| Application code lines (explicit tests pruned) | 7,837 | 118,659 |
| Application source tokens (o200k_base, explicit tests pruned) | 72,052 | 1,646,847 |

Sizes use uninstrumented ARM64 release executables. CN1 bundle includes resources and bundled OFL fonts. Wordcraft bundle uses its upstream packaging recipe without distribution signing/notarization or optional craft-fonts. These are logical byte totals, not compressed download or allocated disk sizes. Framework/dependency code, fonts and generated output are excluded from application LOC/tokens. Source counts are not a matched-feature comparison.

## Environment and evidence

Mac16,5, 16 logical CPUs, 64 GiB RAM, macOS-26.6.2-arm64-arm-64bit-Mach-O. Maven/ParparVM/Xcode Release versus Cargo release. Wordcraft revision `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`; CN1 framework `Codename One 7.0.278 (Maven Central)`.

macOS is the only platform on which the current native applications were exercised. CN1 Windows/Linux/Android/iOS/browser application behavior remains unverified; see [platform matrix](platforms.json).

Reproduce with `scripts/native-bench/README.md`. Raw per-process events, RSS samples, output files, fixture paths and hashes are under `artifacts/native-bench/final-20261018/`. The [summary](native-benchmarks.json) includes distributions and production executable hashes. Instrumentation is confined to disposable copies; application source remains uninstrumented.
