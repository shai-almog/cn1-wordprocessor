#!/usr/bin/env python3
"""Native ribbon acceptance for mail merge recipient and field workflows on an explicitly disposable document.

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

    def ribbon(label):
        if find('Hide keyboard'):
            activate('Hide keyboard')
        if find('Expand ribbon'):
            activate('Expand ribbon')
        activate('Home')
        if find('Ribbon tabs',role='LIST'):choose('Ribbon tabs','Mailings',0)
        else:activate('Mailings')
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

    before=snapshot(True);original_merge=command('mailings.inspect');evidence['before']=before
    a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    try:
        if before['trackChanges']:command('review.trackChanges',{'value':False})
        end=len(before['document']['blocks'][0]['text'].encode('utf-16-le'))//2
        command('selection.set',{'block':0,'start':0,'end':end});command('text.insert',{'text':'Destination: '})
        ribbon('Select Recipients');window=dialog('Select recipients');activate('Continue',window,'BUTTON');window=dialog('Edit recipient list')
        set_text('Recipient CSV','First Name,Last Name,City,Flag\nAda,Lovelace,London,yes\nAlan,Turing,Wilmslow,no',window);activate('Apply',window,'BUTTON')
        assert command('mailings.inspect')['records']==2
        evidence['checks'].append('ribbon-recipient-list-loads-quoted-csv')
        ribbon('Insert Merge Field');window=dialog('Insert merge field');choose('Field','City',window);activate('Insert',window,'BUTTON')
        assert 'Destination: «City»' in snapshot()['text'];evidence['checks'].append('ribbon-inserts-editable-merge-field')
        ribbon('Preview Results');assert 'Destination: London' in snapshot()['text']
        ribbon('Next Recipient');assert 'Destination: Wilmslow' in snapshot()['text'];assert command('mailings.inspect')['record']==2
        ribbon('Previous Recipient');assert 'Destination: London' in snapshot()['text'];evidence['checks'].append('ribbon-preview-and-recipient-navigation')
        ribbon('Find Recipient');window=dialog('Find recipient');set_text('Find text','alan',window);activate('Find',window,'BUTTON');assert 'Wilmslow' in snapshot()['text']
        evidence['checks'].append('ribbon-find-recipient')
        ribbon('Rules');window=dialog('Merge rule');choose('Rule','IF',window);choose('Field','Flag',window);set_text('Compare to','yes',window);set_text('If true','Selected',window);set_text('If false','Other',window);activate('Insert',window,'BUTTON')
        assert 'WilmslowOther' in snapshot()['text'];ribbon('Previous Recipient');assert 'LondonSelected' in snapshot()['text'];evidence['checks'].append('ribbon-if-rule-previews-each-record')
        ribbon('Highlight Merge Fields');assert command('mailings.inspect')['highlight'];evidence['checks'].append('ribbon-merge-field-shading')
        ribbon('Match Fields');window=dialog('Match fields');choose('firstName','City',window);activate('Apply',window,'BUTTON')
        assert command('mailings.matchFields')['mapping']['firstName']=='City';command('edit.undo');assert command('mailings.matchFields')['mapping']['firstName']=='First Name'
        evidence['checks'].append('ribbon-address-field-mapping-and-undo')
        ribbon('Check for Errors');window=dialog('Merge validation');activate('OK',window,'BUTTON');assert command('mailings.checkErrors')['ok']
        ribbon('Select Recipients');window=dialog('Select recipients');activate('Continue',window,'BUTTON');window=dialog('Edit recipient list');set_text('Recipient CSV','Wrong\nDiscard',window);activate('Cancel',window,'BUTTON');assert command('mailings.inspect')['records']==2
        evidence['checks'].append('recipient-dialog-cancel-keeps-list')
        template=snapshot(True);template_merge=command('mailings.inspect')
        ribbon('Finish & Merge');window=dialog('Finish merge');set_text('First recipient','1',window);set_text('Last recipient','2',window);activate('Merge',window,'BUTTON')
        generated=snapshot(True);assert 'Destination: LondonSelected' in generated['text'] and 'Destination: WilmslowOther' in generated['text'];assert 'MERGEFIELD' not in generated['xml']
        evidence['checks'].append('ribbon-finish-creates-independent-merged-letters');evidence['generated']=generated
        import base64
        resource=next(r for r in c.request('resources/list')['resources'] if r.get('mimeType')=='image/png');png=c.request('resources/read',{'uri':resource['uri']});a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
        command('edit.undo');assert snapshot(True)['xml']==template['xml'];assert command('mailings.inspect')==template_merge
        command('edit.redo');assert snapshot(True)['xml']==generated['xml'];evidence['checks'].append('finish-undo-redo-restores-template-and-recipient-state')
        # Physical sheet dialogs are independently reversible after finishing the letters.
        previous=snapshot(True)
        ribbon('Envelopes');window=dialog('Envelopes');set_text('Delivery address','Ada Lovelace\n1 Main Road\nLondon',window);set_text('Return address','Sender',window);choose('Envelope size','Envelope DL',window);activate('Create',window,'BUTTON')
        envelope=snapshot(True);assert 'Ada Lovelace' in envelope['text'] and 'Destination: LondonSelected' in envelope['text'];evidence['envelope']=envelope
        command('edit.undo');assert snapshot(True)['xml']==previous['xml'];evidence['checks'].append('ribbon-add-envelope-section-and-undo')
        ribbon('Labels');window=dialog('Labels');choose('Label product','letter-3x10',window);choose('Contents','Single label',window);set_text('Label text','Ada\nLondon',window);set_text('Label row','2',window);set_text('Label column','3',window);activate('Create',window,'BUTTON')
        label=snapshot(True);assert label['text'].count('Ada')==1 and label['text'].count('London')==1;evidence['labels']=label
        command('edit.undo');assert snapshot(True)['xml']==previous['xml'];evidence['checks'].append('ribbon-single-label-position-and-undo')
        # Native ZIP/XML/date paths, including worksheet choice, run in ParparVM on Mac/iOS.
        import io,zipfile
        wb=io.BytesIO()
        with zipfile.ZipFile(wb,'w',zipfile.ZIP_DEFLATED) as z:
            z.writestr('xl/workbook.xml',"<workbook xmlns:r='r'><sheets><sheet name='Notes' r:id='r1'/><sheet name='People' r:id='r2'/></sheets></workbook>")
            z.writestr('xl/_rels/workbook.xml.rels',"<Relationships><Relationship Id='r1' Target='worksheets/one.xml' Type='x/worksheet'/><Relationship Id='r2' Target='worksheets/two.xml' Type='x/worksheet'/></Relationships>")
            z.writestr('xl/worksheets/one.xml','<worksheet><sheetData/></worksheet>')
            z.writestr('xl/worksheets/two.xml',"<worksheet><sheetData><row><c t='inlineStr'><is><t>Name</t></is></c><c t='inlineStr'><is><t>Date</t></is></c></row><row><c t='inlineStr'><is><t>Ada</t></is></c><c s='1'><v>61.5</v></c></row></sheetData></worksheet>")
            z.writestr('xl/styles.xml',"<styleSheet><cellXfs><xf numFmtId='0'/><xf numFmtId='22'/></cellXfs></styleSheet>")
        data=base64.b64encode(wb.getvalue()).decode();choice=command('mailings.recipients',{'data':data});assert choice['chooseSheet'] and choice['sheets']==['Notes','People']
        command('mailings.recipients',{'data':data,'sheet':'People'});assert command('mailings.editRecipients')['rows']==[['Ada','1900-03-01 12:00']]
        command('edit.undo');assert command('mailings.inspect')['records']==2;evidence['checks'].append('native-xlsx-worksheet-selection-and-date-import')
    finally:
        for window in c.tool('ui_windows'):
            if window['visible'] and window['title'] in ['Edit recipient list','Insert merge field','Find recipient','Merge rule','Match fields','Finish merge','Start mail merge','Select recipients','Import recipients','Import worksheet','Envelopes','Labels']:
                activate('Cancel',window['windowId'],'BUTTON')
            elif window['visible'] and window['title']=='Merge validation':activate('OK',window['windowId'],'BUTTON')
        for undo in range(80):
            if snapshot(True)['xml']==before['xml'] and command('mailings.inspect')==original_merge:break
            command('edit.undo')
        evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['originalMergeStateRestored']=command('mailings.inspect')==original_merge;evidence['undoCount']=undo
        a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored'] and evidence['originalMergeStateRestored']
        if find('Hide keyboard'):activate('Hide keyboard')
        wait(lambda:c.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
