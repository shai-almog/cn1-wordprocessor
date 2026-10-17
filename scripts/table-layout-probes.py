#!/usr/bin/env python3
"""Paired contextual table layout editing and DOCX interchange probes."""
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
 with zipfile.ZipFile(path) as z:t=ET.fromstring(z.read('word/document.xml')).find(W+'body/'+W+'tbl')
 rows=[];heights=[]
 for r in t.findall(W+'tr'):
  row=[];h=r.find(W+'trPr/'+W+'trHeight');heights.append(0 if h is None else float(h.get(W+'val'))/20)
  for c in r.findall(W+'tc'):
   p=c.find(W+'p');align=p.find(W+'pPr/'+W+'jc');vertical=c.find(W+'tcPr/'+W+'vAlign');shade=c.find(W+'tcPr/'+W+'shd')
   row.append({'text':'\n'.join(''.join(x.text or '' for x in p.iter(W+'t')) for p in c.findall(W+'p')),'align':align.get(W+'val') if align is not None else 'left','vertical':vertical.get(W+'val') if vertical is not None else 'top','background':shade.get(W+'fill','FFFFFF').upper() if shade is not None else 'FFFFFF'})
  rows.append(row)
 widths=[float(c.get(W+'w')) for c in t.findall(W+'tblGrid/'+W+'gridCol')];total=sum(widths)
 return {'rows':rows,'heights':heights,'widths':[w/total for w in widths]}
run([cn,'new',a.out/'empty.cn1doc'])
steps=[('table.insert',{'rows':3,'columns':3})]+[('table.setCell',{'block':1,'row':r,'column':c,'text':f'r{r}c{c}'}) for r in range(3) for c in range(3)]+[('table.format',{'block':1,'row':0,'column':0,'columnPercent':60,'minHeight':80}),('table.format',{'block':1,'row':1,'column':1,'background':'123456','minHeight':42})]
macro('fixture',a.out/'empty.cn1doc',steps,a.out/'fixture.docx')
cases=[('insert'+d,{}) for d in ['RowAbove','RowBelow','ColumnLeft','ColumnRight']]+[('distributeRows',{}),('distributeColumns',{})]+[('cellAlign',{'value':v}) for v in ['topLeft','topCenter','topRight','centerLeft','center','centerRight','bottomLeft','bottomCenter','bottomRight']]
for command,args in cases:
 name=command+('-'+args['value'] if args else '')
 for side in ['cn1','wordcraft']:
  out=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':macro(name+'-'+side,a.out/'fixture.docx',[('selection.cell',{'block':1,'row':1,'column':1,'paragraph':0,'start':0,'end':0}),('table.'+command,args)],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','select.text={"text":"r1c1"}','--cmd','table.'+command+'='+json.dumps(args),'--save',out])
   expected=[[{'text':f'r{r}c{c}','align':'left','vertical':'top','background':'123456' if r==c==1 else 'FFFFFF'} for c in range(3)] for r in range(3)]
   if command.startswith('insert'):
    row='Row' in command;at=2 if command.endswith(('Below','Right')) else 1;blank={'text':'','align':'left','vertical':'top','background':'FFFFFF'}
    if row:expected.insert(at,[dict(blank,background='123456' if c==1 else 'FFFFFF') for c in range(3)])
    else:
     for r in expected:r.insert(at,dict(blank))
   elif command=='cellAlign':
    v=args['value'];expected[1][1]['align']='left' if v.endswith('Left') else 'right' if v.endswith('Right') else 'center';expected[1][1]['vertical']='top' if v.startswith('top') else 'bottom' if v.startswith('bottom') else 'center'
   actual=inspect(out);assert actual['rows']==expected,(side,name,actual['rows'],expected)
   if command=='distributeColumns':assert max(actual['widths'])-min(actual['widths'])<.001,actual
   if command=='distributeRows':assert actual['heights']==[80]*3,actual
   if command.startswith('insertRow'):
    heights=[80,42,0];heights.insert(at,42);assert actual['heights']==heights,actual
   checks.append({'case':name,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
   re=inspect(reopened);assert re['rows']==actual['rows'] and re['heights']==actual['heights'],(name,side,re,actual)
   assert len(re['widths'])==len(actual['widths']) and all(abs(x-y)<.001 for x,y in zip(re['widths'],actual['widths'])),(name,side,re,actual)
   checks.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as e:checks.append({'case':name,'origin':side,'passed':False,'error':str(e)})
report={'boundary':__doc__+' These semantic probes do not certify rendered equivalence or all table workflows.','checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.glob('*.docx')}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
