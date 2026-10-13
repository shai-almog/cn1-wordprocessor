#!/usr/bin/env python3
"""A small set of common CLI behavior probes. Not a feature-parity certificate."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path)
p.add_argument('--cn1',type=Path,help='Explicit CN1 executable (defaults to the development launcher)')
p.add_argument('--out',type=Path,required=True,help='New output directory')
a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=False)
a.out=a.out.resolve()
root=Path(__file__).resolve().parent.parent
cn1=str(a.cn1.resolve()) if a.cn1 else str(root/'scripts/wordprocessor')
wc=str(a.wordcraft.resolve())
subprocess.run([cn1,'new',str(a.out/'empty.cn1doc')],check=True)
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
cases=[
 ('paragraph_text',[('text.insert',{'text':'Hello world\nSecond paragraph'})],
  [('text.insert',{'text':'Hello world'}),('text.newParagraph',{}),('text.insert',{'text':'Second paragraph'})],['Hello world','Second paragraph']),
 ('bold_selection',[('text.insert',{'text':'Hello world'}),('selection.set',{'block':0,'start':0,'end':5}),('format.set',{'property':'bold','value':'true'})],
  [('text.insert',{'text':'Hello world'}),('select.text',{'text':'Hello'}),('format.bold',{'value':True})],['Hello world']),
 ('replace_all',[('text.insert',{'text':'one two one'}),('replace.all',{'text':'one','replacement':'three'})],
  [('text.insert',{'text':'one two one'}),('edit.replaceAll',{'text':'one','with':'three'})],['three two three']),
 ('table_grid',[('table.insert',{'rows':2,'columns':3})],
  [('insert.table',{'rows':2,'cols':3})],None),
]
results=[]
for name, cn1steps, ruststeps, expected in cases:
    row={'id':name,'boundary':'headless command execution and DOCX semantics; no UI or rendering'}
    for side,steps in [('cn1',cn1steps),('wordcraft',ruststeps)]:
        out=a.out/(name+'-'+side+'.docx')
        try:
            if side=='cn1':
                macro=a.out/(name+'.json')
                macro.write_text(json.dumps({'version':1,'steps':[{'command':cmd,'args':args} for cmd,args in steps]}))
                command=[cn1,'run',str(a.out/'empty.cn1doc'),str(macro),str(out)]
            else:
                command=[wc,'run','--template','blank']
                for cmd,args in steps: command+=['--cmd',cmd+'='+json.dumps(args,separators=(',',':'))]
                command+=['--save',str(out)]
            subprocess.run(command,check=True,capture_output=True,timeout=120)
            with zipfile.ZipFile(out) as z: xml=ET.fromstring(z.read('word/document.xml'))
            body=xml.find(W+'body')
            text=[''.join(t.text or '' for t in para.iter(W+'t')) for para in body.findall(W+'p')]
            assertions={}
            if expected is not None: assertions['paragraphText']=text==expected
            if name=='bold_selection':
                flags=[]
                for run in body.find(W+'p').findall(W+'r'):
                    value=''.join(t.text or '' for t in run.iter(W+'t'))
                    b=run.find(W+'rPr/'+W+'b')
                    bold=b is not None and b.get(W+'val','true') not in ('0','false','off')
                    flags += [bold]*len(value)
                assertions['boldOnlyHello']=flags==[True]*5+[False]*6
            if name=='table_grid':
                tables=body.findall(W+'tbl')
                assertions['twoRowsThreeColumns']=len(tables)==1 and len(tables[0].findall(W+'tr'))==2 and all(len(r.findall(W+'tc'))==3 for r in tables[0].findall(W+'tr'))
            row[side]={'passed':all(assertions.values()),'assertions':assertions,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'artifact':out.name}
        except Exception as e:
            row[side]={'passed':False,'error':str(e)}
    results.append(row)
report={'schema':1,'probes':results,'catalogRowsCertified':0,'fullParity':False}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n')
for row in results:print(row['id'], 'CN1='+str(row['cn1']['passed']),'Wordcraft='+str(row['wordcraft']['passed']))
if not all(r[x]['passed'] for r in results for x in ['cn1','wordcraft']):raise SystemExit(1)
