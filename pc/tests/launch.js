// Shared launcher for the UI tests of the PC app (Electron + Playwright, Linux under Xvfb).
//
//   MOA_ELECTRON   electron executable            (default: node_modules/electron, then /home/claude/tc/electron-linux/electron)
//   MOA_TESTMEDIA  folder made by make-testmedia.sh (default: <tmp>/moa-testmedia)
//   MOA_SHOTS      where screenshots go             (default: <tmp>/moa-shots)
//
//   xvfb-run -a -s "-screen 0 1366x800x24" node tests/smoke.js
const fs = require('fs');
const os = require('os');
const path = require('path');

function loadPlaywright() {
  for (const id of ['playwright-core', 'playwright', '/opt/node-tools/node_modules/playwright-core']) {
    try { return require(id); } catch (e) { /* next */ }
  }
  throw new Error('playwright-core not found: npm i --no-save playwright-core');
}
const { _electron: electron } = loadPlaywright();

const APP_DIR = path.resolve(__dirname, '..');
const MEDIA = process.env.MOA_TESTMEDIA || path.join(os.tmpdir(), 'moa-testmedia');
const SHOTS = process.env.MOA_SHOTS || path.join(os.tmpdir(), 'moa-shots');
fs.mkdirSync(SHOTS, { recursive: true });

function electronPath() {
  if (process.env.MOA_ELECTRON) return process.env.MOA_ELECTRON;
  try { return require(path.join(APP_DIR, 'node_modules', 'electron')); } catch (e) { /* not installed */ }
  return '/home/claude/tc/electron-linux/electron';
}

exports.media = (rel) => path.join(MEDIA, rel);
exports.shot = (name) => path.join(SHOTS, name);
exports.MEDIA = MEDIA;

exports.launch = async function (opts = {}) {
  const user = opts.userData || path.join(os.tmpdir(), 'moa-test-user');
  if (opts.fresh !== false) fs.rmSync(user, { recursive: true, force: true });
  const app = await electron.launch({
    executablePath: electronPath(),
    args: [APP_DIR, '--no-sandbox', '--disable-gpu-sandbox',
      '--moa-library=' + (opts.library || MEDIA), '--moa-userdata=' + user, ...(opts.files || [])],
    env: { ...process.env, ELECTRON_ENABLE_LOGGING: '0' },
    timeout: 60000,
  });
  const win = await app.firstWindow();
  const logs = [];
  win.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') logs.push(`[${m.type()}] ${m.text()}`); });
  win.on('pageerror', (e) => logs.push('[pageerror] ' + e.message));
  await app.evaluate(({ BrowserWindow }) => { const w = BrowserWindow.getAllWindows()[0]; w.setBounds({ x: 0, y: 0, width: 1280, height: 760 }); });
  return { app, win, logs };
};
