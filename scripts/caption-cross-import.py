#!/usr/bin/env python3
"""Update captions after cross-import, checking instructions and document-order numbering."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft', type=Path)
p.add_argument('fixtures', type=Path)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
cn1 = str(Path(__file__).resolve().parent.parent / 'scripts/wordprocessor')
wc = str(a.wordcraft.resolve())
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
results = []
for name, fmt, numbers in [
    ('decimal', 'ARABIC', ['1', '2', '3']),
    ('lower_roman', 'roman', ['i', 'ii', 'iii']),
    ('upper_roman', 'ROMAN', ['I', 'II', 'III']),
    ('lower_letter', 'alphabetic', ['a', 'b', 'c']),
    ('upper_letter', 'ALPHABETIC', ['A', 'B', 'C']),
]:
    for target, source in [('cn1', 'wordcraft'), ('wordcraft', 'cn1')]:
        ident = name + '-' + target
        input_path = (a.fixtures / ('caption_' + name + '-' + source + '.docx')).resolve()
        output = (a.out / (ident + '.docx')).resolve()
        row = {'id': ident, 'source': str(input_path), 'target': target}
        try:
            caption = {'label': 'Figure', 'text': 'New', 'format': fmt, 'position': 'above'}
            if target == 'cn1':
                inspect = subprocess.run([cn1, 'inspect', str(input_path)], capture_output=True, text=True, check=True)
                row['importWarnings'] = json.loads(inspect.stdout)['warnings']
                steps = [('selection.set', {'block': 1, 'start': 0, 'end': 0}), ('references.caption', caption), ('references.updateFields', {})]
                macro = a.out / (ident + '.json')
                macro.write_text(json.dumps({'version': 1, 'steps': [{'command': cmd, 'args': args} for cmd, args in steps]}))
                command = [cn1, 'run', str(input_path), str(macro.resolve()), str(output), '--allow-conversion']
            else:
                steps = [('select.range', {'anchor': {'block': 1, 'off': 0}, 'focus': {'block': 1, 'off': 0}}), ('references.caption', caption), ('references.updateFields', {})]
                command = [wc, 'run', '--file', str(input_path)]
                for cmd, args in steps:
                    command += ['--cmd', cmd + '=' + json.dumps(args)]
                command += ['--save', str(output)]
            subprocess.run(command, check=True, capture_output=True, timeout=120)
            with zipfile.ZipFile(output) as z:
                body = ET.fromstring(z.read('word/document.xml')).find(W + 'body')
            text = [''.join(t.text or '' for t in para.iter(W + 't')) for para in body.findall(W + 'p')]
            expected = ['Object'] + ['Figure ' + n + ': ' + label for n, label in zip(numbers, ['New', 'First', 'Second'])]
            codes = [f.get(W + 'instr', '').strip() for f in body.iter(W + 'fldSimple')]
            codes += [(f.text or '').strip() for f in body.iter(W + 'instrText')]
            assertions = {'renumberedText': text == expected, 'sequenceInstructions': sum(c.startswith('SEQ Figure') and ('\\* ' + fmt) in c for c in codes) == 3}
            row.update(passed=all(assertions.values()), assertions=assertions, actualText=text, sha256=hashlib.sha256(output.read_bytes()).hexdigest(), artifact=output.name)
        except Exception as e:
            stderr = getattr(e, 'stderr', b'')
            row.update(passed=False, error=str(e), stderr=stderr.decode('utf-8', 'replace') if isinstance(stderr, bytes) else stderr)
        results.append(row)
        print(ident, row['passed'])
(a.out / 'results.json').write_text(json.dumps({'checks': results, 'fullParity': False}, indent=2) + '\n')
if not all(row['passed'] for row in results):
    raise SystemExit(1)
