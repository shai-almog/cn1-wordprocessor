# Encrypted DOCX interop on a running agent-debug build: the app encrypts and msoffcrypto-tool
# decrypts (with HMAC integrity), then msoffcrypto-tool encrypts and the app decrypts.
# Usage: python3 scripts/live-crypto-check.py PORT SCRATCH_DIR  (needs: pip install msoffcrypto-tool)
import base64, io, json, subprocess, sys, zipfile, msoffcrypto
from msoffcrypto.format.ooxml import OOXMLFile
port = sys.argv[1]; S = sys.argv[2]

def mcp(tool, args):
    out = subprocess.run(['python3', 'scripts/live-mcp.py', '--port', port, '--output', S + '/r.json', tool, json.dumps(args)],
                         capture_output=True, text=True)
    if out.returncode: raise SystemExit(out.stderr[-400:])
    d = json.load(open(S + '/r.json'))
    return d

def state():
    return str(body(mcp('word_snapshot', {}))['stateId'])

def cmd(name, args):
    return mcp('word_command', {'stateId': state(), 'command': name, 'args': args})

def body(d):
    return d.get('result', d)

# 1. App encrypts, independent tool decrypts and checks HMAC integrity.
cmd('file.encrypt', {'password': 'Native-Pa55'})
share = body(cmd('file.share', {'format': 'docx', 'name': 'enc'}))
cmd('file.encrypt', {'password': ''})
raw = base64.b64decode(share['base64'])
f = msoffcrypto.OfficeFile(io.BytesIO(raw)); f.load_key(password='Native-Pa55')
plain = io.BytesIO(); f.decrypt(plain, verify_integrity=True)
print('app->msoffcrypto ok', len(raw), 'bytes', len(zipfile.ZipFile(plain).namelist()), 'parts')

# 2. Independent tool encrypts a fresh docx, app decrypts it through Insert Text from File.
cmd('file.encrypt', {'password': ''})
src = io.BytesIO(base64.b64decode(body(cmd('file.share', {'format': 'docx', 'name': 'p'}))['base64']))
enc = io.BytesIO(); OOXMLFile(src).encrypt('Other-Pa55', enc)
before = len(json.dumps(mcp('word_snapshot', {'xml': True})))
cmd('insert.textFromFile', {'data': base64.b64encode(enc.getvalue()).decode(), 'format': 'docx', 'password': 'Other-Pa55'})
after = len(json.dumps(mcp('word_snapshot', {'xml': True})))
try:
    cmd('insert.textFromFile', {'data': base64.b64encode(enc.getvalue()).decode(), 'format': 'docx', 'password': 'wrong'})
    wrong = 'accepted!'
except SystemExit as e:
    wrong = 'rejected'
cmd('edit.undo', {})
print('msoffcrypto->app ok, document grew', before, '->', after, '; wrong password', wrong)
