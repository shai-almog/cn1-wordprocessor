#!/usr/bin/env python3
"""Render the measured scorecard; leaves unmatched scope and unverified platforms visible."""
import argparse
import json
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--artifacts',type=Path,default=Path('artifacts'))
p.add_argument('--out',type=Path,default=Path('docs/COMPARISON.md'))
a=p.parse_args()
metrics=json.loads((a.artifacts/'source-metrics.json').read_text())
cli=json.loads((a.artifacts/'cli-comparison.json').read_text())
probes=json.loads((a.artifacts/'parity-probes/results.json').read_text())
java=metrics['cn1Java'];rust=metrics['wordcraftRust']
j=java['applicationTotal'];r=rust['applicationTotal']
rows=[('Application files',j['files'],r['files']),
      ('Application LOC, before inline-test pruning',j['rawCodeLines'],r['rawCodeLines']),
      ('Application LOC, explicit tests pruned (estimate)',j['testPrunedCodeLines'],r['testPrunedCodeLines']),
      ('Application source tokens, before inline-test pruning',j['sourceTokens'],r['sourceTokens']),
      ('Application source tokens, explicit tests pruned (estimate)',j['testPrunedTokens'],r['testPrunedTokens']),
      ('Separate test source tokens',java['groups']['tests']['sourceTokens'],rust['groups']['tests']['sourceTokens'])]
text='''# Comparison scorecard

**The app is smaller, but the implementations are not feature-equivalent. No language, agent-coding, platform or overall performance superiority is established.** This report measures the resulting source code, not tokens spent by an agent.

## Source footprint

| Metric | Java + Codename One | Wordcraft Rust |
| --- | ---: | ---: |
'''
text+='\n'.join('| '+name+' | '+format(x,',')+' | '+format(y,',')+' |' for name,x,y in rows)
text+='''

Counts use `cloc` and pinned `tiktoken` `o200k_base`. Source tokens include comments/whitespace, tokenize each file separately, and exclude filename/chat framing. Frameworks, dependencies, assets and generated output are excluded for both sides. Java includes the document libraries, desktop host and shared platform lifecycle. Tests, benchmarks, examples and build tooling have separate buckets in [the complete file/hash manifest](source-metrics.json).

'''
text+=f"The Rust estimate removes {r['explicitTestItemsRemoved']} explicit inline test items and identifies external test modules. The parser cannot fully parse {len(rust['unprunedFiles'])} source files; those remain unpruned and are listed in the manifest. This estimate does not evaluate all feature configurations/macros. Separate test-source tokens above do not include tokens removed from inline test items.\n\n"
text+='''The CN1 implementation delegates platform UI/input/storage and several text-format adapters to its framework. Wordcraft uses its own Rust dependency stack. That reuse is part of the proposed stack comparison and must be disclosed; these numbers do not count all code needed to execute either application. [Dependency hashes and artifact sizes](dependency-inventory.json) are recorded separately. The Java distribution requires an installed JVM. Its GUI/CLI ZIP and Wordcraft's CLI binary have different scopes and are not comparable package-size benchmarks.

## Equivalent behavior probes

| Probe | CN1 | Wordcraft |
| --- | --- | --- |
'''
for row in probes['probes']:
    text+='| '+row['id']+' | '+('Pass' if row['cn1']['passed'] else 'Fail')+' | '+('Pass' if row['wordcraft']['passed'] else 'Fail')+' |\n'
text+='''
These probes inspect CLI-generated DOCX paragraphs, direct bold formatting, replacement text and table dimensions. They do not cover GUI interaction, shaped rendering or all edge cases, and certify zero full catalog rows. The broader [502-row catalog](../benchmarks/baseline.json) remains pending. [Probe artifact hashes](parity-probes.json) make the exercised outputs identifiable.

## Performance measured so far

'''
text+=f"Initial JVM baseline: complete fresh-process DOCX text extraction on the same 1,000-paragraph fixture, with {cli['iterations']} timed invocations per app and byte-identical output:\n\n"
text+='| Metric | CN1 Java CLI | Wordcraft Rust CLI |\n| --- | ---: | ---: |\n'
for label,key in [('Median','medianMs'),('p95','p95Ms')]:
    text+=f"| {label} | {cli['cn1'][key]:.2f} ms | {cli['wordcraft'][key]:.2f} ms |\n"
text+='''
The process boundary includes JVM/runtime startup and output capture, with warm filesystem caches after one discarded invocation. It measures neither typing nor rendering. Wordcraft was faster in this initial JVM CLI test. The optimized native CLI is measured separately below. Raw samples, environment and document/output hashes are in [the timing record](cli-comparison.json). Runs were collected after local build/UI work finished.

'''
startup_path=a.artifacts/'startup-final/results.json'
if startup_path.exists():
    startup=json.loads(startup_path.read_text())
    text+='## Optimized native CLI startup\n\nThe same Java CLI compiled locally with GraalVM Native Image, compared with the same Rust release binary. Each cell is median / p95 in milliseconds; outputs are byte-identical for every sample.\n\n| Fixture | Native Java | Wordcraft Rust | Java shell launcher | JVM fallback |\n| --- | ---: | ---: | ---: | ---: |\n'
    for name, fixture in startup['fixtures'].items():
        text+='| '+name+' | '+' | '.join(f"{fixture['arms'][arm]['medianMs']:.2f} / {fixture['arms'][arm]['p95Ms']:.2f}" for arm in ['native','wordcraft','launcher','jvm'])+' |\n'
    text+='\nThese are fresh processes with warm filesystem caches, no daemon or cached output. The JVM GUI is unchanged. Native Java retains a small-document startup gap; this is not a universal startup or language win. See [diagnosis and reproduction](STARTUP.md), [raw samples and binary hashes](startup-comparison.json), and [native/JVM behavior checks](native-validation.json).\n\n'
warm_path=a.artifacts/'warm-import.json'
if warm_path.exists():
    warm=json.loads(warm_path.read_text())
    text+='\nWarmed in-memory DOCX parsing plus text extraction on that same fixture (three alternating process rounds, 50 warmups and 100 measured operations per process):\n\n| Metric | CN1 Java reader | Wordcraft Rust reader |\n| --- | ---: | ---: |\n'
    for label,key in [('Median','medianMs'),('p95','p95Ms')]:
        text+=f"| {label} | {warm['cn1'][key]:.2f} ms | {warm['wordcraft'][key]:.2f} ms |\n"
    text+='\nCN1 is faster for this simple, warmed import workload. Both outputs are byte-identical. Startup, disk I/O, layout, rendering and memory measurement are excluded; normal GC/reference destruction applies. The readers support different broader format scopes, so this result does not establish full DOCX performance/fidelity parity. [Raw warmed samples and linked-library hashes](warm-import.json).\n'
text+='''
The [CN1 model/layout timings](model-layout.json) separately exercise warmed edits with synthetic metrics. They cannot be compared to font-shaped Wordcraft layout timings and do not measure input-to-present latency or peak memory. The overall performance gate is not evaluated.

## Platforms

| Platform | CN1 application evidence | Wordcraft evidence inspected |
| --- | --- | --- |
| macOS | JavaSE GUI launch/render and callback smoke; CLI/MCP tests | Release CLI built and exercised; GUI untested here |
| Windows | Shared JavaSE target; execution untested | Declared target and workflow source; execution untested here |
| Linux | Shared JavaSE target; execution untested | Declared target and workflow source; execution untested here |
| BSD | No app support claimed | Declared target and FreeBSD workflow; execution untested here |
| Browser | JavaScript build passed; live interaction unverified | WebAssembly target exists; not built/tested here |
| Android | CN1 profile/lifecycle present; no device build/test | No advertised app target in inspected README/workspace |
| iOS | CN1 profile/lifecycle present; no device build/test | No advertised app target in inspected README/workspace |

This is not a 6-versus-5 platform-support victory: framework reach, target declaration, compilation and working application behavior are different evidence levels. The [machine-readable platform matrix](platforms.json) records source references. Upstream workflow presence does not verify that a workflow passed, and these local checks are asymmetrical.

## Reproduce and extend

See the root README for the source-metrics, parity-probe, dependency-inventory and CLI comparison commands. The Wordcraft baseline is pinned to `159b84f54c74e9e82fb3109b758ae7dd6f4e3ab0`. Local Java source is identified by per-file hashes in the manifest. Refresh the catalog before any release comparison.

The [experiment protocol](EXPERIMENT.md) defines how to test the narrower, defensible claim: fewer source tokens at equivalent verified behavior and platform coverage, within agreed latency/memory gates. Full application parity and the controlled agent-task experiment are still unfinished.
'''
a.out.parent.mkdir(parents=True,exist_ok=True)
a.out.write_text(text)
print(a.out)
