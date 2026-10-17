#!/usr/bin/env python3
"""Run matching index scenarios and edited two-way DOCX interchange against a pinned Wordcraft CLI."""
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

def inspect(path,expected):
 with zipfile.ZipFile(path) as z:body=E.fromstring(z.read('word/document.xml'))
 entries=[];xe=[];index=[]
 for para in body.iter(W+'p'):
  props=para.find(W+'pPr');style=props.find(W+'pStyle') if props is not None else None
  style=style.get(W+'val','') if style is not None else ''
  if style=='ListEntry' or style=='IndexLetter' or style.startswith('Index') and style!='IndexHeading':
   entries.append(''.join(e.text or '' if e.tag==W+'t' else '\t' for run in para.iter(W+'r') for e in run if e.tag in [W+'t',W+'tab']))
 for e in body.iter():
  code=e.get(W+'instr') if e.tag==W+'fldSimple' else e.text if e.tag==W+'instrText' else None
  if code:
   code=code.strip()
   if code.startswith('XE '):xe.append(code)
   if code.startswith('INDEX'):index.append(code)
 assert entries==expected,(path.name,entries,expected)
 assert len(xe)==3,(path.name,xe)
 assert len(index)==1,(path.name,index)
 return {'passed':True,'entries':entries,'index':index,'marks':xe,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True)
rows=[]
for number,options in enumerate([{'type':'indented','headings':True,'rightAlign':False},{'type':'runIn','headings':False,'rightAlign':True,'tabLeader':'hyphen'}]):
 expected=['K','Kilns, 1','firing, 1','P','Presses. See Kilns'] if number==0 else ['Kilns\t1: firing, 1','Presses. See Kilns']
 for side in ['cn1','wordcraft']:
  name=f'{number}-{side}';stage=name;target=out/(name+'.docx')
  select=lambda b,start,end: ('selection.set',{'block':b,'start':start,'end':end}) if side=='cn1' else ('select.range',{'anchor':{'block':b,'off':start},'focus':{'block':b,'off':end}})
  steps=[('text.insert',{'text':'Kilns\nPresses\n'}),select(0,0,5),('references.markEntry',{'entry':'Kilns'}),select(0,0,5),('references.markEntry',{'entry':'Kilns','subentry':'firing','bold':True}),select(1,0,7),('references.markEntry',{'entry':'Presses','crossReference':'See Kilns'}),select(2,0,0),('references.index',options)]
  if side=='wordcraft':steps[:1]=[('text.insert',{'text':'Kilns'}),('text.newParagraph',{}),('text.insert',{'text':'Presses'}),('text.newParagraph',{})]
  try:
   warning=run(side,out/'empty.cn1doc' if side=='cn1' else None,steps,target)
   row=inspect(target,expected);row.update(id=stage,diagnostics=warning);rows.append(row)
   other='wordcraft' if side=='cn1' else 'cn1';stage=name+'-updated-in-'+other;dest=out/(stage+'.docx')
   warning=run(other,target,[('references.updateIndex',{})],dest)
   row=inspect(dest,expected);row.update(id=stage,diagnostics=warning);rows.append(row)
  except Exception as ex:rows.append({'id':stage,'passed':False,'error':str(ex)})
for row in rows:print(row['id'],row['passed'],row.get('error',''),flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},indent=2)+'\n')
if not all(r['passed'] for r in rows):raise SystemExit(1)
