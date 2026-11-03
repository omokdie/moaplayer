'use strict';
/**
 * Auto-update for the installed Windows app (electron-updater + GitHub Releases).
 *
 *  feed     : build.publish in package.json -> latest.yml + MoaPlayer-Setup-x.y.z.exe of the newest release
 *  policy   : check 10 s after start, then every 4 h while running; download in the background;
 *             install when the app is closed (or right away with "지금 다시 시작")
 *  statuses : {state: idle|unsupported|checking|none|available|downloading|downloaded|done|error, ...}
 *             sent to the renderer as 'update-status'
 *  "done"   : shown once on the first start after an update was installed
 *
 * Only active in the packaged Windows build (resources/app-update.yml exists).  In development and in
 * the old portable zip everything here is a harmless no-op that reports {state:'unsupported'}.
 */
const { app } = require('electron');
const fs = require('fs');
const path = require('path');

const FIRST_CHECK_MS = 10 * 1000;
const INTERVAL_MS = 4 * 60 * 60 * 1000;

let updater = null;
let log = console;
let settings = null;          // JsonStore of main.js
let send = () => {};
let status = { state: 'idle' };
let timer = null;
let firstTimer = null;
let checking = false;
let manualCheck = false;

function supported() {
  if (!app.isPackaged || process.platform !== 'win32') return false;
  try { return fs.existsSync(path.join(process.resourcesPath, 'app-update.yml')); } catch (e) { return false; }
}

function emit(s) {
  status = Object.assign({ current: app.getVersion() }, s);
  try { send('update-status', status); } catch (e) { /* window gone */ }
}

function friendly(err) {
  const raw = String((err && (err.message || err.code)) || err || '');
  const low = raw.toLowerCase();
  if (/err_internet_disconnected|enotfound|econnrefused|etimedout|econnreset|err_connection|err_name_not_resolved|network|socket/.test(low)) {
    return '인터넷에 연결되어 있지 않아 업데이트를 확인하지 못했습니다.';
  }
  if (/404|cannot find latest|no published versions|unable to find latest/.test(low)) return '아직 배포된 업데이트가 없습니다.';
  if (/sha512|checksum|integrity/.test(low)) return '내려받은 업데이트 파일이 손상되었습니다. 나중에 다시 시도합니다.';
  if (/not signed/.test(low)) return '업데이트 파일의 서명을 확인하지 못했습니다.';
  const short = raw.split('\n')[0].slice(0, 140);
  return '업데이트 확인 중 오류가 발생했습니다.' + (short ? ` (${short})` : '');
}

function autoEnabled() { return !settings || settings.data.autoUpdate !== false; }

function wire() {
  updater.on('checking-for-update', () => emit({ state: 'checking' }));
  updater.on('update-not-available', () => { checking = false; emit({ state: 'none' }); });
  updater.on('update-available', (info) => emit({ state: 'available', version: info && info.version }));
  updater.on('download-progress', (p) => emit({
    state: 'downloading', version: status.version, percent: Math.round((p && p.percent) || 0),
  }));
  updater.on('update-downloaded', (info) => {
    checking = false;
    const version = info && info.version;
    if (settings && version) { settings.data.updatePending = version; settings.save(); }
    emit({ state: 'downloaded', version });
  });
  updater.on('error', (err) => {
    checking = false;
    log.warn('[updater] error', err && (err.stack || err.message || err));
    // automatic checks fail silently (offline etc.); only a check the user asked for shows the message
    emit({ state: 'error', message: friendly(err), manual: manualCheck });
  });
}

async function check(manual) {
  if (!updater) return status;
  if (status.state === 'downloaded') { emit(status); return status; }
  if (checking) return status;
  manualCheck = !!manual;
  checking = true;
  try {
    await updater.checkForUpdates();
  } catch (err) {
    checking = false;
    emit({ state: 'error', message: friendly(err), manual: manualCheck });
  }
  return status;
}

function schedule() {
  clearTimeout(firstTimer);
  clearInterval(timer);
  firstTimer = timer = null;
  if (!updater || !autoEnabled()) return;
  firstTimer = setTimeout(() => check(false), FIRST_CHECK_MS);
  timer = setInterval(() => check(false), INTERVAL_MS);
}

/** Called once from main.js after the window exists. */
function init(opts) {
  settings = opts.settings;
  send = opts.send || send;

  // first start after an update was installed -> tell the user once
  const pending = settings && settings.data.updatePending;
  if (pending) {
    if (pending === app.getVersion()) status = { state: 'done', version: pending, current: pending };
    settings.data.updatePending = null;
    settings.save();
  }

  if (!supported()) {
    if (status.state !== 'done') status = { state: 'unsupported', current: app.getVersion() };
    return;
  }
  try {
    updater = require('electron-updater').autoUpdater;
  } catch (err) {
    console.warn('[updater] electron-updater not available:', err.message);
    status = { state: 'unsupported', current: app.getVersion() };
    return;
  }
  try {
    log = require('electron-log/main');
    log.transports.file.level = 'info';
  } catch (e) { log = console; }
  updater.logger = log;
  updater.autoDownload = true;
  updater.autoInstallOnAppQuit = true;
  updater.allowPrerelease = false;
  wire();
  schedule();
}

function setAuto(on) {
  if (settings) { settings.data.autoUpdate = !!on; settings.save(); }
  schedule();
}

/** The renderer asks for the current status (and receives a pending "done" exactly once). */
function getStatus() {
  const s = status;
  if (s.state === 'done') status = { state: updater ? 'idle' : 'unsupported', current: app.getVersion() };
  return s;
}

function install() {
  if (!updater || status.state !== 'downloaded') return false;
  // isSilent: no installer pages, isForceRunAfter: start the new version right away
  setImmediate(() => updater.quitAndInstall(true, true));
  return true;
}

module.exports = { init, check, setAuto, getStatus, install, supported };
