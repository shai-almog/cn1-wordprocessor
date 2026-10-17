#!/usr/bin/env python3
import json,subprocess,zipfile,xml.etree.ElementTree as E
from pathlib import Path
import argparse
p=argparse.ArgumentParser(description='Matched mail merge workflows and DOCX template interchange');p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);args=p.parse_args();out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
cn=str(Path(__file__).resolve().parent/'wordprocessor');wc=str(args.wordcraft.resolve())
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def run(side,source,steps,target):
 if side=='cn1':
  macro=target.with_suffix('.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':a} for c,a in steps]}));cmd=[cn,'run',str(source),str(macro),str(target),'--allow-conversion']
 else:
  cmd=[wc,'run']+(['--file',str(source)] if source else ['--template','blank'])
  for c,a in steps:cmd+=['--cmd',c+'='+json.dumps(a)]
  cmd+=['--save',str(target)]
 result=subprocess.run(cmd,capture_output=True,text=True,timeout=60)
 if result.returncode:raise RuntimeError(result.stdout+result.stderr)
 return result.stdout

def inspect(path):
 with zipfile.ZipFile(path) as z:
  root=E.fromstring(z.read('word/document.xml'))
  paragraphs=[''.join(n.text or '' if n.tag==W+'t' else '\n' for n in p.iter() if n.tag in [W+'t',W+'br']) for p in root.iter(W+'p')]
  fields=[(n.text or '').strip() for n in root.iter(W+'instrText')]
  fields += [p.get(W+'instr','').strip() for p in root.iter(W+'fldSimple')]
 return {'text':[p.strip() for p in paragraphs if p.strip()], 'fields':[f.replace('"','') for f in fields]}
subprocess.run([cn,'new',str(out/'empty.cn1doc')],check=True,capture_output=True)
rec=('mailings.recipients',{'csv':'First Name,Last Name,City,Flag\nAda,Lovelace,London,yes\nAlan,Turing,Wilmslow,no\nGrace,Hopper,New York,yes'})
cases={
 'preview-greeting':[('mailings.greetingLine',{}),('text.insert',{'text':' / '}),('mailings.insertField',{'field':'City'}),('mailings.preview',{'value':True}),('mailings.next',{})],
 'merge-letters':[('mailings.greetingLine',{}),('text.insert',{'text':' / '}),('mailings.insertField',{'field':'City'}),('mailings.finish',{'from':1,'to':2})],
 'merge-directory':[('mailings.start',{'kind':'directory'}),('mailings.insertField',{'field':'City'}),('mailings.finish',{})],
 'if-preview':[('mailings.rules',{'rule':'IF','field':'Flag','value':'yes','then':'Selected','else':'Other'}),('mailings.preview',{'value':True})],
 'skip-if':[('mailings.rules',{'rule':'SKIPIF','field':'Flag','value':'no'}),('mailings.insertField',{'field':'City'}),('mailings.finish',{})],
 'next-record':[('mailings.insertField',{'field':'City'}),('text.insert',{'text':' / '}),('mailings.rules',{'rule':'NEXT'}),('mailings.insertField',{'field':'City'}),('mailings.finish',{})],
 'address-block':[('mailings.addressBlock',{}),('mailings.finish',{'from':1,'to':1})],
 'envelope':[('mailings.envelopes',{'delivery':'Ada Lovelace\n1 Main Road\nLondon','return':'Sender','size':'DL'})],
 'single-label':[('mailings.labels',{'text':'Ada\nLondon','product':'letter-3x10','single':{'row':2,'col':3}})],
 'recipient-labels':[('mailings.labels',{'product':'a4-2x7','fromRecipients':True})],
 'record-number':[('mailings.rules',{'rule':'MERGEREC'}),('mailings.preview',{'value':True}),('mailings.next',{})],
}
checks=[]
for name,steps in cases.items():
 actual={}
 for side in ['cn1','wordcraft']:
  path=out/(name+'-'+side+'.docx')
  try:run(side,out/'empty.cn1doc' if side=='cn1' else None,[rec]+steps,path);actual[side]=inspect(path)
  except Exception as e:actual[side]={'error':str(e)}
 checks.append({'id':name,'passed':actual['cn1']==actual['wordcraft'],'actual':actual})
 print(name,checks[-1]['passed'],flush=True)
# Editable field interoperability both ways: create template, import, attach data, finish.
for side in ['cn1','wordcraft']:
 template=out/(side+'-template.docx');run(side,out/'empty.cn1doc' if side=='cn1' else None,[rec,('mailings.insertField',{'field':'City'})],template)
 other='wordcraft' if side=='cn1' else 'cn1';target=out/(side+'-template-merged-in-'+other+'.docx')
 try:
  run(other,template,[rec,('mailings.finish',{})],target);actual=inspect(target);passed=actual=={'text':['London','Wilmslow','New York'],'fields':[]}
 except Exception as e:actual={'error':str(e)};passed=False
 checks.append({'id':side+'-template-merged-in-'+other,'passed':passed,'actual':actual});print(checks[-1]['id'],passed,flush=True)
(out/'results.json').write_text(json.dumps({'checks':checks,'fullParity':False},ensure_ascii=False,indent=2)+'\n')
