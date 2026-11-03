'use strict';
// Subtitle parsing for SAMI (.smi), SubRip (.srt), WebVTT (.vtt) and ASS/SSA, with Korean encoding detection.

const Subs = (() => {
  const EXTS = ['smi', 'sami', 'srt', 'ass', 'ssa', 'vtt'];

  function decode(bytes, pref) {
    const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    if (b.length >= 3 && b[0] === 0xEF && b[1] === 0xBB && b[2] === 0xBF) return new TextDecoder('utf-8').decode(b.subarray(3));
    if (b.length >= 2 && b[0] === 0xFF && b[1] === 0xFE) return new TextDecoder('utf-16le').decode(b.subarray(2));
    if (b.length >= 2 && b[0] === 0xFE && b[1] === 0xFF) return new TextDecoder('utf-16be').decode(b.subarray(2));
    if (pref && pref !== 'auto') {
      try { return new TextDecoder(pref).decode(b); } catch (e) { /* fall through */ }
    }
    let z0 = 0, z1 = 0;
    const n = Math.min(b.length, 4096);
    for (let i = 0; i < n; i++) if (b[i] === 0) { if (i & 1) z1++; else z0++; }
    if (n > 16 && z1 > n / 4) return new TextDecoder('utf-16le').decode(b);
    if (n > 16 && z0 > n / 4) return new TextDecoder('utf-16be').decode(b);
    try { return new TextDecoder('utf-8', { fatal: true }).decode(b); } catch (e) {
      return new TextDecoder('euc-kr').decode(b); // WHATWG "euc-kr" is Windows-949 (CP949)
    }
  }

  function parseClock(t) {
    t = t.trim().replace(',', '.');
    let frac = '0';
    const dot = t.indexOf('.');
    if (dot >= 0) { frac = t.slice(dot + 1); t = t.slice(0, dot); }
    const p = t.split(':').map((x) => parseInt(x, 10));
    if (p.some((x) => Number.isNaN(x))) return -1;
    let h = 0, m, s;
    if (p.length === 3) [h, m, s] = p; else if (p.length === 2) [m, s] = p; else return -1;
    let ms;
    if (frac.length === 1) ms = parseInt(frac, 10) * 100;
    else if (frac.length === 2) ms = parseInt(frac, 10) * 10;
    else ms = parseInt(frac.slice(0, 3), 10);
    return ((h * 60 + m) * 60 + s) * 1000 + (ms || 0);
  }

  const SRT_TIME = /((?:\d+:)?\d{1,2}:\d{1,2}(?:[,.]\d{1,3})?)\s*-->\s*((?:\d+:)?\d{1,2}:\d{1,2}(?:[,.]\d{1,3})?)/;
  const ASS_TAG = /\{[^}]*\}/g;
  const TOP_TAG = /\{[^}]*\\an[789][^}]*\}/;
  const VTT_TAGS = /<(?!\/?(?:b|i|u)\b)[^>]*>/gi;

  function parseSrtLike(s, vtt) {
    const track = { name: vtt ? 'WebVTT' : 'SRT', cues: [] };
    const lines = s.replace(/\r\n?/g, '\n').split('\n');
    let st = -1, en = -1, text = [];
    const flush = () => {
      if (st < 0 || en <= st) return;
      while (text.length > 1 && /^\s*\d+\s*$/.test(text[text.length - 1])) text.pop();
      let top = false;
      const html = text.map((l) => {
        if (TOP_TAG.test(l)) top = true;
        l = l.replace(ASS_TAG, '');
        if (vtt) l = l.replace(VTT_TAGS, '');
        return l;
      }).join('<br>').trim();
      if (html) track.cues.push({ start: st, end: en, html, top });
    };
    for (const line of lines) {
      const m = SRT_TIME.exec(line);
      if (m) {
        if (st >= 0) flush();
        st = parseClock(m[1]); en = parseClock(m[2]); text = [];
        continue;
      }
      if (st < 0) continue;
      if (!line.trim()) { flush(); st = -1; text = []; continue; }
      text.push(line);
    }
    if (st >= 0) flush();
    return track;
  }

  function smiClassName(c) {
    const u = c.toUpperCase();
    if (u.startsWith('KR') || u.startsWith('KO')) return '한국어';
    if (u.startsWith('EN') || u.startsWith('EG') || u.startsWith('US')) return 'English';
    if (u.startsWith('JP') || u.startsWith('JA')) return '日本語';
    if (u.startsWith('CN') || u.startsWith('ZH')) return '中文';
    if (u === 'DEFAULT') return '자막';
    return c;
  }

  function parseSmi(s) {
    const names = {};
    const head = s.slice(0, 20000);
    const styleRe = /\.([\w-]+)\s*\{([^}]*)\}/g;
    let m;
    while ((m = styleRe.exec(head))) {
      const nm = /name\s*:\s*([^;]+);?/i.exec(m[2]);
      if (nm) names[m[1].toUpperCase()] = nm[1].trim();
    }
    const bi = s.toLowerCase().indexOf('<body');
    let body = bi >= 0 ? s.slice(bi) : s;
    body = body.replace(/<!--[\s\S]*?-->/g, '');
    const syncRe = /<sync[^>]*?start\s*=\s*["']?(-?\d+)[^>]*>/gi;
    const syncs = [];
    while ((m = syncRe.exec(body))) syncs.push({ start: parseInt(m[1], 10), a: m.index, b: m.index + m[0].length });
    const per = new Map(); // class -> [{start, html|null}]
    const add = (cls, start, raw) => {
      let t = raw.replace(/<\/(?:p|sync|body|sami)\s*>/gi, '').replace(/[\r\n]/g, '').trim();
      const plain = t.replace(/<br\s*\/?>/gi, '').replace(/<[^>]*>/g, '').replace(/&nbsp;?/gi, '').trim();
      if (!per.has(cls)) per.set(cls, []);
      per.get(cls).push({ start, html: plain ? t : null });
    };
    for (let i = 0; i < syncs.length; i++) {
      const start = syncs[i].start;
      if (start < 0) continue;
      const seg = body.slice(syncs[i].b, i + 1 < syncs.length ? syncs[i + 1].a : body.length);
      const pRe = /<p(?:\s[^>]*)?>/gi;
      const ps = [];
      let pm;
      while ((pm = pRe.exec(seg))) {
        const cm = /class\s*=\s*["']?([\w-]+)/i.exec(pm[0]);
        ps.push({ a: pm.index, b: pm.index + pm[0].length, cls: cm ? cm[1].toUpperCase() : 'DEFAULT' });
      }
      if (!ps.length) add('DEFAULT', start, seg);
      else ps.forEach((p, k) => add(p.cls, start, seg.slice(p.b, k + 1 < ps.length ? ps[k + 1].a : seg.length)));
    }
    const tracks = [];
    for (const [cls, list] of per) {
      const t = { name: names[cls] || smiClassName(cls), cues: [] };
      list.forEach((e, i) => {
        if (e.html == null) return;
        const end = i + 1 < list.length ? list[i + 1].start : e.start + 8000;
        if (end > e.start) t.cues.push({ start: e.start, end, html: e.html, top: false });
      });
      tracks.push(t);
    }
    const rank = (n) => (/한국|kor|^kr/i.test(n || '') ? 0 : 1);
    tracks.sort((a, b) => rank(a.name) - rank(b.name));
    return tracks;
  }

  function escapeHtml(t) {
    return t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function parseAss(s) {
    const track = { name: 'ASS', cues: [] };
    let events = false, iStart = 1, iEnd = 2, iText = 9, nFields = 10;
    for (const raw of s.replace(/\r\n?/g, '\n').split('\n')) {
      const line = raw.trim();
      if (line.startsWith('[')) { events = line.toLowerCase() === '[events]'; continue; }
      if (!events) continue;
      if (/^format:/i.test(line)) {
        const f = line.slice(7).split(',').map((x) => x.trim().toLowerCase());
        nFields = f.length;
        iStart = f.indexOf('start'); iEnd = f.indexOf('end'); iText = f.indexOf('text');
        continue;
      }
      if (!/^dialogue:/i.test(line)) continue;
      const body = line.slice(9);
      const parts = [];
      let rest = body;
      for (let k = 0; k < nFields - 1; k++) {
        const c = rest.indexOf(',');
        if (c < 0) break;
        parts.push(rest.slice(0, c));
        rest = rest.slice(c + 1);
      }
      parts.push(rest);
      if (parts.length < nFields) continue;
      const st = parseClock(parts[iStart]), en = parseClock(parts[iEnd]);
      if (st < 0 || en <= st) continue;
      let text = parts[iText];
      if (/\{[^}]*\\p[1-9][^}]*\}/.test(text)) continue;
      const top = TOP_TAG.test(text);
      text = escapeHtml(text.replace(ASS_TAG, '')).replace(/\\N|\\n/g, '<br>').replace(/\\h/g, '&nbsp;');
      if (!text.trim()) continue;
      track.cues.push({ start: st, end: en, html: text, top });
    }
    return track;
  }

  function finish(t, file) {
    t.file = file;
    t.cues.sort((a, b) => a.start - b.start);
    let m = 0;
    for (const c of t.cues) m = Math.max(m, c.end - c.start);
    t.maxDur = Math.min(Math.max(m, 1000), 15 * 60 * 1000);
    return t;
  }

  function parse(bytesOrText, fileName, pref) {
    let s = typeof bytesOrText === 'string' ? bytesOrText : decode(bytesOrText, pref);
    if (s.charCodeAt(0) === 0xFEFF) s = s.slice(1);
    const head = s.slice(0, 4000).toLowerCase();
    const ext = (fileName.split('.').pop() || '').toLowerCase();
    let tracks;
    if (head.includes('<sami') || head.includes('<sync') || ext === 'smi' || ext === 'sami') tracks = parseSmi(s);
    else if (head.includes('[script info]') || head.includes('[events]') || ext === 'ass' || ext === 'ssa') tracks = [parseAss(s)];
    else if (head.startsWith('webvtt') || ext === 'vtt') tracks = [parseSrtLike(s, true)];
    else tracks = [parseSrtLike(s, false)];
    return tracks.filter((t) => t.cues.length).map((t) => finish(t, fileName));
  }

  /** Cues active at time t (ms). */
  function activeAt(track, t) {
    const cues = track.cues;
    let lo = 0, hi = cues.length - 1, idx = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (cues[mid].start <= t) { idx = mid; lo = mid + 1; } else hi = mid - 1;
    }
    const out = [];
    for (let i = idx; i >= 0; i--) {
      const c = cues[i];
      if (c.start < t - track.maxDur) break;
      if (c.end > t) out.unshift(c);
    }
    return out;
  }

  // Only a tiny safe subset of HTML survives: b, i, u, br and font color.
  function sanitize(html) {
    const doc = new DOMParser().parseFromString(`<div>${html}</div>`, 'text/html');
    const out = document.createElement('div');
    const walk = (src, dst) => {
      for (const n of src.childNodes) {
        if (n.nodeType === 3) { dst.appendChild(document.createTextNode(n.nodeValue)); continue; }
        if (n.nodeType !== 1) continue;
        const tag = n.tagName.toLowerCase();
        if (tag === 'br') { dst.appendChild(document.createElement('br')); continue; }
        let el = null;
        if (tag === 'b' || tag === 'i' || tag === 'u') el = document.createElement(tag);
        else if (tag === 'font') {
          el = document.createElement('span');
          const c = n.getAttribute('color');
          if (c && /^#?[0-9a-z]{3,20}$/i.test(c)) el.style.color = /^[0-9a-f]{6}$/i.test(c) ? '#' + c : c;
        }
        if (el) { walk(n, el); dst.appendChild(el); } else walk(n, dst);
      }
    };
    walk(doc.body.firstChild || doc.body, out);
    return out.innerHTML;
  }

  function isSubtitle(name) {
    return EXTS.includes((name.split('.').pop() || '').toLowerCase());
  }

  return { parse, decode, activeAt, sanitize, isSubtitle, parseClock };
})();
