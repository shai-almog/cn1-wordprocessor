#!/usr/bin/env python3
"""Native ribbon acceptance for advanced contents sources on an explicitly disposable document.

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


    def set_text(label,text,window):
        nodes=find(label,window,'TEXT_FIELD');assert len(nodes)==1,(label,nodes)
        c.tool('ui_set_text',{'windowId':window,'nodeId':nodes[0]['id'],'text':text})

    def choose(label,value,window):
        tree=c.tool('ui_snapshot',{'windowId':window})['nodes']
        parent=next(n for n in tree if n['label']==label and n['role']=='LIST')
        c.tool('ui_perform_action',{'windowId':window,'nodeId':parent['id'],'actionId':'focus'})
        tree=c.tool('ui_snapshot',{'windowId':window})['nodes']
        items=[n for n in tree if n['parent']==parent['id'] and n['role']=='LIST_ITEM' and n['label']==value]
        assert len(items)==1,(label,value,parent,items)
        result=c.tool('ui_activate',{'windowId':window,'nodeId':items[0]['id']})
        assert result.get('success'),result

    before=snapshot(True);evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        end=len(before['document']['blocks'][0]['text'].encode('utf-16-le'))//2
        command('selection.set',{'block':0,'start':0,'end':end})
        command('text.insert',{'text':'\nHeading source\nMapped source\nOutline source\nMarked source'})
        for block,style in [(0,'Normal'),(1,'Heading1'),(2,'Caption'),(3,'Normal'),(4,'Normal')]:
            command('selection.set',{'block':block,'start':0,'end':0});command('styles.apply',{'id':style})
        command('selection.set',{'block':3,'start':0,'end':0})
        ribbon('Add Text');window=dialog('Add text to contents')
        activate('Level 2',window,'LIST_ITEM');activate('Apply',window,'BUTTON')
        assert 'outlineLevel="2"' in snapshot(True)['xml']
        evidence['checks'].append('ribbon-add-text-outline-level')
        command('selection.set',{'block':4,'start':0,'end':6})
        ribbon('Mark Entry');window=dialog('Mark contents entry')
        assert find('Entry text',window,'TEXT_FIELD')[0]['value']=='Marked'
        set_text('Entry text','Marked entry',window);activate('3',window,'LIST_ITEM')
        activate('Mark',window,'BUTTON')
        assert snapshot()['document']['blocks'][4]['text'].replace('\ufffc','')=='Marked source'
        evidence['checks'].append('ribbon-mark-entry-retains-source')
        command('selection.set',{'block':0,'start':0,'end':0})
        ribbon('Table of Contents');window=dialog('Table of contents')
        activate('Use marked entries',window,'CHECKBOX')
        set_text('Entry identifier (blank for all)','C',window)
        activate('Style levels…',window,'BUTTON');styles=dialog('Contents style levels')
        choose('Caption','2',styles);activate('Apply',styles,'BUTTON')
        activate('Insert',window,'BUTTON')
        result=snapshot(True)
        assert result['text'].split('\n')[:5]==['Contents','Heading source\t1','Mapped source\t1','Outline source\t1','Marked entry\t1'],result['text']
        evidence['checks'].append('ribbon-combined-heading-outline-style-and-marked-sources')
        evidence['inserted']=result
        import base64
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
        png=c.request('resources/read',{'uri':resource['uri']})
        a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        ribbon('Table of Contents');window=dialog('Table of contents')
        activate('Style levels…',window,'BUTTON');styles=dialog('Contents style levels')
        choose('Caption','1',styles);activate('Apply',styles,'BUTTON')
        activate('Cancel',window,'BUTTON');assert snapshot(True)['xml']==result['xml']
        evidence['checks'].append('nested-style-apply-parent-cancel-is-nonmutating')
        ribbon('Update Table');updated=snapshot(True)
        assert updated['text']==result['text']
        evidence['checks'].append('update-retains-advanced-source-options')
        ribbon('Remove Table');assert not snapshot()['text'].startswith('Contents')
        command('edit.undo');assert snapshot(True)['xml']==updated['xml']
        evidence['checks'].append('remove-and-single-undo')
    finally:
        for title in ['Contents style levels','Table of contents','Mark contents entry','Add text to contents']:
            for w in c.tool('ui_windows'):
                if w['title']==title and w['visible']:
                    activate('Cancel',w['windowId'],'BUTTON')
        for undo in range(30):
            if snapshot(True)['xml']==before['xml']:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
        assert evidence['originalXmlRestored']
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
