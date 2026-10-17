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
  key('Right','Control_L');selection(0,4);check('ctrl-right-word-boundary')
  key('Right','Control_L','Shift_L');selection(0,8,0,4);check('ctrl-shift-right-keeps-anchor')
  key('Left');selection(0,4);check('left-collapses-range-to-start')
  key('End','Control_L');selection(2,13);check('ctrl-end-story-boundary')
  key('Home','Control_L','Shift_L');selection(0,0,2,13);check('ctrl-shift-home-reversed-story-selection')
  key('Right');selection(2,13);check('right-collapses-reversed-range-to-end')
  key('BackSpace','Control_L');wait(snapshot,lambda s:s['text']=='one two three\nshort\none two ');check('ctrl-backspace-word-delete')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='one two three\nshort\none two three');check('word-delete-undo')
  key('Home','Control_L');key('Delete','Control_L');wait(snapshot,lambda s:s['text']=='two three\nshort\none two three');check('ctrl-delete-word-and-trailing-space')
  key('z','Control_L');wait(snapshot,lambda s:s['text']=='one two three\nshort\none two three')
  key('Home','Control_L');key('End');selection(0,13)
  key('Down');selection(1,5);key('Down');selection(2,13);check('vertical-preferred-column-survives-short-line')
  key('Up','Shift_L');selection(1,5,2,13);key('Up','Shift_L');selection(0,13,2,13);check('shift-up-keeps-anchor-and-preferred-column')
  fixture('中文\u202fא\u05b7ב\u2007कि cafe\u0301 😀 end')
  key('Right','Control_L');selection(0,3)
  key('Right','Control_L');selection(0,7)
  key('Right','Control_L');selection(0,10)
  key('Right','Control_L');selection(0,16);check('native-word-classification-for-cjk-hebrew-indic-and-combining-mark')
  key('Right','Control_L');selection(0,19);check('native-word-movement-keeps-supplementary-character-whole')
  key('Left','Control_L');selection(0,16)
  key('Delete','Control_L');wait(snapshot,lambda s:s['text']=='中文\u202fא\u05b7ב\u2007कि cafe\u0301 end');check('native-word-delete-keeps-surrogates-whole')
  fixture('0123456789 '*45)
  key('End');end=wait(snapshot,lambda s:0<s['selection']['caret']<495)['selection']['caret'];selection(0,end);check('end-stops-at-visual-wrap')
  key('Home');selection(0,0);check('home-keeps-upstream-wrap-affinity')
  key('End');selection(0,end);key('Down');wait(snapshot,lambda s:s['selection']['caret']>end)
  key('Home');selection(0,end);check('down-and-home-reach-next-visual-line')
  key('Up');selection(0,0);check('up-from-wrap-start-reaches-previous-line')
  fixture('\n'.join('line %02d sample'%i for i in range(80)))
  key('Next');page=wait(snapshot,lambda s:1<s['selection']['block']<79)['selection'];check('page-down-moves-by-viewport')
  key('Prior');selection(0,0);check('page-up-returns-to-start')
  key('End','Control_L');selection(79,len('line 79 sample'));check('ctrl-end-crosses-pages')
  key('Home','Control_L','Shift_L');selection(0,0,79,len('line 79 sample'));check('ctrl-shift-home-selects-across-pages')
  evidence['wrapOffset']=end;evidence['pageDownSelection']=page;evidence['after']=snapshot(True)
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
