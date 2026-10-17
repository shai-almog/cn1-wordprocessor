#!/usr/bin/env python3
"""Native Table Layout ribbon acceptance on a disposable document.

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
        for label,rows,after in [('Insert Above',True,False),('Insert Below',True,True),('Insert Left',False,False),('Insert Right',False,True)]:
            ribbon(label);g=grid();assert len(g.findall('row'))==(4 if rows else 3);assert len(g.findall('row')[0].findall('cell'))==(3 if rows else 4)
            at=2 if after else 1;assert text(at if rows else 1,1 if rows else at)==''
            assert text(2 if rows and not after else 1,2 if not rows and not after else 1)=='r1c1'
            if rows:
                fmt=g.findall('tableFormat/row')[at];assert float(fmt.get('height'))==42 and fmt.findall('cell')[1].get('background')=='123456'
            command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note(label+'-and-undo')
        ribbon('Distribute Columns');widths=[float(c.get('width')) for c in grid().findall('tableFormat/column')];assert max(widths)==min(widths)
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note('distribute-columns-and-undo')
        ribbon('Distribute Rows');assert all(float(r.get('height'))==80 for r in grid().findall('tableFormat/row'))
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note('distribute-rows-and-undo')
        for vertical in ['Top','Center','Bottom']:
            for horizontal in ['Left','Center','Right']:
                label='Align Center' if vertical==horizontal=='Center' else 'Align '+vertical+' '+horizontal
                ribbon(label);g=grid();assert g.findall('tableFormat/row')[1].findall('cell')[1].get('vertical')==vertical.lower()
                assert g.findall('row')[1].findall('cell')[1].find('p').get('align')==horizontal.lower()
                assert snapshot()['cellSelection'] is None
                command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note(label+'-and-undo')
        command('table.selectCells',{'block':1,'row':0,'column':0,'endRow':1,'endColumn':1});ribbon('Align Bottom Right')
        selected=snapshot()['cellSelection'];assert selected['lastRow']==selected['lastColumn']==1
        g=grid()
        for r in range(3):
            for col in range(3):
                assert g.findall('tableFormat/row')[r].findall('cell')[col].get('vertical')==('bottom' if r<2 and col<2 else 'top')
                assert g.findall('row')[r].findall('cell')[col].find('p').get('align')==('right' if r<2 and col<2 else 'left')
        if find('Expand ribbon'):activate('Expand ribbon')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_suffix('.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note('alignment-formats-only-selected-rectangle-and-undo')
        ribbon('Insert Below');assert len(grid().findall('row'))==5 and text(4,0)=='r2c0'
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cellSelection']==selected;note('insert-below-selected-rows-is-one-undo')
    finally:
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
