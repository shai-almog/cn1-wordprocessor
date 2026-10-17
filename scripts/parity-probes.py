#!/usr/bin/env python3
"""A small set of common CLI behavior probes. Not a feature-parity certificate."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('wordcraft',type=Path)
p.add_argument('--cn1',type=Path,help='Explicit CN1 executable (defaults to the development launcher)')
p.add_argument('--out',type=Path,required=True,help='New output directory')
a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=False)
a.out=a.out.resolve()
root=Path(__file__).resolve().parent.parent
cn1=str(a.cn1.resolve()) if a.cn1 else str(root/'scripts/wordprocessor')
wc=str(a.wordcraft.resolve())
subprocess.run([cn1,'new',str(a.out/'empty.cn1doc')],check=True)
W='{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
cases=[
 ('paragraph_text',[('text.insert',{'text':'Hello world\nSecond paragraph'})],
  [('text.insert',{'text':'Hello world'}),('text.newParagraph',{}),('text.insert',{'text':'Second paragraph'})],['Hello world','Second paragraph']),
 ('bold_selection',[('text.insert',{'text':'Hello world'}),('selection.set',{'block':0,'start':0,'end':5}),('format.set',{'property':'bold','value':'true'})],
  [('text.insert',{'text':'Hello world'}),('select.text',{'text':'Hello'}),('format.bold',{'value':True})],['Hello world']),
 ('replace_all',[('text.insert',{'text':'one two one'}),('replace.all',{'text':'one','replacement':'three'})],
  [('text.insert',{'text':'one two one'}),('edit.replaceAll',{'text':'one','with':'three'})],['three two three']),
 ('table_grid',[('table.insert',{'rows':2,'columns':3})],
  [('insert.table',{'rows':2,'cols':3})],None),
]

cases += [
 ('hyperlink', [('text.insert',{'text':'Read the guide'}),('selection.set',{'block':0,'start':9,'end':14}),('format.set',{'property':'link','value':'https://example.com/guide?a=1&b=2'})],
  [('text.insert',{'text':'Read the guide'}),('select.text',{'text':'guide'}),('insert.link',{'url':'https://example.com/guide?a=1&b=2'})],['Read the guide']),
 ('nested_numbering', [('text.insert',{'text':'Parent\nChild'}),('selection.set',{'block':0,'start':0,'endBlock':1,'end':5}),('paragraph.set',{'property':'list','value':'number'}),('selection.set',{'block':1,'start':0,'end':5}),('paragraph.set',{'property':'listLevel','value':'1'})],
  [('text.insert',{'text':'Parent'}),('para.numbering',{}),('text.newParagraph',{}),('text.insert',{'text':'Child'}),('para.listLevel',{'level':1})],['Parent','Child']),
 ('keep_next', [('text.insert',{'text':'Heading\nBody'}),('selection.set',{'block':0,'start':0,'end':7}),('paragraph.set',{'property':'keepNext','value':'true'})],
  [('text.insert',{'text':'Heading'}),('text.newParagraph',{}),('text.insert',{'text':'Body'}),('select.text',{'text':'Heading'}),('para.keepNext',{})],['Heading','Body']),
 ('table_structure', [('table.insert',{'rows':2,'columns':3}),('table.setCell',{'block':1,'row':0,'column':0,'text':'A1'}),('table.structure',{'block':1,'action':'insertRow','index':1}),('table.structure',{'block':1,'action':'insertColumn','index':1})],
  [('insert.table',{'rows':2,'cols':3}),('text.insert',{'text':'A1'}),('table.insertRowBelow',{}),('table.insertColumnRight',{})],None),
 ('table_sort', [('table.insert',{'rows':3,'columns':1}),('table.setCell',{'block':1,'row':0,'column':0,'text':'pear'}),('table.setCell',{'block':1,'row':1,'column':0,'text':'apple'}),('table.setCell',{'block':1,'row':2,'column':0,'text':'banana'}),('table.sort',{'block':1,'column':0,'descending':False,'header':False,'numeric':False})],
  [('insert.table',{'rows':3,'cols':1}),('text.insert',{'text':'pear'}),('text.tab',{}),('text.insert',{'text':'apple'}),('text.tab',{}),('text.insert',{'text':'banana'}),('table.sort',{'column':0,'descending':False,'header':False})],None),
 ('comment_range', [('text.insert',{'text':'Some text here'}),('selection.set',{'block':0,'start':5,'end':9}),('comment.add',{'author':'Reviewer','text':'Check this phrase'})],
  [('text.insert',{'text':'Some text here'}),('select.text',{'text':'text'}),('review.newComment',{'text':'Check this phrase'})],['Some text here']),
]

comment = next(c for c in cases if c[0]=='comment_range')
cases.append(('comment_resolved', comment[1]+[('comment.resolve',{'id':'c2','resolved':True})], comment[2]+[('review.resolveComment',{})], comment[3]))
for alignment in ('left','center','right','decimal'):
    cases.append(('tabs_'+alignment,
      [('text.insert',{'text':'Item\t123.45'}),('paragraph.set',{'property':'tabs','value':'300,'+alignment+',dot'})],
      [('text.insert',{'text':'Item\t123.45'}),('para.tabs',{'tabs':[{'pos':300,'align':alignment,'leader':'dot'}]})],
      ['Item123.45']))
cases.append(('inline_field',
  [('text.insert', {'text':'Page '}), ('field.insert', {'instruction':'PAGE', 'result':'7'})],
  [('text.insert', {'text':'Page '}), ('insert.field', {'instr':'PAGE', 'result':'7'})],
  ['Page 7']))
for kind in ('header','footer'):
    cases.append(('page_'+kind,
      [('headerFooter.set',{'story':kind,'text':'Probe '+kind})],
      [('insert.'+kind,{'text':'Probe '+kind})],None))
for kind in ('nextPage','continuous','oddPage','evenPage'):
    steps=[('text.insert',{'text':'Before'}),('layout.break',{'kind':kind}),('text.insert',{'text':'After'})]
    cases.append(('section_'+kind,steps,steps,['Before','After']))
track_cn=[('text.insert',{'text':'red green blue'}),('review.trackChanges',{'value':True,'author':'Reviewer'}),('selection.set',{'block':0,'start':4,'end':9}),('text.insert',{'text':'gold'})]
track_wc=[('text.insert',{'text':'red green blue'}),('review.trackChanges',{'value':True}),('select.text',{'text':'green'}),('text.insert',{'text':'gold'})]
cases.append(('tracked_replace',track_cn,track_wc,['red gold blue']))
for action, expected in [('acceptAll','red gold blue'),('rejectAll','red green blue')]:
    cases.append(('tracked_'+action,track_cn+[('review.'+action,{})],track_wc+[('review.'+action,{})],[expected]))
for action, expected in [('pending',['First new','last']),('acceptAll',['First new','last']),('rejectAll',['First last'])]:
    cn=[('text.insert',{'text':'First last'}),('review.trackChanges',{'value':True,'author':'Reviewer'}),('selection.set',{'block':0,'start':6,'end':6}),('text.insert',{'text':'new\n'})]
    wc_steps=[('text.insert',{'text':'First last'}),('review.trackChanges',{'value':True}),('select.text',{'text':'last'}),('select.collapse',{'end':False}),('text.insert',{'text':'new'}),('text.newParagraph',{})]
    if action!='pending':
        cn+=[('review.'+action,{})];wc_steps+=[('review.'+action,{})]
    cases.append(('tracked_break_'+action,cn,wc_steps,expected))
for action in ('pending','acceptAll','rejectAll'):
    cn_steps=[('text.insert',{'text':'plain bold'}),('review.trackChanges',{'value':True,'author':'Reviewer'}),('selection.set',{'block':0,'start':6,'end':10}),('format.set',{'property':'bold','value':'true'})]
    wc_steps=[('text.insert',{'text':'plain bold'}),('review.trackChanges',{'value':True}),('select.text',{'text':'bold'}),('format.bold',{'value':True})]
    if action!='pending':
        cn_steps+=[('review.'+action,{})];wc_steps+=[('review.'+action,{})]
    cases.append(('tracked_format_'+action,cn_steps,wc_steps,['plain bold']))
for action in ('pending','acceptAll','rejectAll'):
    cn_steps=[('text.insert',{'text':'Paragraph'}),('review.trackChanges',{'value':True,'author':'Reviewer'}),('paragraph.set',{'property':'align','value':'right'})]
    wc_steps=[('text.insert',{'text':'Paragraph'}),('review.trackChanges',{'value':True}),('para.align',{'value':'right'})]
    if action!='pending':
        cn_steps+=[('review.'+action,{})];wc_steps+=[('review.'+action,{})]
    cases.append(('tracked_paragraph_'+action,cn_steps,wc_steps,['Paragraph']))
for action, remainder, expected in [('accept','rejectAll','before cd'),('reject','acceptAll','before abef')]:
    cn_steps=[('text.insert',{'text':'before '}),('review.trackChanges',{'value':True,'author':'Reviewer'}),('text.insert',{'text':'abcdef'}),('selection.set',{'block':0,'start':9,'end':11}),('review.'+action,{}),('review.'+remainder,{})]
    wc_steps=[('text.insert',{'text':'before '}),('review.trackChanges',{'value':True}),('text.insert',{'text':'abcdef'}),('select.range',{'anchor':{'block':0,'off':9},'focus':{'block':0,'off':11}}),('review.'+action,{}),('review.'+remainder,{})]
    cases.append(('tracked_selection_'+action,cn_steps,wc_steps,[expected]))
for action, expected in [('accept','red gold blue'),('reject','red green blue')]:
    cn_steps=track_cn+[('selection.set',{'block':0,'start':4,'end':13}),('review.'+action,{})]
    wc_steps=track_wc+[('select.range',{'anchor':{'block':0,'off':4},'focus':{'block':0,'off':13}}),('review.'+action,{})]
    cases.append(('tracked_selected_replacement_'+action,cn_steps,wc_steps,[expected]))
for action in ('pending','acceptAll','rejectAll'):
    cn_steps=[('text.insert',{'text':'Heading'}),('review.trackChanges',{'value':True,'author':'Reviewer'}),('styles.apply',{'id':'Heading1'})]
    wc_steps=[('text.insert',{'text':'Heading'}),('review.trackChanges',{'value':True}),('styles.apply',{'style':'Heading 1'})]
    if action!='pending':
        cn_steps+=[('review.'+action,{})];wc_steps+=[('review.'+action,{})]
    cases.append(('tracked_style_'+action,cn_steps,wc_steps,['Heading']))
for fmt, numbers in [('ARABIC',['1','2']),('roman',['i','ii']),('ROMAN',['I','II']),('alphabetic',['a','b']),('ALPHABETIC',['A','B'])]:
    steps=[('text.insert',{'text':'Object'}),('references.caption',{'label':'Figure','text':'First','format':fmt}),('references.caption',{'label':'Figure','text':'Second','format':fmt})]
    cases.append(('caption_'+{'ARABIC':'decimal','roman':'lower_roman','ROMAN':'upper_roman','alphabetic':'lower_letter','ALPHABETIC':'upper_letter'}[fmt],steps,steps,['Object','Figure '+numbers[0]+': First','Figure '+numbers[1]+': Second']))
for position in ('above','below'):
    steps=[('text.insert',{'text':'Object'}),('references.caption',{'label':'Table','text':'Grid','position':position,'excludeLabel':True})]
    cases.append(('caption_'+position,steps,steps,['1: Grid','Object'] if position=='above' else ['Object','1: Grid']))
cn=[('text.insert',{'text':'Chapter'}),('styles.apply',{'id':'Heading1'}),('references.caption',{'label':'Figure','text':'Description','chapter':1})]
wc_steps=[('text.insert',{'text':'Chapter'}),('styles.apply',{'style':'Heading 1'}),('references.caption',{'label':'Figure','text':'Description','chapter':1})]
cases.append(('caption_chapter',cn,wc_steps,['Chapter','Figure 1-1: Description']))
for mode, expected in [('sentence','Hello world. Again!'),('title','Hello World. Again!'),('toggle','Hello world. Again!'),('upper','HELLO WORLD. AGAIN!'),('lower','hello world. again!')]:
    source='hELLO WORLD. aGAIN!'
    cn=[('text.insert',{'text':source}),('selection.set',{'block':0,'start':0,'end':len(source)}),('format.changeCase',{'mode':mode})]
    wc_steps=[('text.insert',{'text':source}),('select.text',{'text':source}),('format.changeCase',{'mode':mode})]
    cases.append(('case_'+mode,cn,wc_steps,[expected]))
for cycles, expected in [(1,'HELLO'),(2,'Hello'),(3,'hello')]:
    cn=[('text.insert',{'text':'hello'}),('selection.set',{'block':0,'start':0,'end':5})]+[('format.changeCase',{})]*cycles
    wc_steps=[('text.insert',{'text':'hello'}),('select.text',{'text':'hello'})]+[('format.changeCase',{})]*cycles
    cases.append(('case_cycle_'+str(cycles),cn,wc_steps,[expected]))
cn=[('text.insert',{'text':'Straße ΟΣ'}),('selection.set',{'block':0,'start':0,'end':9}),('format.changeCase',{'mode':'upper'}),('format.changeCase',{'mode':'lower'})]
wc_steps=[('text.insert',{'text':'Straße ΟΣ'}),('select.text',{'text':'Straße ΟΣ'}),('format.changeCase',{'mode':'upper'}),('format.changeCase',{'mode':'lower'})]
cases.append(('case_unicode',cn,wc_steps,['strasse ος']))
cases.append(('case_caret_word',
    [('text.insert',{'text':'hello'}),('selection.set',{'block':0,'start':2,'end':2}),('format.changeCase',{})],
    [('text.insert',{'text':'hello'}),('select.range',{'anchor':{'block':0,'off':2},'focus':{'block':0,'off':2}}),('format.changeCase',{})],['HELLO']))
results=[]
for name, cn1steps, ruststeps, expected in cases:
    row={'id':name,'boundary':'headless command execution and DOCX semantics; no UI or rendering'}
    for side,steps in [('cn1',cn1steps),('wordcraft',ruststeps)]:
        out=a.out/(name+'-'+side+'.docx')
        try:
            if side=='cn1':
                macro=a.out/(name+'.json')
                macro.write_text(json.dumps({'version':1,'steps':[{'command':cmd,'args':args} for cmd,args in steps]}))
                command=[cn1,'run',str(a.out/'empty.cn1doc'),str(macro),str(out)]
            else:
                command=[wc,'run','--template','blank','--author','Reviewer']
                for cmd,args in steps: command+=['--cmd',cmd+'='+json.dumps(args,separators=(',',':'))]
                command+=['--save',str(out)]
            subprocess.run(command,check=True,capture_output=True,timeout=120)
            with zipfile.ZipFile(out) as z: parts={name:z.read(name) for name in z.namelist()}
            xml=ET.fromstring(parts['word/document.xml'])
            body=xml.find(W+'body')
            text=[''.join(t.text or '' for t in para.iter(W+'t')) for para in body.findall(W+'p')]
            assertions={}
            if expected is not None: assertions['paragraphText']=text==expected
            if name.startswith('tracked_'):
                insertions=body.findall('.//'+W+'ins');deletions=body.findall('.//'+W+'del')
                if name=='tracked_replace':
                    assertions['insertedText']=''.join(t.text or '' for e in insertions for t in e.iter(W+'t'))=='gold'
                    assertions['deletedText']=''.join(t.text or '' for e in deletions for t in e.iter(W+'delText'))=='green'
                    assertions['revisionAuthors']=all(e.get(W+'author')=='Reviewer' for e in insertions+deletions)
                    settings=ET.fromstring(parts['word/settings.xml'])
                    assertions['trackingEnabled']=settings.find(W+'trackRevisions') is not None
                elif name=='tracked_break_pending':
                    assertions['insertedParagraphBreak']=body.find(W+'p/'+W+'pPr/'+W+'rPr/'+W+'ins') is not None
                else:
                    assertions['revisionsResolved']=not insertions and not deletions
            if name.startswith('tracked_style_'):
                props=body.find(W+'p/'+W+'pPr')
                style=None if props is None else props.find(W+'pStyle')
                assertions['paragraphStyle']=(style.get(W+'val') if style is not None else 'Normal')==('Normal' if name.endswith('_rejectAll') else 'Heading1')
                assertions['trackedStyleProperties']=bool(body.findall('.//'+W+'pPrChange'))==name.endswith('_pending')
                if name.endswith('_pending'):
                    previous=props.find(W+'pPrChange/'+W+'pPr/'+W+'pStyle')
                    assertions['originalStyle']=(previous.get(W+'val') if previous is not None else 'Normal')=='Normal'
                else:
                    assertions['styleRevisionsResolved']=not body.findall('.//'+W+'rPrChange')
            if name.startswith('tracked_format_'):
                changes=body.findall('.//'+W+'rPrChange')
                pending=name.endswith('_pending')
                assertions['formatRevision']=bool(changes)==pending
                runs=[r for r in body.findall('.//'+W+'r') if ''.join(t.text or '' for t in r.iter(W+'t'))=='bold']
                bold=runs[0].find(W+'rPr/'+W+'b') if runs else None
                assertions['currentBold']=(bold is not None and bold.get(W+'val','1') not in ('0','false'))==(not name.endswith('_rejectAll'))
                if pending:
                    old=changes[0].find(W+'rPr/'+W+'b')
                    assertions['originalNotBold']=old is None or old.get(W+'val','1') in ('0','false')
                    assertions['formatAuthor']=changes[0].get(W+'author')=='Reviewer'
            if name.startswith('tracked_paragraph_'):
                props=body.find(W+'p/'+W+'pPr');change=None if props is None else props.find(W+'pPrChange')
                assertions['paragraphRevision']=(change is not None)==name.endswith('_pending')
                align=None if props is None else props.find(W+'jc')
                assertions['alignment']=(align.get(W+'val') if align is not None else 'left')==('left' if name.endswith('_rejectAll') else 'right')
                if change is not None:
                    old=change.find(W+'pPr/'+W+'jc')
                    assertions['originalAlignment']=(old.get(W+'val') if old is not None else 'left')=='left'
            if name.startswith('section_'):
                kind=name.removeprefix('section_')
                section=body.find(W+'p/'+W+'pPr/'+W+'sectPr')
                final=body.find(W+'sectPr/'+W+'type')
                assertions['sectionBoundary']=section is not None
                assertions['sectionStart']=final is not None and final.get(W+'val')==kind
            if name.startswith('tabs_'):
                alignment=name.removeprefix('tabs_')
                stops=body.find(W+'p').findall(W+'pPr/'+W+'tabs/'+W+'tab')
                assertions['explicitTabStop']=any(t.get(W+'pos')=='6000' and t.get(W+'val')==alignment and t.get(W+'leader')=='dot' for t in stops)
                assertions['realTabCharacter']=len(body.find(W+'p').findall('.//'+W+'r/'+W+'tab'))==1
            if name in ('page_header','page_footer'):
                kind=name.removeprefix('page_')
                refs=body.findall(W+'sectPr/'+W+kind+'Reference')
                rels=ET.fromstring(parts['word/_rels/document.xml.rels'])
                targets={rel.get('Id'):rel.get('Target') for rel in rels}
                stories=[]
                for ref in refs:
                    target=targets.get(ref.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'),'')
                    path=target.lstrip('/') if target.startswith('/') else 'word/'+target
                    if path in parts:
                        story=ET.fromstring(parts[path]);stories.append(''.join(t.text or '' for t in story.iter(W+'t')))
                assertions['referencedStoryText']=stories==['Probe '+kind]
            if name.startswith('caption_'):
                instructions=[f.get(W+'instr','').strip() for f in body.findall('.//'+W+'fldSimple')]
                instructions += [(t.text or '').strip() for t in body.iter(W+'instrText')]
                assertions['sequenceField']=any(code.startswith('SEQ ') for code in instructions)
                if name=='caption_chapter': assertions['chapterField']=any(code.startswith('STYLEREF 1') for code in instructions)
            if name=='inline_field':
                simple=body.findall('.//'+W+'fldSimple')
                instructions=[f.get(W+'instr','').strip() for f in simple]
                instructions += [(t.text or '').strip() for t in body.iter(W+'instrText')]
                assertions['fieldInstruction']=instructions==['PAGE']
                assertions['cachedResult']=''.join(t.text or '' for t in body.iter(W+'t'))=='Page 7'
            if name=='bold_selection':
                flags=[]
                for run in body.find(W+'p').findall(W+'r'):
                    value=''.join(t.text or '' for t in run.iter(W+'t'))
                    b=run.find(W+'rPr/'+W+'b')
                    bold=b is not None and b.get(W+'val','true') not in ('0','false','off')
                    flags += [bold]*len(value)
                assertions['boldOnlyHello']=flags==[True]*5+[False]*6
            if name in ('table_grid','table_structure'):
                tables=body.findall(W+'tbl')
                rows,cols=(2,3) if name=='table_grid' else (3,4)
                assertions['tableDimensions']=len(tables)==1 and len(tables[0].findall(W+'tr'))==rows and all(len(r.findall(W+'tc'))==cols for r in tables[0].findall(W+'tr'))
                if name=='table_structure': assertions['cellContentPreserved']=''.join(t.text or '' for t in tables[0].find(W+'tr/'+W+'tc').iter(W+'t'))=='A1'
            if name=='hyperlink':
                rels=ET.fromstring(parts['word/_rels/document.xml.rels'])
                targets={r.get('Id'):r.get('Target') for r in rels}
                links=body.findall('.//'+W+'hyperlink')
                assertions['externalLinkTarget']=len(links)==1 and targets.get(links[0].get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'))=='https://example.com/guide?a=1&b=2'
                assertions['linkedText']=len(links)==1 and ''.join(t.text or '' for t in links[0].iter(W+'t'))=='guide'
            if name=='nested_numbering':
                levels=[p.find(W+'pPr/'+W+'numPr/'+W+'ilvl') for p in body.findall(W+'p')]
                assertions['listLevels']=[x.get(W+'val') if x is not None else None for x in levels]==['0','1']
            if name=='keep_next':
                keep=body.find(W+'p/'+W+'pPr/'+W+'keepNext')
                assertions['keepWithNext']=keep is not None and keep.get(W+'val','true') not in ('0','false','off')
            if name=='table_sort':
                table=body.find(W+'tbl')
                values=[''.join(t.text or '' for t in r.iter(W+'t')) for r in table.findall(W+'tr')]
                assertions['sortedRows']=values==['apple','banana','pear']
            if name in ('comment_range','comment_resolved'):
                comments=ET.fromstring(parts['word/comments.xml']).findall(W+'comment')
                assertions['commentAuthorText']=len(comments)==1 and comments[0].get(W+'author')=='Reviewer' and ''.join(t.text or '' for t in comments[0].iter(W+'t'))=='Check this phrase'
                offset=0; anchors=[]
                for el in body.find(W+'p').iter():
                    if el.tag==W+'t': offset+=len(el.text or '')
                    if el.tag in (W+'commentRangeStart',W+'commentRangeEnd'): anchors.append((el.tag,offset,el.get(W+'id')))
                assertions['selectedRange']=len(anchors)==2 and anchors[0][:2]==(W+'commentRangeStart',5) and anchors[1][:2]==(W+'commentRangeEnd',9) and anchors[0][2]==anchors[1][2]==comments[0].get(W+'id')
            if name=='comment_resolved':
                extended=ET.fromstring(parts['word/commentsExtended.xml'])
                ex=list(extended)
                ns='{http://schemas.microsoft.com/office/word/2012/wordml}'
                pid=comments[0].findall(W+'p')[-1].get('{http://schemas.microsoft.com/office/word/2010/wordml}paraId')
                assertions['resolvedComment']=len(ex)==1 and ex[0].get(ns+'done') in ('1','true') and ex[0].get(ns+'paraId')==pid
            row[side]={'passed':all(assertions.values()),'assertions':assertions,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'artifact':out.name}
        except Exception as e:
            row[side]={'passed':False,'error':str(e),'stderr':getattr(e,'stderr',b'').decode('utf-8','replace')}
    results.append(row)
report={'schema':1,'probes':results,'catalogRowsCertified':0,'fullParity':False}
(a.out/'results.json').write_text(json.dumps(report,indent=2)+'\n')
for row in results:print(row['id'], 'CN1='+str(row['cn1']['passed']),'Wordcraft='+str(row['wordcraft']['passed']))
if not all(r[x]['passed'] for r in results for x in ['cn1','wordcraft']):raise SystemExit(1)
