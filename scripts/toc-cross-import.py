#!/usr/bin/env python3
"""Paired TOC options and two-way DOCX update/remove probes; not full UI parity."""
import argparse
import json, subprocess, pathlib, zipfile, xml.etree.ElementTree as E, hashlib
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=pathlib.Path)
p.add_argument('--out',type=pathlib.Path,required=True)
a=p.parse_args()
root=pathlib.Path(__file__).resolve().parent.parent
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cn=str(root/'scripts/wordprocessor');wc=str(a.wordcraft.resolve())
def run(cmd):
 p=subprocess.run(cmd,text=True,capture_output=True,timeout=60)
 if p.returncode: raise RuntimeError(p.stdout+p.stderr)
 return p.stdout
run([cn,'new',str(out/'empty.cn1doc')])
cnsteps=[('text.insert',{'text':'\nOverview\nDetails'}),('selection.set',{'block':1,'start':0,'end':8}),('styles.apply',{'id':'Heading1'}),('selection.set',{'block':2,'start':0,'end':7}),('styles.apply',{'id':'Heading2'}),('selection.set',{'block':0,'start':0,'end':0})]
wcsteps=[('text.newParagraph',{}),('text.insert',{'text':'Overview'}),('styles.apply',{'style':'Heading 1'}),('text.newParagraph',{}),('text.insert',{'text':'Details'}),('styles.apply',{'style':'Heading 2'}),('caret.docStart',{})]
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
rows=[]
for name,options,follow in [('default',{},[]),('level1',{'levels':1},[]),('no_pages',{'pageNumbers':False},[]),('no_links',{'hyperlinks':False},[]),('hyphen',{'tabLeader':'hyphen'},[]),('inline_pages',{'rightAlign':False},[]),('title',{'title':'Guide'},[]),('update',{},[('references.updateToc',{})]),('remove',{},[('references.removeToc',{})])]:
 row={'scenario':name}
 for side,setup in [('cn1',cnsteps),('wordcraft',wcsteps)]:
  f=out/(name+'-'+side+'.docx');steps=setup+[('references.toc',options)]+follow
  if side=='cn1':
   macro=out/(name+'.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':a} for c,a in steps]}))
   command=[cn,'run',str(out/'empty.cn1doc'),str(macro),str(f)]
  else:
   command=[wc,'run','--template','blank']
   for c,a in steps:command+=['--cmd',c+'='+json.dumps(a)]
   command+=['--save',str(f)]
  try:
   run(command)
   with zipfile.ZipFile(f) as z:doc=E.fromstring(z.read('word/document.xml'))
   entries=[];titles=[]
   for p in doc.findall('.//'+W+'body//'+W+'p'):
    ps=p.find(W+'pPr/'+W+'pStyle');style='' if ps is None else ps.get(W+'val')
    text=''.join(x.text or '' for x in p.iter(W+'t'))
    if style=='TOCHeading': titles.append(text)
    if style in ['TOC'+str(i) for i in range(1,10)]:
     entries.append({'text':text,'style':style,'links':len(p.findall('.//'+W+'hyperlink')),'tabs':[{k.split('}')[-1]:v for k,v in t.attrib.items()} for t in p.findall(W+'pPr/'+W+'tabs/'+W+'tab')]})
   expected=[] if name=='remove' else ['Overview']+([] if name=='level1' else ['Details'])
   assert len(entries)==len(expected),entries
   for entry,heading in zip(entries,expected):
    assert entry['text']==heading+('' if name=='no_pages' else ' 1' if name=='inline_pages' else '1'),entry
    assert bool(entry['links'])==(name!='no_links'),entry
   assert titles==([] if name=='remove' else ['Guide' if name=='title' else 'Contents']),titles
   row[side]={'passed':True,'entries':entries,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
  except Exception as ex:row[side]={'passed':False,'error':str(ex)}
 rows.append(row)
 print(name,{s:row[s]['passed'] for s in ['cn1','wordcraft']},flush=True)
# Reopen in the other implementation and regenerate/remove the imported TOC.
for source in ['cn1','wordcraft']:
 target='wordcraft' if source=='cn1' else 'cn1'
 for action in ['update','remove']:
  name=source+'-to-'+target+'-'+action
  before=out/('default-'+source+'.docx');f=out/(name+'.docx')
  try:
   steps=[('references.'+action+'Toc',{})]
   if action=='update':
    steps=([('selection.set',{'block':4,'start':0,'end':8})] if target=='cn1' else [('select.text',{'text':'Overview','occurrence':2})])+[('text.insert',{'text':'Introduction'})]+steps
   if target=='cn1':
    macro=out/(name+'.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}))
    command=[cn,'run',str(before),str(macro),str(f),'--allow-conversion']
   else:
    command=[wc,'run','--file',str(before)]
    for c,v in steps:command+=['--cmd',c+'='+json.dumps(v)]
    command+=['--save',str(f)]
   run(command)
   with zipfile.ZipFile(f) as z:doc=E.fromstring(z.read('word/document.xml'))
   texts=[];entries=[]
   for para in doc.findall('.//'+W+'body//'+W+'p'):
    style=para.find(W+'pPr/'+W+'pStyle');style='' if style is None else style.get(W+'val')
    text=''.join(t.text or '' for t in para.iter(W+'t'))
    texts.append(text)
    if style in ['TOC1','TOC2']:entries.append(text)
   assert entries==(['Introduction1','Details1'] if action=='update' else []),entries
   assert ('Introduction' if action=='update' else 'Overview') in texts,texts
   assert 'Details' in texts,texts
   row={'scenario':name,'passed':True,'text':texts,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
   if target=='cn1':row['importWarnings']=json.loads(run([cn,'inspect',str(before)]))['warnings']
  except Exception as ex:row={'scenario':name,'passed':False,'error':str(ex)}
  rows.append(row);print(name,row['passed'],flush=True)
(out/'results.json').write_text(json.dumps({'scenarios':rows,'fullParity':False},indent=2)+'\n')
if not all(r.get('passed',all(r[s]['passed'] for s in ['cn1','wordcraft']) if 'cn1' in r else False) for r in rows):raise SystemExit(1)
