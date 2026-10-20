#!/usr/bin/env python3
"""Writes the README's per-OS comparison tables from one metrics.yml CI run.

usage: scripts/ci-readme-tables.py RUN_ID

Downloads the run's macos/linux/windows-native artifacts with gh and replaces the block between
the ci-tables markers in README.md. Every number links back to the run that produced it.
"""
import json, pathlib, subprocess, sys, tempfile

REPO = 'shai-almog/cn1-wordprocessor'
OSES = [('macos', 'macOS'), ('linux', 'Linux'), ('windows', 'Windows')]
OPS = [('Type a character', 'insert'), ('Undo', 'undo'), ('Paste 10,000 chars', 'paste10000'),
       ('Save DOCX', 'saveDocx'), ('Reopen DOCX', 'reopenDocx'), ('Export PDF', 'exportPdf')]
MIB = 1024 * 1024
BEGIN, END = '<!-- ci-tables:begin -->', '<!-- ci-tables:end -->'


def row(label, cn1, wc, unit, fmt='{:.0f}'):
    if cn1 is None or wc is None:
        return f'| {label} | {"-" if cn1 is None else fmt.format(cn1)} | {"-" if wc is None else fmt.format(wc)} | |'
    verdict = 'CN1' if cn1 < wc else ('tie' if cn1 == wc else '**Wordcraft**')
    return f'| {label} | {fmt.format(cn1)} {unit} | {fmt.format(wc)} {unit} | {verdict} |'


def table(d):
    s, w, a = d['startup'], d['workload'], d['artifacts']
    med = lambda x, k: x.get(k, {}).get('median')
    out = ['| Measure (lower is better) | Codename One | Wordcraft (Rust) | Better |', '|---|---:|---:|---|']
    for p in ('1', '1000', '10000'):
        if p in s:
            out.append(row(f'Startup, {int(p):,} paragraph{"" if p == "1" else "s"}', med(s[p]['cn1'], 'cpuReadyMs'), med(s[p]['wordcraft'], 'cpuReadyMs'), 'ms'))
    for p in ('1', '1000', '10000'):
        if p in s:
            c, r = med(s[p]['cn1'], 'idleRssBytes'), med(s[p]['wordcraft'], 'idleRssBytes')
            out.append(row(f'Idle RAM, {int(p):,} paragraph{"" if p == "1" else "s"}', c and c / MIB, r and r / MIB, 'MiB'))
    pk = lambda side: (w[side].get('kernelPeakBytes') or {}).get('median')
    c, r = pk('cn1'), pk('wordcraft')
    out.append(row('Peak RAM, 1,000-paragraph workload', c and c / MIB, r and r / MIB, 'MiB'))
    for label, k in OPS:
        out.append(row(f'{label} to painted frame', med(w['cn1']['commandToCpuPaint'], k),
                       med(w['wordcraft']['commandToCpuPaint'], k), 'ms', '{:.1f}'))
    out.append(row('Executable size', a['cn1']['executableBytes'] / MIB, a['wordcraft']['executableBytes'] / MIB, 'MiB', '{:.1f}'))
    return out, a


def main(run):
    tmp = pathlib.Path(tempfile.mkdtemp())
    meta = json.loads(subprocess.check_output(['gh', 'run', 'view', run, '-R', REPO, '--json', 'headSha,createdAt,jobs']))
    jobs = {j['name']: j['url'] for j in meta['jobs']}
    url = f'https://github.com/{REPO}/actions/runs/{run}'
    md = [BEGIN, f'Measured by CI run [{run}]({url}) on commit `{meta["headSha"][:7]}` '
          f'({meta["createdAt"][:10]}), GitHub-hosted runners, same runner and fixtures for both apps. '
          'Medians; startup is 10 fresh launches to the first painted document, latency is command to painted frame. '
          'Regenerate with `scripts/ci-readme-tables.py RUN_ID`.', '']
    for key, name in OSES:
        dest = tmp / key
        if subprocess.call(['gh', 'run', 'download', run, '-R', REPO, '-n', f'{key}-native', '-D', str(dest)]):
            md += [f'### {name}', '', f'No `{key}-native` artifact in this run.', '']
            continue
        rows, a = table(json.loads((dest / 'native-benchmarks.json').read_text()))
        job = next((u for n, u in jobs.items() if key in n.lower() or (key == 'macos' and 'macos' in n)), url)
        md += [f'### {name}', '', f'[{a["machine"]}, {a["cpuCount"]} CPUs]({job})', ''] + rows + ['']
    md.append(END)
    readme = pathlib.Path(__file__).resolve().parent.parent / 'README.md'
    text = readme.read_text()
    if BEGIN not in text:
        sys.exit(f'README.md has no {BEGIN} marker')
    head, rest = text.split(BEGIN, 1)
    readme.write_text(head + '\n'.join(md) + rest.split(END, 1)[1])
    print('\n'.join(md))


if __name__ == '__main__':
    main(sys.argv[1])
