#!/usr/bin/env python3
"""Paired table alternative-text edits and DOCX preservation."""
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
 result=[]
 for t in body.findall(W+'tbl'):
  props=t.find(W+'tblPr');title=props.find(W+'tblCaption');desc=props.find(W+'tblDescription')
  result.append({'title':'' if title is None else title.get(W+'val'),'text':'' if desc is None else desc.get(W+'val')})
 return result
run([cn,'new',a.out/'empty.cn1doc'])
macro('fixture',a.out/'empty.cn1doc',[('table.insert',{'rows':3,'columns':2}),('table.setCell',{'block':1,'row':1,'column':0,'text':'Target'}),('selection.cell',{'block':1,'row':1,'column':0,'paragraph':0,'start':0,'end':0}),('table.altText',{'title':'Sales','text':'Revenue by region'})],a.out/'fixture.docx')
cases=[('unicode',{'title':'Sales & <growth> "2026" 🗺','text':"Région\nRevenue's share"}),('title-only',{'title':'Updated title'}),('description-only',{'text':'Updated description'}),('clear-title',{'title':''}),('clear-description',{'text':''})]
for name,args in cases:
 expected=dict(title='Sales',text='Revenue by region');expected.update(args)
 for side in ['cn1','wordcraft']:
  out=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':macro(name+'-'+side,a.out/'fixture.docx',[('selection.cell',{'block':1,'row':1,'column':0,'paragraph':0,'start':0,'end':0}),('table.altText',args)],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"Target"}','--cmd','table.altText='+json.dumps(args),'--save',out])
   assert inspect(out)==[expected],(inspect(out),expected);checks.append({'case':name,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []));assert inspect(reopened)==[expected],(inspect(reopened),expected);checks.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as error:checks.append({'case':name,'origin':side,'passed':False,'error':str(error)})
report={'boundary':'Alternative text edit/clear and DOCX cross-reader preservation; no screen-reader certification.','checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.out.glob('*.docx'))}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));raise SystemExit(0 if all(x['passed'] for x in checks) else 1)
