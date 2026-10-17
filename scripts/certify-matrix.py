#!/usr/bin/env python3
"""Certify acceptance-matrix rows from native-certify.py evidence.

Later runs override earlier ones, so a targeted rerun can replace a case. Only rows whose
command passed on the native app are certified; every certified row names its evidence mode:
  replay    the JVM test's recorded before-state, command and args replayed on the native app,
            with the resulting document and selection compared (see the rules in native-certify.py)
  sequence  a scripted multi-step check for state that the document XML does not carry
  ui        the ribbon control or Search commands entry activated through accessibility, with an
            observed effect (native panel, window, document, selection, layout, accessible text, pixels)
The compact summary written to docs/ drops screenshots and accessibility snapshots.
"""
import argparse
import json
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('runs', nargs='+', type=Path)
parser.add_argument('--matrix', type=Path, default=Path('docs/acceptance-matrix.json'))
parser.add_argument('--summary', type=Path, default=Path('docs/native-certification.json'))
args = parser.parse_args()

results, started = {}, []
for run in args.runs:
    data = json.loads(run.read_text())
    started.append({'run': run.name, 'started': data.get('started')})
    results.update(data['results'])


def compact(cid, r):
    out = {'mode': r.get('mode'), 'ok': bool(r.get('ok'))}
    for key in ('observed', 'path', 'control', 'tab', 'nativeWindows', 'why'):
        if r.get(key):
            out[key] = r[key]
    runs = r.get('runs') or []
    if runs:
        out['tests'] = sorted({x.get('test') for x in runs if x.get('test')})
        rules = sorted({x.get('rule') for x in runs if x.get('rule')})
        if rules:
            out['rule'] = rules[0]
        whys = [x['why'] for x in runs if x.get('why')]
        if whys:
            out['why'] = whys[0]
    return out


summary = {cid: compact(cid, r) for cid, r in sorted(results.items())}
matrix = json.loads(args.matrix.read_text())
changed = 0
for row in matrix['rows']:
    if row['status'] != 'unverified':
        continue
    cid = row.get('appCommand') or row['command']
    r = summary.get(cid)
    if not r or not r['ok']:
        continue
    row['status'] = 'certified'
    row['evidence'] = ['docs/native-certification.json#' + cid]
    detail = {'replay': 'native replay of ' + ', '.join(r.get('tests', [])[:2]),
              'sequence': 'native scripted sequence',
              'ui': 'native UI activation (%s) observed %s' % (r.get('path'), ', '.join(r.get('observed', [])))}
    row['notes'] = detail.get(r['mode'], r['mode']) + ('; rule: ' + r['rule'] if r.get('rule') else '')
    changed += 1

counts = {}
for row in matrix['rows']:
    counts[row['status']] = counts.get(row['status'], 0) + 1
matrix['counts'] = counts
args.matrix.write_text(json.dumps(matrix, indent=2) + '\n')
args.summary.write_text(json.dumps({
    'generated': time.strftime('%Y-%m-%d'),
    'app': 'Mac native (ParparVM) agent-debug build driven over the live MCP',
    'runs': started,
    'passed': sum(1 for r in summary.values() if r['ok']),
    'total': len(summary),
    'results': summary}, indent=1) + '\n')
print('certified', changed, counts)
