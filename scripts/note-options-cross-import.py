#!/usr/bin/env python3
"""Compare effective DOCX note settings in both apps and after two-way editing/export."""
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

def props(parent,kind):
 node=parent.find(W+kind+'Pr'); result={}
 if node is not None:
  for c in node:
   key=c.tag.removeprefix(W)
   if key in ['numFmt','numStart','numRestart','pos']:result[key]=c.get(W+'val')
 return result

def inspect(path,kind,expected):
 with zipfile.ZipFile(path) as z:
  body=E.fromstring(z.read('word/document.xml'));settings=E.fromstring(z.read('word/settings.xml'))
 defaults={'numFmt':'decimal' if kind=='footnote' else 'lowerRoman','numStart':'1','numRestart':'continuous','pos':'pageBottom' if kind=='footnote' else 'docEnd'}
 defaults.update(props(settings,kind))
 sections=[dict(defaults,**props(e,kind)) for e in body.iter(W+'sectPr')]
 assert sections==expected,(path.name,sections,expected)
 assert len(list(body.iter(W+kind+'Reference')))==2
 return {'passed':True,'sections':sections,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True)
rows=[]
scenarios=[
 ('footnote',{'numFmt':'upperRoman','numStart':3,'numRestart':'eachSect','pos':'beneathText'},None),
 ('footnote',{'numFmt':'decimalZero','numStart':7,'numRestart':'eachPage','pos':'pageBottom'},None),
 ('endnote',{'numFmt':'upperLetter','numStart':5,'numRestart':'eachSect','pos':'sectEnd'},None),
 ('endnote',{'numFmt':'lowerRoman','numStart':2,'numRestart':'continuous','pos':'docEnd'},{'numStart':9,'numRestart':'eachSect','pos':'sectEnd'}),
 ('footnote',{'numFmt':'chicago','numStart':1,'numRestart':'continuous','pos':'pageBottom'},{'numFmt':'ordinal','numStart':21,'numRestart':'eachSect'})]
for index,(kind,options,override) in enumerate(scenarios):
 expected=[{k:str(v) for k,v in options.items()} for _ in range(2)]
 if override:expected[1].update({k:str(v) for k,v in override.items()})
 for side in ['cn1','wordcraft']:
  name=f'{index}-{kind}-{side}';stage=name;file=out/(name+'.docx')
  steps=[('text.insert',{'text':'First '}),('references.'+kind,{'text':'First note'}),('layout.break',{'kind':'nextPage'}),('text.insert',{'text':'Second '}),('references.'+kind,{'text':'Second note'}),('references.noteOptions',dict(options,kind=kind))]
  if side=='wordcraft':
   steps.insert(2,('select.range',{'anchor':{'block':0,'off':7},'focus':{'block':0,'off':7}}))
   steps.insert(6,('select.range',{'anchor':{'block':1,'off':8},'focus':{'block':1,'off':8}}))
  if override:steps+=[('references.noteOptions',dict(override,kind=kind,scope='section'))]
  try:
   warning=execute(side,out/'empty.cn1doc' if side=='cn1' else None,steps,file)
   row=inspect(file,kind,expected);row.update(id=name,diagnostics=warning);rows.append(row)
   other='wordcraft' if side=='cn1' else 'cn1';stage=name+'-edited-in-'+other;target=out/(stage+'.docx')
   warning=execute(other,file,[('text.insert',{'text':'Edited '})],target)
   row=inspect(target,kind,expected);row.update(id=stage,diagnostics=warning);rows.append(row)
  except Exception as ex:rows.append({'id':stage,'passed':False,'error':str(ex)})
for row in rows:print(row['id'],row['passed'],row.get('error',''),flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},indent=2)+'\n')
if not all(r['passed'] for r in rows):raise SystemExit(1)
