#!/usr/bin/env python3
"""Native table sizing and AutoFit ribbon acceptance on a disposable document.

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
    def geometry():return c.tool('word_layout',{})
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
        ribbon('Table properties…');w=dialog('Table properties');set_text('Preferred table width','50',w);activate('Cancel',w)
        assert snapshot(True)['xml']==fixture['xml'];note('properties-cancel-preserves-document')
        for unit,value in [('Percent','60'),('Points','180')]:
            for align in ['left','center','right']:
                ribbon('Table properties…');w=dialog('Table properties')
                choose('Table width units',unit,w);set_text('Preferred table width',value,w);choose('Table alignment',align,w);set_text('Table indent (pt; left alignment)','18',w)
                activate('Apply',w);wait(lambda:props().get('align'),lambda v:v==align)
                f=props();assert f.get('widthUnit')==('percent' if unit=='Percent' else 'points') and float(f.get('width'))==float(value) and float(f.get('indent'))==18
                assert snapshot()['cell']['block']==1 and snapshot()['selection']==fixture['selection']
                evidence.setdefault('layouts',{})[unit+'-'+align]=geometry()
                changed=snapshot(True)['xml'];command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];command('edit.redo');assert snapshot(True)['xml']==changed
                note(unit+'-'+align+'-apply-undo-redo');command('edit.undo')
        ribbon('AutoFit Contents');assert props().get('widthUnit')=='auto' and props().get('fixed')=='false'
        widths=[float(e.get('width')) for e in props().findall('column')];assert widths[1]>widths[0]>0
        contents=snapshot(True);evidence['contentsLayout']=geometry();note('autofit-contents')
        command('table.setCell',{'block':1,'row':1,'column':0,'text':'abcdefghijklmno'})
        evidence['editedContentsLayout']=geometry()
        def first_width(layout):
            cells=[x for x in layout['cells'] if x['block']==1 and x['row']==0 and x['column']==0]
            assert len(cells)==1,layout
            return cells[0]['bounds'][2]
        assert first_width(evidence['editedContentsLayout']) > first_width(evidence['contentsLayout']), evidence
        note('contents-edit-recalculates-layout')
        command('selection.cell',{'block':1,'row':0,'column':0,'paragraph':0,'start':0,'end':0})
        ribbon('Fixed Column Width');assert props().get('fixed')=='true' and props().get('widthUnit')=='points'
        fixed_width=float(props().get('width'));assert fixed_width>sum(widths);note('fixed-freezes-edited-contents-width')
        command('edit.undo');command('edit.undo');assert snapshot(True)['xml']==contents['xml']
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml']
        ribbon('AutoFit Window');f=props();assert f.get('widthUnit')=='percent' and float(f.get('width'))==100 and f.get('fixed')=='false';note('autofit-window')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];note('autofit-window-undo')
        ribbon('Table properties…');w=dialog('Table properties');choose('Table width units','Contents',w);activate('Apply',w)
        assert props().get('widthUnit')=='auto' and props().get('fixed')=='false';note('properties-contents-mode')
        command('edit.undo');assert snapshot(True)['xml']==fixture['xml']
        ribbon('Table properties…');w=dialog('Table properties');choose('Table width units','Percent',w);set_text('Preferred table width','60',w);choose('Table alignment','center',w);activate('Apply',w)
        if find('Expand ribbon'):activate('Expand ribbon')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_suffix('.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
        note('centered-table-screenshot')
    finally:
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
