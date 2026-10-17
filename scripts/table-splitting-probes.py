#!/usr/bin/env python3
"""Paired cell/table splitting with independent DOCX geometry and cross-reader checks."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
root=Path(__file__).resolve().parent.parent;cn=root/'scripts/wordprocessor';wc=a.wordcraft.resolve();W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';checks=[]
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode()
def macro(name,source,steps,target):
 f=a.out/(name+'.json');f.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}));run([cn,'run',source,f,target])
def inspect(path):
 with zipfile.ZipFile(path) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
 tables=[]
 for t in body.findall(W+'tbl'):
  widths=[float(c.get(W+'w')) for c in t.findall(W+'tblGrid/'+W+'gridCol')];total=sum(widths);rows=[]
  for r in t.findall(W+'tr'):
   row=[];col=0
   for c in r.findall(W+'tc'):
    span=c.find(W+'tcPr/'+W+'gridSpan');n=1 if span is None else int(span.get(W+'val'))
    row.append({'text':'\n'.join(''.join(x.text or '' for x in p.iter(W+'t')) for p in c.findall(W+'p')),'width':sum(widths[col:col+n])/total});col+=n
   rows.append(row)
  tables.append(rows)
 return tables

def equivalent(actual,expected):
 assert len(actual)==len(expected),(actual,expected)
 for a,b in zip(actual,expected):
  assert len(a)==len(b),(actual,expected)
  for ar,br in zip(a,b):
   assert len(ar)==len(br),(actual,expected)
   for ac,bc in zip(ar,br):assert ac['text']==bc['text'] and abs(ac['width']-bc['width'])<.001,(ac,bc)
run([cn,'new',a.out/'empty.cn1doc'])
steps=[('table.insert',{'rows':4,'columns':3})]+[('table.setCell',{'block':1,'row':r,'column':c,'text':f'r{r}c{c}'}) for r in range(4) for c in range(3)]+[('table.format',{'block':1,'row':0,'column':0,'columnPercent':50})]
macro('fixture',a.out/'empty.cn1doc',steps,a.out/'fixture.docx')
macro('merged',a.out/'fixture.docx',[('table.merge',{'block':1,'row':1,'column':0,'rows':1,'columns':2})],a.out/'merged.docx')
for case,kind,count,row in [('cell-two','cell',2,1),('cell-five','cell',5,1),('merged-four','merged',4,1),('table-second','table',0,1),('table-last','table',0,3)]:
 source=a.out/('merged.docx' if kind=='merged' else 'fixture.docx');original=inspect(source);expected=json.loads(json.dumps(original))
 column=0 if kind=='merged' else 1
 if kind=='table':expected=[original[0][:row],original[0][row:]]
 else:
  cell=expected[0][row][column];expected[0][row][column:column+1]=[{'text':cell['text'] if n==0 else '', 'width':cell['width']/count} for n in range(count)]
 for side in ['cn1','wordcraft']:
  out=a.out/(case+'-'+side+'.docx')
  try:
   action='table.splitTable' if kind=='table' else 'table.split';args={} if kind=='table' else {'columns':count}
   if side=='cn1':macro(case+'-'+side,source,[('selection.cell',{'block':1,'row':row,'column':column,'paragraph':0,'start':0,'end':0}),(action,args)],out)
   else:run([wc,'run','--file',source,'--cmd','select.text='+json.dumps({'text':f'r{row}c{column}'}),'--cmd',action+'='+json.dumps(args),'--save',out])
   equivalent(inspect(out),expected);checks.append({'case':case,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
   equivalent(inspect(reopened),expected);checks.append({'case':case,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as error:checks.append({'case':case,'origin':side,'passed':False,'error':str(error)})
report={'boundary':'Cell subdivision, neighboring cell widths and table splitting with DOCX cross-reader checks; not rendered equivalence or full parity.','checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.out.glob('*.docx'))}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));raise SystemExit(0 if all(x['passed'] for x in checks) else 1)
