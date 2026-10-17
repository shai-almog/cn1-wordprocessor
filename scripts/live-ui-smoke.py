#!/usr/bin/env python3
"""Exercise native Change Case dialogs via built-in MCP on a disposable document.

Requires an agent-debug app. Changes use the actual UI and normal undo history.
The original model XML must be restored before a successful result is recorded.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import time

spec = importlib.util.spec_from_file_location('live_mcp', Path(__file__).with_name('live-mcp.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port', type=int, default=18765)
parser.add_argument('--allow-disposable', action='store_true', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if args.output.exists():
    parser.error('Choose a new evidence file; existing evidence is not overwritten')

fixture = 'hELLo WORLD. sECOND sentence'
cases = [('Sentence case', 'Hello world. Second sentence'),
         ('lowercase', fixture.lower()), ('UPPERCASE', fixture.upper()),
         ('Capitalize Each Word', 'Hello World. Second Sentence'),
         ('tOGGLE cASE', fixture.swapcase())]
evidence = {'cases': [], 'originalXmlRestored': False}
with module.Client(args.port) as client:
    def command(name, values=None):
        state = client.tool('word_snapshot')
        return client.tool('word_command', {'stateId': state['stateId'],
                                           'command': name, 'args': values or {}})

    def find(label, window):
        return [n for n in client.tool('ui_find', {'label': label, 'windowId': window})
                if n['label'] == label]

    def activate(label, window):
        nodes = find(label, window)
        assert len(nodes) == 1, (label, nodes)
        result = client.tool('ui_activate', {'nodeId': nodes[0]['id'], 'windowId': window})
        assert result.get('pending') or result.get('success'), result
        return result

    def wait_for(read, predicate):
        until = time.monotonic() + 3
        while True:
            value = read()
            if predicate(value):
                return value
            if time.monotonic() >= until:
                raise AssertionError(value)
            time.sleep(.03)

    before = client.tool('word_snapshot', {'xml': True})
    blocks = before['document']['blocks']
    assert blocks[0]['type'] == 'paragraph', 'The disposable document must begin with a paragraph'
    evidence['before'] = before
    try:
        if before['trackChanges']:
            command('review.trackChanges', {'value': False})
        command('selection.set', {'block': 0, 'start': 0, 'end': len(blocks[0]['text'].encode('utf-16-le'))//2})
        command('text.insert', {'text': fixture})
        for label, expected in cases:
            command('selection.set', {'block': 0, 'start': 0, 'end': len(fixture)})
            if find('Hide keyboard', 0):
                activate('Hide keyboard', 0)
            if find('Expand ribbon', 0):
                activate('Expand ribbon', 0)
            for _ in range(8):
                matches = find('Aa ▾', 0)
                if matches and matches[0]['bounds'][2] > 0:
                    break
                activate('More ribbon groups', 0)
            activate('Aa ▾', 0)
            windows = wait_for(lambda: client.tool('ui_windows'),
                               lambda ws: any(w['title'] == 'Change case' and w['visible'] for w in ws))
            window = next(w['windowId'] for w in windows if w['title'] == 'Change case' and w['visible'])
            for _ in range(len(cases)):
                if find(label, window):
                    break
                tree = client.tool('ui_snapshot', {'windowId': window})
                control = next(n for n in tree['nodes'] if n['role'] == 'LIST')
                result = client.tool('ui_perform_action', {'windowId': window,
                    'nodeId': control['id'], 'actionId': 'scrollForward'})
                assert result.get('success'), result
            activate(label, window)
            activate('Apply', window)
            state = wait_for(lambda: client.tool('word_snapshot'), lambda s: s['document']['blocks'][0]['text'] == expected)
            evidence['cases'].append({'choice': label, 'expected': expected, 'state': state})
            command('edit.undo')
            assert client.tool('word_snapshot')['document']['blocks'][0]['text'] == fixture
        command('edit.undo')
        if before['trackChanges']:
            command('edit.undo')
        evidence['originalXmlRestored'] = client.tool('word_snapshot', {'xml': True})['xml'] == before['xml']
        assert evidence['originalXmlRestored']
    finally:
        # Preserve partial evidence on failure; leave the app available for diagnosis.
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
print('Five native ribbon modes, per-mode undo, and original XML restoration passed')
