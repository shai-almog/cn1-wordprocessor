#!/usr/bin/env python3
"""Paired aggregate fields and DOCX cross-reader checks against pinned Wordcraft."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft', type=Path)
p.add_argument('--out', required=True, type=Path)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
a.out = a.out.resolve()
cn = Path(__file__).resolve().parent / 'wordprocessor'
wc = a.wordcraft.resolve()
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
checks = []


def run(args):
    args = list(map(str, args))
    result = subprocess.run(args, capture_output=True, text=True, timeout=120)
    with (a.out / 'commands.log').open('a') as log:
        log.write(json.dumps(args) + '\n' + result.stdout + result.stderr + '\n')
    result.check_returncode()


def macro(name, source, steps, target):
    path = a.out / (name + '.json')
    path.write_text(json.dumps({'version': 1, 'steps': [
        {'command': command, 'args': args} for command, args in steps]}))
    run([cn, 'run', source, path, target])


def inspect(path, row, column, instruction, expected):
    with zipfile.ZipFile(path) as z:
        body = ET.fromstring(z.read('word/document.xml')).find(W + 'body')
    cell = body.find(W + 'tbl').findall(W + 'tr')[row].findall(W + 'tc')[column]
    instructions = [f.get(W + 'instr', '').strip() for f in cell.iter(W + 'fldSimple')]
    instructions += [''.join(n.text or '' for n in cell.iter(W + 'instrText')).strip()]
    assert instruction in instructions, (path, instructions)
    result = ''.join(n.text or '' for n in cell.iter(W + 't'))
    assert result == expected, (path, result, expected)


run([cn, 'new', a.out / 'empty.cn1doc'])
for direction in ['ABOVE', 'LEFT']:
    row, column = (3, 0) if direction == 'ABOVE' else (0, 3)
    steps = [('table.insert', {'rows': 4, 'columns': 4})]
    for n, text in enumerate(['10', '20', '30']):
        steps.append(('table.setCell', {'block': 1, 'row': n if direction == 'ABOVE' else 0,
                                       'column': 0 if direction == 'ABOVE' else n, 'text': text}))
    steps.append(('table.setCell', {'block': 1, 'row': row, 'column': column, 'text': 'Target'}))
    source = a.out / (direction + '.docx')
    macro(direction, a.out / 'empty.cn1doc', steps, source)
    for function, expected in [('SUM', '60'), ('AVERAGE', '20'), ('COUNT', '3'),
                               ('MIN', '10'), ('MAX', '30'), ('PRODUCT', '6000')]:
        instruction = '=' + function + '(' + direction + ')'
        for side in ['cn1', 'wordcraft']:
            case = direction + '-' + function + '-' + side
            out = a.out / (case + '.docx')
            try:
                if side == 'cn1':
                    macro(case, source, [
                        ('table.setCell', {'block': 1, 'row': row, 'column': column, 'text': ''}),
                        ('selection.cell', {'block': 1, 'row': row, 'column': column,
                                            'paragraph': 0, 'start': 0, 'end': 0}),
                        ('table.formula', {'formula': instruction})], out)
                else:
                    run([wc, 'run', '--file', source, '--cmd', 'select.text={"text":"Target"}',
                         '--cmd', 'text.backspace={}', '--cmd', 'table.formula=' + json.dumps({'formula': instruction}),
                         '--save', out])
                inspect(out, row, column, instruction, expected)
                checks.append({'case': case, 'operation': 'insert-field', 'passed': True})
                reopened = out.with_name(out.stem + '-reopened.docx')
                run([wc if side == 'cn1' else cn, 'convert', out, reopened] +
                    (['--allow-conversion'] if side == 'wordcraft' else []))
                inspect(reopened, row, column, instruction, expected)
                checks.append({'case': case, 'operation': 'other-reader-roundtrip', 'passed': True})
            except Exception as error:
                checks.append({'case': case, 'passed': False, 'error': str(error)})

report = {'boundary': 'Six aggregate fields with ABOVE/LEFT in English, and DOCX cross-reader preservation. '
          'Not language-specific number parsing, complete formula syntax or rendered equivalence.',
          'checks': checks, 'artifacts': {f.name: hashlib.sha256(f.read_bytes()).hexdigest()
                                        for f in sorted(a.out.glob('*.docx'))}}
(a.out / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'checks': len(checks), 'passed': sum(x['passed'] for x in checks),
                  'failures': [x for x in checks if not x['passed']]}, indent=2))
raise SystemExit(0 if all(x['passed'] for x in checks) else 1)
