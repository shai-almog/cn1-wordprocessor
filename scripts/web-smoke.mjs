// Headless browser check of the web build: waits for the app, types a short document,
// and saves a screenshot.
// Usage: node web-smoke.mjs URL OUT.png LINES_JSON [PROFILE_DIR]
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
const url = process.argv[2], out = process.argv[3], lines = JSON.parse(process.argv[4]);
const profile = process.argv[5] || '/tmp/web-smoke-profile';
const browser = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
// Ubuntu 24.04 blocks the unprivileged user namespaces Chrome's sandbox needs.
const sandbox = process.platform === 'linux' ? ['--no-sandbox'] : [];
const chrome = spawn(browser,
  [...sandbox,'--headless=new','--remote-debugging-port=9333',`--user-data-dir=${profile}`,'--window-size=1440,900','--force-device-scale-factor=2','about:blank'],{stdio:'ignore'});
const sleep = ms => new Promise(r => setTimeout(r, ms));
let targets;
for (let i = 0; !targets; i++) {
  await sleep(500);
  try { targets = await (await fetch('http://127.0.0.1:9333/json')).json(); }
  catch (e) { if (i > 60) throw e; }
}
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise(r => ws.onopen = r);
let id = 0; const pending = {};
ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && pending[m.id]) { pending[m.id](m.result); delete pending[m.id]; } };
const send = (method, params = {}) => new Promise(r => { pending[++id] = r; ws.send(JSON.stringify({ id, method, params })); });
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 2, mobile: false });
await send('Page.navigate', { url });
await sleep(30000);
const click = async (x, y) => { for (const type of ['mousePressed','mouseReleased']) await send('Input.dispatchMouseEvent', { type, x, y, button: 'left', clickCount: 1 }); };
await click(720, 450); await sleep(800);
const key = async k => { await send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code: k, text: '\r', unmodifiedText: '\r', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 }); await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code: k, windowsVirtualKeyCode: 13 }); };
for (const line of lines) { await send('Input.insertText', { text: line }); await sleep(300); await key('Enter'); await sleep(300); }
await sleep(2000);
const shot = await send('Page.captureScreenshot', { format: 'png' });
writeFileSync(out, Buffer.from(shot.data, 'base64'));
chrome.kill(); process.exit(0);
