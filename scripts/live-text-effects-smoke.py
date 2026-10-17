#!/usr/bin/env python3
"""Native underline and text-effects controls acceptance on a disposable document.

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
        if label=='Underline style':
            types=['none','single','words','double','thick','dotted','dottedHeavy','dash','dashedHeavy','dashLong','dashLongHeavy','dotDash','dashDotHeavy','dotDotDash','dashDotDotHeavy','wave','wavyHeavy','wavyDouble']
            labels=['None','Single','Words only','Double','Thick','Dotted','Heavy dotted','Dashed','Heavy dashed','Long dash','Heavy long dash','Dash-dot','Heavy dash-dot','Dash-dot-dot','Heavy dash-dot-dot','Wavy','Heavy wavy','Double wavy']
            value=labels[types.index(value)]
        tree=c.tool('ui_snapshot',{'windowId':window})['nodes']
        parent=next(n for n in tree if n['label']==label and n['role']=='LIST')
        c.tool('ui_perform_action',{'windowId':window,'nodeId':parent['id'],'actionId':'focus'})
        for attempt in range(40):
            tree=c.tool('ui_snapshot',{'windowId':window})['nodes']
            parent=next(n for n in tree if n['label']==label and n['role']=='LIST')
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
        sample='Alpha beta GAMMA'
        command('text.insert',{'text':sample+'\n'})
        command('selection.set',{'block':0,'start':0,'end':len(sample)})
        ribbon('Clear formatting','Home')
        command('format.set',{'property':'size','value':'28'})
        fixture=snapshot(True)
        ribbon('Font dialog','Home');w=dialog('Font')
        for label in ['Outline','Shadow','Emboss','Engrave']:activate(label,w)
        choose('Underline style','wavyDouble',w);set_text('Underline color (hex or auto)','cc2200',w)
        activate('Cancel',w);assert snapshot(True)['xml']==fixture['xml'];note('effects-cancel-preserves-document')
        types=['none','single','words','double','thick','dotted','dottedHeavy','dash','dashedHeavy','dashLong','dashLongHeavy','dotDash','dashDotHeavy','dotDotDash','dashDotDotHeavy','wave','wavyHeavy','wavyDouble']
        for kind in types:
            before_edit=snapshot(True)['xml']
            ribbon('Font dialog','Home');w=dialog('Font');choose('Underline style',kind,w)
            set_text('Underline color (hex or auto)','cc2200',w)
            activate('Apply',w);wait(runstyle,lambda r:r.get('underlineStyle')==kind and r.get('underlineColor')=='cc2200')
            assert runstyle().get('doubleUnderline')==('true' if kind=='double' else 'false')
            assert runstyle().get('underline')==('true' if kind=='single' else 'false')
            note('underline-'+kind)
            if kind in ['words','dotted','wave','wavyDouble']:capture('underline-'+kind)
            command('edit.undo');assert snapshot(True)['xml']==before_edit
        for key in ['outline','shadow','emboss','engrave']:
            before_edit=snapshot(True)['xml']
            ribbon('Font dialog','Home');w=dialog('Font');activate(key.capitalize(),w);activate('Apply',w)
            wait(runstyle,lambda r:r.get(key)=='true');capture(key);note('font-effect-'+key)
            command('edit.undo');assert snapshot(True)['xml']==before_edit
            command('edit.redo');assert runstyle().get(key)=='true';command('edit.undo')
        command('format.set',{'property':'background','value':'fff080'})
        ribbon('Font dialog','Home');w=dialog('Font');activate('Outline',w);activate('Shadow',w)
        choose('Underline style','wavyDouble',w);set_text('Underline color (hex or auto)','1868c4',w)
        capture('font-dialog');activate('Apply',w)
        wait(runstyle,lambda r:r.get('outline')=='true');capture('highlight-outline-shadow')
        assert runstyle().get('shadow')=='true' and runstyle().get('underlineColor')=='1868c4';note('effects-combine-with-highlight-and-color')
        ribbon('Font dialog','Home');w=dialog('Font');tree=c.tool('ui_snapshot',{'windowId':w})['nodes']
        assert all(next(n for n in tree if n['label']==label)['checked']=='CHECKED' for label in ['Outline','Shadow'])
        assert any(n.get('value')=='1868c4' for n in tree);activate('Cancel',w);note('effects-dialog-reopens-state')
        ribbon('Clear formatting','Home');r=runstyle()
        assert all(r.get(k)=='false' for k in ['outline','shadow','emboss','engrave']) and r.get('underlineStyle')=='none' and r.get('underlineColor')=='auto';note('clear-removes-effects-and-underline-color')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title']=='Font':activate('Cancel',window['windowId'])
        for undo in range(160):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if snapshot()['trackChanges']!=before['trackChanges']:command('review.trackChanges',{'value':before['trackChanges']})
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
