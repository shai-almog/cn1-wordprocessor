#!/usr/bin/env python3
"""Paired authority-table generation and regeneration, including edited DOCX interchange."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as E
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cn=str(Path(__file__).resolve().parent/'wordprocessor');wc=str(a.wordcraft.resolve());W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def run(side,source,steps,target):
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

def inspect(path,expected,mark_count):
 with zipfile.ZipFile(path) as z:body=E.fromstring(z.read('word/document.xml'))
 entries=[];marks=[];tables=[]
 for para in body.iter(W+'p'):
  props=para.find(W+'pPr');style=props.find(W+'pStyle') if props is not None else None
  style=style.get(W+'val','') if style is not None else ''
  if style in ['ListEntry','TableofAuthorities']:
   entries.append(''.join(e.text or '' if e.tag==W+'t' else '\t' for run in para.iter(W+'r') for e in run if e.tag in [W+'t',W+'tab']))
 for e in body.iter():
  code=e.get(W+'instr') if e.tag==W+'fldSimple' else e.text if e.tag==W+'instrText' else None
  if code:
   code=code.strip()
   if code.startswith('TA '):marks.append(code)
   if code.startswith('TOA'):tables.append(code)
 assert entries==expected,(path.name,entries,expected)
 assert len(marks)==mark_count,(path.name,marks)
 assert len(tables)==1,(path.name,tables)
 return {'passed':True,'entries':entries,'fields':tables,'marks':marks,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True);rows=[]
for update in [False,True]:
 for side in ['cn1','wordcraft']:
  name=f'{"update" if update else "insert"}-{side}';stage=name;target=out/(name+'.docx')
  select=lambda b,start,end: ('selection.set',{'block':b,'start':start,'end':end}) if side=='cn1' else ('select.range',{'anchor':{'block':b,'off':start},'focus':{'block':b,'off':end}})
  seed=[('text.insert',{'text':'Smith v. Jones\nDoe v. Roe\n'})] if side=='cn1' else [('text.insert',{'text':'Smith v. Jones'}),('text.newParagraph',{}),('text.insert',{'text':'Doe v. Roe'}),('text.newParagraph',{})]
  steps=seed+[select(0,0,14),('references.markCitation',{'entry':'Smith v. Jones'}),select(2,0,0),('references.tableOfAuthorities',{})]
  if update:steps+=[select(1,0,10),('references.markCitation',{'entry':'Doe v. Roe'}),('references.updateAuthorities',{})]
  expected=(['Doe v. Roe\t1'] if update else [])+['Smith v. Jones\t1']
  try:
   warning=run(side,out/'empty.cn1doc' if side=='cn1' else None,steps,target)
   row=inspect(target,expected,2 if update else 1);row.update(id=stage,diagnostics=warning);rows.append(row)
   other='wordcraft' if side=='cn1' else 'cn1';stage=name+'-updated-in-'+other;dest=out/(stage+'.docx')
   warning=run(other,target,[('references.updateAuthorities',{})],dest)
   row=inspect(dest,expected,2 if update else 1);row.update(id=stage,diagnostics=warning);rows.append(row)
  except Exception as ex:rows.append({'id':stage,'passed':False,'error':str(ex)})
for row in rows:print(row['id'],row['passed'],row.get('error',''),flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},indent=2)+'\n')
if not all(r['passed'] for r in rows):raise SystemExit(1)
