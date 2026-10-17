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
 def command(command,args=None):return client.tool('word_command',{'stateId':snapshot()['stateId'],'command':command,'args':args or {}})
 def selection(block,caret,anchorBlock=None,anchor=None):
  expected={'block':block,'caret':caret,'anchorBlock':block if anchorBlock is None else anchorBlock,'anchor':caret if anchor is None else anchor}
  return wait(snapshot,lambda s:s['selection']==expected)
 def check(label):evidence['checks'].append(label)
 def activate():
  nodes=client.tool('ui_find',{'windowId':0,'label':'Paragraph 1'})
  node=next(n for n in nodes if n['role']=='TEXT_FIELD' and n['label']=='Paragraph 1')
  assert client.tool('ui_activate',{'windowId':0,'nodeId':node['id']}).get('success')
 def fixture(value):
  key('a','Control_L');key('BackSpace');wait(snapshot,lambda s:s['text']=='')
  command('text.insert',{'text':value});key('Home','Control_L')
  # Command editing deliberately stops the native input session. Activate it again.
  # The command scrolls to the end; use selection.set to bring the first paragraph into view.
  command('selection.set',{'block':0,'start':0,'end':0});activate()
 before=snapshot(True);assert before['text']=='','Run on an empty disposable document'
 evidence['before']=before;a.output.write_text(json.dumps(evidence,indent=2)+'\n')
 try:
  windows=[w for w in children(x.XDefaultRootWindow(display)) if size(w)[0]>=400 and size(w)[1]>=200]
  assert len(windows)==1,[(w,name(w)) for w in children(x.XDefaultRootWindow(display))]
  x.XSetInputFocus(display,windows[0],2,0);x.XSync(display,False);time.sleep(.1)
  activate();fixture('one two three\nshort\none two three')
  key('Down','Control_L');selection(1,0);check('ctrl-down-next-paragraph-start')
  key('Down','Control_L','Shift_L');selection(2,0,1,0);check('ctrl-shift-down-paragraph-selection')
  key('End','Control_L');key('Up','Control_L');selection(2,0);check('ctrl-up-current-paragraph-start')
  key('Up','Control_L');selection(1,0);check('ctrl-up-previous-paragraph-start')
  key('Home','Control_L');key('Up','Control_L');selection(0,0);check('paragraph-movement-clamps-at-story-start')
  key('End','Control_L');key('Down','Control_L');selection(2,13);check('paragraph-movement-clamps-at-story-end')
  fixture('')
  text('hello');key('space');text('world');wait(snapshot,lambda s:s['text']=='hello world')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='hello ');check('typed-word-undo')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='');check('preceding-word-undo-includes-space')
  key('y','Control_L');wait(snapshot,lambda s:s['text']=='hello ')
  key('y','Control_L');wait(snapshot,lambda s:s['text']=='hello world');check('typed-word-redo')
  key('a','Control_L');text('new');wait(snapshot,lambda s:s['text']=='new')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='hello world');check('selection-replacement-typing-one-undo')
  key('End');text('xy');wait(snapshot,lambda s:s['text']=='hello worldxy');time.sleep(1.7)
  text('ab');wait(snapshot,lambda s:s['text']=='hello worldxyab')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='hello worldxy');check('typing-pause-starts-new-group')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='hello world');check('prior-group-retained-after-pause')
  key('Home');text('ab');key('Right');text('cd');wait(snapshot,lambda s:s['text']=='abhcdello world')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='abhello world');check('caret-movement-separates-typing')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='hello world')
  key('End');key('b','Control_L');text('bold');wait(snapshot,lambda s:s['text']=='hello worldbold')
  import xml.etree.ElementTree as ET
  state=snapshot(True);evidence['boldSnapshot']=state;assert ''.join(r.text or '' for r in ET.fromstring(state['xml']).findall('./p/r') if r.get('bold')=='true')=='bold'
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='hello world');check('bold-typing-preserves-insertion-style-and-group')
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
