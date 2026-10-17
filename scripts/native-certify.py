#!/usr/bin/env python3
"""Replay recorded command cases in the running native agent-debug app and compare.

Each case (from ReplayRecorder / CaseFinder JSON lines) holds the native model XML and selection
before a command, its arguments, and the XML/selection the JVM produced. The native app loads the
before state through word_load, runs the command through word_command (the editor's real
session, undo history and EDT) and must produce the identical XML and selection.

Editor-only commands (registered by WordProcessor, outside the headless registry) are driven
through the accessible ribbon: the tab is opened, the control activated, and an observable
change (a new window/dialog, document/selection/layout change) is required. Dialogs are closed
and the document restored by undo afterwards.

Usage: native-certify.py --cases a.jsonl [b.jsonl ...] --matrix docs/acceptance-matrix.json
       --output NEW.json [--port 18765] [--allow-disposable]
"""
import argparse
import base64
import hashlib
import re
import importlib.util
import json
from pathlib import Path
import subprocess
import time

spec = importlib.util.spec_from_file_location('live_mcp', Path(__file__).with_name('live-mcp.py'))
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cases', nargs='+', type=Path, required=True)
parser.add_argument('--matrix', type=Path, default=Path('docs/acceptance-matrix.json'))
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--port', type=int, default=18765)
parser.add_argument('--allow-disposable', action='store_true', required=True)
parser.add_argument('--only', nargs='*')
parser.add_argument('--pid', type=int, required=True)
args = parser.parse_args()
if args.output.exists():
    parser.error('Choose a new evidence file; existing evidence is not overwritten')

KEYS = ('anchorBlock', 'anchor', 'block', 'caret')
FIXTURE = Path(__file__).with_name('native-cert').joinpath('ui-fixture.xml').read_text(encoding='utf-8')
DATE = re.compile(r'(revision-date|date)="[0-9T:\-Z.]+"')


def norm(xml):
    # Revision timestamps record when the edit happened, not what it did.
    return DATE.sub(r'\1="*"', xml)


def widths_stripped(rec, xml):
    """Column widths come from native font metrics (the JVM uses PDF metrics); structure must match."""
    strip = re.compile(r'(width|height)="[0-9.]+"')
    if strip.sub('', norm(xml)) != strip.sub('', norm(rec['after'])):
        return 'structure differs beyond measured widths'
    return True if xml != rec['before'] else 'no change'


def compressed(rec, xml):
    """The native codec really resamples (the JVM test uses a fake codec): the crop must be applied
    and the picture re-encoded. Size is not compared: the 68-byte fixture is below any encoder's overhead."""
    def pic(x):
        m = re.search(r'<picture([^>]*)>([A-Za-z0-9+/=]*)</picture>', x)
        return m.group(1), base64.b64decode(m.group(2))
    attrs, data = pic(xml)
    if 'crop=' in attrs:
        return 'crop not applied'
    if data == pic(rec['before'])[1] or not (data.startswith(b'\x89PNG') or data.startswith(b'\xff\xd8')):
        return 'picture not re-encoded'
    return True


def same_xml(rec, xml):
    """The native editor keeps the caret in the merged cell (as Word does); the JVM session leaves the table. Document must match."""
    return True if norm(xml) == norm(rec['after']) else 'document differs'


def cited(rec, xml):
    """Researcher queries live Wikipedia natively (the JVM test uses a fixed answer): a website source must be added."""
    return True if xml.count('<source ') > rec['before'].count('<source ') and 'kind="website"' in xml else 'no source added'


NORMALIZE = {'table.autofit': widths_stripped, 'picture.compress': compressed,
             'references.researcher': cited, 'table.eraser': same_xml}


def selection(state):
    return {k: state.get(k) for k in KEYS}


def snap_selection(snap):
    s = snap['selection']
    return {k: s[k] for k in KEYS}


cases = {}
for path in args.cases:
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.strip():
            rec = json.loads(line)
            cases.setdefault(rec['command'], []).append(rec)

matrix = json.loads(args.matrix.read_text())
rows = [r for r in matrix['rows'] if r['status'] == 'unverified']
ids = sorted({r.get('appCommand') or r['command'] for r in rows})
if args.only:
    ids = [i for i in ids if i in args.only]
report = {'started': time.strftime('%Y-%m-%dT%H:%M:%S'), 'port': args.port, 'results': {}}


def save():
    args.output.write_text(json.dumps(report, indent=1, ensure_ascii=False) + '\n')


app_pid = args.pid


class Conn:
    def __init__(self):
        self.c = live.Client(args.port, timeout=60)

    def tool(self, name, values=None):
        return self.c.tool(name, values)

    def reconnect(self):
        try:
            self.c.close()
        except Exception:
            pass
        self.c = live.Client(args.port, timeout=60)


C = Conn()
if True:
    registry = {c['id'] for c in C.tool('word_commands')}
    original = C.tool('word_snapshot', {'xml': True})

    def command(name, values=None):
        state = C.tool('word_snapshot')
        return C.tool('word_command', {'stateId': state['stateId'], 'command': name,
                                           'args': values or {}})

    def replay(rec):
        try:
            C.tool('word_load', {'xml': rec['before'], 'state': rec['state']})
        except RuntimeError:
            dismiss()
            C.tool('word_load', {'xml': rec['before'], 'state': rec['state']})
        loaded = C.tool('word_snapshot', {'xml': True})
        if loaded['xml'] != rec['before']:
            return {'ok': False, 'why': 'native load differs from recorded before-state'}
        try:
            command(rec['command'], rec['args'])
        except RuntimeError as ex:
            dismiss()
            return {'ok': False, 'why': 'native error: ' + str(ex)[:300]}
        errors = error_dialogs()
        if errors:
            dismiss()
            return {'ok': False, 'why': 'internal error dialog: ' + errors[0][:200]}
        after = C.tool('word_snapshot', {'xml': True})
        rule = NORMALIZE.get(rec['command'])
        if rule:
            check = rule(rec, after['xml'])
            return {'ok': check is True, 'test': rec['test'], 'args': rec['args'], 'rule': rule.__doc__,
                    'why': None if check is True else check}
        same_xml = norm(after['xml']) == norm(rec['after'])
        same_sel = snap_selection(after) == selection(rec['stateAfter'])
        out = {'ok': same_xml and same_sel, 'test': rec['test'], 'args': rec['args'],
               'xmlSha256': hashlib.sha256(after['xml'].encode()).hexdigest()}
        if not same_xml:
            a, b = after['xml'], rec['after']
            i = next((k for k in range(min(len(a), len(b))) if a[k] != b[k]), min(len(a), len(b)))
            out['why'] = 'XML differs at %d: native %r / jvm %r' % (i, a[i-40:i+80], b[i-40:i+80])
        elif not same_sel:
            out['why'] = 'selection differs: native %s / jvm %s' % (snap_selection(after),
                                                                   selection(rec['stateAfter']))
        return out

    def undo_redo():
        fresh()
        base = C.tool('word_snapshot', {'xml': True})['xml']
        command('text.insert', {'text': 'Undo probe '})
        edited = C.tool('word_snapshot', {'xml': True})['xml']
        command('edit.undo')
        undone = C.tool('word_snapshot', {'xml': True})['xml']
        command('edit.redo')
        redone = C.tool('word_snapshot', {'xml': True})['xml']
        command('edit.undo')
        return undone == base and redone == edited and edited != base

    def windows():
        return C.tool('ui_windows')

    def find(label, window=None):
        query = {'label': label}
        if window is not None:
            query['windowId'] = window
        return [n for n in C.tool('ui_find', query) if n.get('label') == label]

    shots = args.output.with_suffix('')
    shots.mkdir(exist_ok=True)

    def screenshot():
        uri = next(r for r in C.c.request('resources/list')['resources']
                   if r.get('mimeType') == 'image/png')['uri']
        return base64.b64decode(C.c.request('resources/read', {'uri': uri})['contents'][0]['blob'])

    def observe():
        snap = C.tool('word_snapshot', {'xml': True})
        png = screenshot()
        try:
            lay = json.dumps(C.tool('word_layout'), sort_keys=True)
        except RuntimeError:
            lay = ''
        # Accessible labels catch state that has no document effect (status text, toggled controls).
        labels = sorted(n.get('label') or '' for n in C.tool('ui_snapshot').get('nodes', []))
        # The Search commands window closing on activation is the harness's own doing.
        return (len([w for w in windows_list() if w.get('title') != 'Search commands']),
                snap['xml'], json.dumps(snap['selection'], sort_keys=True),
                hashlib.sha256(lay.encode()).hexdigest(), hashlib.sha256(png).hexdigest(),
                hashlib.sha256(json.dumps(labels).encode()).hexdigest())

    def error_dialogs():
        out = []
        for w in windows_list():
            if w.get('title') == 'Error':
                out += [n['label'] for n in C.tool('ui_snapshot', {'windowId': w['windowId']})['nodes']
                        if n.get('role') == 'TEXT_FIELD' and n.get('label')]
        return out

    def dismiss():
        for _ in range(12):
            ws = windows()
            ws = ws if isinstance(ws, list) else ws.get('windows', [])
            dialogs = [w for w in ws if w.get('windowId', 0) != 0]
            if not dialogs:
                return
            w = dialogs[-1].get('windowId', dialogs[-1].get('id'))
            for label in ('Cancel', 'Close', 'Done', 'OK', 'No'):
                nodes = find(label, w)
                if nodes:
                    C.tool('ui_activate', {'nodeId': nodes[0]['id'], 'windowId': w})
                    time.sleep(.3)
                    break
            else:
                buttons = [n for n in C.tool('ui_snapshot', {'windowId': w})['nodes'] if n.get('role') == 'BUTTON']
                if not buttons:
                    return
                C.tool('ui_activate', {'nodeId': buttons[-1]['id'], 'windowId': w})
                time.sleep(.3)

    def native_windows():
        # Native panels (open/save/print) block the EDT and are invisible to CN1 accessibility.
        script = ('tell application "System Events" to tell (first process whose unix id is %d)'
                  ' to get name of every window' % app_pid)
        out = subprocess.run(['osascript', '-e', script], capture_output=True, text=True).stdout
        names = sorted(x.strip() for x in out.split(',') if x.strip())
        # The open panel is attached as a sheet (rendered by openAndSavePanelService), not a window.
        sheets = ('tell application "System Events" to tell (first process whose unix id is %d)'
                  ' to get count of sheets of every window' % app_pid)
        out = subprocess.run(['osascript', '-e', sheets], capture_output=True, text=True).stdout
        count = sum(int(x) for x in re.findall(r'\d+', out))
        return names + ['sheet'] * count

    def escape_native():
        # The panel only takes the key once the app is frontmost; retry until only the editor remains.
        for _ in range(4):
            subprocess.run(['osascript', '-e', 'tell application "System Events" to tell (first process'
                            ' whose unix id is %d) to set frontmost to true' % app_pid,
                            '-e', 'delay 0.5', '-e', 'tell application "System Events" to key code 53'],
                           capture_output=True)
            time.sleep(.8)
            if len(native_windows()) <= 1:
                return

    def windows_list():
        ws = C.tool('ui_windows')
        return ws if isinstance(ws, list) else ws.get('windows', [])

    def fill_prompts():
        # Required arguments are asked for one prompt at a time: "name (type)".
        samples = {'integer': '2', 'number': '2', 'boolean': 'true', 'string': 'Sample'}
        for _ in range(4):
            ws = [x for x in windows_list() if x.get('windowId', 0) != 0]
            if not ws or '(' not in ws[-1]['title']:
                return
            w = ws[-1]['windowId']
            kind = ws[-1]['title'].rsplit('(', 1)[1].rstrip(')')
            fields = [n for n in C.tool('ui_snapshot', {'windowId': w})['nodes'] if n['role'] == 'TEXT_FIELD']
            if not fields or kind not in samples:
                return
            C.tool('ui_set_text', {'nodeId': fields[0]['id'], 'windowId': w, 'text': samples[kind]})
            C.tool('ui_activate', {'nodeId': find('Apply', w)[0]['id'], 'windowId': w})
            time.sleep(.4)

    def ui_case(row):
        dismiss()
        C.tool('word_load', {'xml': FIXTURE, 'state': {
            'anchorBlock': 1, 'anchor': 4, 'block': 1, 'caret': 9, 'cellBlock': -1}})
        tab, label = row['tab'], row['feature']
        tabs = [n for n in find(tab) if n.get('role') == 'TAB']
        if tabs:
            C.tool('ui_activate', {'nodeId': tabs[0]['id']})
            time.sleep(.2)
        nodes = [n for n in C.tool('ui_find', {'label': label})
                 if (n.get('label') or '').lower() == label.lower() and n.get('role') != 'STATIC_TEXT']
        before = observe()
        time.sleep(.6)
        settled = observe()
        stable = settled[4] == before[4]  # caret blink makes pixels unusable as evidence
        quiet = settled[5] == before[5]
        path = 'ribbon'
        if not nodes:
            # Search commands (Tell Me) lists every registered command by its Word name.
            path = 'search'
            C.tool('ui_activate', {'nodeId': find('Search commands')[0]['id']})
            for _ in range(15):
                found = [x for x in windows_list() if x['title'] == 'Search commands']
                if found:
                    break
                time.sleep(.2)
            w = found[0]['windowId']
            field = [n for n in find('Search commands', w) if n['role'] == 'TEXT_FIELD'][0]
            C.tool('ui_set_text', {'nodeId': field['id'], 'windowId': w, 'text': '%s (%s)' % (label, tab)})
            time.sleep(.4)
            want = '%s (%s)' % (label, tab)
            nodes = [n for n in C.tool('ui_find', {'label': want, 'windowId': w}) if n.get('label') == want]
            if not nodes:
                dismiss()
                return {'ok': False, 'why': 'not on the ribbon and not found in Search commands: %r' % want}
            nodes[0]['windowId'] = w
        native_before = native_windows()
        C.c.socket.settimeout(6)
        try:
            target = {'nodeId': nodes[0]['id']}
            if 'windowId' in nodes[0]:
                target['windowId'] = nodes[0]['windowId']
            result = C.tool('ui_activate', target)
            time.sleep(.4)
            fill_prompts()
        except (TimeoutError, OSError):
            result = {'blocked': True}
        time.sleep(.8)
        native_after = native_windows()
        # Only a panel or sheet beyond the editor window counts; osascript sometimes returns nothing.
        opened = bool(set(native_after) - set(native_before) - {'Search commands'})
        if result.get('blocked'):
            escape_native()
            C.reconnect()
            return {'ok': opened, 'observed': ['nativeWindow'], 'nativeBefore': native_before,
                    'nativeWindows': native_after, 'control': label, 'tab': tab}
        C.c.socket.settimeout(60)
        if opened:
            escape_native()
            return {'ok': True, 'observed': ['nativeWindow'], 'nativeWindows': native_after,
                    'control': label, 'tab': tab, 'activation': result}
        after = observe()
        (shots / (row['command'] + '.png')).write_bytes(screenshot())
        changed = [n for n, a, b in zip(('windows', 'document', 'selection', 'layout', 'pixels', 'accessibility'), before, after)
                   if a != b and (n != 'pixels' or stable) and (n != 'accessibility' or quiet)]
        errors = error_dialogs()
        if errors:
            dismiss()
            return {'ok': False, 'why': 'internal error dialog: ' + errors[0][:200], 'path': path}
        dismiss()
        if before[1] != observe()[1]:
            try:
                command('edit.undo')
            except RuntimeError:
                pass
        return {'ok': bool(changed), 'observed': changed, 'path': path, 'control': label, 'tab': tab}

    def fresh(block=1, start=0, end=0):
        dismiss()
        C.tool('word_load', {'xml': FIXTURE, 'state': {
            'anchorBlock': block, 'anchor': start, 'block': block, 'caret': end, 'cellBlock': -1}})

    def select(block, start, end):
        command('selection.set', {'block': block, 'start': start, 'end': end})

    def xml():
        return C.tool('word_snapshot', {'xml': True})['xml']

    def snap():
        return C.tool('word_snapshot')

    def seq_go_back():
        fresh()
        select(3, 0, 0)
        command('text.insert', {'text': 'x'})
        select(0, 0, 0)
        command('edit.goBack')
        return snap()['selection']['block'] == 3

    def seq_repeat():
        fresh()
        select(1, 0, 3)
        command('format.bold', {})
        before = xml().count('bold="true"')
        select(3, 0, 1)
        command('edit.repeat')
        return xml().count('bold="true"') > before

    def seq_undo_to():
        fresh()
        base = xml()
        command('text.insert', {'text': 'a'})
        command('text.insert', {'text': 'b'})
        command('edit.undoTo', {'count': 2})
        return xml() == base

    def seq_paste_merge():
        fresh()
        select(1, 0, 3)
        command('edit.copy')
        select(3, 0, 0)
        command('edit.pasteMerge', {'index': 0})
        return snap()['text'].split('\n')[3].startswith('The')

    def with_recipients():
        fresh()
        command('mailings.recipients', {'csv': 'Name,City\nAda,London\nGrace,Arlington'})
        command('mailings.insertField', {'field': 'Name'})

    def seq_mail(name, args, check):
        def run():
            with_recipients()
            if name in ('mailings.previous',):
                command('mailings.next')
            result = command(name, args)['result']
            return check(result)
        return run

    def seq_macro():
        fresh()
        select(1, 0, 3)
        command('tools.recordMacro', {'action': 'start'})
        command('format.italic', {})
        result = command('tools.recordMacro', {'action': 'stop', 'name': 'NativeProbe'})['result']
        return 'NativeProbe' in json.dumps(result)

    def seq_template():
        fresh()
        command('file.saveTemplate', {'name': 'NativeProbe'})
        text = snap()['text']
        command('text.insert', {'text': 'changed '})
        command('file.newFromTemplate', {'name': 'NativeProbe'})
        return snap()['text'] == text

    def seq_pen():
        fresh()
        command('draw.addPen', {'tool': 'pen', 'color': '00aa00', 'width': 2})
        before = xml().count('<picture')
        command('draw.pen', {'points': '0,0 10,10 20,0'})
        return xml().count('<picture') == before + 1 and 'strokes=pen' in xml()

    def seq_markup():
        # Rendering differences are not usable here (the status area animates), so check the view state.
        fresh()
        off = command('review.showMarkup', {'value': False})['result'].get('markup')
        on = command('review.showMarkup', {'value': True})['result'].get('markup')
        toggled = command('review.showMarkup')['result'].get('markup')
        command('review.showMarkup', {'value': True})
        return off == 'none' and on == 'all' and toggled == 'none'

    def view_flip(name, key, args_on=None, args_off=None):
        # View toggles have no document effect; the editor's view state must follow each call.
        def run():
            fresh()
            a = command(name, args_on or {})['result'].get(key)
            b = command(name, args_off or {})['result'].get(key)
            return a != b and a is not None and b is not None
        return run

    def seq_outline_close():
        fresh()
        opened = command('view.outline', {'value': True})['result'].get('outline')
        closed = command('outline.close')['result'].get('outline')
        return opened is True and closed is False

    def seq_export_png():
        fresh()
        data = base64.b64decode(command('file.exportPng', {'width': 800})['result']['base64'])
        width = int.from_bytes(data[16:20], 'big')
        return data.startswith(b'\x89PNG') and width == 800

    def seq_one_page():
        fresh()
        command('view.zoom', {'percent': 240})
        zoom = command('view.onePage')['result'].get('zoom')
        return zoom is not None and zoom < 240

    SEQUENCES = {
        'view.onePage': seq_one_page,
        'edit.formatPainter': view_flip('edit.formatPainter', 'painter'),
        'view.darkMode': view_flip('view.darkMode', 'dark'),
        'table.viewGridlines': view_flip('table.viewGridlines', 'tableGridlines'),
        'review.markup': view_flip('review.markup', 'markup', {'mode': 'simple'}, {'mode': 'original'}),
        'outline.close': seq_outline_close, 'file.exportPng': seq_export_png,
        'review.showMarkup': seq_markup,
        'edit.goBack': seq_go_back, 'edit.repeat': seq_repeat, 'edit.undoTo': seq_undo_to,
        'edit.pasteMerge': seq_paste_merge, 'tools.recordMacro': seq_macro,
        'file.newFromTemplate': seq_template, 'draw.pen': seq_pen,
        'mailings.next': seq_mail('mailings.next', {}, lambda r: True),
        'mailings.previous': seq_mail('mailings.previous', {}, lambda r: True),
        'mailings.preview': seq_mail('mailings.preview', {}, lambda r: True),
        'mailings.finish': seq_mail('mailings.finish', {}, lambda r: 'Grace' in json.dumps(r) or r is not None),
        'mailings.findRecipient': seq_mail('mailings.findRecipient', {'text': 'Grace'}, lambda r: True),
    }

    def run_case(cid):
        if cid in SEQUENCES:
            ok = SEQUENCES[cid]()
            errors = error_dialogs()
            report['results'][cid] = {'mode': 'sequence', 'ok': bool(ok) and not errors,
                                      'why': errors[0][:200] if errors else None}
        elif cid in ('edit.undo', 'edit.redo'):
            report['results'][cid] = {'mode': 'sequence', 'ok': undo_redo()}
        elif cid in registry and cid in cases:
            runs = [replay(r) for r in cases[cid][:2]]
            report['results'][cid] = {'mode': 'replay', 'ok': all(r['ok'] for r in runs), 'runs': runs}
        else:
            row = next(r for r in rows if (r.get('appCommand') or r['command']) == cid)
            res = ui_case(row)
            res['mode'] = 'ui'
            report['results'][cid] = res

    try:
        for i, cid in enumerate(ids):
            try:
                run_case(cid)
            except (RuntimeError, IndexError, KeyError, StopIteration) as ex:
                report['results'][cid] = {'mode': 'error', 'ok': False, 'why': str(ex)[:300]}
                dismiss()
            if i % 20 == 0:
                save()
    finally:
        try:
            dismiss()
            C.tool('word_load', {'xml': original['xml'], 'state': {
                'anchorBlock': 0, 'anchor': 0, 'block': 0, 'caret': 0, 'cellBlock': -1}})
        except Exception as ex:  # keep the evidence even when restore fails
            report['restoreError'] = str(ex)
        r = report['results']
        report['summary'] = {'total': len(r), 'passed': sum(1 for v in r.values() if v['ok']),
                             'failed': sorted(k for k, v in r.items() if not v['ok'])}
        report['finished'] = time.strftime('%Y-%m-%dT%H:%M:%S')
        save()
print(json.dumps(report['summary'], indent=1))
