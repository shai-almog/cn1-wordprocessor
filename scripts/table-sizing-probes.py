#!/usr/bin/env python3
"""Paired table width/placement/AutoFit commands and reciprocal DOCX property checks.

Widths are checked semantically. Different font metrics mean contents widths need
not be numerically identical; this is not a rendered-equivalence certification.
"""
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
 with zipfile.ZipFile(path) as z:table=ET.fromstring(z.read('word/document.xml')).find(W+'body/'+W+'tbl')
 props=table.find(W+'tblPr')
 def attr(name,key,default):
  e=props.find(W+name);return e.get(W+key,default) if e is not None else default
 return {'unit':attr('tblW','type','auto'),'width':float(attr('tblW','w','0')),'align':attr('jc','val','left'),'indent':float(attr('tblInd','w','0')),'fixed':attr('tblLayout','type','autofit')=='fixed','grid':[float(e.get(W+'w')) for e in table.findall(W+'tblGrid/'+W+'gridCol')],'text':[''.join(t.text or '' for t in e.iter(W+'t')) for e in table.findall('.//'+W+'tc')]}
run([cn,'new',a.out/'empty.cn1doc']);macro('fixture',a.out/'empty.cn1doc',[('table.insert',{'rows':2,'columns':2}),('table.setCell',{'block':1,'row':0,'column':0,'text':'alpha'}),('table.setCell',{'block':1,'row':0,'column':1,'text':'longer phrase'})],a.out/'fixture.docx')
cases=[]
for align in ['left','center','right']:
 for prop in [{'width':180},{'widthPct':60}]:cases.append((align+'-'+next(iter(prop)),'properties',dict(prop,align=align,indent=18)))
for mode in ['contents','window','fixed']:cases.append((mode,'autofit',{'mode':mode}))
for name,command,args in cases:
 for side in ['cn1','wordcraft']:
  out=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':macro(name+'-'+side,a.out/'fixture.docx',[('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':0,'end':0}),('table.'+command,args)],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"alpha"}','--cmd','table.'+command+'='+json.dumps(args),'--save',out])
   value=inspect(out);assert value['text']==['alpha','longer phrase','',''],value
   if command=='properties':
    assert value['align']==args['align'] and value['indent']==360,value
    assert value['unit']==('pct' if 'widthPct' in args else 'dxa') and value['width']==(3000 if 'widthPct' in args else 3600),value
   else:
    assert value['fixed']==(name=='fixed'),value
    if name=='contents':assert sum(value['grid'])<5000 and value['grid'][1]>value['grid'][0]>0,value
    elif name=='window':assert value['unit']=='pct' and value['width']==5000,value
   checks.append({'case':name,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
   re=inspect(reopened)
   for key in ['unit','width','align','indent','fixed','text']:assert re[key]==value[key],(key,re,value)
   assert len(re['grid'])==len(value['grid']) and all(abs(x-y)<=1 for x,y in zip(re['grid'],value['grid'])),(re,value)
   checks.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as e:checks.append({'case':name,'origin':side,'passed':False,'error':str(e)})
report={'boundary':__doc__,'checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.glob('*.docx')}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'checks':len(checks),'failed':[c for c in checks if not c['passed']]},indent=2));raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
