#!/usr/bin/env python3
"""Native table formula ribbon acceptance on a disposable document.

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

    import xml.etree.ElementTree as ET, base64
    def table():
        return next(t for t in ET.fromstring(snapshot(True)['xml']).findall('table') if t.get('id') == fixture_id)
    def field(row=3, col=1):
        return table().findall('row')[row].findall('cell')[col].find('.//field')
    def enter(row=3, col=1):
        command('selection.cell', {'block':1, 'row':row, 'column':col, 'paragraph':0, 'start':0, 'end':0})
    def note(label):evidence['checks'].append(label)
    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        command('selection.set',{'block':0,'start':0,'end':0});command('table.insert',{'rows':4,'columns':3})
        fixture_id=snapshot()['document']['blocks'][1]['id']
        for row, value in enumerate(['10','20','30']):
            command('table.setCell',{'block':1,'row':row,'column':1,'text':value})
        enter();fixture=snapshot(True)
        ribbon('Formula…');d=dialog('Table Formula');set_text('Formula','=PRODUCT(ABOVE)',d);activate('Cancel',d)
        assert snapshot(True)['xml']==fixture['xml'];note('formula-dialog-cancel')
        for function, expected in [('SUM','60'),('AVERAGE','20'),('COUNT','3'),('MIN','10'),('MAX','30'),('PRODUCT','6000')]:
            enter();ribbon('Formula…');d=dialog('Table Formula')
            set_text('Formula','='+function+'(ABOVE)',d);activate('Insert',d)
            wait(lambda:field(),lambda f:f is not None and f.get('result')==expected)
            assert field().get('instruction')=='='+function+'(ABOVE)';note('insert-'+function)
            inserted=snapshot(True)['xml'];command('edit.undo');assert snapshot(True)['xml']==fixture['xml']
            command('edit.redo');assert snapshot(True)['xml']==inserted;note('undo-redo-'+function)
            command('edit.undo')
        enter();ribbon('Formula…');d=dialog('Table Formula');activate('Insert',d)
        assert field().get('result')=='60'
        command('table.setCell',{'block':1,'row':1,'column':1,'text':'70'});enter()
        before_update=snapshot(True)['xml'];ribbon('Update Formulas')
        wait(lambda:field().get('result'),lambda value:value=='110');assert snapshot()['cell']['row']==3;note('update-formulas-and-caret')
        command('edit.undo');assert snapshot(True)['xml']==before_update;note('update-formulas-undo')
        ribbon('Update fields','Insert');wait(lambda:field().get('result'),lambda value:value=='110');note('general-update-fields')
        # Forward dependency: A1 depends on A2, which depends on B1.
        enter(0,0);command('field.insert',{'instruction':'=SUM(A2)','result':'old'})
        enter(1,0);command('field.insert',{'instruction':'=PRODUCT(B1,2)','result':'old'})
        ribbon('Update Formulas');assert field(0,0).get('result')==field(1,0).get('result')=='20';note('forward-dependencies')
        enter(3,1);ribbon('Edit field…','Insert');d=dialog('Edit field');activate('Lock field',d);activate('Apply',d)
        assert field().get('locked')=='true'
        command('table.setCell',{'block':1,'row':1,'column':1,'text':'20'});enter();ribbon('Update Formulas')
        assert field().get('result')=='110';note('locked-field-preserved')
        # Restore the matching source total for the screenshot after checking the lock.
        command('table.setCell',{'block':1,'row':1,'column':1,'text':'70'});enter()
        # Capture the visible table and its calculated field results.
        if find('Hide keyboard'):activate('Hide keyboard')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        a.output.with_suffix('.png').write_bytes(base64.b64decode(c.request('resources/read',{'uri':resource['uri']})['contents'][0]['blob']))
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title'] in ['Table Formula','Edit field']:activate('Cancel',window['windowId'])
        for undo in range(100):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':len(evidence['checks']),'restored':evidence['originalXmlRestored']},indent=2))
