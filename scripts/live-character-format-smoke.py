#!/usr/bin/env python3
"""Native advanced character formatting controls acceptance on a disposable document.

Uses built-in MCP UI actions for dialogs, normal commands for fixture setup,
and shared undo to restore the exact original document XML.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('live_mcp', Path(__file__).with_name('live-mcp.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--port', type=int, default=18765)
p.add_argument('--allow-disposable', action='store_true', required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if a.output.exists():
    p.error('Choose a new evidence file')
evidence = {'checks': [], 'originalXmlRestored': False}
with m.Client(a.port) as c:
    def snapshot(xml=False):
        return c.tool('word_snapshot', {'xml': xml})

    def command(name, args=None):
        # Closing the mobile IME can report a final selection asynchronously.
        # Retry only an explicit pre-execution stale-state rejection, and only
        # while document/review/cell state remains unchanged in this disposable run.
        initial = snapshot(True)
        current = initial
        for attempt in range(4):
            try:
                return c.tool('word_command', {'stateId': current['stateId'], 'command': name, 'args': args or {}})['result']
            except RuntimeError as error:
                if 'Document or selection changed; read word_snapshot and retry with its stateId' not in str(error) or attempt == 3:
                    raise
                current = snapshot(True)
                for field in ['xml', 'cell', 'trackChanges', 'trackFormatting', 'reviewAuthor']:
                    assert current[field] == initial[field], ('Concurrent state change', field)
                assert current['document']['revision'] == initial['document']['revision']
                evidence['staleSelectionRetries'] = evidence.get('staleSelectionRetries', 0) + 1

    def find(label, window=0, role=None):
        return [n for n in c.tool('ui_find', {'windowId': window, 'label': label})
                if n['label'] == label and (role is None or n['role'] == role)]

    def activate(label, window=0, role=None):
        nodes = find(label, window, role)
        assert len(nodes) == 1, (label, nodes)
        result = c.tool('ui_activate', {'windowId': window, 'nodeId': nodes[0]['id']})
        assert result.get('pending') or result.get('success'), result

    def wait(read, predicate):
        deadline = time.monotonic() + 4
        while True:
            result = read()
            if predicate(result):
                return result
            if time.monotonic() >= deadline:
                raise AssertionError(result)
            time.sleep(.03)

    def dialog(title):
        windows = wait(lambda: c.tool('ui_windows'), lambda ws: any(w['title'] == title and w['visible'] for w in ws))
        return next(w['windowId'] for w in windows if w['title'] == title and w['visible'])

    def ribbon(label,tab='Table Layout'):
        if find('Hide keyboard'):
            activate('Hide keyboard')
        if find('Expand ribbon'):
            activate('Expand ribbon')
        activate('Home')
        if find('Ribbon tabs',role='LIST'):choose('Ribbon tabs',tab,0)
        else:activate(tab)
        for _ in range(8):
            if not find('Previous ribbon groups'): break
            activate('Previous ribbon groups')
        for _ in range(8):
            nodes = find(label, role='BUTTON')
            if nodes and nodes[0]['bounds'][2] > 0:
                activate(label, role='BUTTON')
                return
            activate('More ribbon groups')
        raise AssertionError('Ribbon control not visible: ' + label)


    def set_text(label,text,window):
        nodes=find(label,window,'TEXT_FIELD');assert len(nodes)==1,(label,nodes)
        c.tool('ui_set_text',{'windowId':window,'nodeId':nodes[0]['id'],'text':text})

    def choose(label,value,window):
        for attempt in range(12):
            tree=c.tool('ui_snapshot',{'windowId':window})['nodes']
            parent=next(n for n in tree if n['label']==label and n['role']=='LIST')
            c.tool('ui_perform_action',{'windowId':window,'nodeId':parent['id'],'actionId':'focus'})
            tree=c.tool('ui_snapshot',{'windowId':window})['nodes']
            items=[n for n in tree if n['parent']==parent['id'] and n['role']=='LIST_ITEM' and n['label']==value]
            if items:
                assert len(items)==1,(label,value,items)
                result=c.tool('ui_activate',{'windowId':window,'nodeId':items[0]['id']})
                assert result.get('success'),result
                return
            c.tool('ui_perform_action',{'windowId':window,'nodeId':parent['id'],'actionId':'scrollForward'})
        raise AssertionError((label,value,parent))

    import xml.etree.ElementTree as ET,base64
    def note(label):evidence['checks'].append(label)
    def runstyle():return ET.fromstring(snapshot(True)['xml']).find('p/r')
    def capture(name):
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_name(a.output.stem+'-'+name+'.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        command('selection.set',{'block':0,'start':0,'end':0})
        sample='Character Alpha beta sample'
        command('text.insert',{'text':sample+'\n'})
        command('selection.set',{'block':0,'start':0,'end':len(sample)})
        keys=['doubleUnderline','doubleStrike','smallCaps','allCaps','hidden','characterSpacing','characterScale','characterPosition']
        for key in keys:command('format.set',{'property':key,'value':'100' if key=='characterScale' else '0' if key.startswith('character') else 'false'})
        command('format.set',{'property':'size','value':'24'})
        fixture=snapshot(True)
        ribbon('Font dialog','Home');w=dialog('Font')
        labels=['Double underline','Double strikethrough','Small caps','All caps','Hidden text']
        for label in labels:activate(label,w)
        for label,value in [('Spacing (pt)','2.5'),('Scale (%)','140'),('Position (pt)','-3')]:set_text(label,value,w)
        tree=c.tool('ui_snapshot',{'windowId':w})['nodes'];height=next(n['bounds'][3] for n in tree if n['label']=='Spacing (pt)' and n['role']=='TEXT_FIELD')
        assert all(n['bounds'][3]>=height*.65 for n in tree if n['role']=='CHECKBOX'),[(n['label'],n['bounds']) for n in tree if n['role']=='CHECKBOX'];note('font-checkboxes-have-readable-height')
        capture('font-dialog');activate('Cancel',w)
        assert snapshot(True)['xml']==fixture['xml'];note('font-cancel-is-nondestructive')
        ribbon('Font dialog','Home');w=dialog('Font')
        for label in labels[:-1]:activate(label,w)
        for label,value in [('Spacing (pt)','2.5'),('Scale (%)','140'),('Position (pt)','-3')]:set_text(label,value,w)
        activate('Apply',w)
        wait(runstyle,lambda r:r.get('doubleUnderline')=='true')
        expected={'doubleUnderline':'true','doubleStrike':'true','smallCaps':'true','allCaps':'true','hidden':'false','characterSpacing':'2.5','characterScale':'140.0','characterPosition':'-3.0'}
        for key,value in expected.items():assert runstyle().get(key)==value,(key,runstyle().attrib);note('font-control-'+key)
        assert snapshot()['selection']==fixture['selection'];note('font-preserves-selection')
        assert sample in ''.join(ET.fromstring(snapshot(True)['xml']).itertext());note('case-effects-preserve-source')
        changed=snapshot(True)['xml'];capture('combined-effects');evidence['combinedLayout']=c.tool('word_layout',{})
        ribbon('Font dialog','Home');w=dialog('Font');tree=c.tool('ui_snapshot',{'windowId':w})['nodes'];evidence['reopenedFontDialog']=tree
        for label in labels:assert any(n['label']==label for n in tree),label
        for value in ['2.5','140.0','-3.0']:assert any(n.get('value')==value or n.get('label')==value for n in tree),value
        activate('Underline',w)
        tree=c.tool('ui_snapshot',{'windowId':w})['nodes'];assert next(n for n in tree if n['label']=='Double underline')['checked']=='UNCHECKED';note('underline-modes-exclusive-in-dialog')
        activate('Cancel',w);note('font-dialog-reopens-current-properties')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];command('edit.redo');assert snapshot(True)['xml']==changed;note('font-dialog-is-one-undo-step')
        for key,value in [('allCaps','false'),('doubleUnderline','false'),('doubleStrike','false'),('characterSpacing','0'),('characterScale','100'),('characterPosition','0')]:command('format.set',{'property':key,'value':value})
        capture('small-caps');note('small-caps-native-render')
        ribbon('Font dialog','Home');w=dialog('Font');activate('Hidden text',w);activate('Apply',w);wait(runstyle,lambda r:r.get('hidden')=='true');capture('hidden');note('hidden-font-control')
        command('edit.undo');assert runstyle().get('hidden')=='false';note('hidden-undo-restores-visible-text')
        for key,value in [('characterSpacing','-1'),('characterScale','75'),('characterPosition','4.5')]:command('format.set',{'property':key,'value':value})
        capture('condensed-raised');note('condensed-raised-native-render')
        command('selection.set',{'block':0,'start':3,'end':3});caret=snapshot()['selection'];command('format.set',{'property':'allCaps','value':'true'})
        xml=ET.fromstring(snapshot(True)['xml']);r=xml.find('p/r');assert r.text=='Character' and r.get('allCaps')=='true';assert snapshot()['selection']==caret;note('caret-formatting-targets-surrounding-word');command('edit.undo')
        command('selection.set',{'block':0,'start':0,'end':len(sample)})
        ribbon('Clear formatting','Home');assert all(runstyle().get(k)==('100.0' if k=='characterScale' else '0.0' if k.startswith('character') else 'false') for k in keys),runstyle().attrib;note('clear-formatting-resets-all-advanced-properties')
        command('selection.set',{'block':0,'start':0,'end':0});command('field.insert',{'instruction':'TESTFIELD','result':'alpha BETA'})
        command('selection.set',{'block':0,'start':0,'end':1})
        for key,value in [('smallCaps','true'),('characterSpacing','2'),('characterScale','125')]:command('format.set',{'property':key,'value':value})
        assert ET.fromstring(snapshot(True)['xml']).find('p/field').get('result')=='alpha BETA';capture('formatted-field-result');note('field-results-retain-text-with-character-effects')
        command('selection.set',{'block':0,'start':0,'end':0});command('text.insert',{'text':'A\\tB\\n'.encode().decode('unicode_escape')});command('selection.set',{'block':0,'start':0,'end':3})
        for key,value in [('smallCaps','false'),('characterSpacing','1'),('characterScale','200')]:command('format.set',{'property':key,'value':value})
        command('paragraph.set',{'property':'align','value':'left'});command('paragraph.set',{'property':'tabs','value':'120,left,dot'});capture('scaled-spaced-tab-leader');note('tab-leaders-render-with-character-effects')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title']=='Font':activate('Cancel',window['windowId'])
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if snapshot()['trackChanges']!=before['trackChanges']:command('review.trackChanges',{'value':before['trackChanges']})
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
