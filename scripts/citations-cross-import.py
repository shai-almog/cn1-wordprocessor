#!/usr/bin/env python3
"""Compare citation styles and edited DOCX source libraries in both applications."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as E
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True)
a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cn=str(Path(__file__).resolve().parent/'wordprocessor');wc=str(a.wordcraft.resolve())
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';B='{http://schemas.openxmlformats.org/officeDocument/2006/bibliography}'
styles=['APA','Chicago','GB/T 7714','GOST (name sort)','GOST (title sort)','Harvard (Anglia)','IEEE','ISO 690 (author-date)','ISO 690 (numerical)','MLA','SIST02','Turabian']
def run(side,source,steps,target):
 if side=='cn1':
  macro=target.with_suffix('.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}))
  command=[cn,'run',str(source),str(macro),str(target),'--allow-conversion']
 else:
  command=[wc,'run']+(['--file',str(source)] if source else ['--template','blank'])
  for c,v in steps:command+=['--cmd',c+'='+json.dumps(v)]
  command+=['--save',str(target)]
 result=subprocess.run(command,text=True,capture_output=True,timeout=90)
 if result.returncode:raise AssertionError(result.stdout+result.stderr)
 return result.stderr

def inspect(path):
 with zipfile.ZipFile(path) as z:
  root=E.fromstring(z.read('word/document.xml'));sources=[]
  for name in z.namelist():
   if name.startswith('customXml/') and name.endswith('.xml'):
    data=E.fromstring(z.read(name))
    if data.tag==B+'Sources':sources += [s.findtext(B+'Tag') for s in data.findall(B+'Source')]
 paragraphs=[]
 for para in root.iter(W+'p'):
  text=''.join(e.text or '' if e.tag==W+'t' else '\t' for run in para.iter(W+'r') for e in run if e.tag in [W+'t',W+'tab'])
  if text.strip():paragraphs.append(text.strip())
 return {'paragraphs':paragraphs,'sources':sorted(sources)}

subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True)
rows=[]
for i,style in enumerate(styles):
 outputs={}
 for side in ['cn1','wordcraft']:
  target=out/(str(i)+'-'+side+'.docx')
  newline=('text.insert',{'text':'\n'}) if side=='cn1' else ('text.newParagraph',{})
  steps=[('references.citationStyle',{'style':style}),('references.citation',{'source':{'tag':'A','author':'Rivera, Alex; Chen, Mei','title':'Shared Spaces','year':'2021','publisher':'Harbor Books','city':'Portland'},'pages':'12'}),newline,
   ('references.citation',{'source':{'tag':'B','kind':'article','author':'Adams, Ann','title':'A Study','year':'2022','journal':'Journal of Examples','volume':'7','pages':'15–28','url':'https://example.org/study'}}),newline,('references.bibliography',{'title':'References'})]
  warning=run(side,out/'empty.cn1doc' if side=='cn1' else None,steps,target)
  outputs[side]=inspect(target)
 rows.append({'id':style+'-generation','passed':outputs['cn1']==outputs['wordcraft'],'actual':outputs})
 for side in ['cn1','wordcraft']:
  other='wordcraft' if side=='cn1' else 'cn1';target=out/(str(i)+'-'+side+'-updated-in-'+other+'.docx')
  try:
   warning=run(other,out/(str(i)+'-'+side+'.docx'),[('references.citationStyle',{'style':style})],target)
   actual=inspect(target);rows.append({'id':style+'-'+side+'-updated-in-'+other,'passed':actual==outputs['wordcraft'],'actual':actual,'diagnostics':warning})
  except Exception as ex:rows.append({'id':style+'-'+side+'-updated-in-'+other,'passed':False,'error':str(ex)})
 print(style,[(r['id'],r['passed']) for r in rows[-3:]],flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},ensure_ascii=False,indent=2)+'\n')
if not all(r['passed'] for r in rows):raise SystemExit(1)
