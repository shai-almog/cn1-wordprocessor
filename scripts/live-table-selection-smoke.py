#!/usr/bin/env python3
"""Native ribbon acceptance for rectangular table selection and editing on an explicitly disposable document.

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
    def celltext(row,col):return ''.join(grid().findall('row')[row].findall('cell')[col].itertext())
    def note(label):evidence['checks'].append(label)
    def select(row,col,endrow=None,endcol=None):
        command('table.selectCells',{'block':1,'row':row,'column':col,'endRow':row if endrow is None else endrow,'endColumn':col if endcol is None else endcol})
    def enter(row,col):command('selection.cell',{'block':1,'row':row,'column':col,'paragraph':0,'start':0,'end':0})
    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        command('selection.set',{'block':0,'start':0,'end':0})
        command('table.insert',{'rows':3,'columns':3})
        for row in range(3):
            for col in range(3):command('table.setCell',{'block':1,'row':row,'column':col,'text':f'r{row}c{col}'})
        fixture_table_id=snapshot()['document']['blocks'][1]['id']
        enter(1,1);fixture=snapshot(True)
        ribbon('Select Column');selected=snapshot()['cellSelection'];assert selected['firstColumn']==1 and selected['lastColumn']==1 and selected['firstRow']==0 and selected['lastRow']==2
        note('contextual-ribbon-selects-column')
        geometry=c.tool('word_layout');assert sum(x['selected'] for x in geometry['cells'])==3,geometry
        evidence['selectedGeometry']=geometry
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_suffix('.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
        ribbon('Bold','Home');assert snapshot()['cellSelection'] is not None
        for row in range(3):
            for col in range(3):
                runs=grid().findall('row')[row].findall('cell')[col].findall('p/r')
                assert all(x.get('bold')==('true' if col==1 else 'false') for x in runs),(row,col,ET.tostring(grid()))
        note('home-formatting-applies-only-to-selected-cells')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cellSelection'] is not None
        note('formatting-undo-restores-grid-selection')
        ribbon('Select range…');window=dialog('Select table cells')
        set_text('First row','1',window);set_text('First column','1',window);set_text('Last row','2',window);set_text('Last column','2',window)
        activate('Cancel',window,'BUTTON');assert snapshot()['cellSelection']==selected
        note('cell-range-dialog-cancel-preserves-selection')
        ribbon('Select range…');window=dialog('Select table cells')
        set_text('First row','1',window);set_text('First column','1',window);set_text('Last row','2',window);set_text('Last column','2',window)
        activate('Select',window,'BUTTON');x=snapshot()['cellSelection'];assert x['firstRow']==0 and x['lastRow']==1 and x['firstColumn']==0 and x['lastColumn']==1
        note('cell-range-dialog-selects-rectangle')
        ribbon('Clear Cell Contents');assert celltext(0,0)=='' and celltext(1,1)=='' and celltext(2,1)=='r2c1' and celltext(1,2)=='r1c2'
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cellSelection']['lastRow']==1
        note('clear-selected-cells-keeps-grid-and-undo-restores-range')
        command('edit.pasteText',{'text':'A\tB\nC\tD'});assert celltext(0,0)=='A' and celltext(1,1)=='D' and celltext(1,2)=='r1c2';command('edit.undo')
        note('tsv-paste-fills-selected-rectangle-and-undo')
        ribbon('Merge Selected Cells');assert len(grid().findall('merge'))==1
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cellSelection'] is not None
        note('merge-selected-cells-and-undo')
        ribbon('Delete Selected Rows');assert len(grid().findall('row'))==1 and celltext(0,0)=='r2c0'
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cellSelection'] is not None
        note('delete-selected-rows-and-undo')
        ribbon('Delete Selected Columns');assert len(grid().findall('row')[0].findall('cell'))==1 and celltext(1,0)=='r1c2'
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'] and snapshot()['cellSelection'] is not None
        note('delete-selected-columns-and-undo')
        enter(1,1);ribbon('Select Row');x=snapshot()['cellSelection'];assert x['firstRow']==x['lastRow']==1 and x['firstColumn']==0 and x['lastColumn']==2
        note('contextual-ribbon-selects-row')
        ribbon('Select Table');x=snapshot()['cellSelection'];assert x['firstRow']==0 and x['lastRow']==2 and x['firstColumn']==0 and x['lastColumn']==2
        note('contextual-ribbon-selects-whole-table')
        ribbon('Delete Selected Rows');assert grid() is None;command('edit.undo');assert snapshot(True)['xml']==fixture['xml']
        note('deleting-all-selected-rows-removes-table-and-undo-restores-it')
        command('selection.set',{'block':0,'start':0,'end':0});assert snapshot()['cellSelection'] is None
        if not find('Ribbon tabs',role='LIST'):assert not find('Table Layout',role='BUTTON')
        note('table-context-clears-on-body-selection')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title'] in ['Select table cells','Table properties']:activate('Cancel',window['windowId'],'BUTTON')
        for undo in range(80):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
