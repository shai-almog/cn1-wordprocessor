#!/usr/bin/env python3
"""Native ribbon acceptance for bookmarks/references on an explicitly disposable document.

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
        activate('References')
        for _ in range(8):
            nodes = find(label)
            if nodes and nodes[0]['bounds'][2] > 0:
                activate(label)
                return
            activate('More ribbon groups')
        raise AssertionError('Ribbon control not visible: ' + label)

    def bookmark(action):
        ribbon('Bookmark')
        window = dialog('Bookmarks')
        if action == 'Add':
            n = find('Bookmark name', window, 'TEXT_FIELD')[0]
            result = c.tool('ui_set_text', {'windowId': window, 'nodeId': n['id'], 'text': 'Spot'})
            assert result.get('success'), result
        activate(action, window, 'BUTTON')
        wait(lambda: c.tool('ui_windows'), lambda ws: not any(w['title'] == 'Bookmarks' and w['visible'] for w in ws))

    def reference(kind, display):
        state = snapshot()
        end = len(state['document']['blocks'][1]['text'].encode('utf-16-le')) // 2
        command('selection.set', {'block': 1, 'start': end, 'end': end})
        ribbon('Cross-reference')
        window = dialog('Cross-reference')
        activate(kind, window, 'LIST_ITEM')
        activate(display, window, 'LIST_ITEM')
        activate('Insert', window, 'BUTTON')
        wait(lambda: c.tool('ui_windows'), lambda ws: not any(w['title'] == 'Cross-reference' and w['visible'] for w in ws))

    before = snapshot(True)
    evidence['before'] = before
    try:
        assert before['document']['blocks'][0]['type'] == 'paragraph'
        if before['trackChanges']:
            command('review.trackChanges', {'value': False})
        end = len(before['document']['blocks'][0]['text'].encode('utf-16-le')) // 2
        command('selection.set', {'block': 0, 'start': 0, 'end': end})
        command('text.insert', {'text': 'Kilns\nSee '})
        command('selection.set', {'block': 0, 'start': 0, 'end': 5})
        command('styles.apply', {'id': 'Heading1'})
        bookmark('Add')
        marks = command('bookmark.list')['result']
        assert any(m['name'] == 'Spot' and m['text'] == 'Kilns' for m in marks), marks
        evidence['checks'].append('bookmark-add-native-dialog')
        command('selection.set', {'block': 1, 'start': 4, 'end': 4})
        bookmark('Go To')
        assert snapshot()['selection'] == {'anchorBlock': 0, 'anchor': 0, 'block': 0, 'caret': 5}
        evidence['checks'].append('bookmark-go-to-native-dialog')
        bookmark('Delete')
        assert not any(m['name'] == 'Spot' for m in command('bookmark.list')['result'])
        command('edit.undo')
        assert any(m['name'] == 'Spot' for m in command('bookmark.list')['result'])
        evidence['checks'].append('bookmark-delete-and-undo')
        reference('Bookmark', 'Text')
        assert snapshot()['text'].split('\n')[1] == 'See Kilns'
        evidence['checks'].append('bookmark-text-reference-native-dialog')
        ribbon('Go to reference')
        assert snapshot()['selection'] == {'anchorBlock': 0, 'anchor': 0, 'block': 0, 'caret': 5}
        evidence['checks'].append('follow-reference-ribbon')
        reference('Heading', 'Page number')
        assert snapshot()['text'].split('\n')[1] == 'See Kilns1'
        evidence['checks'].append('heading-page-reference-native-dialog')
        reference('Heading', 'Above or below')
        assert snapshot()['text'].split('\n')[1] == 'See Kilns1above'
        evidence['checks'].append('relative-reference-native-dialog')
        command('selection.set', {'block': 0, 'start': 1, 'end': 5})
        command('text.insert', {'text': 'ILNS'})
        ribbon('Update fields')
        assert snapshot()['text'].split('\n')[:2] == ['KILNS', 'See KILNS1above']
        evidence['checks'].append('references-update-after-source-edit')
        evidence['result'] = snapshot(True)
        for undo in range(30):
            if snapshot(True)['xml'] == before['xml']:
                break
            command('edit.undo')
        evidence['originalXmlRestored'] = snapshot(True)['xml'] == before['xml']
        evidence['undoCount'] = undo
        assert evidence['originalXmlRestored']
        wait(lambda: c.tool('ui_snapshot', {'windowId': 0}),
             lambda tree: any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
        evidence['recoverySavedBeforeExit'] = True
    finally:
        a.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'checks': evidence['checks'], 'originalXmlRestored': evidence['originalXmlRestored']}))
