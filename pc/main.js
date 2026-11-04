'use strict';
// 모아 플레이어 (PC) - main process
const { app, BrowserWindow, ipcMain, dialog, protocol, shell, Menu, powerSaveBlocker, nativeImage, nativeTheme } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { Readable } = require('stream');
const { JsonStore } = require('./lib/store');
const library = require('./lib/library');
const ff = require('./lib/ffmpeg');
const updater = require('./updater');

// ---------------------------------------------------------------- startup options
function argValue(name) {
  const p = process.argv.find((a) => a.startsWith(`--${name}=`));
  return p ? p.slice(name.length + 3) : null;
}
const testUserData = argValue('moa-userdata');
app.setName('MoaPlayer');
app.setPath('userData', testUserData || path.join(app.getPath('appData'), 'MoaPlayer'));
// Same id as the installer's shortcuts (build.appId), so the taskbar groups and pins the app correctly.
if (process.platform === 'win32') app.setAppUserModelId(app.isPackaged ? 'com.moa.player' : process.execPath);

protocol.registerSchemesAsPrivileged([{
  scheme: 'moa',
  privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true },
}]);

// Lets <video>.audioTracks work for files with several audio tracks.
app.commandLine.appendSwitch('enable-blink-features', 'AudioVideoTracks');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const USER = app.getPath('userData');
const THUMBS = path.join(USER, 'thumbs');
const SUBCACHE = path.join(USER, 'subcache');
fs.mkdirSync(THUMBS, { recursive: true });
fs.mkdirSync(SUBCACHE, { recursive: true });

const defaultFolders = () => ['videos', 'downloads', 'desktop']
  .map((k) => { try { return app.getPath(k); } catch (e) { return null; } })
  .filter((p) => p && fs.existsSync(p));

const settings = new JsonStore(path.join(USER, 'settings.json'), {
  folders: null, decoder: 'auto', resume: 'ask', autoplayNext: true, seekSeconds: 10, doubleClick: 'mx',
  gestureSeek: true, gestureVolume: true, gestureBrightness: true, gestureZoom: true, longPressSpeed: true,
  wheel: 'volume', volume: 1, boost: true, subSize: 100, subColor: '#ffffff', subOutline: true, subBg: false,
  subBottom: 6, subEncoding: 'auto', sort: 'name', sortDesc: false, view: 'grid', showThumbs: true,
  hevcNative: 'auto', alwaysOnTop: false, bounds: null, autoUpdate: true, updatePending: null, fastSeek: true,
});
const history = new JsonStore(path.join(USER, 'history.json'), { items: {} });
const meta = new JsonStore(path.join(USER, 'meta.json'), { items: {} });

const testLibrary = argValue('moa-library');
if (testLibrary) settings.data.folders = testLibrary.split(path.delimiter);
if (!Array.isArray(settings.data.folders)) settings.data.folders = defaultFolders();

ff.findBinaries(process.resourcesPath, __dirname);
let ffmpegOk = false;

// ---------------------------------------------------------------- window
let win = null;
let pendingFiles = filesFromArgv(process.argv);
let blocker = -1;

function filesFromArgv(argv) {
  const out = [];
  for (const a of argv.slice(1)) {
    if (!a || a.startsWith('-') || a === '.') continue;
    try {
      const p = path.resolve(a);
      if (fs.existsSync(p) && fs.statSync(p).isFile() && library.isVideo(p)) out.push(p);
    } catch (e) { /* ignore */ }
  }
  return out;
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', (e, argv) => {
    const files = filesFromArgv(argv);
    if (win) {
      if (win.isMinimized()) win.restore();
      win.show();
      win.focus();
      if (files.length) win.webContents.send('open-files', files);
    }
  });
}

function createWindow() {
  const b = settings.data.bounds || {};
  const iconPath = path.join(__dirname, 'assets', process.platform === 'win32' ? 'icon.ico' : 'icon.png');
  win = new BrowserWindow({
    width: b.width || 1280, height: b.height || 780, x: b.x, y: b.y,
    minWidth: 720, minHeight: 460, backgroundColor: '#121418', title: '모아 플레이어',
    icon: fs.existsSync(iconPath) ? nativeImage.createFromPath(iconPath) : undefined,
    autoHideMenuBar: true, show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false,
      sandbox: true, backgroundThrottling: false, spellcheck: false,
    },
  });
  Menu.setApplicationMenu(null);
  if (settings.data.alwaysOnTop) win.setAlwaysOnTop(true);
  if (b.maximized) win.maximize();
  win.once('ready-to-show', () => win.show());
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.on('enter-full-screen', () => win.webContents.send('fullscreen', true));
  win.on('leave-full-screen', () => win.webContents.send('fullscreen', false));
  const saveBounds = () => {
    if (!win || win.isFullScreen()) return;
    settings.data.bounds = Object.assign(win.isMaximized() ? (settings.data.bounds || {}) : win.getBounds(), { maximized: win.isMaximized() });
    settings.save();
  };
  win.on('resize', saveBounds);
  win.on('move', saveBounds);
  win.on('closed', () => { win = null; thumbKey = ''; stopStream(); });
  win.loadURL('moa://app/index.html');
}

// ---------------------------------------------------------------- moa:// protocol
const MIME = {
  html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', css: 'text/css; charset=utf-8',
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', svg: 'image/svg+xml', ico: 'image/x-icon', woff2: 'font/woff2',
  mp4: 'video/mp4', m4v: 'video/mp4', mov: 'video/mp4', webm: 'video/webm', mkv: 'video/x-matroska',
  ogv: 'video/ogg', ogg: 'video/ogg', '3gp': 'video/3gpp', mp3: 'audio/mpeg',
};

async function serveFile(req, file, mime) {
  let st;
  try { st = await fs.promises.stat(file); } catch (e) { return new Response('not found', { status: 404 }); }
  const size = st.size;
  const range = req.headers.get('range');
  let start = 0, end = size - 1, status = 200;
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    if (m) {
      if (m[1] === '' && m[2] !== '') { start = Math.max(0, size - Number(m[2])); }
      else { start = Number(m[1] || 0); if (m[2] !== '') end = Number(m[2]); }
      status = 206;
    }
  }
  if (start >= size && size > 0) return new Response(null, { status: 416, headers: { 'Content-Range': `bytes */${size}` } });
  end = Math.min(end, size - 1);
  const headers = {
    'Content-Type': mime || 'application/octet-stream', 'Accept-Ranges': 'bytes',
    'Content-Length': String(Math.max(0, end - start + 1)), 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-cache',
  };
  if (status === 206) headers['Content-Range'] = `bytes ${start}-${end}/${size}`;
  const body = size === 0 ? null : Readable.toWeb(fs.createReadStream(file, { start, end }));
  return new Response(body, { status, headers });
}

let activeStream = null;
function stopStream() {
  if (activeStream) {
    try { activeStream.kill('SIGKILL'); } catch (e) { /* already gone */ }
    activeStream = null;
  }
}

function handleStream(u) {
  const file = u.searchParams.get('p');
  if (!file || !fs.existsSync(file)) return new Response('not found', { status: 404 });
  stopStream();
  const proc = ff.spawnStream({
    file, start: Number(u.searchParams.get('t') || 0), audio: Number(u.searchParams.get('a') || 0),
    videoCopy: u.searchParams.get('v') === 'copy', burnSub: Number(u.searchParams.get('s') ?? -1),
    hevcTag: u.searchParams.get('hevc') === '1', maxHeight: Number(u.searchParams.get('h') || 0),
  });
  activeStream = proc;
  let err = '';
  proc.stderr.on('data', (d) => { err += d.toString(); if (err.length > 4000) err = err.slice(-4000); });
  proc.on('exit', (code) => {
    if (code && code !== 255 && activeStream === proc && win) win.webContents.send('stream-error', err.trim().split('\n').slice(-3).join('\n'));
    if (activeStream === proc) activeStream = null;
  });
  proc.on('error', (e) => { if (win) win.webContents.send('stream-error', e.message); });
  return new Response(Readable.toWeb(proc.stdout), {
    status: 200, headers: { 'Content-Type': 'video/mp4', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' },
  });
}

async function handleMoa(req) {
  const u = new URL(req.url);
  if (u.host === 'app') {
    const rel = decodeURIComponent(u.pathname).replace(/^\/+/, '') || 'index.html';
    const file = path.normalize(path.join(__dirname, 'renderer', rel));
    if (!file.startsWith(path.join(__dirname, 'renderer'))) return new Response('forbidden', { status: 403 });
    return serveFile(req, file, MIME[library.ext(file)]);
  }
  if (u.host === 'media') {
    const file = u.searchParams.get('p') || '';
    if (!library.isVideo(file) && !library.isSubtitle(file)) return new Response('forbidden', { status: 403 });
    return serveFile(req, file, MIME[library.ext(file)] || 'video/mp4');
  }
  if (u.host === 'thumb') {
    const k = (u.searchParams.get('k') || '').replace(/[^a-f0-9]/g, '');
    return serveFile(req, path.join(THUMBS, k + '.jpg'), 'image/jpeg');
  }
  if (u.host === 'stream') return handleStream(u);
  return new Response('not found', { status: 404 });
}

// ---------------------------------------------------------------- metadata, thumbnails
function fileKey(p, st) { return crypto.createHash('sha1').update(`${p}|${st.size}|${Math.round(st.mtimeMs)}`).digest('hex'); }

const probeWaiters = new Map();
async function probeCached(p) {
  const st = await fs.promises.stat(p);
  const key = fileKey(p, st);
  const hit = meta.data.items[key];
  if (hit) return hit;
  if (probeWaiters.has(key)) return probeWaiters.get(key);
  const job = ff.probe(p).then((info) => {
    meta.data.items[key] = info;
    meta.save();
    probeWaiters.delete(key);
    return info;
  }).catch((e) => { probeWaiters.delete(key); throw e; });
  probeWaiters.set(key, job);
  return job;
}

// simple concurrency limiter for thumbnail jobs
const thumbQueue = [];
let thumbRunning = 0;
function enqueueThumb(fn) {
  return new Promise((resolve) => {
    thumbQueue.push(async () => { try { resolve(await fn()); } catch (e) { resolve(null); } });
    pumpThumbs();
  });
}
function pumpThumbs() {
  while (thumbRunning < 2 && thumbQueue.length) {
    const job = thumbQueue.pop(); // newest first: what the user is looking at
    thumbRunning++;
    job().finally(() => { thumbRunning--; pumpThumbs(); });
  }
}

async function thumbFor(p) {
  if (!ffmpegOk) return null;
  const st = await fs.promises.stat(p);
  const key = fileKey(p, st);
  const out = path.join(THUMBS, key + '.jpg');
  if (fs.existsSync(out)) return `moa://thumb/?k=${key}`;
  return enqueueThumb(async () => {
    let dur = 0;
    try { dur = (await probeCached(p)).duration; } catch (e) { /* ignore */ }
    const ok = await ff.thumbnail(p, out, dur);
    return ok ? `moa://thumb/?k=${key}` : null;
  });
}

// ---------------------------------------------------------------- IPC
function handle(ch, fn) {
  ipcMain.handle(ch, async (e, ...args) => {
    try { return await fn(...args); } catch (err) { return { __error: err.message || String(err) }; }
  });
}

handle('app:info', async () => ({
  version: app.getVersion(), platform: process.platform, ffmpeg: ffmpegOk, ffmpegPath: ff.ffmpegPath,
  pictures: app.getPath('pictures'), packaged: app.isPackaged, updatable: updater.supported(),
}));
handle('update:status', async () => updater.getStatus());
handle('update:check', async () => updater.check(true));
handle('update:install', async () => updater.install());
handle('app:takePendingFiles', async () => { const f = pendingFiles; pendingFiles = []; return f; });

handle('settings:get', async () => settings.data);
handle('settings:set', async (patch) => {
  Object.assign(settings.data, patch || {});
  settings.save();
  if (patch && 'alwaysOnTop' in patch && win) win.setAlwaysOnTop(!!patch.alwaysOnTop);
  if (patch && 'autoUpdate' in patch) updater.setAuto(patch.autoUpdate !== false);
  return settings.data;
});

handle('history:all', async () => history.data.items);
const recentDocs = new Set();
handle('history:save', async (p, entry) => {
  if (!p) return false;
  if (!recentDocs.has(p)) {
    recentDocs.add(p);
    try { app.addRecentDocument(p); } catch (e) { /* not supported on this platform */ }
  }
  history.data.items[p] = Object.assign(history.data.items[p] || {}, entry, { last: Date.now() });
  history.save();
  return true;
});
handle('history:remove', async (p) => { delete history.data.items[p]; history.save(); return true; });
handle('history:clear', async () => { history.data.items = {}; history.flush(); return true; });

handle('lib:scan', async () => library.scan(settings.data.folders || []));
handle('lib:addFolder', async () => {
  const r = await dialog.showOpenDialog(win, { title: '동영상 폴더 추가', properties: ['openDirectory', 'multiSelections'] });
  if (r.canceled) return settings.data;
  const set = new Set(settings.data.folders || []);
  r.filePaths.forEach((p) => set.add(p));
  settings.data.folders = Array.from(set);
  settings.save();
  return settings.data;
});
handle('lib:addFolderPath', async (p) => {
  if (!p || !fs.existsSync(p) || !fs.statSync(p).isDirectory()) return settings.data;
  const set = new Set(settings.data.folders || []);
  set.add(p);
  settings.data.folders = Array.from(set);
  settings.save();
  return settings.data;
});
// ---------------------------------------------------------------- folder rename / delete
const samePath = (a, b) => (process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b);
const insidePath = (p, dir) => {
  const d = dir.endsWith(path.sep) ? dir : dir + path.sep;
  return process.platform === 'win32' ? p.toLowerCase().startsWith(d.toLowerCase()) : p.startsWith(d);
};
/** Drive roots, the user's standard folders and system/program folders are never renamed or removed. */
function protectedFolder(dir) {
  const d = path.resolve(dir);
  if (path.parse(d).root === d) return true;
  const special = ['home', 'desktop', 'documents', 'downloads', 'music', 'pictures', 'videos', 'appData', 'userData', 'temp']
    .map((k) => { try { return app.getPath(k); } catch (e) { return null; } });
  special.push(path.dirname(process.execPath), process.env.SystemRoot, process.env.ProgramFiles, process.env['ProgramFiles(x86)'],
    process.env.ProgramData, process.env.LOCALAPPDATA, process.env.APPDATA, process.env.USERPROFILE);
  for (const s of special.filter(Boolean).map((x) => path.resolve(x))) {
    if (samePath(d, s)) return true;
  }
  for (const s of [process.env.SystemRoot, process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.ProgramData,
    path.dirname(process.execPath), app.getPath('appData')].filter(Boolean).map((x) => path.resolve(x))) {
    if (insidePath(d, s)) return true;
  }
  return false;
}
function folderNameError(name) {
  if (!name) return '폴더 이름을 입력하세요';
  if (/[\\/:*?"<>|\u0000-\u001f]/.test(name)) return '폴더 이름에는 \\ / : * ? " < > | 를 쓸 수 없습니다';
  if (/[. ]$/.test(name)) return '폴더 이름은 마침표나 공백으로 끝날 수 없습니다';
  if (/^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$/i.test(name)) return 'Windows에서 쓸 수 없는 폴더 이름입니다';
  if (name.length > 200) return '폴더 이름이 너무 깁니다';
  return '';
}
async function retry(fn, times = 4) {
  for (let i = 0; ; i++) {
    try { return await fn(); } catch (e) {
      if (i >= times - 1 || !['EBUSY', 'EPERM', 'EACCES'].includes(e.code)) throw e;
      await new Promise((r) => setTimeout(r, 250 * (i + 1)));   // a thumbnail/probe job may still hold a file
    }
  }
}
/** Library folders, playback history and the recent list follow a renamed folder. */
function remapFolder(from, to) {
  const map = (p) => (samePath(p, from) ? to : insidePath(p, from) ? to + p.slice(from.length) : p);
  settings.data.folders = Array.from(new Set((settings.data.folders || []).map(map)));
  settings.save();
  const items = {};
  for (const [k, v] of Object.entries(history.data.items)) items[map(k)] = v;
  history.data.items = items;
  history.save();
}
handle('lib:renameFolder', async (dir, newName) => {
  const name = String(newName || '').trim();
  const bad = folderNameError(name);
  if (bad) throw new Error(bad);
  if (!dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) throw new Error('폴더를 찾을 수 없습니다');
  if (protectedFolder(dir)) throw new Error('Windows 기본 폴더(바탕 화면·다운로드·동영상 등)는 이름을 바꿀 수 없습니다');
  const target = path.join(path.dirname(dir), name);
  if (target === dir) return dir;
  if (fs.existsSync(target) && !samePath(target, dir)) throw new Error('같은 이름의 폴더가 이미 있습니다');
  stopStream();
  try {
    await retry(() => fs.promises.rename(dir, target));
  } catch (e) {
    if (['EBUSY', 'EPERM', 'EACCES'].includes(e.code)) throw new Error('폴더 안의 파일을 다른 프로그램이 쓰고 있어 이름을 바꿀 수 없습니다');
    throw e;
  }
  remapFolder(dir, target);
  return target;
});
/**
 * "Delete folder": the videos shown for this folder and their same-name subtitles go to the Recycle Bin;
 * the folder itself goes too when nothing else is left in it. Other files are never touched.
 */
handle('lib:deleteFolder', async (dir) => {
  if (!dir || !fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) throw new Error('폴더를 찾을 수 없습니다');
  const entries = await fs.promises.readdir(dir, { withFileTypes: true });
  const videos = entries.filter((e) => e.isFile() && library.isVideo(e.name)).map((e) => e.name);
  const bases = videos.map((n) => n.slice(0, n.lastIndexOf('.')).toLowerCase());
  const subs = entries.filter((e) => e.isFile() && library.isSubtitle(e.name)
    && bases.some((b) => e.name.toLowerCase().startsWith(b + '.'))).map((e) => e.name);
  stopStream();
  let moved = 0;
  const failed = [];
  for (const n of [...videos, ...subs]) {
    try { await retry(() => shell.trashItem(path.join(dir, n))); moved++; } catch (e) { failed.push(n); }
  }
  for (const n of videos) delete history.data.items[path.join(dir, n)];
  history.save();
  let folderRemoved = false;
  if (!protectedFolder(dir)) {
    const rest = (await fs.promises.readdir(dir)).filter((n) => !/^(thumbs\.db|desktop\.ini|\.ds_store)$/i.test(n));
    if (!rest.length) {
      try { await retry(() => shell.trashItem(dir)); folderRemoved = true; } catch (e) { /* leave the empty folder */ }
    }
  }
  if (folderRemoved && (settings.data.folders || []).some((f) => samePath(f, dir))) {
    settings.data.folders = settings.data.folders.filter((f) => !samePath(f, dir));
    settings.save();
  }
  return { videos: videos.length, subs: subs.length, moved, failed, folderRemoved };
});
handle('lib:folderInfo', async (dir) => ({ protected: protectedFolder(dir), root: (settings.data.folders || []).some((f) => samePath(f, dir)) }));

handle('lib:removeFolder', async (p) => {
  settings.data.folders = (settings.data.folders || []).filter((x) => x !== p);
  settings.save();
  return settings.data;
});

handle('media:probe', async (p) => probeCached(p));
handle('media:thumb', async (p) => thumbFor(p));
handle('media:sidecars', async (p) => library.sidecars(p));
handle('media:siblings', async (p) => {
  const dir = path.dirname(p);
  const names = await fs.promises.readdir(dir);
  const out = [];
  for (const n of names) {
    if (!library.isVideo(n)) continue;
    const full = path.join(dir, n);
    try { const st = await fs.promises.stat(full); if (st.isFile()) out.push({ path: full, name: n, size: st.size, mtime: st.mtimeMs }); } catch (e) { /* skip */ }
  }
  return out;
});
handle('media:readSubtitle', async (p) => {
  if (!library.isSubtitle(p)) throw new Error('not a subtitle file');
  const st = await fs.promises.stat(p);
  if (st.size > 30 * 1024 * 1024) throw new Error('file too large');
  return new Uint8Array(await fs.promises.readFile(p));
});
handle('media:extractSub', async (p, index) => {
  const st = await fs.promises.stat(p);
  const out = path.join(SUBCACHE, `${fileKey(p, st)}_${index}.srt`);
  if (fs.existsSync(out)) return fs.promises.readFile(out, 'utf8');
  const text = await ff.extractSubtitle(p, index);
  await fs.promises.writeFile(out, text, 'utf8');
  return text;
});
handle('media:keyframes', async (p, ms, before, after) => ff.keyframesAround(p, ms, before, after));
handle('media:stopStream', async () => { stopStream(); return true; });
handle('media:saveScreenshot', async (bytes, name) => {
  const dir = path.join(app.getPath('pictures'), 'MoaPlayer');
  await fs.promises.mkdir(dir, { recursive: true });
  const safe = String(name || 'screenshot').replace(/[\\/:*?"<>|]/g, '_').slice(0, 80);
  const file = path.join(dir, `${safe}.png`);
  await fs.promises.writeFile(file, Buffer.from(bytes));
  return file;
});
handle('media:delete', async (p) => {
  await shell.trashItem(p);
  delete history.data.items[p];
  history.save();
  return true;
});
handle('media:rename', async (p, newName) => {
  const safe = String(newName).replace(/[\\/:*?"<>|]/g, '_').trim();
  if (!safe) throw new Error('empty name');
  const target = path.join(path.dirname(p), safe + path.extname(p));
  if (fs.existsSync(target)) throw new Error('같은 이름의 파일이 이미 있습니다');
  await fs.promises.rename(p, target);
  // rename matching subtitle files too
  const base = path.basename(p, path.extname(p));
  for (const n of await fs.promises.readdir(path.dirname(p))) {
    if (library.isSubtitle(n) && n.startsWith(base + '.')) {
      await fs.promises.rename(path.join(path.dirname(p), n), path.join(path.dirname(p), safe + n.slice(base.length))).catch(() => {});
    }
  }
  if (history.data.items[p]) { history.data.items[target] = history.data.items[p]; delete history.data.items[p]; history.save(); }
  return target;
});

handle('dialog:openFiles', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: '동영상 파일 열기', properties: ['openFile', 'multiSelections'],
    filters: [{ name: '동영상', extensions: Array.from(library.VIDEO_EXT) }, { name: '모든 파일', extensions: ['*'] }],
  });
  return r.canceled ? [] : r.filePaths;
});
handle('dialog:openSubtitle', async (defaultPath) => {
  const r = await dialog.showOpenDialog(win, {
    title: '자막 파일 열기', defaultPath, properties: ['openFile'],
    filters: [{ name: '자막', extensions: Array.from(library.SUB_EXT) }, { name: '모든 파일', extensions: ['*'] }],
  });
  return r.canceled ? null : r.filePaths[0];
});

handle('win:fullscreen', async (on) => { if (!win) return false; win.setFullScreen(on === undefined ? !win.isFullScreen() : !!on); return win.isFullScreen(); });
handle('win:isFullscreen', async () => !!(win && win.isFullScreen()));
handle('win:alwaysOnTop', async (on) => { if (!win) return false; win.setAlwaysOnTop(!!on); settings.data.alwaysOnTop = !!on; settings.save(); return win.isAlwaysOnTop(); });
handle('win:setTitle', async (t) => { if (win) win.setTitle(t ? `${t} - 모아 플레이어` : '모아 플레이어'); return true; });
handle('power:playing', async (on) => {
  if (on && blocker < 0) blocker = powerSaveBlocker.start('prevent-display-sleep');
  if (!on && blocker >= 0) { powerSaveBlocker.stop(blocker); blocker = -1; }
  return true;
});
handle('shell:showItem', async (p) => { shell.showItemInFolder(p); return true; });
handle('win:playerState', async (st) => { updateThumbar(st); return true; });

// Windows taskbar preview buttons: previous / play-pause / next (like Windows Media Player).
// The preview flyout follows the *system* theme (Windows mode), not the app's dark theme:
// dark glyphs on a light taskbar, white glyphs with a dark rim otherwise.
const thumbIcons = {};
function thumbSet() {
  try { return nativeTheme.shouldUseDarkColorsForSystemIntegratedUI === false ? 'light' : ''; } catch (e) { return ''; }
}
function thumbIcon(name) {
  const key = thumbSet() + '/' + name;
  if (!thumbIcons[key]) thumbIcons[key] = nativeImage.createFromPath(path.join(__dirname, 'assets', 'thumbar', thumbSet(), name + '.png'));
  return thumbIcons[key];
}
let thumbKey = '';
let thumbState = null;
function updateThumbar(st) {
  if (process.platform !== 'win32' || !win || win.isDestroyed()) return;
  thumbState = st || null;
  const open = !!(st && st.open);
  const key = open ? `${st.playing ? 1 : 0}${st.prev ? 1 : 0}${st.next ? 1 : 0}${thumbSet()}` : '';
  if (key === thumbKey) return;
  thumbKey = key;
  const cmd = (c) => () => { if (win && !win.isDestroyed()) win.webContents.send('media-command', c); };
  try {
    if (!open) { win.setThumbarButtons([]); return; }
    win.setThumbarButtons([
      { tooltip: '이전 동영상', icon: thumbIcon('prev'), click: cmd('prev'), flags: st.prev ? [] : ['disabled'] },
      { tooltip: st.playing ? '일시정지' : '재생', icon: thumbIcon(st.playing ? 'pause' : 'play'), click: cmd('toggle') },
      { tooltip: '다음 동영상', icon: thumbIcon('next'), click: cmd('next'), flags: st.next ? [] : ['disabled'] },
    ]);
  } catch (e) { /* thumbnail toolbar not available */ }
}

// ---------------------------------------------------------------- lifecycle
app.whenReady().then(async () => {
  if (!gotLock) return;
  nativeTheme.themeSource = 'dark';   // dark window frame / title bar to match the app
  nativeTheme.on('updated', () => { thumbKey = ''; updateThumbar(thumbState); });   // Windows light/dark mode switched
  protocol.handle('moa', handleMoa);
  ffmpegOk = await ff.available();
  createWindow();
  updater.init({
    settings,
    send: (ch, payload) => { if (win && !win.isDestroyed()) win.webContents.send(ch, payload); },
  });
});
app.on('window-all-closed', () => {
  settings.flush(); history.flush(); meta.flush();
  stopStream();
  app.quit();
});
app.on('before-quit', () => { settings.flush(); history.flush(); meta.flush(); stopStream(); });
