#!/usr/bin/env python3
"""Native ribbon acceptance for table of contents on an explicitly disposable document.

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
        return c.tool('word_command', {'stateId': snapshot()['stateId'], 'command': name, 'args': args or {}})

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
        activate('References')
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


    before = snapshot(True)
    evidence['before'] = before
    try:
        if before['trackChanges']: command('review.trackChanges', {'value':False})
        end = len(before['document']['blocks'][0]['text'].encode('utf-16-le')) // 2
        command('selection.set', {'block':0,'start':0,'end':end})
        command('text.insert', {'text':'\nOverview\nDetails'})
        command('selection.set', {'block':1,'start':0,'end':8})
        command('styles.apply', {'id':'Heading1'})
        command('selection.set', {'block':2,'start':0,'end':7})
        command('styles.apply', {'id':'Heading2'})
        command('selection.set', {'block':0,'start':0,'end':0})
        ribbon('Table of Contents')
        window = dialog('Table of contents')
        n = find('Title', window, 'TEXT_FIELD')[0]
        c.tool('ui_set_text', {'windowId':window,'nodeId':n['id'],'text':'Guide'})
        activate('2', window, 'LIST_ITEM')
        activate('Hyphen', window, 'LIST_ITEM')
        activate('Insert', window, 'BUTTON')
        wait(lambda:c.tool('ui_windows'), lambda ws:not any(w['title']=='Table of contents' and w['visible'] for w in ws))
        result = snapshot(True)
        assert result['text'].split('\n')[:3] == ['Guide','Overview\t1','Details\t1'], result['text']
        evidence['checks'].append('native-dialog-title-levels-leader-and-insert')
        evidence['inserted'] = result
        import base64
        resource = next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        png = c.request('resources/read', {'uri':resource['uri']})
        a.output.with_name(a.output.stem+'-inserted.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        heading = next(i for i,b in enumerate(result['document']['blocks']) if b.get('text')=='Overview')
        command('selection.set', {'block':1,'start':1,'end':1})
        ribbon('Go to reference')
        assert snapshot()['selection']['block'] == heading, snapshot()['selection']
        evidence['checks'].append('follow-generated-heading-link')
        command('selection.set', {'block':heading,'start':0,'end':8})
        command('text.insert', {'text':'Introduction'})
        ribbon('Update Table')
        assert snapshot()['text'].split('\n')[1] == 'Introduction\t1'
        evidence['checks'].append('update-after-heading-edit')
        evidence['updated'] = snapshot(True)
        import base64
        resource = next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        png = c.request('resources/read', {'uri':resource['uri']})
        a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        ribbon('Remove Table')
        assert 'Guide' not in snapshot()['text']
        command('edit.undo')
        assert snapshot()['text'].split('\n')[0] == 'Guide'
        evidence['checks'].append('remove-and-single-undo')
    finally:
        for undo in range(30):
            if snapshot(True)['xml'] == before['xml']: break
            command('edit.undo')
        evidence['originalXmlRestored'] = snapshot(True)['xml'] == before['xml']
        evidence['undoCount'] = undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
        assert evidence['originalXmlRestored']
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
