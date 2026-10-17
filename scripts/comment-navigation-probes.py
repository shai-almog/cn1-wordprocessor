#!/usr/bin/env python3
"""Paired caret navigation through body/cell comments, verified with inserted DOCX markers."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path);p.add_argument('fixtures',type=Path,help='cell-comments-cross-import fixture directory');p.add_argument('--out',type=Path,required=True)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve();a.fixtures=a.fixtures.resolve()
r=Path(__file__).resolve().parent.parent;cn=str(r/'scripts/wordprocessor');wc=str(a.wordcraft.resolve());W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';checks=[]
def run(args):
 result=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+result.stdout+result.stderr+'\n')
 result.check_returncode()
def inspect(path,where):
 with zipfile.ZipFile(path) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
 text=lambda node:''.join(t.text or '' for t in node.iter(W+'t'))
 actual=[text(body.find(W+'p')),text(body.find(W+'tbl/'+W+'tr/'+W+'tc'))]
 expected=['B§ody','alpha beta gamma'] if where=='body' else ['Body','alpha §beta gamma']
 assert actual==expected,(actual,expected)
 return actual
for label,nav,where in [('next-body',['next'],'body'),('next-cell',['next','next'],'cell'),('next-wrap',['next','next','next'],'body'),('previous-wrap',['previous'],'cell'),('previous-body',['previous','previous'],'body')]:
 for side in ['cn1','wordcraft']:
  path=a.out/(label+'-'+side+'.docx')
  try:
   if side=='cn1':
    steps=[('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':6,'end':10}),('comment.add',{'author':'Reviewer','text':'Cell'}),('selection.set',{'block':0,'start':1,'end':3}),('comment.add',{'author':'Reviewer','text':'Body'}),('selection.set',{'block':0,'start':0,'end':0})]
    steps += [('comment.'+action,{}) for action in nav]+[('text.insert',{'text':'§'})]
    macro=path.with_suffix('.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}))
    run([cn,'run',a.fixtures/'fixture.cn1doc',macro,path])
   else:
    steps=[('select.text',{'text':'beta'}),('review.newComment',{'text':'Cell'}),('select.text',{'text':'od'}),('review.newComment',{'text':'Body'}),('caret.docStart',{})]
    steps += [('review.'+action+'Comment',{}) for action in nav]+[('text.insert',{'text':'§'})]
    args=[wc,'run','--file',a.fixtures/'fixture.docx','--author','Reviewer','--print']
    for command,values in steps:args+=['--cmd',command+'='+json.dumps(values,separators=(',',':'))]
    run(args+['--save',path])
   values=inspect(path,where);checks.append({'case':label,'origin':side,'operation':'navigate-and-insert','passed':True,'paragraphText':values})
   other=path.with_name(path.stem+'-reopened.docx')
   run([wc if side=='cn1' else cn,'convert',path,other]+(['--allow-conversion'] if side=='wordcraft' else []))
   inspect(other,where);checks.append({'case':label,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as error:checks.append({'case':label,'origin':side,'passed':False,'error':str(error)})
report={'boundary':'Paired command navigation and plain-text marker placement across body/table cells; not UI or rendered parity.','checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.out.glob('*.docx'))}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));raise SystemExit(0 if all(x['passed'] for x in checks) else 1)
