#!/usr/bin/env python3
"""Compare and resolve matching text fixtures in both implementations and both DOCX directions."""
import argparse,json,subprocess,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
root=Path(__file__).resolve().parent.parent;cn1=str(root/'scripts/wordprocessor');wc=str(a.wordcraft.resolve());W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}';results=[]
def run(command):return subprocess.run(command,check=True,capture_output=True,text=True,timeout=120)
def cn_run(source,steps,destination):
 macro=destination.with_suffix('.json');macro.write_text(json.dumps({'version':1,'steps':steps}));run([cn1,'run',str(source),str(macro),str(destination),'--allow-conversion'])
def paragraphs(path):
 with zipfile.ZipFile(path) as z:body=ET.fromstring(z.read('word/document.xml')).find(W+'body')
 return [''.join(n.text or '' for n in para.iter(W+'t')) for para in body.findall(W+'p')]
cases=[('word','The cat sat.\nKeep this.','The dog sat.\nKeep this.\nNew line here.',{}),('character','colour 😀','color 😁',{'level':'character'}),('delete','First\nSecond\nThird','First\nThird',{}),('insert','First\nThird','First\nSecond\nThird',{}),('ignore-case','Hello World','HELLO world',{'caseChanges':False}),('ignore-spacing','one   two','one two',{'whiteSpace':False})]
for name,old,new,options in cases:
 source=a.out/(name+'.txt');source.write_text(old);options=dict(options,text=new,author='Comparison Reviewer')
 for origin in ['cn1','wordcraft']:
  compared=a.out/(name+'-'+origin+'.docx')
  if origin=='cn1':cn_run(source,[{'command':'review.compare','args':options}],compared)
  else:run([wc,'run','--file',str(source),'--cmd','review.compare='+json.dumps(options),'--save',str(compared)])
  for target in ['cn1','wordcraft']:
   for accept in [True,False]:
    action='review.acceptAll' if accept else 'review.rejectAll';ident=name+'-'+origin+'-to-'+target+('-accept' if accept else '-reject');dest=a.out/(ident+'.docx');row={'id':ident}
    try:
     if target=='cn1':cn_run(compared,[{'command':action,'args':{}}],dest)
     else:run([wc,'run','--file',str(compared),'--cmd',action+'={}','--save',str(dest)])
     expected=(new if accept or name.startswith('ignore-') else old).split('\n');actual=paragraphs(dest);row.update(passed=actual==expected,expected=expected,actual=actual)
    except Exception as e:row.update(passed=False,error=str(e),stderr=getattr(e,'stderr',''))
    results.append(row)
(a.out/'results.json').write_text(json.dumps({'checks':results,'fullParity':False},indent=2)+'\n');print(json.dumps({'passed':sum(x['passed'] for x in results),'failed':sum(not x['passed'] for x in results)}))
