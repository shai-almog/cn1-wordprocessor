#!/usr/bin/env python3
"""Native table splitting ribbon acceptance on a disposable document.

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
            for accessible_label in (['Track Changes off', 'Track Changes'] if label=='Track Changes' else [label]):
                nodes = find(accessible_label, role='BUTTON')
                if nodes and nodes[0]['bounds'][2] > 0:
                    activate(accessible_label, role='BUTTON')
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
    def grid():return next((t for t in ET.fromstring(snapshot(True)['xml']).findall('table') if t.get('id')==fixture_table_id),None)
    def text(row,col):return ''.join(grid().findall('row')[row].findall('cell')[col].itertext())
    def enter():command('selection.cell',{'block':1,'row':1,'column':1,'paragraph':0,'start':1,'end':2})
    def note(label):evidence['checks'].append(label)
    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        command('selection.set',{'block':0,'start':0,'end':0});command('table.insert',{'rows':3,'columns':3})
        for row in range(3):
            for col in range(3):command('table.setCell',{'block':1,'row':row,'column':col,'text':f'r{row}c{col}'})
        fixture_table_id=snapshot()['document']['blocks'][1]['id']
        command('table.format',{'block':1,'row':0,'column':0,'columnPercent':60,'minHeight':80})
        command('table.format',{'block':1,'row':1,'column':1,'minHeight':42,'background':'123456'})
        enter();fixture=snapshot(True)
        ribbon('Track Changes',tab='Review');tracked=snapshot();assert tracked['trackChanges'] and tracked['cell']['row']==tracked['cell']['column']==1
        note('track-changes-ribbon-keeps-focused-cell')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cell']['row']==1;note('track-changes-undo-keeps-focused-cell')
        ribbon('Split Cells…');d=dialog('Split Cells');set_text('Number of columns','3',d);activate('Cancel',d)
        assert snapshot(True)['xml']==fixture['xml'];note('split-cell-cancel')
        ribbon('Split Cells…');d=dialog('Split Cells');set_text('Number of columns','3',d);activate('Split',d)
        g=grid();assert len(g.findall('row')[1].findall('cell'))==5
        assert text(1,1)=='r1c1' and text(1,2)==text(1,3)=='' and text(1,4)=='r1c2'
        spans=g.findall('merge');assert len(spans)==2,(ET.tostring(g),spans)
        widths=[float(x.get('width')) for x in g.findall('tableFormat/column')];assert abs(widths[1]-widths[2])<.0001 and abs(widths[2]-widths[3])<.0001
        note('split-cell-three-equal-columns-preserves-neighbors')
        if find('Expand ribbon'):activate('Expand ribbon')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_suffix('.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note('split-cell-one-step-undo')
        command('edit.redo');assert len(grid().findall('row')[1].findall('cell'))==5;note('split-cell-redo');command('edit.undo')
        ribbon('Split Table');root=ET.fromstring(snapshot(True)['xml']);head=grid();assert len(head.findall('row'))==1
        tables=root.findall('table');index=next(i for i,t in enumerate(tables) if t.get('id')==fixture_table_id);tail=tables[index+1]
        assert len(tail.findall('row'))==2 and ''.join(tail.findall('row')[0].findall('cell')[1].itertext())=='r1c1'
        assert snapshot()['cell']['block']==-1 and snapshot()['selection']['block']==2
        note('split-table-current-row-and-separator-caret')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cell']['row']==1;note('split-table-undo-restores-cell')
        command('edit.redo');assert len(grid().findall('row'))==1;note('split-table-redo');command('edit.undo')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title']=='Split Cells':activate('Cancel',window['windowId'])
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
