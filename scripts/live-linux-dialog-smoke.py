#!/usr/bin/env python3
"""Local X11 dialog-field Unicode acceptance against one disposable native Linux review app.

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
x.XInternAtom.argtypes=[C.c_void_p,C.c_char_p,C.c_int];x.XInternAtom.restype=C.c_ulong
x.XGetWindowProperty.argtypes=[C.c_void_p,C.c_ulong,C.c_ulong,C.c_long,C.c_long,C.c_int,C.c_ulong,C.POINTER(C.c_ulong),C.POINTER(C.c_int),C.POINTER(C.c_ulong),C.POINTER(C.c_ulong),C.POINTER(C.c_void_p)]
x.XTranslateCoordinates.argtypes=[C.c_void_p,C.c_ulong,C.c_ulong,C.c_int,C.c_int,C.POINTER(C.c_int),C.POINTER(C.c_int),C.POINTER(C.c_ulong)]
xt.XTestFakeMotionEvent.argtypes=[C.c_void_p,C.c_int,C.c_int,C.c_int,C.c_ulong]
xt.XTestFakeButtonEvent.argtypes=[C.c_void_p,C.c_uint,C.c_int,C.c_ulong]
def title(window):
 atom=x.XInternAtom(display,b'_NET_WM_NAME',True)
 actual=C.c_ulong();fmt=C.c_int();count=C.c_ulong();remaining=C.c_ulong();value=C.c_void_p()
 result=x.XGetWindowProperty(display,window,atom,0,4096,False,0,C.byref(actual),C.byref(fmt),C.byref(count),C.byref(remaining),C.byref(value))
 if result or not value.value:return name(window)
 label=C.string_at(value,count.value).decode('utf-8',errors='replace');x.XFree(value);return label
with m.Client(a.port) as client:
 evidence={'checks':[],'method':'XTest keyboard and pointer events into a CN1 EditField through the GTK input-method bridge, observed by MCP','originalXmlRestored':False,'physicalKeyboardCertified':False}
 def snapshot():return client.tool('word_snapshot',{'xml':True})
 def find(label,window=0):return [n for n in client.tool('ui_find',{'windowId':window,'label':label}) if n['label']==label]
 def activate(label,window=0):
  nodes=find(label,window);assert len(nodes)==1,(label,nodes)
  result=client.tool('ui_activate',{'windowId':window,'nodeId':nodes[0]['id']});assert result.get('success') or result.get('pending'),result
 def dialog(label):return next(w['windowId'] for w in wait(lambda:client.tool('ui_windows'),lambda ws:any(w['title']==label and w['visible'] for w in ws)) if w['title']==label and w['visible'])
 before=snapshot();evidence['before']=before
 a.output.write_text(json.dumps(evidence,indent=2)+'\n')
 try:
  activate('Review')
  for attempt in range(8):
   nodes=find('Compare documents')
   if nodes and nodes[0]['bounds'][2]>0:break
   activate('More ribbon groups')
  activate('Compare documents');window=dialog('Compare documents');activate('Continue',window);window=dialog('Comparison options')
  field=next(n for n in find('Revised text',window) if n['role']=='TEXT_FIELD')
  targets=[w for w in children(x.XDefaultRootWindow(display)) if title(w)=='Comparison options']
  assert len(targets)==1,[(w,title(w),size(w)) for w in children(x.XDefaultRootWindow(display))]
  target=targets[0];x.XSetInputFocus(display,target,2,0)
  bounds=field['bounds'];rx=C.c_int();ry=C.c_int();child=C.c_ulong()
  assert x.XTranslateCoordinates(display,target,x.XDefaultRootWindow(display),bounds[0]+12,bounds[1]+12,C.byref(rx),C.byref(ry),C.byref(child))
  assert xt.XTestFakeMotionEvent(display,-1,rx.value,ry.value,0)
  assert xt.XTestFakeButtonEvent(display,1,True,0);assert xt.XTestFakeButtonEvent(display,1,False,0);x.XSync(display,False)
  time.sleep(.15)
  key('a','Control_L');text('caf');key('u','Control_L','Shift_L');text('e9');key('Return');key('u','Control_L','Shift_L');text('1f600');key('Return')
  def fieldValue():return next(n for n in client.tool('ui_snapshot',{'windowId':window})['nodes'] if n.get('label')=='Revised text' and n.get('role')=='TEXT_FIELD')
  evidence['field']=wait(fieldValue,lambda n:n.get('value')=='café😀')
  evidence['checks'].append('dialog-editfield-preserves-bmp-and-supplementary-unicode')
  x.XSetInputFocus(display,x.XDefaultRootWindow(display),2,0);x.XSync(display,False);time.sleep(.2)
  x.XSetInputFocus(display,target,2,0);x.XSync(display,False);time.sleep(.2)
  key('x');wait(fieldValue,lambda n:n.get('value')=='café😀x')
  evidence['checks'].append('dialog-input-resumes-after-native-window-focus-return')
  author=next(n for n in find('Label changes with',window) if n['role']=='TEXT_FIELD')
  ab=author['bounds']
  assert x.XTranslateCoordinates(display,target,x.XDefaultRootWindow(display),ab[0]+12,ab[1]+12,C.byref(rx),C.byref(ry),C.byref(child))
  xt.XTestFakeMotionEvent(display,-1,rx.value,ry.value,0)
  xt.XTestFakeButtonEvent(display,1,True,0);xt.XTestFakeButtonEvent(display,1,False,0);x.XSync(display,False);time.sleep(.15)
  key('a','Control_L');text('writer');key('u','Control_L','Shift_L');text('e9');key('Return')
  wait(lambda:next(n for n in client.tool('ui_snapshot',{'windowId':window})['nodes'] if n.get('label')=='Label changes with' and n.get('role')=='TEXT_FIELD'),lambda n:n.get('value')=='writeré')
  assert fieldValue()['value']=='café😀x'
  evidence['checks'].append('switching-dialog-fields-routes-input-to-new-owner')
  assert snapshot()['xml']==before['xml'];evidence['checks'].append('native-field-keystrokes-do-not-edit-document-canvas')
  import base64
  resource=next(r for r in client.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
  png=client.request('resources/read',{'uri':resource['uri']});a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
 finally:
  evidence['windows']=client.tool('ui_windows')
  evidence['x11Windows']=[{'id':w,'title':title(w),'size':size(w),'children':[(c,size(c)) for c in children(w)]} for w in children(x.XDefaultRootWindow(display))]
  import base64
  resource=next(r for r in client.request('resources/list')['resources'] if r.get('mimeType')=='image/png')
  png=client.request('resources/read',{'uri':resource['uri']});a.output.with_suffix('.png').write_bytes(base64.b64decode(png['contents'][0]['blob']))
  for w in client.tool('ui_windows'):
   if w['visible'] and w['title'] in ['Comparison options','Compare documents']:activate('Cancel',w['windowId'])
  evidence['originalXmlRestored']=snapshot()['xml']==before['xml'];a.output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n');assert evidence['originalXmlRestored']
x.XCloseDisplay(display)
print(json.dumps({'checks':evidence['checks'],'originalXmlRestored':evidence['originalXmlRestored']}))
