const { app, BrowserWindow, shell, nativeTheme, ipcMain, net } = require('electron');
const path = require('path'), fs = require('fs');
nativeTheme.themeSource = 'dark';

/* ---------- auto updates for the player page ----------
   The app ships with a built-in copy of the player (index.html). It also checks an
   update link (a folder online holding version.json + index.html). When a newer
   version is posted there, it is downloaded to the user's app data and used from then on. */
const BUNDLED = path.join(__dirname, 'index.html');
const DATA = () => app.getPath('userData');
const LIVE = () => path.join(DATA(), 'live', 'index.html');
const CFG = () => path.join(DATA(), 'update.json');
const DEFAULT_FEED = 'https://raw.githubusercontent.com/slayerischillin/abyss/main';
const readCfg = () => { try { return JSON.parse(fs.readFileSync(CFG(), 'utf8')); } catch { return {}; } };
const writeCfg = c => { try { fs.writeFileSync(CFG(), JSON.stringify(c, null, 1)); } catch {} };
const feed = () => (readCfg().feed || DEFAULT_FEED).replace(/\/+$/, '');
const verOf = f => { try { const m = fs.readFileSync(f, 'utf8').slice(0, 4000).match(/name="abyss-version" content="([\d.]+)"/); return m ? +m[1] : 0; } catch { return 0; } };
const pagePath = () => verOf(LIVE()) > verOf(BUNDLED) ? LIVE() : BUNDLED;
const curVer = () => Math.max(verOf(LIVE()), verOf(BUNDLED));

function get(url) {
  return new Promise((res, rej) => {
    const r = net.request({ url: url + (url.includes('?') ? '&' : '?') + 't=' + Date.now(), cache: 'no-cache' });
    let chunks = [];
    r.on('response', resp => { if (resp.statusCode !== 200) return rej(new Error('HTTP ' + resp.statusCode));
      resp.on('data', c => chunks.push(c)); resp.on('end', () => res(Buffer.concat(chunks).toString('utf8'))); resp.on('error', rej); });
    r.on('error', rej); r.end();
  });
}
let checking = false;
async function checkUpdate(win, manual) {
  const f = feed(); if (!f || checking) { if (manual) send(win, { state: f ? 'busy' : 'nofeed', version: curVer() }); return; }
  checking = true;
  try {
    const info = JSON.parse(await get(f + '/version.json'));
    const v = +info.version;
    if (!(v > curVer())) { send(win, { state: 'current', version: curVer(), manual }); return; }
    send(win, { state: 'downloading', version: v });
    const html = await get(f + '/' + (info.file || 'index.html'));
    if (!html.includes('name="abyss-version"') || !html.includes('<title>')) throw new Error('bad file');
    fs.mkdirSync(path.dirname(LIVE()), { recursive: true });
    fs.writeFileSync(LIVE() + '.tmp', html); fs.renameSync(LIVE() + '.tmp', LIVE());
    send(win, { state: 'ready', version: v, notes: info.notes || '' });
  } catch (e) { send(win, { state: 'error', error: String(e.message || e), version: curVer(), manual }); }
  finally { checking = false; }
}
const send = (win, s) => { if (win && !win.isDestroyed()) win.webContents.send('update', s); };

function create() {
  const win = new BrowserWindow({
    width: 1400, height: 860, minWidth: 900, minHeight: 600,
    title: 'Abyss', backgroundColor: '#020104', show: false,
    icon: path.join(__dirname, 'icon.png'), autoHideMenuBar: true, frame: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), webSecurity: false, backgroundThrottling: false, autoplayPolicy: 'no-user-gesture-required' }
  });
  win.setMenu(null);
  win.loadFile(pagePath());
  win.once('ready-to-show', () => win.show());
  const st = () => win.webContents.send('win-state', { max: win.isMaximized(), full: win.isFullScreen() });
  ['maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen'].forEach(e => win.on(e, st));
  win.webContents.on('did-finish-load', () => { st(); setTimeout(() => checkUpdate(win), 4000); });
  setInterval(() => checkUpdate(win), 30 * 60 * 1000);
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('before-input-event', (e, i) => {
    if (i.type !== 'keyDown') return;
    if (i.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (i.control && i.shift && i.key.toLowerCase() === 'i') win.webContents.toggleDevTools();
  });
}
ipcMain.on('win', (e, a) => { const w = BrowserWindow.fromWebContents(e.sender); if (!w) return;
  if (a === 'min') w.minimize(); else if (a === 'max') w.isMaximized() ? w.unmaximize() : w.maximize(); else if (a === 'close') w.close(); });
ipcMain.handle('upd-get', () => ({ feed: feed(), version: curVer() }));
ipcMain.handle('upd-set', (e, f) => { const c = readCfg(); c.feed = String(f || '').trim(); writeCfg(c); checkUpdate(BrowserWindow.fromWebContents(e.sender), true); return feed(); });
ipcMain.on('upd-check', e => checkUpdate(BrowserWindow.fromWebContents(e.sender), true));
ipcMain.on('upd-apply', e => { const w = BrowserWindow.fromWebContents(e.sender); if (w) w.loadFile(pagePath()); });
ipcMain.on('upd-reset', e => { try { fs.rmSync(LIVE()); } catch {} const w = BrowserWindow.fromWebContents(e.sender); if (w) w.loadFile(pagePath()); });


/* ---------- Discord Rich Presence (talks to the Discord app on this PC) ----------
   The page sends { clientId, activity } and we pass it to Discord over its local pipe.
   Keeping the details in the page means they update along with the player. */
const netMod = require('net');
const rpc = { sock: null, ready: false, clientId: '', pending: null, buf: Buffer.alloc(0), retry: null, nonce: 0 };
function rpcSend(op, obj) {
  if (!rpc.sock) return;
  const body = Buffer.from(JSON.stringify(obj), 'utf8'), head = Buffer.alloc(8);
  head.writeInt32LE(op, 0); head.writeInt32LE(body.length, 4);
  try { rpc.sock.write(Buffer.concat([head, body])); } catch {}
}
function rpcClose() { if (rpc.sock) { try { rpc.sock.destroy(); } catch {} } rpc.sock = null; rpc.ready = false; }
function rpcConnect(i = 0) {
  if (!rpc.clientId || rpc.sock) return;
  const pipe = process.platform === 'win32' ? '\\\\?\\pipe\\discord-ipc-' + i
    : path.join(process.env.XDG_RUNTIME_DIR || process.env.TMPDIR || '/tmp', 'discord-ipc-' + i);
  const s = netMod.createConnection(pipe);
  s.once('connect', () => { rpc.sock = s; rpc.buf = Buffer.alloc(0); rpcSend(0, { v: 1, client_id: rpc.clientId }); });
  s.on('data', d => {
    rpc.buf = Buffer.concat([rpc.buf, d]);
    while (rpc.buf.length >= 8) {
      const len = rpc.buf.readInt32LE(4); if (rpc.buf.length < 8 + len) break;
      let msg = {}; try { msg = JSON.parse(rpc.buf.slice(8, 8 + len).toString('utf8')); } catch {}
      rpc.buf = rpc.buf.slice(8 + len);
      if (msg.evt === 'READY') { rpc.ready = true; if (rpc.pending) rpcActivity(rpc.pending); }
    }
  });
  s.on('error', () => { s.destroy(); if (!rpc.sock && i < 9) rpcConnect(i + 1); else scheduleRetry(); });
  s.on('close', () => { if (rpc.sock === s) { rpcClose(); scheduleRetry(); } });
}
function scheduleRetry() { clearTimeout(rpc.retry); rpc.retry = setTimeout(() => rpcConnect(0), 15000); }
function rpcActivity(activity) {
  rpc.pending = activity;
  if (!rpc.ready) return;
  rpcSend(1, { cmd: 'SET_ACTIVITY', args: { pid: process.pid, activity: activity || undefined }, nonce: String(++rpc.nonce) });
}
ipcMain.on('rpc', (e, msg) => {
  if (!msg) return;
  if (msg.clientId && msg.clientId !== rpc.clientId) { rpcClose(); rpc.clientId = String(msg.clientId); rpcConnect(0); }
  if (msg.off) { rpcActivity(null); return; }
  if ('activity' in msg) rpcActivity(msg.activity);
});
app.on('before-quit', () => { try { rpcActivity(null); } catch {} rpcClose(); });

app.setAppUserModelId('com.abyss.player');
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { const w = BrowserWindow.getAllWindows()[0]; if (w) { if (w.isMinimized()) w.restore(); w.focus(); } });
  app.whenReady().then(create);
  app.on('window-all-closed', () => app.quit());
}
