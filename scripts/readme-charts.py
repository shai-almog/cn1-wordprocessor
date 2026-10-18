#!/usr/bin/env python3
"""Render the README comparison charts (SVG) from the committed evidence JSON files."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent.parent
docs = root / 'docs'
out = docs / 'images'
JAVA, RUST = '#2f7ff0', '#e0663a'


def chart(name, title, unit, rows, note, lower_better=True):
    """rows: [(label, java, rust)]; one pair of horizontal bars per row."""
    w, label_w, bar_h, gap = 760, 190, 18, 18
    top = 64
    h = top + len(rows) * (2 * bar_h + gap) + 44
    peak = max(max(j, r) for _, j, r in rows) or 1
    span = w - label_w - 150
    s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
         'font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif">',
         '<style>text{fill:#1f2328}.muted{fill:#59636e}rect.bg{fill:#ffffff}'
         '@media (prefers-color-scheme:dark){text{fill:#e6edf3}.muted{fill:#9198a1}rect.bg{fill:#0d1117}}</style>',
         f'<rect class="bg" width="{w}" height="{h}" rx="8"/>',
         f'<text x="16" y="28" font-size="17" font-weight="600">{title}</text>',
         f'<text x="16" y="48" font-size="12" class="muted">{unit} · '
         f'{"lower" if lower_better else "higher"} is better</text>',
         f'<rect x="{w-250}" y="16" width="12" height="12" fill="{JAVA}"/>'
         f'<text x="{w-232}" y="27" font-size="12">Java / Codename One</text>',
         f'<rect x="{w-250}" y="34" width="12" height="12" fill="{RUST}"/>'
         f'<text x="{w-232}" y="45" font-size="12">Rust / Wordcraft</text>']
    y = top
    for label, j, r in rows:
        s.append(f'<text x="16" y="{y + bar_h + 4}" font-size="13">{label}</text>')
        for value, color, dy in ((j, JAVA, 0), (r, RUST, bar_h + 2)):
            bw = max(2, value / peak * span)
            s.append(f'<rect x="{label_w}" y="{y + dy}" width="{bw:.1f}" height="{bar_h}" rx="3" fill="{color}"/>')
            s.append(f'<text x="{label_w + bw + 6:.1f}" y="{y + dy + 13}" font-size="12">{fmt(value)}</text>')
        ratio = (r / j if lower_better else j / r) if j and r else 0
        if ratio >= 1.05:
            s.append(f'<text x="{w - 16}" y="{y + bar_h + 4}" font-size="12" font-weight="600" '
                     f'text-anchor="end" fill="{JAVA}">{ratio:.1f}× better</text>')
        y += 2 * bar_h + gap
    s.append(f'<text x="16" y="{h - 16}" font-size="11" class="muted">{note}</text></svg>')
    out.mkdir(exist_ok=True)
    (out / f'{name}.svg').write_text('\n'.join(s) + '\n')


def fmt(v):
    return f'{v:,.0f}' if v >= 100 else f'{v:.1f}'


bench = json.loads((docs / 'native-benchmarks.json').read_text())
source = json.loads((docs / 'source-metrics.json').read_text())
ci = json.loads((docs / 'ci-metrics.json').read_text())
work, start = bench['workload'], bench['startup']
paint = lambda side, op: work[side]['commandToCpuPaint'][op]['median']
ops = [('Type a character', 'insert'), ('Undo', 'undo'), ('Paste 10,000 chars', 'paste10000'),
       ('Save DOCX', 'saveDocx'), ('Reopen DOCX', 'reopenDocx'), ('Export PDF', 'exportPdf')]
chart('latency', 'Command to painted frame, 1,000-paragraph document', 'milliseconds, median of 3 native runs',
      [(l, paint('cn1', k), paint('wordcraft', k)) for l, k in ops],
      'Native macOS ARM64 release builds, same machine and fixture. Source: docs/native-benchmarks.json')
chart('startup', 'Launch to first painted document', 'milliseconds, median of 10 fresh launches',
      [(f'{int(p):,} paragraphs', start[p]['cn1']['cpuReadyMs']['median'], start[p]['wordcraft']['cpuReadyMs']['median'])
       for p in ('1', '1000', '10000')],
      'Warm file cache; ends at the CPU paint of the document. Source: docs/native-benchmarks.json')
mib = 1024 * 1024
chart('memory', 'Resident memory with the document open', 'MiB, median idle RSS',
      [(f'{int(p):,} paragraphs', start[p]['cn1']['idleRssBytes']['median'] / mib,
        start[p]['wordcraft']['idleRssBytes']['median'] / mib) for p in ('1', '1000', '10000')],
      'Sampled every ~10 ms after the first paint. Source: docs/native-benchmarks.json')
art = bench['artifacts']
chart('size', 'Size on disk', 'MiB, unsigned ARM64 release',
      [('Executable', art['cn1']['executableBytes'] / mib, art['wordcraft']['executableBytes'] / mib),
       ('App bundle', art['cn1']['appBundleBytes'] / mib, art['wordcraft']['appBundleBytes'] / mib)],
      'CN1 bundle includes its fonts and resources. Source: docs/native-benchmarks.json')
ja, ra = source['cn1Java']['groups']['application'], source['wordcraftRust']['groups']['application']
chart('source', 'Application source an LLM has to read', 'thousands; tests excluded',
      [('Code lines (no comments)', ja['prunedLoc']['code'] / 1000, ra['prunedLoc']['code'] / 1000),
       ('Tokens (o200k_base)', ja['testPrunedTokens'] / 1000, ra['testPrunedTokens'] / 1000),
       ('Files', ja['files'], ra['files'])],
      'cloc + tiktoken; Wordcraft pinned at 159b84f. Source: docs/source-metrics.json, CI workflow metrics.yml')
chart('ci', 'Test-suite cost', 'seconds on the same machine',
      [('Build + run tests, cold', ci['java']['coldBuildTestSeconds'], ci['rust']['coldBuildTestSeconds']),
       ('Run tests, compiled', ci['java']['testSeconds'], ci['rust']['testSeconds'])],
      f"{ci['java']['tests']:,} Java tests vs {ci['rust']['tests']:,} Rust tests. Source: docs/ci-metrics.json, CI workflow metrics.yml")
print('wrote', sorted(p.name for p in out.glob('*.svg')))
