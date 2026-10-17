#!/usr/bin/env python3
"""Paired bar-tab DOCX interchange checks; this is not a rendered-layout comparison."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',required=True,type=Path);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve()
root=Path(__file__).resolve().parent.parent;cn=root/'scripts/wordprocessor';wc=a.wordcraft.resolve();ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'};W='{'+ns['w']+'}';checks=[]
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=90)
 with (a.out/'commands.log').open('a') as f:f.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode()
def macro(label,source,steps,target):
 path=a.out/(label+'.json');path.write_text(json.dumps({'version':1,'steps':[{'command':x,'args':y} for x,y in steps]}));run([cn,'run',source,path,target])
def read(path):
 with zipfile.ZipFile(path) as z:
  doc=ET.fromstring(z.read('word/document.xml'));props=doc.find('w:body/w:p/w:pPr',ns)
  tabs=[(round(float(t.get(W+'pos'))/20,2),t.get(W+'val'),t.get(W+'leader','none')) for t in props.findall('w:tabs/w:tab',ns) if t.get(W+'val')!='clear']
  settings=ET.fromstring(z.read('word/settings.xml')) if 'word/settings.xml' in z.namelist() else None
  stop=settings.find('w:defaultTabStop',ns) if settings is not None else None
  interval=float(stop.get(W+'val'))/20 if stop is not None else 36
  return {'tabs':tabs,'default':interval}
run([cn,'new',a.out/'empty.cn1doc'])
macro('fixture',a.out/'empty.cn1doc',[('text.insert',{'text':'Item\tAmount'})],a.out/'fixture.docx')
for name,stops,interval in [('bar',[(72,'bar','none')],36),('bars',[(48,'bar','none'),(144,'bar','none')],36),('bar-and-right',[(72,'bar','none'),(144,'right','dot')],36),('nondefault',[(48,'bar','none')],48)]:
 for side in ['cn1','wordcraft']:
  out=a.out/(name+'-'+side+'.docx')
  try:
   if side=='cn1':macro(name+'-'+side,a.out/'fixture.docx',[('selection.set',{'block':0,'start':0,'end':0}),('paragraph.set',{'property':'tabs','value':';'.join(f'{pos},{align},{leader}' for pos,align,leader in stops)}),('paragraph.set',{'property':'tabInterval','value':str(interval)})],out)
   else:run([wc,'run','--file',a.out/'fixture.docx','--cmd','para.tabs='+json.dumps({'tabs':[{'pos':pos,'align':align,'leader':leader} for pos,align,leader in stops],'default':interval}),'--save',out])
   assert read(out)=={'tabs':stops,'default':interval},(name,side,read(out),stops,interval);checks.append({'case':name,'origin':side,'operation':'edit','passed':True})
   reopened=out.with_name(out.stem+'-reopened.docx');run([wc if side=='cn1' else cn,'convert',out,reopened]+(['--allow-conversion'] if side=='wordcraft' else []))
   assert read(reopened)=={'tabs':stops,'default':interval},(name,side,read(reopened),stops,interval);checks.append({'case':name,'origin':side,'operation':'other-reader-roundtrip','passed':True})
  except Exception as error:checks.append({'case':name,'origin':side,'passed':False,'error':str(error)})
report={'boundary':__doc__,'checks':checks,'artifacts':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in a.out.glob('*.docx')}}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));raise SystemExit(0 if all(c['passed'] for c in checks) else 1)
