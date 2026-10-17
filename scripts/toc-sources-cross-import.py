#!/usr/bin/env python3
"""Advanced TOC source scenarios, paired exports and two-way regeneration."""
import argparse, hashlib, json, pathlib, subprocess, zipfile
import xml.etree.ElementTree as E
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=pathlib.Path)
p.add_argument('--out',type=pathlib.Path,required=True)
a=p.parse_args(); root=pathlib.Path(__file__).resolve().parent.parent
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
cn=str(root/'scripts/wordprocessor');wc=str(a.wordcraft.resolve())
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
def run(command):
    result=subprocess.run(command,text=True,capture_output=True,timeout=60)
    if result.returncode: raise RuntimeError(result.stdout+result.stderr)
    return result.stdout
run([cn,'new',str(out/'empty.cn1doc')])
def cnsetup():
    return [('text.insert',{'text':'\nHeading source\nMapped source\nOutline source\nMarked source'}),
        ('selection.set',{'block':1,'start':0,'end':0}),('styles.apply',{'id':'Heading1'}),
        ('selection.set',{'block':2,'start':0,'end':0}),('styles.apply',{'id':'Caption'}),
        ('selection.set',{'block':3,'start':0,'end':0}),('references.addText',{'level':2}),
        ('selection.set',{'block':4,'start':13,'end':13}),
        ('field.insert',{'instruction':'TC "Marked entry" \\l 3 \\f "C"','result':''}),
        ('field.insert',{'instruction':'TC "Other entry" \\l 1 \\f "B"','result':''}),
        ('selection.set',{'block':0,'start':0,'end':0})]
def wcsetup():
    steps=[]
    for text,style in [('Heading source','Heading 1'),('Mapped source','Caption'),('Outline source','Normal'),('Marked source','Normal')]:
        steps += [('text.newParagraph',{}),('text.insert',{'text':text}),('styles.apply',{'style':style})]
        if text=='Outline source':steps += [('references.addText',{'level':2})]
        if text=='Marked source':steps += [('references.addText',{'level':0})]
    return steps+[('insert.field',{'instr':'TC "Marked entry" \\l 3 \\f "C"','result':''}),
        ('insert.field',{'instr':'TC "Other entry" \\l 1 \\f "B"','result':''}),('caret.docStart',{})]
def export(side,name,steps,source=None):
    path=out/(name+'.docx')
    if side=='cn1':
        macro=out/(name+'.json');macro.write_text(json.dumps({'version':1,'steps':[{'command':c,'args':v} for c,v in steps]}))
        command=[cn,'run',str(source or out/'empty.cn1doc'),str(macro),str(path)]
        if source:command+=['--allow-conversion']
    else:
        command=[wc,'run']+(['--file',str(source)] if source else ['--template','blank'])
        for c,v in steps:command+=['--cmd',c+'='+json.dumps(v)]
        command+=['--save',str(path)]
    run(command);return path

def inspect(path,expected):
    with zipfile.ZipFile(path) as z: xml=E.fromstring(z.read('word/document.xml'))
    entries=[]
    for para in xml.findall('.//'+W+'body//'+W+'p'):
        style=para.find(W+'pPr/'+W+'pStyle');style='' if style is None else style.get(W+'val')
        if style in ['TOC'+str(i) for i in range(1,10)]:entries.append((style,''.join(t.text or '' for t in para.iter(W+'t'))))
    assert entries==expected,(path.name,entries,expected)
    return {'passed':True,'entries':entries,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
scenarios=[
    ('headings',{'outlineLevels':False},[('TOC1','Heading source1')]),
    ('outlines',{'headingStyles':False},[('TOC2','Outline source1')]),
    ('styles',{'headingStyles':False,'outlineLevels':False,'styleLevels':{'Caption':4}},[('TOC4','Mapped source1')]),
    ('marked',{'headingStyles':False,'outlineLevels':False,'tcFields':True},[('TOC3','Marked entry1'),('TOC1','Other entry1')]),
    ('marked-level1',{'headingStyles':False,'outlineLevels':False,'tcFields':True,'levels':1},[('TOC1','Other entry1')]),
    ('combined',{'tcFields':True,'styleLevels':{'Caption':2}},[('TOC1','Heading source1'),('TOC2','Mapped source1'),('TOC2','Outline source1'),('TOC3','Marked entry1'),('TOC1','Other entry1')])]
rows=[]
for name,options,expected in scenarios:
    for side in ['cn1','wordcraft']:
        row={'scenario':name,'application':side}
        try:
            path=export(side,name+'-'+side,(cnsetup() if side=='cn1' else wcsetup())+[('references.toc',options)])
            row.update(inspect(path,expected))
        except Exception as ex:row.update(passed=False,error=str(ex))
        rows.append(row);print(name,side,row['passed'],row.get('error',''),flush=True)
for source in ['cn1','wordcraft']:
    side='wordcraft' if source=='cn1' else 'cn1'
    for name,options,expected in scenarios:
        row={'scenario':name,'source':source,'application':side,'crossImport':True}
        try:
            path=export(side,name+'-'+source+'-to-'+side,[('references.updateToc',{})],out/(name+'-'+source+'.docx'))
            row.update(inspect(path,expected))
        except Exception as ex:row.update(passed=False,error=str(ex))
        rows.append(row);print(name,source+'-to-'+side,row['passed'],row.get('error',''),flush=True)
(out/'results.json').write_text(json.dumps({'checks':rows,'fullParity':False},indent=2)+'\n')
if not all(row['passed'] for row in rows):raise SystemExit(1)
