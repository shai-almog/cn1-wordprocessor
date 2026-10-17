#!/usr/bin/env python3
"""Local X11 keyboard acceptance against one disposable native Linux review app.

XTest sends actual X11 key events through GTK; built-in MCP observes document
state, activates its canvas, and restores the exact input XML through undo.
"""
import argparse, ctypes as C, importlib.util, json, time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--port',type=int,default=18768)
p.add_argument('--allow-disposable',action='store_true',required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--mcp-script',type=Path,required=True)
a=p.parse_args()
if a.output.exists():p.error('Choose a new evidence file')
spec=importlib.util.spec_from_file_location('mcp',a.mcp_script);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
x=C.CDLL('libX11.so.6');xt=C.CDLL('libXtst.so.6')
x.XOpenDisplay.argtypes=[C.c_char_p];x.XOpenDisplay.restype=C.c_void_p
x.XDefaultRootWindow.argtypes=[C.c_void_p];x.XDefaultRootWindow.restype=C.c_ulong
x.XQueryTree.argtypes=[C.c_void_p,C.c_ulong,C.POINTER(C.c_ulong),C.POINTER(C.c_ulong),C.POINTER(C.POINTER(C.c_ulong)),C.POINTER(C.c_uint)]
x.XGetGeometry.argtypes=[C.c_void_p,C.c_ulong,C.POINTER(C.c_ulong),C.POINTER(C.c_int),C.POINTER(C.c_int),C.POINTER(C.c_uint),C.POINTER(C.c_uint),C.POINTER(C.c_uint),C.POINTER(C.c_uint)]
x.XFetchName.argtypes=[C.c_void_p,C.c_ulong,C.POINTER(C.c_void_p)]
x.XFree.argtypes=[C.c_void_p]
x.XStringToKeysym.argtypes=[C.c_char_p];x.XStringToKeysym.restype=C.c_ulong
x.XKeysymToKeycode.argtypes=[C.c_void_p,C.c_ulong];x.XKeysymToKeycode.restype=C.c_uint
x.XSetInputFocus.argtypes=[C.c_void_p,C.c_ulong,C.c_int,C.c_ulong]
x.XSync.argtypes=[C.c_void_p,C.c_int];x.XCloseDisplay.argtypes=[C.c_void_p]
xt.XTestFakeKeyEvent.argtypes=[C.c_void_p,C.c_uint,C.c_int,C.c_ulong]
display=x.XOpenDisplay(None);assert display,'No X11 display'
def children(window):
 root=C.c_ulong();parent=C.c_ulong();items=C.POINTER(C.c_ulong)();count=C.c_uint()
 assert x.XQueryTree(display,window,C.byref(root),C.byref(parent),C.byref(items),C.byref(count))
 values=[items[i] for i in range(count.value)]
 if items:x.XFree(items)
 return values
def size(window):
 root=C.c_ulong();left=C.c_int();top=C.c_int();width=C.c_uint();height=C.c_uint();border=C.c_uint();depth=C.c_uint()
 assert x.XGetGeometry(display,window,C.byref(root),C.byref(left),C.byref(top),C.byref(width),C.byref(height),C.byref(border),C.byref(depth))
 return width.value,height.value
def name(window):
 value=C.c_void_p()
 if not x.XFetchName(display,window,C.byref(value)) or not value.value:return ''
 text=C.string_at(value).decode('utf-8',errors='replace');x.XFree(value);return text
def send(key,down):
 symbol=x.XStringToKeysym(key.encode('ascii'));code=x.XKeysymToKeycode(display,symbol)
 assert code,(key,symbol)
 assert xt.XTestFakeKeyEvent(display,code,down,0)
def key(value,*modifiers):
 try:
  for mod in modifiers:send(mod,True)
  send(value,True);send(value,False)
 finally:
  for mod in reversed(modifiers):send(mod,False)
 x.XSync(display,False)
def text(value):
 for char in value:key(char)
def wait(read,predicate):
 until=time.monotonic()+8
 while True:
  result=read()
  if predicate(result):return result
  if time.monotonic()>=until:raise AssertionError(result)
  time.sleep(.04)
evidence={'checks':[],'method':'XTest native X11 key events through GTK, observed by built-in MCP','physicalKeyboardCertified':False,'originalXmlRestored':False}
with m.Client(a.port) as client:
 def snapshot(xml=False):return client.tool('word_snapshot',{'xml':xml})
 def command(command):return client.tool('word_command',{'stateId':snapshot()['stateId'],'command':command,'args':{}})
 def expect(value,label):
  wait(snapshot,lambda s:s['text']==value);evidence['checks'].append(label)
 before=snapshot(True);assert before['text']=='','Run on an empty disposable document'
 evidence['before']=before;a.output.write_text(json.dumps(evidence,indent=2)+'\n')
 try:
  windows=[w for w in children(x.XDefaultRootWindow(display)) if size(w)[0]>=400 and size(w)[1]>=200]
  assert len(windows)==1,[(w,name(w)) for w in children(x.XDefaultRootWindow(display))]
  x.XSetInputFocus(display,windows[0],2,0);x.XSync(display,False);time.sleep(.1)
  nodes=client.tool('ui_find',{'windowId':0,'label':'Paragraph 1'})
  node=next(n for n in nodes if n['role']=='TEXT_FIELD' and n['label']=='Paragraph 1')
  assert client.tool('ui_activate',{'windowId':0,'nodeId':node['id']}).get('success')
  text('abc');expect('abc','native-x11-text-commit')
  key('Left','Shift_L');key('z','Shift_L');expect('abZ','native-shift-selection-and-replacement')
  key('BackSpace');expect('ab','native-backspace')
  key('z','Control_L');expect('abZ','native-undo')
  key('y','Control_L');expect('ab','native-redo')
  key('Return');text('cd');expect('ab\ncd','native-return-and-multiline-entry')
  key('Home');key('Delete');expect('ab\nd','native-paragraph-navigation-and-forward-delete')
  key('a','Control_L');key('x','Control_L');expect('','native-select-all-and-cut')
  key('v','Control_L');expect('ab\nd','native-paste-from-gtk-clipboard')
  key('End')
  key('u','Control_L','Shift_L');text('1f600');key('Return');expect('ab\nd😀','native-gtk-unicode-composition')
  key('a','Control_L');key('x','Control_L');expect('','native-unicode-cut')
  key('v','Control_L');expect('ab\nd😀','native-unicode-clipboard-round-trip')
  x.XSetInputFocus(display,x.XDefaultRootWindow(display),2,0);x.XSync(display,False);time.sleep(.2)
  x.XSetInputFocus(display,windows[0],2,0);x.XSync(display,False);time.sleep(.2)
  key('x');expect('ab\nd😀x','native-document-input-resumes-after-window-focus-return')
  evidence['after']=snapshot(True)
  import base64
  resource=next(r for r in client.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
  png=client.request('resources/read',{'uri':resource['uri']});a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
 finally:
  for count in range(100):
   if snapshot(True)['xml']==before['xml']:break
   command('edit.undo')
  evidence['originalXmlRestored']=snapshot(True)['xml']==before['xml'];evidence['undoCount']=count
  a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
  assert evidence['originalXmlRestored']
  wait(lambda:client.tool('ui_snapshot',{'windowId':0}),lambda tree:any('Recovery saved' in (n.get('label') or '') for n in tree['nodes']))
x.XCloseDisplay(display)
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
