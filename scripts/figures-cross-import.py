#!/usr/bin/env python3
"""Check caption lists in both editors and regenerate each other's DOCX output."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as E
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path)
p.add_argument('--out',type=Path,required=True)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cn=str(Path(__file__).resolve().parent/'wordprocessor');wc=str(a.wordcraft.resolve())
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def execute(side,source,steps,target):
 if side=='cn1':
  macro=target.with_suffix('.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}))
  command=[cn,'run',str(source),str(macro),str(target),'--allow-conversion']
 else:
  command=[wc,'run']+(['--file',str(source)] if source else ['--template','blank'])
  for c,v in steps:command+=['--cmd',c+'='+json.dumps(v)]
  command+=['--save',str(target)]
 r=subprocess.run(command,text=True,capture_output=True,timeout=90)
 if r.returncode:raise AssertionError(r.stdout+r.stderr)
def inspect(file,expected):
 with zipfile.ZipFile(file) as z:doc=E.fromstring(z.read('word/document.xml'))
 entries=[]
 for para in doc.findall('.//'+W+'body//'+W+'p'):
  style=para.find(W+'pPr/'+W+'pStyle')
  if style is not None and style.get(W+'val') in ['ListEntry','TableofFigures']:
   entries.append(''.join(t.text or '' for t in para.iter(W+'t')))
 assert entries==expected,entries
 return {'passed':True,'entries':entries,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()}
subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True)
rows=[]
for label in ['Figure','Table','Equation']:
 for side in ['cn1','wordcraft']:
  name=label+'-'+side;stage=name;file=out/(name+'.docx')
  setup=[('text.insert',{'text':'Object'}),('references.caption',{'label':label,'text':'Chart one'})]
  setup+= [('selection.set',{'block':0,'start':0,'end':0})] if side=='cn1' else [('caret.docStart',{})]
  try:
   execute(side,out/'empty.cn1doc' if side=='cn1' else None,setup+[('references.tableOfFigures',{'label':label})],file)
   row=inspect(file,[label+' 1: Chart one1']);row['id']=name;rows.append(row)
   other='wordcraft' if side=='cn1' else 'cn1';stage=name+'-updated-in-'+other;target=out/(stage+'.docx')
   if other=='wordcraft':steps=[('select.text',{'text':'Chart one','occurrence':2})]
   else:
    info=json.loads(subprocess.run([cn,'inspect',str(file)],check=True,text=True,capture_output=True).stdout)
    block=next((i,b) for i,b in enumerate(info['document']['blocks']) if b.get('style')=='Caption')
    start=len(label)+4
    steps=[('selection.set',{'block':block[0],'start':start,'end':start+9})]
   steps+=[('text.insert',{'text':'Updated'}),('references.updateFigures',{})]
   execute(other,file,steps,target)
   row=inspect(target,[label+' 1: Updated1']);row['id']=name+'-updated-in-'+other;rows.append(row)
  except Exception as ex:rows.append({'id':stage,'passed':False,'error':str(ex)})
for row in rows:print(row['id'],row['passed'],row.get('error',''),flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},indent=2)+'\n')
if not all(r['passed'] for r in rows):raise SystemExit(1)
