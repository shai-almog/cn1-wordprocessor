#!/usr/bin/env python3
"""Checks every ribbon tab of the running agent-debug app for overlapping or cut-off controls.

The simulator's test theme measures controls differently from the native look and feel, so a
layout that fits there can overlap in the shipped app. This checks the native app's accessibility
bounds and runs its word_ribbon_check tool (RibbonLint): launch a `-Pagent-debug` build, then run `scripts/ribbon-check.py`.
Exit status 1 lists each defect; `--screenshots DIR` also saves every tab.
"""
import argparse
import base64
import importlib.util
from pathlib import Path
import sys
import time

spec = importlib.util.spec_from_file_location('live_mcp', Path(__file__).with_name('live-mcp.py'))
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)


def nodes_of(snapshot):
    return {n['id']: n for n in snapshot['nodes']}


def box(n):
    b = n.get('bounds') or [0, 0, 0, 0]
    return b[0], b[1], b[2], b[3]


def visible(n):
    x, y, w, h = box(n)
    return w > 0 and h > 0


def ribbon_band(nodes, tabs):
    """The selected tab's page: from the tab strip down to the first full-width node below it.

    The accessibility tree is flat (every control's parent is the form), so the page is found by
    geometry rather than by containment.
    """
    strip = max(box(t)[1] + box(t)[3] for t in tabs)
    # The window's width: a tab wider than the window (References) has controls past its edge.
    width = max(box(n)[2] for n in nodes.values() if box(n)[0] == 0 and box(n)[1] == 0)
    left = min(box(t)[0] for t in tabs)
    bottom = None
    for n in nodes.values():
        x, y, w, h = box(n)
        if y > strip + 30 and w > width * 0.6 and (bottom is None or y < bottom):
            bottom = y
    return strip, bottom, left, width


def defects(nodes, band, tab):
    top, bottom, left, width = band
    controls = []
    for n in nodes.values():
        x, y, w, h = box(n)
        # A combo box's LIST and its entries are one control, not overlapping ones.
        if not visible(n) or n.get('role') in ('TAB', 'LIST') or w > width * 0.6:
            continue
        if y >= top - 2 and y < bottom:
            controls.append(n)
    out = []
    for n in controls:
        x, y, w, h = box(n)
        if y + h > bottom + 1:
            out.append(f'{tab}: "{n.get("label") or n.get("role")}" is cut off by the ribbon ({y}+{h} past {bottom})')
    for i, a in enumerate(controls):
        ax, ay, aw, ah = box(a)
        for b in controls[i + 1:]:
            bx2, by2, bw2, bh2 = box(b)
            ow = min(ax + aw, bx2 + bw2) - max(ax, bx2)
            oh = min(ay + ah, by2 + bh2) - max(ay, by2)
            inside = (ax <= bx2 and ay <= by2 and ax + aw >= bx2 + bw2 and ay + ah >= by2 + bh2) or (
                bx2 <= ax and by2 <= ay and bx2 + bw2 >= ax + aw and by2 + bh2 >= ay + ah)
            if ow > 2 and oh > 2 and not inside:
                out.append(f'{tab}: "{a.get("label") or a.get("role")}" overlaps "{b.get("label") or b.get("role")}"')
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=18765)
    parser.add_argument('--screenshots', type=Path)
    args = parser.parse_args()
    problems = []
    with live.Client(args.port, 30) as c:
        first = c.tool('ui_snapshot', {})
        snap = first.get('snapshot', first)
        tabs = [n for n in snap['nodes'] if n.get('role') == 'TAB' and visible(n)]
        if not tabs:
            sys.exit('no ribbon tabs in the accessibility snapshot')
        for tab in tabs:
            label = tab.get('label')
            c.tool('ui_activate', {'nodeId': tab['id']})
            time.sleep(0.6)
            snap = c.tool('ui_snapshot', {})
            snap = snap.get('snapshot', snap)
            nodes = nodes_of(snap)
            now = [n for n in nodes.values() if n.get('role') == 'TAB' and visible(n)]
            band = ribbon_band(nodes, now)
            if band[1] is None:
                problems.append(f'{label}: no ribbon page under the tabs')
                continue
            problems.extend(defects(nodes, band, label))
            if args.screenshots:
                args.screenshots.mkdir(parents=True, exist_ok=True)
                resource = next(r for r in c.request('resources/list')['resources'] if r.get('mimeType') == 'image/png')
                blob = c.request('resources/read', {'uri': resource['uri']})['contents'][0]['blob']
                (args.screenshots / f'{label}.png').write_bytes(base64.b64decode(blob))
        # Accessibility bounds miss a squeezed control painting over its neighbor; the app's
        # own check measures the components' preferred sizes in the native look and feel.
        problems.extend(c.tool('word_ribbon_check', {})['defects'])
        home = [t for t in tabs if t.get('label') == 'Home']
        if home:
            c.tool('ui_activate', {'nodeId': home[0]['id']})
    for p in problems:
        print(p)
    print(f'{len(tabs)} tabs checked, {len(problems)} defects')
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
