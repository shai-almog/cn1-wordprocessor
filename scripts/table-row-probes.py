#!/usr/bin/env python3
"""Paired row heights and page-break controls with reciprocal DOCX checks.

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
 rows=[]
 for row in table.findall(W+'tr'):
  props=row.find(W+'trPr');h=props.find(W+'trHeight') if props is not None else None;c=props.find(W+'cantSplit') if props is not None else None
  rows.append({'height':float(h.get(W+'val','0')) if h is not None else 0,'rule':h.get(W+'hRule','atLeast') if h is not None else 'auto','allowBreak':c is None or c.get(W+'val','true') in ['false','0','off']})
 return {'rows':rows,'text':[''.join(t.text or '' for t in e.iter(W+'t')) for e in table.findall('.//'+W+'tc')]}
run([cn,'new',a.out/'empty.cn1doc']);macro('fixture',a.out/'empty.cn1doc',[('table.insert',{'rows':2,'columns':2}),('table.setCell',{'block':1,'row':0,'column':0,'text':'alpha\nsecond\nthird'}),('table.setCell',{'block':1,'row':0,'column':1,'text':'longer phrase'})],a.out/'fixture.docx')
cases=[('tight-exact','rowHeight',{'height':12,'rule':'exact'}),('height-minimum','rowHeight',{'height':36,'rule':'atLeast'}),('height-exact','rowHeight',{'height':18,'rule':'exact'}),('no-break','properties',{'allowBreak':False}),('allow-break','properties',{'allowBreak':True}),('exact-no-break','properties',{'rowHeight':24,'rowHeightRule':'exact','allowBreak':False})]
for name,command,args in cases:
 for side in ['cn1','wordcraft']:
  out=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':macro(name+'-'+side,a.out/'fixture.docx',[('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':0,'end':0}),*([('paragraph.set',{'property':'spacing','value':'0.5'})] if name=='tight-exact' else []),('table.'+command,args)],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"alpha"}',*(['--cmd','para.lineSpacing={"value":0.5}'] if name=='tight-exact' else []),'--cmd','table.'+command+'='+json.dumps(args),'--save',out])
   value=inspect(out);assert value['text']==['alphasecondthird','longer phrase','',''],value
   if name=='tight-exact':
    with zipfile.ZipFile(out) as z:
     spacing=ET.fromstring(z.read('word/document.xml')).find('.//'+W+'tc/'+W+'p/'+W+'pPr/'+W+'spacing')
     assert spacing is not None and spacing.get(W+'line')=='120'
   row=value['rows'][0]
   expected_height=args.get('height',args.get('rowHeight',0))*20
   expected_rule=args.get('rule',args.get('rowHeightRule','auto'))
   assert row=={'height':expected_height,'rule':expected_rule,'allowBreak':args.get('allowBreak',True)},value
   checks.append({'case':name,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
   re=inspect(reopened)
   assert re==value,(re,value)
   checks.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as e:checks.append({'case':name,'origin':side,'passed':False,'error':str(e)})
report={'boundary':__doc__,'checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.glob('*.docx')}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'checks':len(checks),'failed':[c for c in checks if not c['passed']]},indent=2));raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
