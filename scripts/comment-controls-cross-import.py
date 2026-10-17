#!/usr/bin/env python3
"""Paired edited table-cell comments and DOCX cross-import; not rendering certification."""
import argparse, hashlib, json, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
r=Path(__file__).resolve().parent.parent;cn1=str(r/'scripts/wordprocessor');wc=str(a.wordcraft.resolve());W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';X='{http://schemas.microsoft.com/office/word/2012/wordml}';results=[]
def run(args):
 result=subprocess.run(list(map(str,args)),text=True,capture_output=True,timeout=120)
 (a.out/'commands.log').open('a').write(json.dumps(list(map(str,args)))+'\n'+result.stdout+result.stderr+'\n')
 result.check_returncode();return result

def macro(source,out,steps):
 path=out.with_suffix('.json');path.write_text(json.dumps({'version':1,'steps':[{'command':name,'args':args} for name,args in steps]}));run([cn1,'run',source,path,out])
def inspect(path,reply,resolved,rootText,replyText):
 def paragraphs(e):return "\n".join("".join(p.itertext()) for p in e.findall(W+"p"))
 with zipfile.ZipFile(path) as z:
  doc=ET.fromstring(z.read('word/document.xml'));comments=ET.fromstring(z.read('word/comments.xml'));extended=ET.fromstring(z.read('word/commentsExtended.xml'))
 cell=doc.find('.//'+W+'tbl/'+W+'tr/'+W+'tc');paragraph=cell.find(W+'p');offset=0;anchors=[]
 for e in paragraph.iter():
  if e.tag==W+'t':offset+=len(e.text or '')
  elif e.tag in (W+'commentRangeStart',W+'commentRangeEnd'):anchors.append((e.tag,offset,e.get(W+'id')))
 assert [(tag,off) for tag,off,_ in anchors]==[(W+'commentRangeStart',6),(W+'commentRangeEnd',10)],anchors
 assert anchors[0][2]==anchors[1][2]
 root=next(c for c in comments if c.get(W+'id')==anchors[0][2]);assert paragraphs(root)==rootText, (paragraphs(root),rootText);assert root.get(W+'author')=='Reviewer'
 assert len(comments)==(2 if reply else 1),ET.tostring(comments)
 if reply:assert any(paragraphs(c)==replyText and c.get(W+'author')=='Reviewer' for c in comments)
 paraId=root.findall(W+'p')[-1].get('{http://schemas.microsoft.com/office/word/2010/wordml}paraId');ex=next(c for c in extended if c.get(X+'paraId')==paraId);assert (ex.get(X+'done') in ('1','true'))==resolved
 if reply:assert any(c.get(X+'paraIdParent')==paraId for c in extended)
 return {'cellText':''.join(paragraph.itertext()),'anchorStart':6,'anchorEnd':10,'reply':reply,'resolved':resolved}
run([cn1,'new',a.out/'empty.cn1doc'])
macro(a.out/'empty.cn1doc',a.out/'fixture.cn1doc',[('text.insert',{'text':'Body'}),('table.insert',{'rows':1,'columns':2}),('table.setCell',{'block':1,'row':0,'column':0,'text':'alpha beta gamma'})])
run([cn1,'convert',a.out/'fixture.cn1doc',a.out/'fixture.docx'])
with zipfile.ZipFile(a.out/'fixture.cn1doc') as z:commentId='c'+ET.fromstring(z.read('document.xml')).get('next')
for name,reply,resolved,rootText,replyText in [('edit-root',True,True,'Edited root\nSecond line','Confirmed'),('edit-reply',True,True,'Check beta','Edited reply\nSecond line'),('blank-lines',True,True,'\nEdited\n\nroot\n','\nEdited reply\n\n')]:
 for side in ['cn1','wordcraft']:
  path=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':
    steps=[('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':6,'end':10}),('comment.add',{'author':'Reviewer','text':'Check beta'})]
    if reply:steps.append(('comment.reply',{'id':commentId,'author':'Reviewer','text':'Confirmed'}))
    if resolved:steps.append(('comment.resolve',{'id':commentId,'resolved':True}))
    if rootText!='Check beta':steps.append(('comment.edit',{'id':commentId,'text':rootText}))
    if replyText!='Confirmed':steps.append(('comment.edit',{'id':commentId,'reply':0,'text':replyText}))
    macro(a.out/'fixture.cn1doc',path,steps)
   else:
    steps=[('select.text',{'text':'beta'}),('review.newComment',{'text':'Check beta'})]
    if reply:steps.append(('review.reply',{'id':0,'text':'Confirmed'}))
    if resolved:steps.append(('review.resolveComment',{'id':0}))
    if rootText!='Check beta':steps.append(('review.editComment',{'id':0,'text':rootText}))
    if replyText!='Confirmed':steps.append(('review.editComment',{'id':1,'text':replyText}))
    args=[wc,'run','--file',a.out/'fixture.docx','--author','Reviewer']
    for command,values in steps:args+=['--cmd',command+'='+json.dumps(values,separators=(',',':'))]
    run(args+['--save',path])
   assertions=inspect(path,reply,resolved,rootText,replyText);results.append({'case':name,'origin':side,'operation':'create','passed':True,'assertions':assertions})
   target=a.out/(name+'-'+side+'-reopened.docx');run([wc if side=='cn1' else cn1,'convert',path,target]+(['--allow-conversion'] if side=='wordcraft' else []))
   assertions=inspect(target,reply,resolved,rootText,replyText);results.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True,'assertions':assertions})
  except Exception as error:results.append({'case':name,'origin':side,'passed':False,'error':str(error)})
report={'boundary':'Paired CLI commands and DOCX anchor/thread structure; not UI or rendered fidelity','checks':results,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(a.out.glob('*.docx'))}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(results,indent=2));raise SystemExit(0 if all(x['passed'] for x in results) else 1)
