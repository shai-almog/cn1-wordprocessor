#!/usr/bin/env python3
"""Paired Quick Tables insertion, contents and headers, including DOCX cross-readers."""
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


def inspect(path):
    with zipfile.ZipFile(path) as z:
        body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
    table=body.find(W+'tbl');rows=[];headers=[]
    for row in table.findall(W+'tr'):
        rows.append([''.join(n.text or '' for n in cell.iter(W+'t')) for cell in row.findall(W+'tc')])
        headers.append(row.find(W+'trPr/'+W+'tblHeader') is not None)
    paragraphs=[''.join(n.text or '' for n in para.iter(W+'t')) for para in body.findall(W+'p')]
    return {'rows':rows,'headers':headers,'paragraphs':paragraphs}

run([cn,'new',a.out/'empty.cn1doc'])
macro('fixture',a.out/'empty.cn1doc',[('text.insert',{'text':'Before TARGET after'})],a.out/'fixture.docx')
for kind in ['tabular','matrix','calendar']:
    outputs={}
    for side in ['cn1','wordcraft']:
        case=kind+'-'+side;out=a.out/(case+'.docx')
        try:
            if side=='cn1':macro(case,a.out/'fixture.docx',[
                ('selection.set',{'block':0,'start':7,'end':13}),('table.quick',{'kind':kind})],out)
            else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"TARGET"}',
                '--cmd','table.quick='+json.dumps({'kind':kind}),'--save',out])
            actual=inspect(out);outputs[side]=actual
            assert actual['paragraphs']==['Before ',' after'],actual
            assert actual['headers']==[True]+[False]*(len(actual['rows'])-1),actual
            expected={'tabular':(7,2),'matrix':(4,4),'calendar':(7,7)}[kind]
            assert (len(actual['rows']),len(actual['rows'][0]))==expected,actual
            checks.append({'case':case,'operation':'insert-at-selection','passed':True})
            reopened=out.with_name(out.stem+'-reopened.docx')
            run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
            assert inspect(reopened)==actual,(actual,inspect(reopened))
            checks.append({'case':case,'operation':'other-reader-roundtrip','passed':True})
        except Exception as error:checks.append({'case':case,'passed':False,'error':str(error)})
    checks.append({'case':kind,'operation':'paired-content-equivalence','passed':outputs.get('cn1')==outputs.get('wordcraft') and len(outputs)==2})
report={'boundary':'Template contents, dimensions, leading headers and text-selection insertion. '
    'CN1 materializes cell styling; named Wordcraft table styles and rendered equivalence are not certified.',
    'checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.out.glob('*.docx'))}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'checks':len(checks),'passed':sum(x['passed'] for x in checks),'failures':[x for x in checks if not x['passed']]},indent=2))
raise SystemExit(0 if all(x['passed'] for x in checks) else 1)
