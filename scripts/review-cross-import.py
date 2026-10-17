#!/usr/bin/env python3
"""Verify review decisions after opening the other implementation's DOCX fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft', type=Path)
p.add_argument('fixtures', type=Path, help='Output of parity-probes.py')
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
root = Path(__file__).resolve().parent.parent
cn1 = str(root / 'scripts/wordprocessor')
wc = str(a.wordcraft.resolve())
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
results = []
for name, accepted, rejected in [
    ('tracked_replace', ['red gold blue'], ['red green blue']),
    ('tracked_break_pending', ['First new', 'last'], ['First last']),
    ('tracked_format_pending', ['plain bold'], ['plain bold']),
    ('tracked_paragraph_pending', ['Paragraph'], ['Paragraph']),
    ('tracked_style_pending', ['Heading'], ['Heading']),
]:
    for target, source in [('cn1', 'wordcraft'), ('wordcraft', 'cn1')]:
        for accept, expected in [(True, accepted), (False, rejected)]:
            action = 'review.acceptAll' if accept else 'review.rejectAll'
            ident = name + '-' + target + ('-accept' if accept else '-reject')
            output = (a.out / (ident + '.docx')).resolve()
            input_path = (a.fixtures / (name + '-' + source + '.docx')).resolve()
            row = {'id': ident, 'source': str(input_path), 'target': target}
            try:
                if target == 'cn1':
                    inspect = subprocess.run([cn1, 'inspect', str(input_path)], capture_output=True, text=True, check=True)
                    row['importWarnings'] = json.loads(inspect.stdout)['warnings']
                    macro = a.out / (ident + '.json')
                    macro.write_text(json.dumps({'version': 1, 'steps': [{'command': action, 'args': {}}]}))
                    command = [cn1, 'run', str(input_path), str(macro.resolve()), str(output), '--allow-conversion']
                else:
                    command = [wc, 'run', '--file', str(input_path), '--cmd', action + '={}', '--save', str(output)]
                subprocess.run(command, check=True, capture_output=True, timeout=120)
                with zipfile.ZipFile(output) as z:
                    body = ET.fromstring(z.read('word/document.xml')).find(W + 'body')
                text = [''.join(t.text or '' for t in para.iter(W + 't')) for para in body.findall(W + 'p')]
                assertions = {'text': text == expected, 'resolved': all(body.find('.//' + W + tag) is None for tag in ('ins', 'del', 'rPrChange', 'pPrChange'))}
                if name == 'tracked_format_pending':
                    bold = []
                    for run in body.findall('.//' + W + 'r'):
                        flag = run.find(W + 'rPr/' + W + 'b')
                        on = flag is not None and flag.get(W + 'val', '1') not in ('0', 'false', 'off')
                        bold += [on] * len(''.join(t.text or '' for t in run.iter(W + 't')))
                    assertions['format'] = bold == [False] * 6 + [accept] * 4
                if name == 'tracked_paragraph_pending':
                    align = body.find(W + 'p/' + W + 'pPr/' + W + 'jc')
                    assertions['alignment'] = (align.get(W + 'val') if align is not None else 'left') == ('right' if accept else 'left')
                if name == 'tracked_style_pending':
                    style=body.find(W+'p/'+W+'pPr/'+W+'pStyle')
                    assertions['paragraphStyle']=(style.get(W+'val') if style is not None else 'Normal')==('Heading1' if accept else 'Normal')
                    if not accept:
                        sizes=body.findall('.//'+W+'r/'+W+'rPr/'+W+'sz')
                        # Both source Normal styles are 12pt in these fixtures.
                        assertions['restoredInheritedSize']=all(size.get(W+'val')=='24' for size in sizes)
                row.update(passed=all(assertions.values()), assertions=assertions, sha256=hashlib.sha256(output.read_bytes()).hexdigest(), artifact=output.name)
            except Exception as e:
                row.update(passed=False, error=str(e), stderr=getattr(e, 'stderr', b'').decode('utf-8', 'replace'))
            results.append(row)
            print(ident, row['passed'])
(a.out / 'results.json').write_text(json.dumps({'checks': results, 'fullParity': False}, indent=2) + '\n')
if not all(row['passed'] for row in results):
    raise SystemExit(1)
