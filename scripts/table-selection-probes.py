#!/usr/bin/env python3
"""Compare table range editing and cross-reader DOCX preservation with Wordcraft."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
r=Path(__file__).resolve().parent.parent;cn=str(r/'scripts/wordprocessor');wc=str(a.wordcraft.resolve());W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';checks=[]
def run(args):
 result=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+result.stdout+result.stderr+'\n')
 result.check_returncode()
def macro(name,steps,source,out):
 path=a.out/(name+'.json');path.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}))
 run([cn,'run',source,path,out])
def inspect(path):
 with zipfile.ZipFile(path) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
 tables=[]
 for table in body.findall(W+'tbl'):
  rows=[]
  for row in table.findall(W+'tr'):
   cells=[]
   for cell in row.findall(W+'tc'):
    text='\n'.join(''.join(t.text or '' for t in para.iter(W+'t')) for para in cell.findall(W+'p'))
    bold=[]
    for run in cell.iter(W+'r'):
     if not ''.join(t.text or '' for t in run.iter(W+'t')):continue
     b=run.find(W+'rPr/'+W+'b');bold.append(b is not None and b.get(W+'val','1') not in ('0','false','off'))
    align=[(para.find(W+'pPr/'+W+'jc').get(W+'val') if para.find(W+'pPr/'+W+'jc') is not None else 'left') for para in cell.findall(W+'p')]
    cells.append({'text':text,'bold':bool(bold) and all(bold),'align':align})
   rows.append(cells)
  tables.append(rows)
 return tables
run([cn,'new',a.out/'empty.cn1doc'])
steps=[('table.insert',{'rows':3,'columns':3})]+[('table.setCell',{'block':1,'row':row,'column':col,'text':f'r{row}c{col}'}) for row in range(3) for col in range(3)]
macro('fixture',steps,a.out/'empty.cn1doc',a.out/'fixture.docx')
for scope in ['Row','Column','Table']:
 for operation in ['bold','clear','replace','align']:
  case=scope.lower()+'-'+operation;outputs={}
  expected=[[{'text':f'r{row}c{col}','bold':False,'align':['left']} for col in range(3)] for row in range(3)]
  picked=[(row,col) for row in range(3) for col in range(3) if scope=='Table' or (row==1 if scope=='Row' else col==1)]
  for row,col in picked:
   if operation=='bold':expected[row][col]['bold']=True
   elif operation=='align':expected[row][col]['align']=['center']
   else:expected[row][col]['text']='MARK' if operation=='replace' and (row,col)==picked[0] else ''
  for side in ['cn1','wordcraft']:
   path=a.out/(case+'-'+side+'.docx')
   try:
    if side=='cn1':
     actions=[('selection.cell',{'block':1,'row':1,'column':1,'paragraph':0,'start':0,'end':0}),('table.select'+scope,{})]
     actions += {'bold':[('format.set',{'property':'bold','value':'true'})],'clear':[('table.clearCells',{})],'replace':[('text.insert',{'text':'MARK'})],'align':[('paragraph.set',{'property':'align','value':'center'})]}[operation]
     macro(case+'-'+side,actions,a.out/'fixture.docx',path)
    else:
     actions=[('select.text',{'text':'r1c1'}),('table.select'+scope,{})]+{'bold':[('format.bold',{'value':True})],'clear':[('text.delete',{})],'replace':[('text.insert',{'text':'MARK'})],'align':[('para.align',{'value':'center'})]}[operation]
     args=[wc,'run','--file',a.out/'fixture.docx']
     for command,values in actions:args+=['--cmd',command+'='+json.dumps(values,separators=(',',':'))]
     run(args+['--save',path])
    actual=inspect(path);assert actual==[expected],(case,side,actual,expected)
    outputs[side]=actual;checks.append({'case':case,'origin':side,'operation':'edit','passed':True})
    other=path.with_name(path.stem+'-reopened.docx')
    run([wc if side=='cn1' else cn,'convert',path,other]+(['--allow-conversion'] if side=='wordcraft' else []))
    assert inspect(other)==actual,(case,side,inspect(other),actual)
    checks.append({'case':case,'origin':side,'operation':'other-reader-roundtrip','passed':True})
   except Exception as error:checks.append({'case':case,'origin':side,'passed':False,'error':str(error)})
report={'boundary':'Table row, column and whole-grid text/format editing plus independent DOCX and cross-reader checks; not visual or full feature parity.','checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.out.glob('*.docx'))}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));raise SystemExit(0 if all(x['passed'] for x in checks) else 1)
