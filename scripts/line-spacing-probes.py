#!/usr/bin/env python3
"""Paired spacing edits and reciprocal DOCX checks against the pinned Wordcraft CLI.
Semantic rule/value and text checks; rendered equivalence is a separate gate.
"""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
cn=Path(__file__).resolve().parent/'wordprocessor';wc=a.wordcraft.resolve();W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';checks=[]
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode()
def macro(name,source,steps,target):
 f=a.out/(name+'.json');f.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}));run([cn,'run',source,f,target])
def inspect(path):
 with zipfile.ZipFile(path) as z:root=ET.fromstring(z.read('word/document.xml'))
 para=root.find(W+'body/'+W+'p');spacing=para.find(W+'pPr/'+W+'spacing')
 return {'rule':spacing.get(W+'lineRule','auto'),'amount':float(spacing.get(W+'line')),'text':''.join(t.text or '' for t in para.iter(W+'t'))}
run([cn,'new',a.out/'empty.cn1doc']);macro('fixture',a.out/'empty.cn1doc',[('text.insert',{'text':'Spacing fixture with text that remains editable.'})],a.out/'fixture.docx')
for name,rule,value,wargs in [('multiple','auto',1.5,{'value':1.5}),('tight-multiple','auto',.5,{'value':.5}),('minimum','atLeast',24,{'atLeast':24}),('exact','exact',24,{'exactly':24}),('tight-exact','exact',6,{'exactly':6}),('fractional','exact',18.5,{'exactly':18.5})]:
 for side in ['cn1','wordcraft']:
  out=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':macro(name+'-'+side,a.out/'fixture.docx',[('selection.set',{'block':0,'start':0,'end':7}),('paragraph.spacing',{'rule':rule,'value':value})],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"Spacing"}','--cmd','para.lineSpacing='+json.dumps(wargs),'--save',out])
   expected={'rule':rule,'amount':value*(240 if rule=='auto' else 20),'text':'Spacing fixture with text that remains editable.'}
   got=inspect(out);assert got==expected,(got,expected);checks.append({'case':name,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
   assert inspect(reopened)==expected,inspect(reopened);checks.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as e:checks.append({'case':name,'origin':side,'passed':False,'error':str(e)})
(a.out/'results.json').write_text(json.dumps({'boundary':__doc__,'checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.glob('*.docx')}},indent=2)+'\n')
print(json.dumps({'checks':len(checks),'failed':[c for c in checks if not c['passed']]},indent=2));raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
