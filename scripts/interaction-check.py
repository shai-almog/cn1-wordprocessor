#!/usr/bin/env python3
"""Checks pointer interactions in the running agent-debug app: scrollbar, objects and draw mode.

Gestures go through the app's word_pointer tool, which delivers in-process pointer events to the
form the way the port does; nothing touches the OS mouse. Launch a `-Pagent-debug` build, then run
`scripts/interaction-check.py` (it replaces the open document). Exit status 1 lists each failure;
`--screenshots DIR` saves a screenshot after each gesture.
"""
import argparse
import base64
import importlib.util
from pathlib import Path
import struct
import sys
import time
import zlib

spec = importlib.util.spec_from_file_location('live_mcp', Path(__file__).with_name('live-mcp.py'))
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


def png(w, h):
    raw = b''.join(b'\x00' + bytes([200, 80, 40]) * w for _ in range(h))
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


class Check:
    def __init__(self, client, shots):
        self.c = client
        self.shots = shots
        self.failures = []

    def expect(self, ok, message):
        print(('ok   ' if ok else 'FAIL ') + message)
        if not ok:
            self.failures.append(message)

    def shot(self, name):
        if not self.shots:
            return
        self.shots.mkdir(parents=True, exist_ok=True)
        r = next(r for r in self.c.request('resources/list')['resources'] if r.get('mimeType') == 'image/png')
        blob = self.c.request('resources/read', {'uri': r['uri']})['contents'][0]['blob']
        (self.shots / f'{name}.png').write_bytes(base64.b64decode(blob))

    def cmd(self, name, args=None):
        state = self.c.tool('word_snapshot', {})['stateId']
        return self.c.tool('word_command', {'stateId': state, 'command': name, 'args': args or {}})

    def ptr(self, events, block=-1):
        return self.c.tool('word_pointer', {'events': events, 'block': block})

    def drag(self, x0, y0, x1, y1, block=-1, hold=0):
        mid = ((x0 + x1) // 2, (y0 + y1) // 2)
        return self.ptr([{'type': 'press', 'x': x0, 'y': y0, 'waitMs': hold or 30},
                         {'type': 'drag', 'x': mid[0], 'y': mid[1]},
                         {'type': 'drag', 'x': x1, 'y': y1, 'waitMs': 300},
                         {'type': 'release', 'x': x1, 'y': y1, 'waitMs': 600}], block)

    def click(self, x, y, block=-1):
        return self.ptr([{'type': 'press', 'x': x, 'y': y}, {'type': 'release', 'x': x, 'y': y, 'waitMs': 700}], block)

    def objects(self):
        # The snapshot lists text blocks only; objects are blocks too.
        xml = self.c.tool('word_snapshot', {'xml': True})['xml']
        count = len(self.c.tool('word_snapshot', {})['document']['blocks']) + xml.count('<picture') + 2
        return {b: r for b in range(count) if (r := self.ptr([], b).get('rect'))}

    def viewport(self):
        st = self.ptr([])
        x, y, w, h = st['bounds']
        return x + w - 6, y + st['scrollY'], h, st

    def bring_into_view(self, block):
        sx, top, h, _ = self.viewport()
        for _ in range(60):
            r = self.ptr([], block)['rect']
            if top <= r[1] and r[1] + r[3] <= top + h:
                return r
            self.click(sx, top + 3 if r[1] < top else top + h - 3)
        return self.ptr([], block)['rect']

    def scrollbar(self):
        sx, top, h, st = self.viewport()
        max_scroll = st['scrollHeight'] - h
        if max_scroll <= 0:
            self.expect(False, 'scrollbar: the document is too short to scroll')
            return
        # The thumb's center for the current scroll, from the track's geometry.
        thumb = max(16, h * h // st['scrollHeight'])
        y = top + (h - thumb) * st['scrollY'] // max_scroll + thumb // 2
        target = top + h // 4 if st['scrollY'] > max_scroll // 2 else top + 3 * h // 4
        after = self.drag(sx, y, sx, target)
        self.expect(after['scrollY'] != st['scrollY'], f"thumb drag scrolls ({st['scrollY']} -> {after['scrollY']})")
        self.expect((after['block'], after['caret'], after['anchorBlock'], after['anchor'])
                    == (st['block'], st['caret'], st['anchorBlock'], st['anchor']), 'thumb drag leaves the selection')
        before = after['scrollY']
        page = self.click(sx, top + 3 if before > max_scroll // 2 else top + h - 3)
        self.expect(page['scrollY'] != before, f"track click pages ({before} -> {page['scrollY']})")
        self.expect(page['block'] == st['block'] and page['caret'] == st['caret'], 'track click leaves the caret')
        self.shot('scrollbar')

    def picture(self):
        known = set(self.objects())
        self.cmd('picture.insert', {'base64': base64.b64encode(png(40, 30)).decode(), 'width': 80,
                                    'height': 60, 'alt': 'interaction check'})
        block = next((b for b in self.objects() if b not in known), None)
        self.expect(block is not None, 'picture inserted')
        if block is None:
            return
        r = self.bring_into_view(block)
        cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
        st = self.click(cx, cy, block)
        self.expect(st.get('selectedObject') is not None, 'click selects the picture')
        self.shot('selected')
        scroll = st['scrollY']
        bx, by = r[0] + r[2], r[1] + r[3]
        st = self.drag(bx, by, bx + 24, by + 18, block)
        s = st['rect']
        self.expect(s[2] > r[2] and abs(s[2] / s[3] - r[2] / r[3]) < .05,
                    f'corner handle resizes keeping the ratio ({r[2]}x{r[3]} -> {s[2]}x{s[3]})')
        self.expect(st['scrollY'] == scroll, 'resizing keeps the view where it was')
        cx, cy = s[0] + s[2] // 2, s[1] + s[3] // 2
        self.drag(cx, cy, cx + 150, cy, hold=600)
        # A drop re-anchors the object, so its block index may change: find it by its size.
        moved = [r for r in self.objects().values() if r[2:] == s[2:]]
        self.expect(any(r[0] > s[0] + 100 for r in moved), f"dragging moves the picture ({s[0]} -> {[r[0] for r in moved]})")
        self.shot('moved')
        if not moved:
            return
        block = next(b for b, r in self.objects().items() if r[2:] == s[2:])
        r = self.bring_into_view(block)
        time.sleep(.7)
        st = self.click(r[0] + r[2] // 2, r[1] + r[3] // 2, block)
        self.expect(st.get('selectedObject') is not None, 'click selects the moved picture')
        hx, hy = r[0] + r[2] // 2, r[1] - st['rotateOffset']
        self.drag(hx, hy, r[0] + r[2] + 120, r[1] + r[3] // 2 + 2, block)
        xml = self.c.tool('word_snapshot', {'xml': True})['xml']
        self.expect('rotation="90"' in xml, 'the rotation handle turns the picture a quarter turn')
        self.shot('rotated')

    def draw(self):
        snap = self.c.tool('ui_snapshot', {})
        snap = snap.get('snapshot', snap)
        node = lambda label, role: next(n['id'] for n in snap['nodes'] if n.get('label') == label and n.get('role') == role)
        self.c.tool('ui_activate', {'nodeId': node('Draw', 'TAB')})
        time.sleep(1)
        snap = self.c.tool('ui_snapshot', {})
        snap = snap.get('snapshot', snap)
        known = set(self.objects())
        self.c.tool('ui_activate', {'nodeId': node('Pen', 'BUTTON')})
        time.sleep(1)
        self.expect(self.ptr([])['inking'], 'Pen starts draw mode')
        block = next((b for b in self.objects() if b not in known), None)
        self.expect(block is not None, 'Pen inserts a canvas')
        if block is None:
            return
        r = self.bring_into_view(block)
        xml = self.c.tool('word_snapshot', {'xml': True})['xml']
        cx, cy = r[0] + r[2] // 2, r[1] + r[3] // 2
        self.drag(cx, cy, cx + 60, cy - 20)
        self.expect(self.c.tool('word_snapshot', {'xml': True})['xml'] != xml, 'a drag inside the canvas draws')
        self.shot('stroke')
        st = self.drag(r[0] + 2, cy, r[0] + 122, cy + 30, hold=300)
        self.expect(any(o[2:] == r[2:] and o[0] > r[0] + 60 for o in self.objects().values()), 'dragging the canvas edge moves it')
        self.expect(st['inking'], 'moving the canvas stays in draw mode')
        self.c.tool('ui_activate', {'nodeId': node('Select', 'BUTTON')})
        time.sleep(.5)
        self.expect(not self.ptr([])['inking'], 'Select leaves draw mode')
        self.c.tool('ui_activate', {'nodeId': node('Pen', 'BUTTON')})
        time.sleep(1)
        self.expect(self.ptr([])['inking'], 'Pen restarts draw mode')
        # The Mac port reports Escape as the text input's editor action (cancelOperation:).
        blocks = len(self.c.tool('word_snapshot', {})['document']['blocks'])
        self.expect(not self.ptr([{'type': 'action', 'x': 0, 'y': 0, 'waitMs': 400}])['inking'], 'Escape leaves draw mode')
        self.expect(len(self.c.tool('word_snapshot', {})['document']['blocks']) == blocks, 'Escape adds no paragraph')
        self.shot('canvas')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18765)
    parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    with live.Client(args.port, 60) as c:
        check = Check(c, args.screenshots)
        # A fresh document, so Pen inserts a new canvas rather than inking an earlier one.
        c.tool('word_load', {'xml': '<?xml version="1.0" encoding="UTF-8"?><document version="1" '
                                    'title="Interaction check" next="2"><page width="595.28" height="841.89" top="54.0" '
                                    'right="54.0" bottom="54.0" left="54.0"/><p id="p1"></p></document>'})
        for i in range(40):
            check.cmd('text.insert', {'text': f'Interaction check paragraph {i}.'})
            check.cmd('text.newParagraph')
        check.scrollbar()
        check.picture()
        check.draw()
    print(f'{len(check.failures)} failures')
    sys.exit(1 if check.failures else 0)


if __name__ == '__main__':
    main()
