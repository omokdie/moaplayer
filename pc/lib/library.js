'use strict';
// Scans watched folders for video files and groups them by directory (like MX Player's folder view).
const fs = require('fs');
const path = require('path');

const VIDEO_EXT = new Set(['mp4', 'm4v', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'ts', 'm2ts', 'mts', 'mpg', 'mpeg',
  'vob', '3gp', '3g2', 'ogv', 'rm', 'rmvb', 'asf', 'divx', 'f4v', 'tp', 'trp', 'mxf', 'dv', 'qt']);
const SUB_EXT = new Set(['smi', 'sami', 'srt', 'ass', 'ssa', 'vtt']);
const SKIP_DIRS = new Set(['node_modules', 'appdata', 'windows', 'program files', 'program files (x86)', 'programdata',
  '$recycle.bin', 'system volume information', '.git', 'library', 'proc', 'sys', 'dev']);

function ext(p) {
  const i = p.lastIndexOf('.');
  return i >= 0 ? p.slice(i + 1).toLowerCase() : '';
}
function isVideo(p) { return VIDEO_EXT.has(ext(p)); }
function isSubtitle(p) { return SUB_EXT.has(ext(p)); }

async function scan(roots, { maxDepth = 6, maxFiles = 40000 } = {}) {
  const folders = new Map();
  let count = 0;
  const seen = new Set();
  async function walk(dir, depth) {
    if (depth > maxDepth || count >= maxFiles) return;
    let real;
    try { real = await fs.promises.realpath(dir); } catch (e) { return; }
    if (seen.has(real)) return;
    seen.add(real);
    let entries;
    try { entries = await fs.promises.readdir(dir, { withFileTypes: true }); } catch (e) { return; }
    const subdirs = [];
    for (const ent of entries) {
      const name = ent.name;
      if (name.startsWith('.') || name.startsWith('$')) continue;
      const full = path.join(dir, name);
      if (ent.isDirectory()) {
        if (!SKIP_DIRS.has(name.toLowerCase())) subdirs.push(full);
      } else if ((ent.isFile() || ent.isSymbolicLink()) && isVideo(name)) {
        let st;
        try { st = await fs.promises.stat(full); } catch (e) { continue; }
        if (!st.isFile() || st.size < 1024) continue;
        let f = folders.get(dir);
        if (!f) { f = { path: dir, name: path.basename(dir) || dir, videos: [] }; folders.set(dir, f); }
        f.videos.push({ path: full, name, size: st.size, mtime: st.mtimeMs });
        if (++count >= maxFiles) return;
      }
    }
    for (const s of subdirs) await walk(s, depth + 1);
  }
  for (const r of roots) {
    try {
      const st = await fs.promises.stat(r);
      if (st.isDirectory()) await walk(r, 0);
    } catch (e) { /* missing root */ }
  }
  return Array.from(folders.values());
}

async function sidecars(videoPath) {
  const dir = path.dirname(videoPath);
  const base = path.basename(videoPath, path.extname(videoPath)).toLowerCase();
  let names = [];
  try { names = await fs.promises.readdir(dir); } catch (e) { return { exact: [], others: [] }; }
  const exact = [], partial = [], others = [];
  const rank = (n) => ({ smi: 0, sami: 0, srt: 1, ass: 2, ssa: 2, vtt: 3 }[ext(n)] ?? 4);
  for (const n of names) {
    if (!isSubtitle(n)) continue;
    const b = n.slice(0, n.lastIndexOf('.')).toLowerCase();
    const full = path.join(dir, n);
    if (b === base) exact.push(full);
    else if (b.startsWith(base + '.') || b.startsWith(base + '_') || b.startsWith(base + ' ')) partial.push(full);
    else others.push(full);
  }
  exact.sort((a, b) => rank(a) - rank(b));
  const coll = new Intl.Collator('ko', { numeric: true });
  others.sort((a, b) => coll.compare(path.basename(a), path.basename(b)));
  return { exact: exact.concat(partial), others };
}

module.exports = { scan, sidecars, isVideo, isSubtitle, VIDEO_EXT, SUB_EXT, ext };
