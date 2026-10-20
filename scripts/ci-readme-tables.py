#!/usr/bin/env python3
"""Writes the README's per-OS comparison tables from one metrics.yml CI run.

usage: scripts/ci-readme-tables.py RUN_ID

Downloads the run's artifacts with gh, rewrites the summary table and the per-OS tables between
their README.md markers, refreshes docs/native-benchmarks.json (macOS), docs/source-metrics.json
and docs/ci-metrics.json, and redraws the charts. Every number links back to the run.
"""
import json, pathlib, subprocess, sys, tempfile

REPO = 'shai-almog/cn1-wordprocessor'
OSES = [('macos', 'macOS'), ('linux', 'Linux'), ('windows', 'Windows')]
OPS = [('Type a character', 'insert'), ('Undo', 'undo'), ('Paste 10,000 chars', 'paste10000'),
       ('Save DOCX', 'saveDocx'), ('Reopen DOCX', 'reopenDocx'), ('Export PDF', 'exportPdf')]
MIB = 1024 * 1024
BEGIN, END = '<!-- ci-tables:begin -->', '<!-- ci-tables:end -->'
SBEGIN, SEND = '<!-- ci-summary:begin -->', '<!-- ci-summary:end -->'
ROOT = pathlib.Path(__file__).resolve().parent.parent


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


def replace(text, begin, end, body):
    if begin not in text:
        sys.exit(f'README.md has no {begin} marker')
    head, rest = text.split(begin, 1)
    return head + begin + '\n' + body + '\n' + end + rest.split(end, 1)[1]


def summary(run, url, sha, bench, ci, src):
    cell = lambda f: ' / '.join(f(bench[k]) for k, _ in OSES)
    med = lambda d, side, *path: d['startup']['1000'][side][path[0]]['median'] if path[0] != 'insert' else d['workload'][side]['commandToCpuPaint']['insert']['median']
    rows = [('Faster startup', lambda s: cell(lambda d: f"{med(d, s, 'cpuReadyMs'):.0f}") + ' ms'),
            ('Faster editing', lambda s: cell(lambda d: f"{med(d, s, 'insert'):.1f}") + ' ms to type a character'),
            ('Less RAM', lambda s: cell(lambda d: f"{d['startup']['1000'][s]['idleRssBytes']['median'] / MIB:.0f}") + ' MiB'),
            ('Smaller executable', lambda s: cell(lambda d: f"{d['artifacts'][s]['executableBytes'] / MIB:.1f}") + ' MiB')]
    ja, ra = src['cn1Java']['groups']['application'], src['wordcraftRust']['groups']['application']
    j, r = ci['java'], ci['rust']
    wins = total = 0
    for d in bench.values():
        for line in table(d)[0][2:]:
            total += 1
            wins += line.rstrip().endswith('| CN1 |')
    out = ['| Front | Java (this app) | Rust (Wordcraft) |', '| --- | --- | --- |']
    out += [f'| **{n}** | {f("cn1")} | {f("wordcraft")} |' for n, f in rows]
    out += ['| **More platforms from one source** | macOS, Linux, Windows, iPhone/iPad, Android, browser | macOS, Linux, Windows, browser |',
            '| **More features, deeper native** | native menu bar, rich copy out, drag out, high contrast | see [below](#more-features-and-deeper-native) |',
            '| **Equally functional** | 487 / 487 implementable catalog rows | 487 rows implemented, 15 stubs |',
            f"| **Easier for LLMs** | {ja['prunedLoc']['code']:,} lines, {ja['testPrunedTokens']:,} tokens | {ra['prunedLoc']['code']:,} lines, {ra['testPrunedTokens']:,} tokens |",
            f"| **Cheaper CI per test** | {j['coldBuildTestSeconds'] * 1000 / j['tests']:.0f} ms ({j['tests']:,} tests in {j['coldBuildTestSeconds']:,} s) | {r['coldBuildTestSeconds'] * 1000 / r['tests']:.0f} ms ({r['tests']:,} tests in {r['coldBuildTestSeconds']:,} s) |",
            '', f'Desktop figures are macOS / Linux / Windows, measured on GitHub\'s runners by CI run [{run}]({url}) '
            f'(commit `{sha[:7]}`) with the 1,000-paragraph document: launch to first painted document, idle '
            'resident memory, and command to painted frame. Code counts are application code without tests or '
            f'comment lines. Java wins {wins} of the {total} desktop measurements; the rest are listed in '
            '[Per-OS results](#per-os-results).']
    return '\n'.join(out)


def main(run):
    tmp = pathlib.Path(tempfile.mkdtemp())
    get = lambda name: subprocess.call(['gh', 'run', 'download', run, '-R', REPO, '-n', name, '-D', str(tmp / name)]) == 0
    meta = json.loads(subprocess.check_output(['gh', 'run', 'view', run, '-R', REPO, '--json', 'headSha,createdAt,jobs']))
    jobs = {j['name']: j['url'] for j in meta['jobs']}
    url = f'https://github.com/{REPO}/actions/runs/{run}'
    md = [f'Measured by CI run [{run}]({url}) on commit `{meta["headSha"][:7]}` '
          f'({meta["createdAt"][:10]}), GitHub-hosted runners, same runner and fixtures for both apps. '
          'Medians; startup is 10 fresh launches to the first painted document, latency is command to painted frame. '
          'Regenerate with `scripts/ci-readme-tables.py RUN_ID`.', '']
    bench = {}
    for key, name in OSES:
        if not get(f'{key}-native'):
            sys.exit(f'run {run} has no {key}-native artifact')
        bench[key] = json.loads((tmp / f'{key}-native' / 'native-benchmarks.json').read_text())
        rows, a = table(bench[key])
        job = next((u for n, u in jobs.items() if key in n.lower()), url)
        md += [f'### {name}', '', f'[{a["machine"]}, {a["cpuCount"]} CPUs]({job})', ''] + rows + ['']
    if not (get('java-metrics') and get('wordcraft-metrics')):
        sys.exit(f'run {run} lacks java-metrics or wordcraft-metrics')
    num = lambda f: int((tmp / f).read_text().strip())
    ci = json.loads((ROOT / 'docs/ci-metrics.json').read_text())
    ci['measured'] = f'CI run {run} ({meta["headSha"][:7]}), GitHub ubuntu-24.04 runners'
    ci['java'].update(coldBuildTestSeconds=num('java-metrics/java-build-test-seconds.txt'),
                      testSeconds=num('java-metrics/java-test-seconds.txt'), tests=num('java-metrics/java-tests.txt'))
    ci['rust'].update(coldBuildTestSeconds=num('wordcraft-metrics/rust-build-test-seconds.txt'),
                      testSeconds=num('wordcraft-metrics/rust-test-seconds.txt'), tests=num('wordcraft-metrics/rust-tests.txt'))
    src = json.loads((tmp / 'java-metrics/source-metrics.json').read_text())
    (ROOT / 'docs/ci-metrics.json').write_text(json.dumps(ci, indent=2) + '\n')
    (ROOT / 'docs/source-metrics.json').write_text(json.dumps(src, indent=2) + '\n')
    (ROOT / 'docs/native-benchmarks.json').write_text(json.dumps(bench['macos'], indent=2) + '\n')
    readme = ROOT / 'README.md'
    text = replace(readme.read_text(), BEGIN, END, '\n'.join(md).rstrip())
    readme.write_text(replace(text, SBEGIN, SEND, summary(run, url, meta['headSha'], bench, ci, src)))
    subprocess.check_call([sys.executable, str(ROOT / 'scripts/readme-charts.py')])


if __name__ == '__main__':
    main(sys.argv[1])
