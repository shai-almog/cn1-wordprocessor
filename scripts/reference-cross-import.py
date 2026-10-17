#!/usr/bin/env python3
"""Paired bookmark/REF/PAGEREF artifacts, followed by edits in the other editor.

This checks headless command and DOCX behavior, not UI or full feature parity.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft', type=Path)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out = a.out.resolve()
a.out.mkdir(parents=True, exist_ok=False)
cn1 = str(Path(__file__).resolve().parent.parent / 'scripts/wordprocessor')
wc = str(a.wordcraft.resolve())
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
results = []

def run(side, source, steps, output):
    if side == 'cn1':
        macro = output.with_suffix('.json')
        macro.write_text(json.dumps({'version': 1, 'steps': [{'command': c, 'args': v} for c, v in steps]}))
        command = [cn1, 'run', str(source), str(macro), str(output), '--allow-conversion']
    else:
        command = [wc, 'run'] + (['--file', str(source)] if source else ['--template', 'blank'])
        for c, v in steps:
            command += ['--cmd', c + '=' + json.dumps(v)]
        command += ['--save', str(output)]
    subprocess.run(command, check=True, capture_output=True, text=True, timeout=120)

def inspect(path, expected):
    with zipfile.ZipFile(path) as z:
        body = ET.fromstring(z.read('word/document.xml')).find(W + 'body')
    text = [''.join(t.text or '' for t in para.iter(W + 't')) for para in body.findall(W + 'p')]
    starts = list(body.iter(W + 'bookmarkStart'))
    ends = list(body.iter(W + 'bookmarkEnd'))
    codes = [f.get(W + 'instr', '').strip() for f in body.iter(W + 'fldSimple')]
    codes += [(f.text or '').strip() for f in body.iter(W + 'instrText')]
    names = {f.get(W + 'name') for f in starts}
    checks = {'text': text == expected, 'oneReusableBookmark': len(starts) == len(ends) == 1,
              'balancedRange': {f.get(W + 'id') for f in starts} == {f.get(W + 'id') for f in ends},
              'liveFieldTargets': len(codes) == 2 and all(c.split()[1] in names for c in codes),
              'textAndPageFields': {c.split()[0] for c in codes} == {'REF', 'PAGEREF'}}
    return {'assertions': checks, 'passed': all(checks.values()), 'text': text, 'instructions': codes,
            'artifact': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}

empty = a.out / 'empty.cn1doc'
subprocess.run([cn1, 'new', str(empty)], check=True, capture_output=True)
for kind in ('heading', 'bookmark'):
    for side in ('cn1', 'wordcraft'):
        ident = kind + '-' + side
        output = a.out / (ident + '.docx')
        try:
            if side == 'cn1':
                steps = [('text.insert', {'text': 'Kilns\nSee '}), ('selection.set', {'block': 0, 'start': 0, 'end': 5})]
                steps += [('styles.apply', {'id': 'Heading1'})] if kind == 'heading' else [('bookmark.add', {'name': 'Spot'})]
                steps += [('selection.set', {'block': 1, 'start': 4, 'end': 4})]
                steps += [('reference.insert', {'kind': kind, 'target': 'Kilns' if kind == 'heading' else 'Spot', 'show': show}) for show in ('text', 'page')]
            else:
                steps = [('text.insert', {'text': 'Kilns'}), ('text.newParagraph', {}), ('text.insert', {'text': 'See '}), ('select.text', {'text': 'Kilns'})]
                steps += [('styles.apply', {'style': 'Heading 1'})] if kind == 'heading' else [('insert.bookmark', {'name': 'Spot'})]
                steps += [('caret.docEnd', {})]
                steps += [('insert.crossReference', {'to': kind, 'target': 'Kilns' if kind == 'heading' else 'Spot', 'show': show}) for show in ('text', 'page')]
            run(side, empty if side == 'cn1' else None, steps, output)
            row = inspect(output, ['Kilns', 'See Kilns1'])
            row['id'] = ident
            results.append(row)
            target = 'wordcraft' if side == 'cn1' else 'cn1'
            changed = a.out / (ident + '-edited-in-' + target + '.docx')
            steps = [('selection.set', {'block': 0, 'start': 1, 'end': 5})] if target == 'cn1' else [('select.text', {'text': 'ilns'})]
            steps += [('text.insert', {'text': 'ILNS'}), ('references.updateFields', {})]
            run(target, output, steps, changed)
            row = inspect(changed, ['KILNS', 'See KILNS1'])
            row['id'] = ident + '-edited-in-' + target
            if target == 'cn1':
                row['importWarnings'] = json.loads(subprocess.run([cn1, 'inspect', str(output)], check=True, text=True, capture_output=True).stdout)['warnings']
                row['assertions']['noBookmarkConversionWarnings'] = not any('bookmark' in w.lower() for w in row['importWarnings'])
                row['passed'] &= row['assertions']['noBookmarkConversionWarnings']
            results.append(row)
        except Exception as e:
            results.append({'id': ident, 'passed': False, 'error': str(e), 'stderr': getattr(e, 'stderr', '')})
for row in results:
    print(row['id'], row['passed'])
(a.out / 'results.json').write_text(json.dumps({'checks': results, 'fullParity': False}, indent=2) + '\n')
if not all(row['passed'] for row in results):
    raise SystemExit(1)
