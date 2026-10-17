#!/usr/bin/env python3
"""Native ribbon acceptance for tracked comparison workflows on an explicitly disposable document.

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
p.add_argument('--blank',type=Path,required=True,help='Disposable blank CN1DOC fixture')
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

    def ribbon(label):
        if find('Hide keyboard'):
            activate('Hide keyboard')
        if find('Expand ribbon'):
            activate('Expand ribbon')
        activate('Home')
        if find('Ribbon tabs',role='LIST'):choose('Ribbon tabs','Review',0)
        else:activate('Review')
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

    import zipfile,base64
    with zipfile.ZipFile(a.blank) as z:blank=z.read('document.xml').decode()
    before=snapshot(True);evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    old='The cat sat.\nKeep this.';new='The dog sat.\nKeep this.\nNew line here.'
    def comparison(text,author='Native reviewer',characters=False):
        ribbon('Compare documents');window=dialog('Compare documents');activate('Continue',window,'BUTTON')
        window=dialog('Comparison options');set_text('Revised text',text,window);set_text('Label changes with',author,window)
        if characters:choose('Compare at','Character',window)
        activate('Compare',window,'BUTTON');window=dialog('Comparison complete');activate('OK',window,'BUTTON')
    try:
        command('review.compare',{'originalXml':blank,'text':old});command('review.acceptAll');fixture=snapshot(True)
        comparison(new)
        changes=command('review.changes');assert {'insert','delete'}<=set(x['kind'] for x in changes);assert all(x['author']=='Native reviewer' for x in changes)
        compared=snapshot(True);evidence['compared']=compared;evidence['checks'].append('ribbon-comparison-produces-authored-insertions-deletions-and-paragraph-revisions')
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png');png=c.request('resources/read',{'uri':resource['uri']});a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        ribbon('Accept all');assert snapshot()['text']==new;assert not command('review.changes');evidence['checks'].append('ribbon-accept-all-produces-exact-revised-paragraphs')
        command('edit.undo');assert snapshot(True)['xml']==compared['xml']
        ribbon('Reject all');assert snapshot()['text']==old;assert not command('review.changes');evidence['checks'].append('ribbon-reject-all-restores-exact-original-paragraphs')
        command('edit.undo');command('edit.undo');assert snapshot(True)['xml']==fixture['xml'];command('edit.redo');assert snapshot(True)['xml']==compared['xml'];command('edit.undo');evidence['checks'].append('comparison-is-one-undo-redo-step')
        ribbon('Compare documents');window=dialog('Compare documents');activate('Continue',window,'BUTTON');window=dialog('Comparison options');set_text('Revised text','Discarded',window);activate('Cancel',window,'BUTTON');assert snapshot(True)['xml']==fixture['xml'];evidence['checks'].append('comparison-cancel-preserves-original')
        comparison('The cats sat.\nKeep this.',characters=True);changes=command('review.changes');assert any(x['kind']=='insert' and x['text']=='s' for x in changes);ribbon('Accept all');assert snapshot()['text']=='The cats sat.\nKeep this.';evidence['checks'].append('ribbon-character-level-comparison')
        command('edit.undo');command('edit.undo')
        ribbon('Compare documents');window=dialog('Compare documents');activate('Continue',window,'BUTTON');window=dialog('Comparison options');set_text('Revised text','THE CAT SAT.\nKeep this.',window);activate('Case changes',window);activate('Compare',window,'BUTTON');window=dialog('Comparison complete');activate('OK',window,'BUTTON');assert not command('review.changes');assert snapshot()['text']=='THE CAT SAT.\nKeep this.';evidence['checks'].append('ribbon-ignore-case-adopts-revised-spelling-without-revisions')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title'] in ['Compare documents','Comparison options']:activate('Cancel',window['windowId'],'BUTTON')
            elif window['visible'] and window['title'] in ['Comparison complete','Error']:activate('OK',window['windowId'],'BUTTON')
        for undo in range(80):
            if snapshot(True)['xml']==before['xml']:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
