#!/usr/bin/env python3
"""Native ribbon acceptance for table-cell comment workflows on an explicitly disposable document.

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
p.add_argument('--comment-controls', action='store_true', help='Also exercise comment/reply editing and navigation')
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

    import base64, xml.etree.ElementTree as ET
    def comments():return ET.fromstring(snapshot(True)['xml']).findall('comment')
    def cell(start,end,paragraph=0):command('selection.cell',{'block':1,'row':0,'column':0,'paragraph':paragraph,'start':start,'end':end})
    def note(label):evidence['checks'].append(label)
    def shown_comments():
        activate('Comments',role='BUTTON');return dialog('Comments')
    before=snapshot(True);evidence['before']=before;a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        blocks=before['document']['blocks'];last=len(blocks)-1
        command('selection.set',{'block':0,'start':0,'endBlock':last,'end':len(blocks[last]['text'].encode('utf-16-le'))//2})
        command('text.insert',{'text':'Body for table comments'})
        command('table.insert',{'rows':2,'columns':2})
        command('table.setCell',{'block':1,'row':0,'column':0,'text':'alpha beta gamma'})
        command('table.setCell',{'block':1,'row':1,'column':1,'text':'Other cell'})
        cell(6,10);fixture=snapshot(True)
        ribbon('New comment');window=dialog('New comment');set_text('Author','Native reviewer',window);set_text('Comment','Check the table value',window);activate('Add',window,'BUTTON')
        thread=wait(comments,lambda rows:len(rows)==1)[0];identifier=thread.get('id');assert thread.findtext('text')=='Check the table value';note('ribbon-add-comment-to-selected-cell-text')
        window=shown_comments();activate('Reply',window,'BUTTON');reply=dialog('Reply to comment');set_text('Author','Second reviewer',reply);set_text('Reply','Confirmed in this cell',reply);activate('Reply',reply,'BUTTON');window=dialog('Comments');activate('Close',window,'BUTTON')
        thread=comments()[0];assert thread.findtext('reply')=='Confirmed in this cell';note('ribbon-reply-to-cell-comment')
        window=shown_comments();activate('Resolve',window,'BUTTON');window=dialog('Comments');activate('Close',window,'BUTTON');assert comments()[0].get('resolved')=='true';note('ribbon-resolve-cell-thread')
        if a.comment_controls:
            window=shown_comments();activate('Edit',window,'BUTTON');edit=dialog('Edit comment');set_text('Text','Edited table comment\nSecond line',edit);activate('Save',edit,'BUTTON');window=dialog('Comments');activate('Close',window,'BUTTON')
            thread=comments()[0];assert thread.findtext('text')=='Edited table comment\nSecond line' and thread.get('author')=='Native reviewer' and thread.get('resolved')=='true';note('edit-cell-comment-preserves-author-and-resolved-state')
            window=shown_comments();activate('Edit',window,'BUTTON');edit=dialog('Edit comment');set_text('Text','Discard root edit',edit);activate('Cancel',edit,'BUTTON');activate('Close',window,'BUTTON');assert comments()[0].findtext('text')=='Edited table comment\nSecond line';note('cancel-comment-edit-preserves-text')
            window=shown_comments();activate('Edit reply 1 by Second reviewer',window,'BUTTON');edit=dialog('Edit reply');set_text('Text','Edited reply\nSecond line',edit);activate('Save',edit,'BUTTON');window=dialog('Comments');activate('Close',window,'BUTTON')
            thread=comments()[0];assert thread.findtext('reply')=='Edited reply\nSecond line' and thread.find('reply').get('author')=='Second reviewer' and thread.get('resolved')=='true';note('edit-cell-reply-preserves-author-and-resolved-state')
            window=shown_comments();activate('Edit reply 1 by Second reviewer',window,'BUTTON');edit=dialog('Edit reply');set_text('Text','Discard reply edit',edit);activate('Cancel',edit,'BUTTON');activate('Close',window,'BUTTON');assert comments()[0].findtext('reply')=='Edited reply\nSecond line';note('cancel-reply-edit-preserves-text')
            command('edit.undo');assert comments()[0].findtext('reply')=='Confirmed in this cell';note('reply-edit-undo-restores-text')
            command('edit.redo');assert comments()[0].findtext('reply')=='Edited reply\nSecond line';note('reply-edit-redo-restores-text')
            command('edit.undo');command('edit.undo');assert comments()[0].findtext('text')=='Check the table value'
        window=shown_comments();activate('Reopen',window,'BUTTON');window=dialog('Comments');activate('Close',window,'BUTTON');assert comments()[0].get('resolved')=='false';note('ribbon-reopen-cell-thread')
        command('selection.set',{'block':0,'start':0,'end':0});window=shown_comments();activate('Go to',window,'BUTTON');state=snapshot();assert state['cell']=={'block':1,'row':0,'column':0};assert state['selection']['anchor']==6 and state['selection']['caret']==10;note('ribbon-go-to-reenters-cell-and-selects-anchor')
        cell(0,0);command('text.insert',{'text':'prefix '});command('comment.select',{'id':identifier});state=snapshot();assert state['selection']['anchor']==13 and state['selection']['caret']==17;note('cell-comment-follows-insertion')
        cell(13,13);command('text.insert',{'text':'\n'});command('comment.select',{'id':identifier});state=snapshot();assert state['selection']['block']==1 and state['selection']['anchor']==0 and state['selection']['caret']==4;note('cell-comment-follows-paragraph-split')
        cell(0,0);resource=next(x for x in c.request('resources/list')['resources'] if x.get('mimeType')=='image/png');png=c.request('resources/read',{'uri':resource['uri']});a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        ribbon('New comment');window=dialog('New comment');set_text('Comment','Discard this',window);activate('Cancel',window,'BUTTON');assert len(comments())==1;note('cancel-new-cell-comment-preserves-thread')
        command('table.structure',{'block':1,'action':'insertRow','index':0});window=shown_comments();activate('Go to',window,'BUTTON');state=snapshot();assert state['cell']['row']==1;note('comment-navigation-follows-inserted-table-row')
        window=shown_comments();activate('Delete',window,'BUTTON');window=dialog('Comments');activate('Close',window,'BUTTON');assert not comments();note('ribbon-delete-cell-thread')
        command('edit.undo');assert len(comments())==1 and len(comments()[0].findall('reply'))==1;note('delete-undo-restores-cell-comment-and-reply')
        command('table.structure',{'block':1,'action':'deleteRow','index':1});assert not comments();command('edit.undo');assert len(comments())==1;note('deleting-commented-cell-removes-thread-and-undo-restores-it')
        if a.comment_controls:
            command('selection.set',{'block':0,'start':1,'end':4});command('comment.add',{'author':'Body reviewer','text':'Earlier body comment'})
            command('selection.set',{'block':0,'start':0,'end':0});ribbon('Next comment');state=snapshot();assert state['cell']['block']==-1 and state['selection']['caret']==1 and state['selection']['anchor']==1;note('next-comment-uses-document-order-not-creation-order')
            ribbon('Next comment');state=snapshot();assert state['cell']=={'block':1,'row':1,'column':0} and state['selection']['block']==1 and state['selection']['caret']==0;note('next-comment-enters-cell-at-anchor')
            ribbon('Next comment');state=snapshot();assert state['cell']['block']==-1 and state['selection']['caret']==1;note('next-comment-wraps-to-body')
            ribbon('Previous comment');state=snapshot();assert state['cell']=={'block':1,'row':1,'column':0} and state['selection']['block']==1 and state['selection']['caret']==0;note('previous-comment-wraps-into-cell')
        evidence['after']=snapshot(True)
    finally:
        # Mobile dialogs share window 0; closing a child reveals its parent.
        # Refresh after each close instead of using one stale window inventory.
        for _ in range(12):
            windows=[w for w in c.tool('ui_windows') if w['visible'] and w['title'] in ['New comment','Reply to comment','Edit comment','Edit reply','Comments','Error']]
            if not windows:break
            window=windows[-1]
            label='Close' if window['title']=='Comments' else 'OK' if window['title']=='Error' else 'Cancel'
            activate(label,window['windowId'],'BUTTON')
            time.sleep(.05)
        for undo in range(100):
            if snapshot(True)['xml']==before['xml']:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
