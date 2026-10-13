#!/usr/bin/env python3
"""Local process-level parity checks for the JVM and native builds of our complete CLI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--native', required=True, type=Path)
p.add_argument('--out', type=Path)
a = p.parse_args()
if os.environ.get('CI'):
    p.error('Native builds/tests are local only')
root = Path(__file__).resolve().parent.parent
commands = {'jvm': [str(root/'scripts/wordprocessor')], 'native': [str(a.native.resolve())]}
env = dict(os.environ, WORDPROCESSOR_RUNTIME='jvm')
def run(side, *args, stdin=None, success=True):
    r = subprocess.run(commands[side] + list(map(str, args)), input=stdin, text=True,
                       capture_output=True, env=env, timeout=60)
    if (r.returncode == 0) != success:
        raise AssertionError(f'{side} {args}: {r.returncode}\n{r.stderr}')
    return r.stdout if success else r.stderr
checks = []
def equal(name, x, y):
    assert x == y, name
    checks.append(name)
with tempfile.TemporaryDirectory(prefix='wordprocessor-runtimes-') as tmp:
    base = Path(tmp)
    for command in ['help', 'commands']:
        equal(command, run('jvm', command), run('native', command))
    macro = base/'macro.json'
    macro.write_text(json.dumps({'version': 1, 'steps': [
        {'command': 'text.insert', 'args': {'text': 'Résumé € café\nSecond paragraph'}},
        {'command': 'selection.set', 'args': {'block': 0, 'start': 0, 'end': 6}},
        {'command': 'format.set', 'args': {'property': 'bold', 'value': 'true'}}]}))
    results = {}
    for side in commands:
        directory = base/side
        directory.mkdir()
        run(side, 'new', directory/'empty.cn1doc')
        preview = run(side, 'run', directory/'empty.cn1doc', macro, directory/'unused.docx', '--preview')
        assert not (directory/'unused.docx').exists()
        run(side, 'run', directory/'empty.cn1doc', macro, directory/'styled.docx')
        text = run(side, 'text', directory/'styled.docx')
        assert text == 'Résumé € café\nSecond paragraph\n'
        data = {'preview': json.loads(preview), 'text': text,
                'inspect': json.loads(run(side, 'inspect', directory/'styled.docx'))}
        for ext in ['cn1doc', 'txt', 'html', 'md', 'rtf', 'adoc', 'pdf']:
            output = directory/('converted.'+ext)
            run(side, 'convert', directory/'styled.docx', output, '--allow-conversion')
            if ext == 'pdf':
                assert output.read_bytes().startswith(b'%PDF-1.4')
                data[ext] = hashlib.sha256(output.read_bytes()).hexdigest()
            else:
                data[ext] = run(side, 'text', output)
                assert 'Second paragraph' in data[ext]
        before = (directory/'styled.docx').read_bytes()
        assert 'Output exists' in run(side, 'convert', directory/'empty.cn1doc', directory/'styled.docx', success=False)
        assert (directory/'styled.docx').read_bytes() == before
        bad = directory/'bad.docx'
        bad.write_bytes(b'not a ZIP')
        data['badDocx'] = run(side, 'text', bad, success=False)
        unicode_file = directory/'unicode.txt'
        unicode_file.write_text('שלום')
        assert 'does not cover' in run(side, 'convert', unicode_file, directory/'bad.pdf', success=False)
        assert not (directory/'bad.pdf').exists()
        messages = [
            {'jsonrpc':'2.0','id':1,'method':'initialize','params':{}},
            {'jsonrpc':'2.0','method':'notifications/initialized'},
            {'jsonrpc':'2.0','id':2,'method':'tools/list'},
            {'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'text_insert','arguments':{'text':'Hello from MCP'}}},
            {'jsonrpc':'2.0','id':4,'method':'tools/call','params':{'name':'save_document','arguments':{'path':'mcp.docx'}}},
            {'jsonrpc':'2.0','id':5,'method':'tools/call','params':{'name':'save_document','arguments':{'path':'../escape.txt'}}}]
        replies = [json.loads(line) for line in run(side, 'mcp', '--root', directory,
            stdin=''.join(json.dumps(m)+'\n' for m in messages)).splitlines()]
        assert len(replies) == 5 and replies[2]['result']['isError'] is False
        assert replies[3]['result']['isError'] is False and replies[4]['result']['isError'] is True
        assert run(side, 'text', directory/'mcp.docx') == 'Hello from MCP\n'
        data['mcpTools'] = replies[1]
        results[side] = data
    for name in results['jvm']:
        equal(name, results['jvm'][name], results['native'][name])
    checks += ['no-overwrite', 'unsupported-PDF-glyph', 'MCP-edit-save', 'MCP-root-confinement']
report = {'checks': checks, 'passed': True, 'nativeSha256': hashlib.sha256(a.native.read_bytes()).hexdigest(),
          'boundary': 'local CLI processes; not GUI, physical devices or full Wordcraft parity'}
if a.out:
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(report, indent=2)+'\n')
print(f'CLI_RUNTIME_PARITY_OK {len(checks)} checks')
