#!/usr/bin/env python3
"""Native ribbon acceptance for note settings on an explicitly disposable document.

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
        return c.tool('word_command', {'stateId': snapshot()['stateId'], 'command': name, 'args': args or {}})['result']

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

    before=snapshot(True);evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']: command('review.trackChanges',{'value':False})
        end=len(before['document']['blocks'][0]['text'].encode('utf-16-le'))//2
        command('selection.set',{'block':0,'start':0,'end':end})
        command('text.insert',{'text':'Note options acceptance. '})
        command('styles.apply',{'id':'Normal'})
        command('references.footnote',{'text':'Configured footnote'})
        command('references.endnote',{'text':'Configured endnote'})
        baseline=snapshot(True)
        ribbon('Note Options');window=dialog('Footnote and Endnote')
        import base64
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        png=c.request('resources/read',{'uri':resource['uri']})
        a.output.with_name(a.output.stem+'-dialog.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        choose('Number format','I, II, III',window)
        set_text('Start at','7',window)
        choose('Numbering','Each page',window)
        choose('Position','Below text',window)
        activate('Apply',window,'BUTTON')
        options=command('references.noteOptions')
        assert options['footnote']=={'numFmt':'upperRoman','numStart':7,'numRestart':'eachPage','pos':'beneathText'},options
        notes=command('references.notes');assert notes[0]['label']=='VII',notes
        evidence['checks'].append('ribbon-footnote-format-start-page-restart-and-placement')
        foot=snapshot(True)
        ribbon('Note Options');window=dialog('Footnote and Endnote')
        choose('Notes','Endnotes',window)
        choose('Number format','A, B, C',window)
        set_text('Start at','4',window)
        choose('Numbering','Each section',window)
        choose('Position','End of section',window)
        choose('Apply to','Selected sections',window)
        activate('Apply',window,'BUTTON')
        options=command('references.noteOptions')
        assert options['endnote']=={'numFmt':'upperLetter','numStart':4,'numRestart':'eachSect','pos':'sectEnd'},options
        notes=command('references.notes');assert notes[1]['label']=='D',notes
        evidence['checks'].append('ribbon-endnote-section-override')
        changed=snapshot(True);evidence['options']=options;evidence['changed']=changed
        command('edit.undo');assert snapshot(True)['xml']==foot['xml']
        command('edit.redo');assert snapshot(True)['xml']==changed['xml']
        evidence['checks'].append('single-undo-and-redo-retain-note-settings')
        ribbon('Note Options');window=dialog('Footnote and Endnote')
        set_text('Start at','31',window);activate('Cancel',window,'BUTTON')
        assert snapshot(True)['xml']==changed['xml']
        evidence['checks'].append('cancel-does-not-change-document')
        import base64
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        png=c.request('resources/read',{'uri':resource['uri']})
        a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title']=='Footnote and Endnote':
                activate('Cancel',window['windowId'],'BUTTON')
        for undo in range(40):
            if snapshot(True)['xml']==before['xml']:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
        assert evidence['originalXmlRestored']
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
