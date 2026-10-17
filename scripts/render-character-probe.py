#!/usr/bin/env python3
"""Render an advanced font specimen in both engines; retain pages and text geometry.
Relative scaling, hidden text and source-preserving case effects are checked in CN1.
The reference renders are evidence for visual review, not an equivalence certificate.
"""
import argparse,json,subprocess,hashlib,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False);a.out=a.out.resolve();cn=Path(__file__).resolve().parent/'wordprocessor'
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode();return r.stdout
cases=[('Normal',{}),('Small caps',{'smallCaps':'true'}),('All caps',{'allCaps':'true'}),('Double underline',{'doubleUnderline':'true'}),('Double strike',{'doubleStrike':'true'}),('Expanded',{'characterSpacing':'2'}),('Condensed',{'characterSpacing':'-1'}),('Wide',{'characterScale':'150'}),('Narrow',{'characterScale':'75'}),('Raised',{'characterPosition':'5'}),('Lowered',{'characterPosition':'-5'}),('Hidden',{'hidden':'true'}),('Combined',{'smallCaps':'true','characterSpacing':'1','characterScale':'120','doubleUnderline':'true'})]
text='Alpha beta ZETA';steps=[('text.insert',{'text':'\n'.join(text for _ in cases)})]
for i,(name,props) in enumerate(cases):
 steps += [('selection.set',{'block':i,'start':0,'end':len(text)}),('format.set',{'property':'font','value':'Source Sans 3'}),('format.set',{'property':'size','value':'18'}),('paragraph.spacing',{'rule':'exact','value':36}),('paragraph.set',{'property':'after','value':'0'})]
 steps += [('format.set',{'property':key,'value':value}) for key,value in props.items()]
run([cn,'new',a.out/'empty.cn1doc']);m=a.out/'specimen.json';m.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}));source=a.out/'specimen.docx';run([cn,'run',a.out/'empty.cn1doc',m,source]);results={}
for side,tool in [('cn1',cn),('wordcraft',a.wordcraft.resolve())]:
 pdf=a.out/(side+'.pdf');run([tool,'convert',source,pdf]);run(['pdftoppm','-r','144','-f','1','-singlefile','-png',pdf,pdf.with_suffix('')]);run(['pdftotext','-bbox',pdf,pdf.with_suffix('.html')]);root=ET.parse(pdf.with_suffix('.html'));ns={'x':'http://www.w3.org/1999/xhtml'};words=root.findall('.//x:word',ns);rows=[]
 for word in words:
  y=float(word.get('yMin'));found=next((row for row in rows if abs(row['y']-y)<8),None)
  if found is None:found={'y':y,'words':[]};rows.append(found)
  found['words'].append({'text':word.text,'left':float(word.get('xMin')),'right':float(word.get('xMax'))})
 for row in rows:row['width']=max(w['right'] for w in row['words'])-min(w['left'] for w in row['words'])
 results[side]={'rows':rows,'pdfSha256':hashlib.sha256(pdf.read_bytes()).hexdigest()}
rows=results['cn1']['rows'];assert len(rows)==12,rows
checks={'hiddenTextOmitted':len(rows)==len(cases)-1,'wideScalesInkWidth':abs(rows[7]['width']/rows[0]['width']-1.5)<.01,'narrowScalesInkWidth':abs(rows[8]['width']/rows[0]['width']-.75)<.01,'expandedIsWider':rows[5]['width']>rows[0]['width'],'condensedIsNarrower':rows[6]['width']<rows[0]['width'],'smallCapsChangesOnlyDisplay':''.join(w['text'] for w in rows[1]['words'])=='ALPHABETAZETA','allCapsChangesOnlyDisplay':''.join(w['text'] for w in rows[2]['words'])=='ALPHABETAZETA'}
report={'boundary':__doc__,'cases':cases,'results':results,'checks':checks,'renderedEquivalence':False};(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));assert all(checks.values())
