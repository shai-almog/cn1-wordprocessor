#!/usr/bin/env python3
"""Attach to the running agent-debug app's built-in loopback MCP server.

Examples: live-mcp.py tools; live-mcp.py word_snapshot; live-mcp.py ui_find '{"label":"Home"}'
Use --request-file for exact JSON requests, --output to retain a response, or --screenshot PATH.
"""
import argparse
import base64
import json
from pathlib import Path
import socket

class Client:
    def __init__(self, port=18765, timeout=15):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout)
        self.stream = self.socket.makefile('rwb')
        self.next_id = 0
        self.request('initialize', {'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'wordprocessor-local-review','version':'1'}})
        self.send({'jsonrpc':'2.0','method':'notifications/initialized','params':{}})
    def send(self, data):
        self.stream.write((json.dumps(data,ensure_ascii=False)+'\n').encode('utf-8'))
        self.stream.flush()
    def request(self, method, params=None):
        self.next_id += 1
        ident = self.next_id
        self.send({'jsonrpc':'2.0','id':ident,'method':method,'params':params or {}})
        while True:
            line = self.stream.readline(32*1024*1024)
            if not line:
                raise RuntimeError('MCP connection closed')
            message = json.loads(line)
            if message.get('id') != ident:
                continue
            if 'error' in message:
                raise RuntimeError(message['error'])
            return message['result']
    def tool(self, name, args=None):
        result = self.request('tools/call', {'name':name,'arguments':args or {}})
        if result.get('isError'):
            raise RuntimeError(result)
        content = result.get('content',[])
        if len(content)==1 and content[0].get('type')=='text':
            value = content[0]['text']
            try: return json.loads(value)
            except ValueError: return value
        return result
    def close(self):
        self.stream.close()
        self.socket.close()
    def __enter__(self): return self
    def __exit__(self,*args): self.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tool',nargs='?',default='word_snapshot')
    parser.add_argument('arguments',nargs='?',default='{}')
    parser.add_argument('--port',type=int,default=18765)
    parser.add_argument('--timeout',type=int,default=15)
    parser.add_argument('--request-file',type=Path)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--screenshot',type=Path)
    args=parser.parse_args()
    with Client(args.port,args.timeout) as client:
        if args.screenshot:
            resources=client.request('resources/list')['resources']
            resource=next(r for r in resources if r.get('mimeType')=='image/png')
            result=client.request('resources/read',{'uri':resource['uri']})
            args.screenshot.write_bytes(base64.b64decode(result['contents'][0]['blob']))
            result={'screenshot':str(args.screenshot)}
        elif args.request_file:
            request=json.loads(args.request_file.read_text())
            result=client.tool(request['tool'],request.get('arguments',{}))
        elif args.tool=='tools':
            result=client.request('tools/list')
        else:
            result=client.tool(args.tool,json.loads(args.arguments))
    rendered=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output: args.output.write_text(rendered)
    else: print(rendered,end='')
