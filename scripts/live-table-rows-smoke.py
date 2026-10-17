#!/usr/bin/env python3
"""Native row height and pagination controls acceptance on a disposable document.

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
    def grid():return next(t for t in ET.fromstring(snapshot(True)['xml']).findall('table') if t.get('id')==fixture_id)
    def props():return grid().find('tableFormat')
    def note(label):evidence['checks'].append(label)
    def geometry():
        value=c.tool('word_layout',{})
        assert value['displayHeight']-value['viewport'][1]>=100, ('Window too short for visible editor acceptance',value)
        return value
    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        command('selection.set',{'block':0,'start':0,'end':0});command('table.insert',{'rows':2,'columns':2})
        fixture_id=snapshot()['document']['blocks'][1]['id']
        command('table.setCell',{'block':1,'row':0,'column':0,'text':'alpha'})
        command('table.setCell',{'block':1,'row':0,'column':1,'text':'longer phrase'})
        command('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':1,'end':3})
        fixture=snapshot(True)
        ribbon('Table properties…');w=dialog('Table properties');choose('Row height rule','Exactly',w);set_text('Row height (pt)','24',w);activate('Cancel',w)
        assert snapshot(True)['xml']==fixture['xml'];note('row-properties-cancel')
        for label,height,rule in [('Exactly',24,'exact'),('At least',64,'atLeast'),('Automatic',0,'auto')]:
            ribbon('Table properties…');w=dialog('Table properties');choose('Row height rule',label,w);set_text('Row height (pt)',str(height),w)
            activate('Allow row to break across pages',w);activate('Apply',w)
            wait(lambda:props().find('row').get('allowBreak'),lambda v:v=='false')
            row=props().find('row');assert float(row.get('height'))==height and row.get('rule','auto' if height==0 else 'atLeast')==rule
            assert snapshot()['selection']==fixture['selection'];note(label+'-row-properties')
            evidence.setdefault('layouts',{})[label]=geometry()
            changed=snapshot(True)['xml'];command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];command('edit.redo');assert snapshot(True)['xml']==changed
            note(label+'-undo-redo');command('edit.undo')
        ribbon('Row height…');w=dialog('Row height');set_text('Row height (pt)','36',w);choose('Row height rule','Exactly',w);activate('Apply',w)
        assert props().find('row').get('rule')=='exact' and float(props().find('row').get('height'))==36;note('ribbon-row-height')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note('ribbon-row-height-undo')
        command('table.setCell',{'block':1,'row':0,'column':0,'text':'first line\nsecond line\nthird line'})
        command('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':0,'end':0})
        ribbon('Table properties…');w=dialog('Table properties');choose('Row height rule','Exactly',w);set_text('Row height (pt)','24',w);activate('Apply',w)
        evidence['clippedLayout']=geometry();note('exact-row-with-overflow')
        # Check the actual native layout report, not just saved model attributes.
        cells=[v for v in evidence['clippedLayout']['cells'] if v['block']==1 and v['row']==0]
        reference=[v['bounds'][3] for v in evidence['layouts']['Exactly']['cells'] if v['block']==1 and v['row']==0]
        assert reference and cells and all(abs(v['bounds'][3]-reference[0])<=1 for v in cells),cells
        exact_pixels=reference[0]
        note('exact-row-native-geometry')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_suffix('.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
        note('clipped-row-screenshot')
        ribbon('Table properties…');w=dialog('Table properties');choose('Row height rule','Automatic',w);activate('Apply',w)
        restored=geometry();cells=[v for v in restored['cells'] if v['block']==1 and v['row']==0]
        assert cells and all(v['bounds'][3]>exact_pixels for v in cells),cells
        note('automatic-restores-overflow-content')
        command('paragraph.set',{'property':'spacing','value':'0.5'})
        ribbon('Row height…');w=dialog('Row height');set_text('Row height (pt)','14',w);choose('Row height rule','Exactly',w);activate('Apply',w)
        assert props().find('row').get('rule')=='exact' and float(props().find('row').get('height'))==14
        note('tight-spacing-exact-row')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_name(a.output.stem+'-tight.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
        note('tight-spacing-clipping-screenshot')
    finally:
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
