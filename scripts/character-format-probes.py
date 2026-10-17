#!/usr/bin/env python3
"""Paired advanced character edits and reciprocal DOCX persistence against pinned Wordcraft."""
import argparse,json,subprocess,zipfile,hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve();cn=Path(__file__).resolve().parent/'wordprocessor';wc=a.wordcraft.resolve();checks=[];W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as f:f.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode()
def macro(name,source,steps,target):
 f=a.out/(name+'.json');f.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}));run([cn,'run',source,f,target])
def read(path,tag,default):
 with zipfile.ZipFile(path) as z:root=ET.fromstring(z.read('word/document.xml'))
 p=root.find(W+'body/'+W+'p'); text=''.join(n.text or '' for n in p.iter(W+'t')); assert text=='Alpha beta gamma',text
 r=next(r for r in p.iter(W+'r') if ''.join(t.text or '' for t in r.iter(W+'t')).startswith('Alpha'))
 e=r.find(W+'rPr/'+W+tag)
 return default if e is None else e.get(W+'val','1')
run([cn,'new',a.out/'empty.cn1doc']);macro('fixture',a.out/'empty.cn1doc',[('text.insert',{'text':'Alpha beta gamma'})],a.out/'fixture.docx')
cases=[('doubleUnderline','true','format.doubleUnderline',{},'u','double','none'),('doubleStrike','true','format.doubleStrikethrough',{'value':True},'dstrike','1','0'),('smallCaps','true','format.smallCaps',{'value':True},'smallCaps','1','0'),('allCaps','true','format.allCaps',{'value':True},'caps','1','0'),('hidden','true','format.hidden',{'value':True},'vanish','1','0'),('characterSpacing','2.5','format.spacing',{'points':2.5},'spacing','50','0'),('characterSpacing','-1','format.spacing',{'points':-1},'spacing','-20','0'),('characterScale','140','format.scale',{'percent':140},'w','140','100'),('characterScale','75','format.scale',{'percent':75},'w','75','100'),('characterPosition','4.5','format.position',{'points':4.5},'position','9','0'),('characterPosition','-3','format.position',{'points':-3},'position','-6','0')]
for value in ['none','single','words','thick','dotted','dash','dotDash','dotDotDash','wave','wavyDouble']:
 cases.append(('underlineStyle',value,'format.underline',{'style':value},'u',value,'none'))
for key,tag in [('outline','outline'),('shadow','shadow'),('emboss','emboss'),('engrave','imprint')]:
 cases.append((key,'true','format.'+key,{'value':True},tag,'1','0'))
for index,(key,value,cmd,args,tag,expected,default) in enumerate(cases):
 for side in ['cn1','wordcraft']:
  name=str(index)+'-'+key+'-'+side;out=a.out/(name+'.docx')
  try:
   if side=='cn1':macro(name,a.out/'fixture.docx',[('selection.set',{'block':0,'start':0,'end':5}),('format.set',{'property':key,'value':value})],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"Alpha"}','--cmd',cmd+'='+json.dumps(args),'--save',out])
   assert read(out,tag,default)==expected,(read(out,tag,default),expected);checks.append({'case':key+'='+value,'side':side,'operation':'edit','passed':True})
   copy=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,copy]+(['--allow-conversion'] if side=='wordcraft' else []))
   assert read(copy,tag,default)==expected,(read(copy,tag,default),expected);checks.append({'case':key+'='+value,'side':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as error:checks.append({'case':key+'='+value,'side':side,'passed':False,'error':str(error)})
for side in ['cn1','wordcraft']:
 name='caret-word-'+side;out=a.out/(name+'.docx')
 try:
  if side=='cn1':macro(name,a.out/'fixture.docx',[('selection.set',{'block':0,'start':2,'end':2}),('format.set',{'property':'smallCaps','value':'true'})],out)
  else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.range={"anchor":{"block":0,"off":2},"focus":{"block":0,"off":2}}','--cmd','format.smallCaps={"value":true}','--save',out])
  with zipfile.ZipFile(out) as z:root=ET.fromstring(z.read('word/document.xml'))
  runs=list(root.find(W+'body/'+W+'p').iter(W+'r'))
  changed=[r for r in runs if r.find(W+'rPr/'+W+'smallCaps') is not None and r.find(W+'rPr/'+W+'smallCaps').get(W+'val','1')!='0']
  assert ''.join(t.text or '' for r in changed for t in r.iter(W+'t'))=='Alpha'
  checks.append({'case':'caret-surrounding-word','side':side,'operation':'edit','passed':True})
 except Exception as error:checks.append({'case':'caret-surrounding-word','side':side,'passed':False,'error':str(error)})
(a.out/'results.json').write_text(json.dumps({'checks':checks,'boundary':__doc__,'renderedEquivalence':False,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.glob('*.docx')}},indent=2)+'\n')
print(json.dumps({'checks':len(checks),'failed':[c for c in checks if not c['passed']]},indent=2));raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
