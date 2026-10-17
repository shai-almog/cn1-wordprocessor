#!/usr/bin/env python3
"""Exercise footnotes/endnotes in both editors and cross-import their DOCX files."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as E
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True)
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
 result=subprocess.run(command,text=True,capture_output=True,timeout=90)
 if result.returncode:raise AssertionError(result.stdout+result.stderr)
 return result.stderr

def inspect(path,kind,mark):
 with zipfile.ZipFile(path) as z:
  body=E.fromstring(z.read('word/document.xml'));part=E.fromstring(z.read('word/'+kind+'s.xml'))
 refs=body.findall('.//'+W+kind+'Reference');assert len(refs)==1,E.tostring(body).decode()
 note=next(n for n in part.findall(W+kind) if n.get(W+'id')==refs[0].get(W+'id'))
 text=''.join(t.text or '' for t in note.iter(W+'t'))
 assert 'Reference note content' in text,text
 if mark:assert refs[0].get(W+'customMarkFollows') in ['1','true'],E.tostring(refs[0]).decode()
 return {'passed':True,'noteText':text,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True)
rows=[]
for kind in ['footnote','endnote']:
 for mark in ['', '*']:
  for side in ['cn1','wordcraft']:
   name=kind+('-custom' if mark else '')+'-'+side;stage=name;file=out/(name+'.docx')
   command='references.insertNote' if side=='cn1' else 'references.'+kind
   args={'text':'Reference note content','mark':mark}
   if side=='cn1':args['kind']=kind
   try:
    warning=execute(side,out/'empty.cn1doc' if side=='cn1' else None,[('text.insert',{'text':'Anchor '}),(command,args)],file)
    row=inspect(file,kind,mark);row.update(id=name,diagnostics=warning);rows.append(row)
    other='wordcraft' if side=='cn1' else 'cn1';stage=name+'-opened-in-'+other;target=out/(stage+'.docx')
    warning=execute(other,file,[],target)
    row=inspect(target,kind,mark);row.update(id=stage,diagnostics=warning);rows.append(row)
   except Exception as ex:rows.append({'id':stage,'passed':False,'error':str(ex)})
for row in rows:print(row['id'],row['passed'],row.get('error',''),flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},indent=2)+'\n')
if not all(r['passed'] for r in rows):raise SystemExit(1)
