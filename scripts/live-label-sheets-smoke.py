#!/usr/bin/env python3
"""Native ribbon acceptance for multi-sheet recipient labels on an explicitly disposable document.

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
    def save_image(name):
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_name(a.output.stem+'-'+name+'.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        csv='First Name,City\n'+''.join('Person '+str(i)+',London\n' for i in range(1,62))
        command('mailings.recipients',{'csv':csv})
        original=snapshot(True)
        ribbon('Labels','Mailings');window=dialog('Labels')
        choose('Contents','One recipient per label',window)
        activate('Cancel',window,'BUTTON');assert snapshot(True)['xml']==original['xml']
        note('label-dialog-cancel-preserves-document')
        ribbon('Labels','Mailings');window=dialog('Labels')
        choose('Contents','One recipient per label',window)
        activate('Create',window,'BUTTON')
        result=snapshot(True);root=ET.fromstring(result['xml']);tables=root.findall('table');assert len(tables)==3
        names=[]
        for table in tables:
            for run in table.findall('row/cell/p/r'):
                if (run.text or '').startswith('Person '):names.append(run.text)
        assert names==['Person '+str(i) for i in range(1,62)],names
        note('all-61-recipients-fill-three-sheets-in-order')
        assert sum(bool(''.join(c.itertext())) for c in tables[-1].findall('row/cell'))==1
        note('last-sheet-unused-labels-remain-empty')
        save_image('sheets');evidence['labels']=result
        command('edit.undo');assert snapshot(True)['xml']==original['xml'];assert command('mailings.inspect')['records']==61
        note('multi-sheet-generation-is-one-undo')
        command('edit.redo');assert snapshot(True)['xml']==result['xml'];command('edit.undo')
        note('redo-restores-all-label-sheets')
        # Filtered order is the label order, not the original CSV order.
        command('mailings.manageRecipients',{'filterField':'First Name','filterOperator':'contains','filterValue':'Person 6','sortField':'First Name','descending':True})
        applied=command('mailings.labels',{'fromRecipients':True});assert applied['filled']==3 and applied['sheets']==1,applied
        expected=['Person 61','Person 60','Person 6']
        table=ET.fromstring(snapshot(True)['xml']).find('table')
        names=[r.text for r in table.findall('row/cell/p/r') if (r.text or '').startswith('Person ')]
        assert names==expected,names
        note('label-sheets-follow-selected-recipient-order')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title']=='Labels' and find('Cancel',window['windowId'],'BUTTON'):activate('Cancel',window['windowId'],'BUTTON')
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
