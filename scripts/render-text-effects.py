#!/usr/bin/env python3
"""Render matching text-effect specimens and retain independent pixel checks."""
import argparse,json,subprocess,hashlib
from pathlib import Path
from PIL import Image
p=argparse.ArgumentParser(description=__doc__);p.add_argument('wordcraft',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out=a.out.resolve();a.out.mkdir(parents=True,exist_ok=False);cn=Path(__file__).resolve().parent/'wordprocessor'
def run(args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True,timeout=120)
 with (a.out/'commands.log').open('a') as log:log.write(json.dumps(list(map(str,args)))+'\n'+r.stdout+r.stderr+'\n')
 r.check_returncode()
types=['none','single','words','double','thick','dotted','dottedHeavy','dash','dashedHeavy','dashLong','dashLongHeavy','dotDash','dashDotHeavy','dotDotDash','dashDotDotHeavy','wave','wavyHeavy','wavyDouble']
cases=[('Normal',{}),('Outline',{'outline':'true'}),('Shadow',{'shadow':'true'}),('Emboss',{'emboss':'true'}),('Engrave',{'engrave':'true'})]+[(t,{'underlineStyle':t,'underlineColor':'cc2200'}) for t in types]
text='Alpha beta GAMMA';steps=[('text.insert',{'text':'\n'.join(text for _ in cases)})]
for i,(name,props) in enumerate(cases):
 steps += [('selection.set',{'block':i,'start':0,'end':len(text)}),('format.set',{'property':'font','value':'Source Sans 3'}),('format.set',{'property':'size','value':'28' if i<5 else '18'}),('format.set',{'property':'background','value':'fff080' if i<2 else 'ffffff'}),('paragraph.spacing',{'rule':'exact','value':48 if i<5 else 24}),('paragraph.set',{'property':'after','value':'0'})]
 steps += [('format.set',{'property':key,'value':value}) for key,value in props.items()]
run([cn,'new',a.out/'empty.cn1doc']);macro=a.out/'specimen.json';macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}));source=a.out/'specimen.docx';run([cn,'run',a.out/'empty.cn1doc',macro,source]);results={}
for side,tool in [('cn1',cn),('wordcraft',a.wordcraft.resolve())]:
 pdf=a.out/(side+'.pdf');run([tool,'convert',source,pdf]);run(['pdftoppm','-r','144','-f','1','-singlefile','-png',pdf,pdf.with_suffix('')]);run(['pdftotext','-bbox',pdf,pdf.with_suffix('.html')]);results[side]={'pdfSha256':hashlib.sha256(pdf.read_bytes()).hexdigest()}
image=Image.open(a.out/'cn1.png').convert('RGB');counts=[]
for index in range(5):
 pixels=list(image.crop((105,108+index*96,1000,108+(index+1)*96)).getdata())
 counts.append({'dark':sum(max(p)<100 for p in pixels),'yellow':sum(p[0]>235 and p[1]>210 and p[2]<180 for p in pixels),'gray':sum(max(p)-min(p)<5 and 100<sum(p)/3<180 for p in pixels)})
checks={'outlineHasLessSolidInk':counts[1]['dark']<counts[0]['dark']*.8 and counts[1]['dark']>0,'outlineInteriorKeepsHighlight':counts[1]['yellow']>counts[0]['yellow'],'shadowAddsGrayInk':counts[2]['gray']>100,'embossAddsBevelInk':counts[3]['gray']>50,'engraveAddsBevelInk':counts[4]['gray']>50,'coloredUnderlinesRendered':sum(r>150 and g<90 and b<90 for r,g,b in image.getdata())>500}
report={'boundary':__doc__,'cases':cases,'results':results,'pixelCounts':counts,'checks':checks,'renderedEquivalence':False};(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(checks,indent=2));assert all(checks.values())
