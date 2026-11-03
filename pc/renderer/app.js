'use strict';
/* 모아 플레이어 (PC) - renderer */
(() => {
  const $ = (s, r = document) => r.querySelector(s);
  const api = window.moa;
  const coll = new Intl.Collator('ko', { numeric: true, sensitivity: 'base' });
  const video = $('#video');

  // ------------------------------------------------------------ state
  const S = { settings: {}, history: {}, info: {}, folders: [], folder: null, search: '', meta: new Map(), hevc: false, update: { state: 'idle' }, updateManual: false };
  const P = {
    list: [], index: -1, item: null, info: null, sw: false, swOffset: 0, swCopy: false, swBurn: -1, dur: 0,
    audioIdx: 0, subTracks: [], subSel: -1, subDelay: 0, otherSubs: [], aspect: 0, zoom: 1, panX: 0, panY: 0,
    volume: 1, muted: false, brightness: 1, speed: 1, repeat: 0, abA: -1, abB: -1, locked: false, controls: true,
    sleepAt: 0, sleepEnd: false, lastSave: 0, triedSw: false, showRemain: false, token: 0, pendingSeek: 0,
    longPress: false, shownCues: '', seeking: false,
  };
  const ASPECTS = ['화면에 맞춤', '늘이기', '꽉 채우기(자르기)', '원본 크기 (100%)', '16:9', '4:3'];
  const REPEATS = ['끄기', '한 개 반복', '전체 반복'];

  // ------------------------------------------------------------ helpers
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const basename = (p) => p.split(/[\\/]/).pop();
  const dirname = (p) => p.replace(/[\\/][^\\/]*$/, '');
  const stripExt = (n) => n.replace(/\.[^.]+$/, '');
  const extOf = (n) => (n.split('.').pop() || '').toLowerCase();
  function fmtTime(ms) {
    if (!Number.isFinite(ms) || ms < 0) ms = 0;
    const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    const p2 = (n) => String(n).padStart(2, '0');
    return h > 0 ? `${h}:${p2(m)}:${p2(x)}` : `${p2(m)}:${p2(x)}`;
  }
  const fmtSigned = (ms) => (ms < 0 ? '-' : '+') + fmtTime(Math.abs(ms));
  function fmtSize(b) {
    if (!b) return '';
    if (b < 1024 * 1024) return Math.round(b / 1024) + 'KB';
    if (b < 1024 ** 3) return (b / 1024 / 1024).toFixed(1) + 'MB';
    return (b / 1024 ** 3).toFixed(2) + 'GB';
  }
  const fmtSpeed = (s) => (Math.round(s * 100) / 100).toFixed(2).replace(/0$/, '') + 'x';
  function resLabel(w, h) {
    const m = Math.min(w, h);
    if (!m) return '';
    if (m >= 2100) return '4K';
    if (m >= 1400) return '1440p';
    if (m >= 1000) return '1080p';
    if (m >= 700) return '720p';
    return m + 'p';
  }
  function el(tag, cls, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html !== undefined) e.innerHTML = html;
    return e;
  }
  const esc = (t) => String(t).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  let toastTimer = 0;
  function toast(msg, ms = 2300) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), ms);
  }
  function setIcon(sel, name) { const e = typeof sel === 'string' ? $(sel) : sel; if (e) e.innerHTML = icon(name); }
  async function saveSettings(patch) { Object.assign(S.settings, patch); await api.invoke('settings:set', patch); }
  const langName = (code) => {
    if (!code || code === 'und') return '';
    const map = { kor: '한국어', ko: '한국어', eng: 'English', en: 'English', jpn: '日本語', ja: '日本語', chi: '中文', zho: '中文', zh: '中文', spa: 'Español', fre: 'Français', fra: 'Français', ger: 'Deutsch', deu: 'Deutsch' };
    if (map[code]) return map[code];
    try { return new Intl.DisplayNames(['ko'], { type: 'language' }).of(code) || code; } catch (e) { return code; }
  };
  function codecName(c) {
    const m = { h264: 'H.264', hevc: 'H.265', av1: 'AV1', vp9: 'VP9', vp8: 'VP8', mpeg4: 'MPEG-4', msmpeg4v3: 'DivX3', mpeg2video: 'MPEG-2', mpeg1video: 'MPEG-1', wmv3: 'WMV', vc1: 'VC-1', aac: 'AAC', ac3: 'AC3', eac3: 'E-AC3', dts: 'DTS', truehd: 'TrueHD', mp3: 'MP3', mp2: 'MP2', opus: 'Opus', vorbis: 'Vorbis', flac: 'FLAC', subrip: 'SRT', ass: 'ASS', ssa: 'SSA', mov_text: 'TX3G', webvtt: 'VTT', hdmv_pgs_subtitle: 'PGS', dvd_subtitle: 'VobSub', dvb_subtitle: 'DVB', wmav2: 'WMA', pcm_s16le: 'PCM' };
    return m[c] || (c || '?').toUpperCase();
  }
  const chName = (n) => (n === 1 ? '모노' : n === 2 ? '스테레오' : n === 6 ? '5.1ch' : n === 8 ? '7.1ch' : n ? n + 'ch' : '');

  // limited parallel probing for list metadata
  const probeQ = [];
  let probing = 0;
  function probe(path) {
    if (S.meta.has(path)) return Promise.resolve(S.meta.get(path));
    return new Promise((resolve) => {
      probeQ.push(async () => {
        try { const i = await api.invoke('media:probe', path); S.meta.set(path, i); resolve(i); } catch (e) { resolve(null); }
      });
      pumpProbe();
    });
  }
  function pumpProbe() {
    while (probing < 4 && probeQ.length) {
      const job = probeQ.shift();
      probing++;
      job().finally(() => { probing--; pumpProbe(); });
    }
  }

  // ------------------------------------------------------------ library
  function allVideos() { return S.folders.flatMap((f) => f.videos); }

  function isNew(v) { return !S.history[v.path]; }

  function sortVideos(list) {
    const by = S.settings.sort, desc = S.settings.sortDesc;
    const dur = (v) => (S.meta.get(v.path) || {}).duration || 0;
    const cmp = {
      name: (a, b) => coll.compare(a.name, b.name), date: (a, b) => a.mtime - b.mtime,
      size: (a, b) => a.size - b.size, duration: (a, b) => dur(a) - dur(b),
    }[by] || ((a, b) => coll.compare(a.name, b.name));
    return list.slice().sort((a, b) => { const r = cmp(a, b) || coll.compare(a.name, b.name); return desc ? -r : r; });
  }
  function sortFolders(list) {
    const by = S.settings.sort, desc = S.settings.sortDesc;
    const sz = (f) => f.videos.reduce((s, v) => s + v.size, 0);
    const mt = (f) => Math.max(...f.videos.map((v) => v.mtime));
    const cmp = {
      name: (a, b) => coll.compare(a.name, b.name), date: (a, b) => mt(a) - mt(b),
      size: (a, b) => sz(a) - sz(b), duration: (a, b) => a.videos.length - b.videos.length,
    }[by] || ((a, b) => coll.compare(a.name, b.name));
    return list.slice().sort((a, b) => { const r = cmp(a, b) || coll.compare(a.name, b.name); return desc ? -r : r; });
  }

  async function loadLibrary() {
    const content = $('#lib-content');
    if (!S.folders.length) content.innerHTML = `<div class="empty">${icon('refresh')}동영상을 찾는 중…</div>`;
    try {
      const [folders, hist] = await Promise.all([api.invoke('lib:scan'), api.invoke('history:all')]);
      S.folders = folders;
      S.history = hist || {};
    } catch (e) {
      toast('라이브러리를 읽을 수 없습니다: ' + e.message);
    }
    if (S.folder) S.folder = S.folders.find((f) => f.path === S.folder.path) || null;
    renderLibrary();
  }

  let io = null;
  function renderLibrary() {
    const content = $('#lib-content');
    content.innerHTML = '';
    if (io) io.disconnect();
    io = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { io.unobserve(e.target); hydrate(e.target); }
    }, { root: content, rootMargin: '400px' });
    $('#lib-back').classList.toggle('hidden', !S.folder);
    setIcon('#btn-view', S.settings.view === 'grid' ? 'list' : 'grid');
    const nVideos = allVideos().length;
    if (S.folder) {
      $('#lib-title').textContent = S.folder.name;
      $('#lib-sub').textContent = `${S.folder.path} · 동영상 ${S.folder.videos.length}개`;
    } else {
      $('#lib-title').textContent = '모아 플레이어';
      $('#lib-sub').textContent = nVideos ? `폴더 ${S.folders.length}개 · 동영상 ${nVideos}개` : '';
    }
    const q = S.search.trim().toLowerCase();
    if (q) {
      const base = S.folder ? S.folder.videos : allVideos();
      const res = sortVideos(base.filter((v) => v.name.toLowerCase().includes(q)));
      if (!res.length) content.innerHTML = `<div class="empty">${icon('search')}검색 결과가 없습니다</div>`;
      else renderVideos(content, res, !S.folder);
    } else if (S.folder) {
      renderVideos(content, sortVideos(S.folder.videos), false);
    } else if (!S.folders.length) {
      const folders = (S.settings.folders || []).map((f) => `<div>${esc(f)}</div>`).join('') || '<div>(등록된 폴더 없음)</div>';
      content.innerHTML = `<div class="empty">${icon('movie')}<div>동영상이 없습니다.</div>
        <div style="margin-top:10px;font-size:12.5px">검색하는 폴더:</div><div style="font-size:12.5px">${folders}</div>
        <div><button class="btn" id="empty-add">폴더 추가</button> <button class="btn ghost" id="empty-open">파일 열기</button></div>
        <div style="margin-top:12px;font-size:12.5px">동영상 파일이나 폴더를 창에 끌어다 놓아도 됩니다.</div></div>`;
      $('#empty-add').onclick = addFolder;
      $('#empty-open').onclick = openFiles;
    } else {
      renderFolders(content);
    }
    updateFab();
  }

  function renderFolders(content) {
    const wrap = el('div', 'folder-list');
    for (const f of sortFolders(S.folders)) {
      const n = f.videos.filter(isNew).length;
      const size = fmtSize(f.videos.reduce((s, v) => s + v.size, 0));
      const row = el('div', 'folder', `<div class="f-ic">${icon('folder')}</div><div class="f-txt"><div class="f-name">${esc(f.name)}</div>
        <div class="f-info">동영상 ${f.videos.length}개${size ? ' · ' + size : ''} · ${esc(f.path)}</div></div>${n ? `<span class="badge-new">${n} NEW</span>` : ''}`);
      row.title = f.path;
      row.onclick = () => { S.folder = f; S.search = ''; $('#search').value = ''; renderLibrary(); $('#lib-content').scrollTop = 0; };
      row.oncontextmenu = (e) => {
        e.preventDefault();
        showMenu(e.clientX, e.clientY, [
          { label: '열기', fn: () => row.onclick() },
          { label: '탐색기에서 열기', fn: () => api.invoke('shell:showItem', f.videos[0].path) },
          { label: '전체 재생', fn: () => play(sortVideos(f.videos), 0) },
        ]);
      };
      wrap.appendChild(row);
    }
    content.appendChild(wrap);
  }

  function renderVideos(content, list, showFolder) {
    const grid = S.settings.view === 'grid';
    const wrap = el('div', 'videos ' + (grid ? 'grid' : 'list'));
    list.forEach((v, i) => {
      const h = S.history[v.path];
      const pct = h && h.dur && h.pos > 3000 && h.pos < h.dur - Math.max(3000, h.dur / 50) ? Math.round((h.pos / h.dur) * 100) : 0;
      const card = el('div', 'vcard', `<div class="thumb"><span class="ph">${icon('movie')}</span>
        ${isNew(v) ? '<span class="badge-new">NEW</span>' : ''}<span class="dur hidden"></span>
        ${pct ? `<div class="watched"><i style="width:${pct}%"></i></div>` : ''}<div class="play-ov">${icon('play_circle')}</div></div>
        <div class="v-meta"><div class="v-title">${esc(stripExt(v.name))}</div><div class="v-info">${esc([fmtSize(v.size), showFolder ? basename(dirname(v.path)) : ''].filter(Boolean).join(' · '))}</div></div>`);
      card._v = v;
      card._showFolder = showFolder;
      card.title = v.name;
      card.onclick = () => play(list, i);
      card.oncontextmenu = (e) => { e.preventDefault(); videoMenu(e.clientX, e.clientY, v, list, i); };
      wrap.appendChild(card);
      io.observe(card);
    });
    content.appendChild(wrap);
  }

  async function hydrate(card) {
    const v = card._v;
    if (S.settings.showThumbs !== false && S.info.ffmpeg) {
      api.invoke('media:thumb', v.path).then((url) => {
        if (!url) return;
        const img = new Image();
        img.onload = () => { const ph = card.querySelector('.ph'); if (ph) ph.replaceWith(img); };
        img.src = url;
      }).catch(() => {});
    }
    const info = await probe(v.path);
    if (!info) return;
    const d = card.querySelector('.dur');
    if (info.duration) { d.textContent = fmtTime(info.duration); d.classList.remove('hidden'); }
    const parts = [fmtSize(v.size)];
    if (info.video) parts.push(resLabel(info.video.width, info.video.height), codecName(info.video.codec));
    if (card._showFolder) parts.push(basename(dirname(v.path)));
    card.querySelector('.v-info').textContent = parts.filter(Boolean).join(' · ');
  }

  function videoMenu(x, y, v, list, i) {
    showMenu(x, y, [
      { label: '재생', fn: () => play(list, i) },
      { label: '처음부터 재생', fn: () => play(list, i, { fromStart: true }) },
      { label: '정보', fn: () => showFileInfo(v) },
      { label: '탐색기에서 열기', fn: () => api.invoke('shell:showItem', v.path) },
      { sep: true },
      { label: '이름 변경', fn: () => renameFile(v) },
      { label: '삭제 (휴지통으로)', fn: () => deleteFile(v) },
    ]);
  }

  async function showFileInfo(v) {
    const info = await probe(v.path);
    const lines = [`파일: ${v.name}`, `위치: ${dirname(v.path)}`, `크기: ${fmtSize(v.size)}`, `수정한 날짜: ${new Date(v.mtime).toLocaleString('ko-KR')}`];
    if (info) {
      if (info.duration) lines.push(`재생 시간: ${fmtTime(info.duration)}`);
      if (info.video) lines.push(`영상: ${codecName(info.video.codec)} ${info.video.width}×${info.video.height}${info.video.fps ? ' ' + info.video.fps.toFixed(2) + 'fps' : ''}`);
      info.audio.forEach((a, k) => lines.push(`음성 ${k + 1}: ${codecName(a.codec)} ${chName(a.channels)} ${langName(a.lang)} ${a.title || ''}`.trim()));
      info.subs.forEach((s, k) => lines.push(`자막 ${k + 1}: ${codecName(s.codec)} ${langName(s.lang)} ${s.title || ''}`.trim()));
    }
    const h = S.history[v.path];
    if (h) lines.push(`마지막 재생 위치: ${fmtTime(h.pos)}`);
    dialog('정보', lines.join('\n'), [{ label: '확인', primary: true }]);
  }

  function renameFile(v) {
    dialog('이름 변경', '', [
      { label: '취소' },
      {
        label: '변경', primary: true, fn: async (inp) => {
          try { await api.invoke('media:rename', v.path, inp.value); toast('이름을 변경했습니다'); loadLibrary(); } catch (e) { toast(e.message); }
        },
      },
    ], stripExt(v.name));
  }

  function deleteFile(v) {
    dialog('삭제', `'${v.name}' 파일을 휴지통으로 옮길까요?`, [
      { label: '취소' },
      { label: '삭제', primary: true, fn: async () => { try { await api.invoke('media:delete', v.path); toast('휴지통으로 옮겼습니다'); loadLibrary(); } catch (e) { toast('삭제할 수 없습니다: ' + e.message); } } },
    ]);
  }

  function lastPlayed() {
    let best = null;
    for (const [p, h] of Object.entries(S.history)) if (h && h.last && (!best || h.last > best.h.last)) best = { p, h };
    return best;
  }
  function updateFab() {
    const lp = lastPlayed();
    const fab = $('#fab');
    fab.classList.toggle('hidden', !lp);
    if (lp) fab.title = '마지막 재생 이어보기: ' + basename(lp.p);
  }

  async function addFolder() {
    S.settings = await api.invoke('lib:addFolder');
    await loadLibrary();
  }
  async function openFiles() {
    const files = await api.invoke('dialog:openFiles');
    if (files.length) playFiles(files);
  }

  // ------------------------------------------------------------ menus, sheets, dialogs
  function showMenu(x, y, items) {
    const m = $('#menu');
    m.innerHTML = '';
    for (const it of items) {
      if (it.sep) { m.appendChild(el('div', 'sep')); continue; }
      const row = el('div', 'item' + (it.disabled ? ' disabled' : ''), `<span class="lbl">${esc(it.label)}</span>`);
      row.onclick = () => { if (it.disabled) return; hideMenu(); it.fn && it.fn(); };
      m.appendChild(row);
    }
    m.classList.remove('hidden');
    const r = m.getBoundingClientRect();
    m.style.left = clamp(x, 4, innerWidth - r.width - 4) + 'px';
    m.style.top = clamp(y, 4, innerHeight - r.height - 4) + 'px';
    $('#backdrop').classList.remove('hidden');
    $('#backdrop').style.background = 'transparent';
    keepControls(true);
  }
  function hideMenu() {
    $('#menu').classList.add('hidden');
    if ($('#sheet').classList.contains('hidden')) { $('#backdrop').classList.add('hidden'); keepControls(false); }
    $('#backdrop').style.background = '';
  }

  function showSheet(title, build) {
    hideMenu();
    const sh = $('#sheet');
    sh.querySelector('.sheet-title').textContent = title;
    const body = sh.querySelector('.sheet-body');
    body.innerHTML = '';
    build(body);
    sh.classList.remove('hidden');
    $('#backdrop').classList.remove('hidden');
    keepControls(true);
  }
  function closeSheet() {
    $('#sheet').classList.add('hidden');
    $('#menu').classList.add('hidden');
    $('#backdrop').classList.add('hidden');
    keepControls(false);
  }
  const sheetOpen = () => !$('#sheet').classList.contains('hidden') || !$('#menu').classList.contains('hidden') || !$('#dialog').classList.contains('hidden');

  function addItem(body, { label, sub, checked, onClick, disabled, keep }) {
    const row = el('div', 'item' + (disabled ? ' disabled' : ''),
      `<span class="chk">${checked ? icon('check') : ''}</span><span class="lbl">${esc(label)}${sub ? `<small>${esc(sub)}</small>` : ''}</span>`);
    row.onclick = () => { if (disabled) return; if (!keep) closeSheet(); onClick && onClick(); };
    body.appendChild(row);
    return row;
  }
  const groupTitle = (body, t) => body.appendChild(el('div', 'group-title', esc(t)));
  function addSwitch(body, label, value, onChange) {
    const row = el('div', 'row', `<span class="lbl">${esc(label)}</span><span class="switch ${value ? 'on' : ''}"></span>`);
    const sw = row.querySelector('.switch');
    row.onclick = () => { const on = !sw.classList.contains('on'); sw.classList.toggle('on', on); onChange(on); };
    body.appendChild(row);
  }
  function addSelect(body, label, options, value, onChange) {
    const row = el('div', 'row', `<span class="lbl">${esc(label)}</span>`);
    const sel = document.createElement('select');
    for (const [v, t] of options) {
      const o = document.createElement('option');
      o.value = String(v); o.textContent = t;
      if (String(v) === String(value)) o.selected = true;
      sel.appendChild(o);
    }
    sel.onchange = () => onChange(sel.value);
    row.appendChild(sel);
    body.appendChild(row);
  }
  function addRange(body, label, min, max, step, value, fmt, onInput) {
    const row = el('div', 'row', `<span class="lbl">${esc(label)} <b>${esc(fmt(value))}</b></span>`);
    const r = document.createElement('input');
    r.type = 'range'; r.min = min; r.max = max; r.step = step; r.value = value;
    r.oninput = () => { row.querySelector('b').textContent = fmt(Number(r.value)); onInput(Number(r.value)); };
    row.appendChild(r);
    body.appendChild(row);
    return r;
  }

  function dialog(title, msg, buttons, inputValue) {
    const d = $('#dialog');
    d.innerHTML = `<h3>${esc(title)}</h3>${msg ? `<div class="msg">${esc(msg)}</div>` : ''}${inputValue !== undefined ? '<input type="text">' : ''}<div class="actions"></div>`;
    const inp = d.querySelector('input');
    if (inp) inp.value = inputValue;
    const close = () => { d.classList.add('hidden'); $('#backdrop').classList.add('hidden'); keepControls(false); };
    let primary = null;
    for (const b of buttons) {
      const btn = el('button', 'btn' + (b.primary ? '' : ' ghost'), esc(b.label));
      btn.onclick = () => { close(); b.fn && b.fn(inp); };
      if (b.primary) primary = btn;
      d.querySelector('.actions').appendChild(btn);
    }
    d._cancel = () => { close(); const c = buttons.find((b) => b.cancel); if (c && c.fn) c.fn(inp); };
    d._ok = () => primary && primary.click();
    d.classList.remove('hidden');
    $('#backdrop').classList.remove('hidden');
    keepControls(true);
    setTimeout(() => (inp ? (inp.focus(), inp.select()) : primary && primary.focus()), 30);
  }

  // ------------------------------------------------------------ player: open / load
  async function playFiles(paths) {
    paths = (paths || []).filter(Boolean);
    if (!paths.length) return;
    if (paths.length === 1) {
      let sib = [];
      try { sib = await api.invoke('media:siblings', paths[0]); } catch (e) { sib = []; }
      sib.sort((a, b) => coll.compare(a.name, b.name));
      let idx = sib.findIndex((v) => v.path === paths[0]);
      if (idx < 0) { sib = [{ path: paths[0], name: basename(paths[0]), size: 0, mtime: 0 }]; idx = 0; }
      play(sib, idx);
    } else {
      play(paths.map((p) => ({ path: p, name: basename(p), size: 0, mtime: 0 })), 0);
    }
  }

  function play(list, index, opts = {}) {
    if (!list.length) return;
    saveHistory();
    P.list = list.slice();
    P.index = clamp(index, 0, list.length - 1);
    $('#library').classList.add('hidden');
    $('#player').classList.remove('hidden');
    closeSheet();
    showControls();
    loadCurrent(!!opts.fromStart);
  }

  function nativeVideo(v) {
    if (!v) return true;
    if (v.codec === 'h264') return !/(10|12)(le|be)|422|444/.test(v.pixFmt || '');
    if (v.codec === 'hevc') return S.hevc;
    return ['vp8', 'vp9', 'av1', 'theora'].includes(v.codec);
  }
  const NATIVE_AUDIO = new Set(['aac', 'mp3', 'opus', 'vorbis', 'flac', 'pcm_s16le', 'pcm_s24le', 'pcm_s32le', 'pcm_f32le', 'pcm_u8']);
  function nativeContainer(info) {
    return /mov|mp4|matroska|webm|ogg|mp3|wav|flac/.test(info.format || '');
  }
  function canPlayNative(info, audioIdx) {
    if (!info) return true;
    const a = info.audio[audioIdx];
    return nativeContainer(info) && nativeVideo(info.video) && (!a || NATIVE_AUDIO.has(a.codec));
  }
  function canCopyVideo(info) {
    return !!(info && info.video && nativeVideo(info.video) && ['h264', 'hevc', 'vp9', 'av1'].includes(info.video.codec));
  }
  function pickAudio(info) {
    if (!info || !info.audio.length) return 0;
    const ko = info.audio.findIndex((a) => /^(ko|kor)$/i.test(a.lang));
    if (ko >= 0) return ko;
    const def = info.audio.findIndex((a) => a.def);
    return def >= 0 ? def : 0;
  }

  async function loadCurrent(fromStart) {
    const token = ++P.token;
    const item = P.list[P.index];
    P.item = item;
    Object.assign(P, { abA: -1, abB: -1, zoom: 1, panX: 0, panY: 0, subTracks: [], subSel: -1, otherSubs: [], triedSw: false, swBurn: -1, shownCues: '', info: null, dur: 0 });
    renderSubsHtml('', false);
    updateAbUi();
    const title = stripExt(item.name);
    $('#p-title').textContent = title;
    api.invoke('win:setTitle', title);
    $('#spinner').classList.remove('hidden');
    let info = null;
    try { info = await api.invoke('media:probe', item.path); } catch (e) { info = null; }
    if (token !== P.token) return;
    P.info = info;
    P.dur = info && info.duration ? info.duration : 0;
    P.audioIdx = pickAudio(info);
    const h = S.history[item.path];
    P.subDelay = (h && h.subDelay) || 0;
    let resume = 0;
    if (!fromStart && h && h.pos > 5000 && !(h.dur && h.pos >= h.dur - Math.max(3000, h.dur / 50))) resume = h.pos;
    if (S.settings.resume === 'never') resume = 0;
    const ask = resume > 0 && S.settings.resume === 'ask';
    const mode = S.settings.decoder || 'auto';
    const native = canPlayNative(info, P.audioIdx);
    const sw = S.info.ffmpeg && (mode === 'sw' || (mode !== 'hw' && !native));
    if (!native && !S.info.ffmpeg) toast('FFmpeg가 없어 이 형식은 재생되지 않을 수 있습니다');
    else if (!native && sw && mode !== 'sw') toast('이 파일은 SW 디코더(FFmpeg)로 재생합니다');
    await startPlayback(sw, ask ? 0 : resume, !ask);
    if (ask) askResume(resume);
    else if (resume) toast(fmtTime(resume) + '부터 이어서 재생합니다');
    updateNav();
    loadSubtitles(item, info, token);
  }

  async function streamUrl(startMs) {
    const info = P.info;
    const v = info && info.video;
    const copy = P.swBurn < 0 && canCopyVideo(info);
    let start = startMs;
    if (copy && startMs > 0) {
      // with stream copy the output begins at the key frame before the requested time
      try { start = await api.invoke('media:keyframe', P.item.path, startMs); } catch (e) { start = startMs; }
    }
    P.swCopy = copy;
    P.swOffset = start;
    P.swTarget = startMs;
    const hmax = v && v.height > 1080 && !copy ? 1080 : 0;
    return `moa://stream/?p=${encodeURIComponent(P.item.path)}&t=${Math.round(startMs)}&a=${P.audioIdx}` +
      `&v=${copy ? 'copy' : 'enc'}&s=${P.swBurn}&h=${hmax}&hevc=${v && v.codec === 'hevc' ? 1 : 0}&r=${Date.now()}`;
  }

  async function startPlayback(sw, startMs, autoplay) {
    P.sw = !!sw;
    updateDecoderChip();
    video.defaultPlaybackRate = P.speed;
    P.catchup = false;
    video.muted = false;
    video.style.opacity = '';
    if (P.sw) {
      P.pendingSeek = 0;
      video.src = await streamUrl(startMs || 0);
      video.playbackRate = P.speed;
      applyVolume();
      if (autoplay) video.play().catch(() => {});
      beginCatchup(autoplay);
      return;
    } else {
      api.invoke('media:stopStream');
      P.swOffset = 0;
      P.pendingSeek = startMs || 0;
      video.src = 'moa://media/?p=' + encodeURIComponent(P.item.path);
    }
    video.playbackRate = P.speed;
    applyVolume();
    if (autoplay) video.play().catch(() => {});
  }

  function askResume(pos) {
    dialog('이어서 재생', `마지막으로 본 위치(${fmtTime(pos)})부터 이어서 재생할까요?`, [
      { label: '처음부터', cancel: true, fn: () => { seekTo(0); video.play().catch(() => {}); } },
      { label: '이어서 재생', primary: true, fn: () => { seekTo(pos); video.play().catch(() => {}); } },
    ]);
  }

  function closePlayer() {
    saveHistory();
    P.token++;
    api.invoke('media:stopStream');
    video.pause();
    video.removeAttribute('src');
    video.load();
    api.invoke('power:playing', false);
    api.invoke('win:setTitle', '');
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    $('#player').classList.add('hidden');
    $('#library').classList.remove('hidden');
    closeSheet();
    P.item = null;
    P.locked = false;
    $('#p-unlock').classList.add('hidden');
    reportPlayerState();
    renderLibrary();
  }

  function getPos() {
    if (P.catchup) return P.swTarget;
    return P.sw ? P.swOffset + video.currentTime * 1000 : video.currentTime * 1000;
  }

  // Stream copy can only start at a key frame. Play invisibly and fast from that key frame
  // to the requested time, so seeks land exactly where the user asked.
  function beginCatchup(keepPlaying) {
    const gap = (P.swTarget || 0) - P.swOffset;
    if (!P.sw || !P.swCopy || gap < 400) { P.catchup = false; return; }
    P.catchup = true;
    P.catchupPlay = keepPlaying;
    video.muted = true;
    video.style.opacity = '0';
    $('#spinner').classList.remove('hidden');
    video.playbackRate = gap > 6000 ? 16 : 8;
    video.play().catch(() => {});
  }
  function checkCatchup() {
    if (P.swOffset + video.currentTime * 1000 >= P.swTarget - 120) endCatchup();
  }
  function endCatchup() {
    P.catchup = false;
    video.playbackRate = P.longPress ? video.playbackRate : P.speed;
    video.muted = false;
    video.style.opacity = '';
    $('#spinner').classList.add('hidden');
    if (!P.catchupPlay) video.pause();
  }
  function getDur() { return P.dur || (Number.isFinite(video.duration) ? video.duration * 1000 : 0); }

  async function seekTo(ms) {
    const dur = getDur();
    ms = clamp(ms, 0, dur > 0 ? Math.max(0, dur - 300) : ms);
    if (P.sw) {
      const wasPlaying = !video.paused;
      $('#spinner').classList.remove('hidden');
      video.src = await streamUrl(ms);
      video.playbackRate = P.speed;
      if (wasPlaying || P.seekResume) video.play().catch(() => {});
      beginCatchup(wasPlaying || P.seekResume);
    } else if (video.readyState === 0) {
      P.pendingSeek = ms;
    } else {
      video.currentTime = ms / 1000;
    }
    updateProgress();
  }
  const seekBy = (d) => seekTo(getPos() + d);

  function saveHistory() {
    if (!P.item) return;
    const pos = video.ended ? getDur() : getPos();
    const dur = getDur();
    if (!(pos >= 0)) return;
    const e = { pos: Math.round(pos), dur: Math.round(dur), subDelay: P.subDelay };
    S.history[P.item.path] = Object.assign(S.history[P.item.path] || {}, e, { last: Date.now() });
    api.invoke('history:save', P.item.path, e);
  }

  function skip(dir) {
    const ni = P.index + dir;
    if (ni < 0 || ni >= P.list.length) { toast(dir > 0 ? '마지막 동영상입니다' : '첫 번째 동영상입니다'); return; }
    saveHistory();
    P.index = ni;
    loadCurrent(false);
  }

  function onEnded() {
    const dur = getDur();
    if (P.sw && dur && getPos() < dur - 8000) {
      // the decoder stopped early; resume from where it stopped
      toast('SW 스트림이 중단되어 다시 시작합니다');
      seekTo(getPos());
      return;
    }
    saveHistory();
    if (P.repeat === 1) { seekTo(0); video.play().catch(() => {}); return; }
    if (P.sleepEnd) { P.sleepEnd = false; toast('수면 타이머: 재생을 마쳤습니다'); closePlayer(); return; }
    if ((S.settings.autoplayNext || P.repeat === 2) && P.index + 1 < P.list.length) { P.index++; loadCurrent(false); return; }
    if (P.repeat === 2 && P.list.length) { P.index = 0; loadCurrent(false); return; }
    closePlayer();
  }

  // ------------------------------------------------------------ video events
  video.addEventListener('loadedmetadata', () => {
    if (!P.sw && P.pendingSeek > 0) { video.currentTime = P.pendingSeek / 1000; P.pendingSeek = 0; }
    if (!P.dur && Number.isFinite(video.duration)) P.dur = video.duration * 1000;
    layoutVideo();
    if (!P.sw && video.audioTracks && video.audioTracks.length > 1) {
      for (let i = 0; i < video.audioTracks.length; i++) video.audioTracks[i].enabled = i === P.audioIdx;
    }
    setTimeout(() => {
      if (!P.sw && P.info && P.info.video && video.videoWidth === 0 && video.src) fallbackToSw('영상 코덱');
    }, 1500);
  });
  video.addEventListener('error', () => {
    if (!video.getAttribute('src')) return;
    if (!P.sw) { fallbackToSw(); return; }
    $('#spinner').classList.add('hidden');
    dialog('재생 오류', '이 파일을 재생할 수 없습니다.\n파일이 손상되었거나 지원하지 않는 형식일 수 있습니다.', [
      { label: '닫기', fn: closePlayer },
      ...(P.index + 1 < P.list.length ? [{ label: '다음 파일', primary: true, fn: () => skip(1) }] : []),
    ]);
  });
  function fallbackToSw(what) {
    if (P.sw || P.triedSw || !P.item) return;
    if (!S.info.ffmpeg) {
      dialog('재생 오류', '이 형식은 내장 디코더로 재생할 수 없습니다.\nFFmpeg가 없어 SW 디코더도 사용할 수 없습니다.', [{ label: '닫기', primary: true }]);
      return;
    }
    P.triedSw = true;
    toast(`HW 디코더로 재생할 수 없어${what ? ' (' + what + ')' : ''} SW 디코더로 전환합니다`);
    startPlayback(true, getPos() || P.pendingSeek || 0, true);
  }
  video.addEventListener('waiting', () => $('#spinner').classList.remove('hidden'));
  video.addEventListener('playing', () => { if (!P.catchup) $('#spinner').classList.add('hidden'); updatePlayBtn(); api.invoke('power:playing', true); scheduleHide(); });
  video.addEventListener('canplay', () => { if (!P.catchup) $('#spinner').classList.add('hidden'); });
  video.addEventListener('pause', () => { if (P.catchup) return; updatePlayBtn(); api.invoke('power:playing', false); saveHistory(); showControls(); });
  video.addEventListener('play', updatePlayBtn);
  video.addEventListener('seeked', () => { if (!P.catchup) reportPlayerState(); });
  video.addEventListener('ratechange', () => { if (!P.catchup) reportPlayerState(); });
  video.addEventListener('ended', onEnded);
  video.addEventListener('resize', layoutVideo);
  window.addEventListener('resize', () => { layoutVideo(); applySubStyle(); });

  // ------------------------------------------------------------ periodic UI
  setInterval(() => {
    if (!P.item) return;
    if (P.controls && !P.seeking) updateProgress();
    const pos = getPos();
    if (P.abA >= 0 && P.abB > P.abA && pos >= P.abB) seekTo(P.abA);
    if (!video.paused && Date.now() - P.lastSave > 5000) { P.lastSave = Date.now(); saveHistory(); }
    if (P.sleepAt && Date.now() >= P.sleepAt) { P.sleepAt = 0; video.pause(); toast('수면 타이머: 재생을 멈췄습니다'); }
  }, 250);

  function subLoop() {
    if (P.catchup) checkCatchup();
    if (P.item && P.subSel >= 0 && P.subTracks[P.subSel]) {
      const t = P.subTracks[P.subSel];
      const cues = Subs.activeAt(t, getPos() - P.subDelay);
      const key = cues.map((c) => c.start + ':' + c.end).join('|');
      if (key !== P.shownCues) {
        P.shownCues = key;
        const html = cues.map((c) => {
          if (c.safe === undefined) c.safe = Subs.sanitize(c.html);
          return `<span class="line">${c.safe}</span>`;
        }).join('<br>');
        renderSubsHtml(html, cues.length > 0 && cues.every((c) => c.top));
      }
    } else if (P.shownCues) {
      P.shownCues = '';
      renderSubsHtml('', false);
    }
    requestAnimationFrame(subLoop);
  }
  requestAnimationFrame(subLoop);

  function renderSubsHtml(html, top) {
    const s = $('#subs');
    s.innerHTML = html;
    s.classList.toggle('top', !!top);
  }

  function applySubStyle() {
    const s = $('#subs');
    const H = $('#player').clientHeight || innerHeight;
    const size = Math.max(14, H * 0.052 * ((S.settings.subSize || 100) / 100));
    s.style.fontSize = size + 'px';
    s.style.setProperty('--sub-color', S.settings.subColor || '#ffffff');
    s.classList.toggle('outline', S.settings.subOutline !== false);
    s.classList.toggle('bg', !!S.settings.subBg);
    const lift = P.controls ? ($('.bottom-bar').offsetHeight - 10) : 0;
    s.style.bottom = Math.max(H * ((S.settings.subBottom ?? 6) / 100), lift) + 'px';
  }

  function updateProgress() {
    const pos = getPos(), dur = getDur();
    $('#t-pos').textContent = fmtTime(pos);
    $('#t-dur').textContent = dur ? (P.showRemain ? '-' + fmtTime(dur - pos) : fmtTime(dur)) : '--:--';
    const pct = dur ? clamp(pos / dur, 0, 1) * 100 : 0;
    const sb = $('#seekbar');
    sb.querySelector('.prog').style.width = pct + '%';
    sb.querySelector('.knob').style.left = pct + '%';
    let buf = 0;
    if (!P.sw && video.buffered.length && dur) buf = (video.buffered.end(video.buffered.length - 1) * 1000) / dur;
    else if (P.sw && video.buffered.length && dur) buf = (P.swOffset + video.buffered.end(video.buffered.length - 1) * 1000) / dur;
    sb.querySelector('.buf').style.width = clamp(buf, 0, 1) * 100 + '%';
  }
  function updatePlayBtn() { setIcon('#p-play', video.paused || video.ended ? 'play' : 'pause'); reportPlayerState(); }
  function updateNav() {
    $('#p-prev').classList.toggle('dim', P.index <= 0);
    $('#p-next').classList.toggle('dim', P.index >= P.list.length - 1);
    reportPlayerState();
  }

  // ------------------------------------------------------------ Windows integration
  // Taskbar thumbnail buttons (prev / play-pause / next) and the system media controls
  // (keyboard media keys, the Windows volume flyout) both end up in mediaCommand().
  let lastOsState = '';
  function reportPlayerState() {
    const st = { open: !!P.item, playing: !!P.item && !video.paused && !video.ended, prev: P.index > 0, next: P.index < P.list.length - 1 };
    const key = JSON.stringify(st);
    if (key !== lastOsState) {
      lastOsState = key;
      api.invoke('win:playerState', st).catch(() => {});
    }
    updateMediaSession(st);
  }
  let lastMediaCmd = { group: '', t: 0 };
  function mediaCommand(c) {
    // a media key can arrive twice (keydown + media session); play/pause/toggle count as one group
    const group = c === 'prev' || c === 'next' ? c : 'pp';
    const now = Date.now();
    if (group === lastMediaCmd.group && now - lastMediaCmd.t < 350) return;
    lastMediaCmd = { group, t: now };
    if (!P.item) return;
    if (c === 'toggle') togglePlay();
    else if (c === 'play') { if (video.paused || video.ended) togglePlay(); }
    else if (c === 'pause') { if (!video.paused) video.pause(); }
    else if (c === 'prev') skip(-1);
    else if (c === 'next') skip(1);
    showControls();
  }
  const mSession = navigator.mediaSession;
  function setupMediaSession() {
    if (!mSession) return;
    const on = (action, fn) => { try { mSession.setActionHandler(action, fn); } catch (e) { /* action not supported */ } };
    const step = (d) => ((d && d.seekOffset) || Number(S.settings.seekSeconds) || 10) * 1000;
    on('play', () => mediaCommand('play'));
    on('pause', () => mediaCommand('pause'));
    on('previoustrack', () => mediaCommand('prev'));
    on('nexttrack', () => mediaCommand('next'));
    on('seekbackward', (d) => { if (P.item) seekBy(-step(d)); });
    on('seekforward', (d) => { if (P.item) seekBy(step(d)); });
    on('seekto', (d) => { if (P.item && d && Number.isFinite(d.seekTime)) seekTo(d.seekTime * 1000); });
    on('stop', () => { if (P.item) video.pause(); });
  }
  let msItem = null;
  function updateMediaSession(st) {
    if (!mSession) return;
    try {
      if (!st.open) {
        if (msItem) { mSession.metadata = null; mSession.playbackState = 'none'; msItem = null; }
        return;
      }
      if (msItem !== P.item && typeof MediaMetadata === 'function') {
        msItem = P.item;
        const folder = P.item.path.split(/[\\/]/).slice(-2, -1)[0] || '';
        mSession.metadata = new MediaMetadata({ title: stripExt(P.item.name), artist: folder, album: '모아 플레이어' });
      }
      mSession.playbackState = st.playing ? 'playing' : 'paused';
      const dur = getDur() / 1000, pos = getPos() / 1000;
      if (mSession.setPositionState && dur > 0 && pos >= 0) {
        mSession.setPositionState({ duration: dur, position: Math.min(pos, dur), playbackRate: P.speed > 0 ? P.speed : 1 });
      }
    } catch (e) { /* ignore */ }
  }
  function updateDecoderChip() {
    const c = $('#chip-dec');
    c.textContent = P.sw ? 'SW' : 'HW';
    c.title = P.sw ? 'SW 디코더 (FFmpeg) — 눌러서 HW로 전환 (H)' : 'HW 디코더 (하드웨어 가속) — 눌러서 SW로 전환 (H)';
  }
  function updateSpeedChip() {
    const c = $('#chip-speed');
    c.textContent = fmtSpeed(P.speed);
    c.classList.toggle('on', Math.abs(P.speed - 1) > 0.001);
  }
  function updateAbUi() {
    const c = $('#chip-ab');
    c.classList.toggle('hidden', P.abA < 0);
    c.textContent = P.abB < 0 ? 'A-' : 'A-B';
    const ab = $('#seekbar .ab');
    const dur = getDur();
    if (P.abA >= 0 && P.abB > P.abA && dur) {
      ab.style.display = 'block';
      ab.style.left = (P.abA / dur) * 100 + '%';
      ab.style.width = ((P.abB - P.abA) / dur) * 100 + '%';
    } else ab.style.display = 'none';
  }

  // ------------------------------------------------------------ layout (aspect, zoom, pan)
  function layoutVideo() {
    const st = $('#stage');
    const W = st.clientWidth, H = st.clientHeight;
    if (!W || !H) return;
    let vw = video.videoWidth || (P.info && P.info.video && P.info.video.width) || 16;
    let vh = video.videoHeight || (P.info && P.info.video && P.info.video.height) || 9;
    let aspect = vw / vh;
    if (P.aspect === 4) aspect = 16 / 9;
    if (P.aspect === 5) aspect = 4 / 3;
    const screen = W / H;
    let w, h;
    switch (P.aspect) {
      case 1: w = W; h = H; break;
      case 2: if (screen > aspect) { w = W; h = W / aspect; } else { h = H; w = H * aspect; } break;
      case 3: w = vw / devicePixelRatio; h = vh / devicePixelRatio; break;
      default: if (screen > aspect) { h = H; w = H * aspect; } else { w = W; h = W / aspect; }
    }
    w *= P.zoom; h *= P.zoom;
    const maxX = Math.max(0, (w - W) / 2) + (P.zoom > 1 ? W * 0.25 : 0);
    const maxY = Math.max(0, (h - H) / 2) + (P.zoom > 1 ? H * 0.25 : 0);
    P.panX = clamp(P.panX, -maxX, maxX);
    P.panY = clamp(P.panY, -maxY, maxY);
    Object.assign(video.style, {
      width: w + 'px', height: h + 'px', left: (W - w) / 2 + P.panX + 'px', top: (H - h) / 2 + P.panY + 'px',
      filter: Math.abs(P.brightness - 1) > 0.01 ? `brightness(${P.brightness})` : '',
    });
  }

  // ------------------------------------------------------------ volume (with boost up to 200%)
  let audioCtx = null, gainNode = null, analyser = null;
  function ensureAudioGraph() {
    if (audioCtx || !S.settings.boost) return;
    try {
      audioCtx = new AudioContext();
      const src = audioCtx.createMediaElementSource(video);
      gainNode = audioCtx.createGain();
      src.connect(gainNode).connect(audioCtx.destination);
      analyser = audioCtx.createAnalyser();
      gainNode.connect(analyser);
    } catch (e) { audioCtx = null; gainNode = null; }
  }
  function applyVolume() {
    const v = P.muted ? 0 : P.volume;
    if (v > 1) ensureAudioGraph();
    if (gainNode) {
      video.volume = 1;
      gainNode.gain.value = v;
      if (audioCtx.state === 'suspended') audioCtx.resume().catch(() => {});
    } else {
      video.volume = clamp(v, 0, 1);
    }
    const sl = $('#vol-slider');
    sl.max = S.settings.boost ? 200 : 100;
    sl.value = Math.round(P.volume * 100);
    setIcon('#p-mute', P.muted || P.volume === 0 ? 'volume_off' : 'volume_up');
  }
  function setVolume(v, hud = true) {
    P.volume = clamp(Math.round(v * 100) / 100, 0, S.settings.boost ? 2 : 1);
    P.muted = false;
    applyVolume();
    saveSettingsDebounced({ volume: P.volume });
    if (hud) {
      const pct = Math.round(P.volume * 100);
      showHud(pct === 0 ? 'volume_off' : 'volume_up', `볼륨 ${pct}%`, pct > 100 ? `부스트 +${pct - 100}%` : null, Math.min(100, pct), pct > 100 ? (pct - 100) : 0);
      hideHudSoon();
    }
  }
  let settingsTimer = 0, pendingPatch = {};
  function saveSettingsDebounced(patch) {
    Object.assign(S.settings, patch);
    Object.assign(pendingPatch, patch);
    clearTimeout(settingsTimer);
    settingsTimer = setTimeout(() => { api.invoke('settings:set', pendingPatch); pendingPatch = {}; }, 500);
  }

  function setBrightness(b) {
    P.brightness = clamp(Math.round(b * 100) / 100, 0.1, 2);
    layoutVideo();
    const pct = Math.round(P.brightness * 100);
    showHud('brightness', `밝기 ${pct}%`, null, Math.min(100, pct / 2), 0);
  }

  function setSpeed(s, hud = true) {
    P.speed = clamp(Math.round(s * 100) / 100, 0.25, 4);
    if (!P.longPress) video.playbackRate = P.speed;
    video.defaultPlaybackRate = P.speed;
    updateSpeedChip();
    if (hud) { showHud('speed', `속도 ${fmtSpeed(P.speed)}`, null, -1); hideHudSoon(); }
  }

  // ------------------------------------------------------------ HUD & controls
  let hudTimer = 0;
  function showHud(ic, text, sub, pct = -1, extra = 0) {
    const h = $('#hud');
    h.querySelector('.hud-icon').innerHTML = ic ? icon(ic) : '';
    h.querySelector('.hud-text').textContent = text;
    const s = h.querySelector('.hud-sub');
    s.textContent = sub || '';
    s.classList.toggle('hidden', !sub);
    const bar = h.querySelector('.hud-bar');
    bar.classList.toggle('hidden', pct < 0);
    bar.querySelector('i').style.width = clamp(pct, 0, 100) + '%';
    bar.querySelector('b').style.width = clamp(extra, 0, 100) + '%';
    h.classList.remove('hidden');
    clearTimeout(hudTimer);
  }
  function hideHudSoon(ms = 700) { clearTimeout(hudTimer); hudTimer = setTimeout(() => $('#hud').classList.add('hidden'), ms); }

  let hideTimer = 0, keep = false, overBars = false;
  function showControls() {
    if (P.locked) return;
    P.controls = true;
    $('#controls').classList.remove('off');
    $('#player').classList.remove('cursor-hidden');
    updateProgress();
    applySubStyle();
    scheduleHide();
  }
  function hideControls() {
    if (keep || overBars || P.seeking || video.paused) return;
    P.controls = false;
    $('#controls').classList.add('off');
    $('#player').classList.add('cursor-hidden');
    applySubStyle();
  }
  function hideControlsNow() {
    P.controls = false;
    $('#controls').classList.add('off');
    $('#player').classList.add('cursor-hidden');
    applySubStyle();
  }
  function scheduleHide() { clearTimeout(hideTimer); hideTimer = setTimeout(hideControls, 3000); }
  function keepControls(on) { keep = on; if (on) { clearTimeout(hideTimer); if (P.item && !P.locked) showControls(); } else scheduleHide(); }
  for (const sel of ['.top-bar', '.bottom-bar']) {
    $(sel).addEventListener('mouseenter', () => { overBars = true; clearTimeout(hideTimer); });
    $(sel).addEventListener('mouseleave', () => { overBars = false; scheduleHide(); });
  }
  let lastMove = 0;
  $('#player').addEventListener('mousemove', (e) => {
    if (Math.abs(e.movementX) + Math.abs(e.movementY) < 2 || G.down) return;
    const now = Date.now();
    if (now - lastMove < 80) return;
    lastMove = now;
    if (P.locked) { showUnlock(); return; }
    if (!P.controls) showControls(); else scheduleHide();
  });

  function lock() {
    P.locked = true;
    hideControlsNow();
    showUnlock();
    toast('화면을 잠갔습니다 (L 또는 자물쇠 버튼으로 해제)');
  }
  function unlock() {
    P.locked = false;
    $('#p-unlock').classList.add('hidden');
    showControls();
  }
  let unlockTimer = 0;
  function showUnlock() {
    $('#p-unlock').classList.remove('hidden');
    $('#player').classList.remove('cursor-hidden');
    clearTimeout(unlockTimer);
    unlockTimer = setTimeout(() => { $('#p-unlock').classList.add('hidden'); if (P.locked) $('#player').classList.add('cursor-hidden'); }, 2500);
  }

  function togglePlay() {
    if (!P.item) return;
    if (video.ended) { seekTo(0); video.play().catch(() => {}); return; }
    if (video.paused) video.play().catch(() => {}); else video.pause();
  }

  // ------------------------------------------------------------ gestures (mouse, touch, pen)
  const G = { down: false, x0: 0, y0: 0, t0: 0, mode: null, pointers: new Map(), lp: 0, single: 0, lastTap: 0, lastX: 0, lastY: 0, chainUntil: 0, chainZone: 0, chainSec: 0, start: 0, target: 0, pinch: null, wasPlaying: false };
  const gesture = $('#gesture');
  const zoneOf = (x) => { const w = gesture.clientWidth; return x < w * 0.38 ? -1 : x > w * 0.62 ? 1 : 0; };

  gesture.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    gesture.setPointerCapture(e.pointerId);
    G.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (P.locked) { G.down = true; G.mode = null; G.x0 = e.clientX; G.y0 = e.clientY; G.t0 = performance.now(); return; }
    if (G.pointers.size === 2 && S.settings.gestureZoom) {
      endMode();
      const [a, b] = Array.from(G.pointers.values());
      G.mode = 'pinch';
      G.pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), z: P.zoom, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 };
      clearTimeout(G.lp);
      return;
    }
    G.down = true; G.mode = null; G.x0 = e.clientX; G.y0 = e.clientY; G.t0 = performance.now();
    clearTimeout(G.lp);
    if (S.settings.longPressSpeed) G.lp = setTimeout(() => { if (G.down && !G.mode && !video.paused) { G.mode = 'long'; longPressStart(); } }, 550);
  });

  gesture.addEventListener('pointermove', (e) => {
    if (!G.pointers.has(e.pointerId)) return;
    G.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (P.locked) return;
    if (G.mode === 'pinch' && G.pointers.size >= 2) {
      const [a, b] = Array.from(G.pointers.values());
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      P.panX += mx - G.pinch.mx; P.panY += my - G.pinch.my;
      G.pinch.mx = mx; G.pinch.my = my;
      setZoom(G.pinch.z * (d / Math.max(1, G.pinch.d)));
      return;
    }
    if (!G.down) return;
    const dx = e.clientX - G.x0, dy = e.clientY - G.y0;
    if (!G.mode) {
      if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
      clearTimeout(G.lp);
      if (Math.abs(dx) > Math.abs(dy)) {
        if (!S.settings.gestureSeek || !getDur()) { G.mode = 'none'; return; }
        G.mode = 'seek'; G.start = getPos(); G.target = G.start; P.seeking = true;
        G.wasPlaying = !video.paused;
        if (!P.sw) video.pause();
      } else if (G.x0 < gesture.clientWidth / 2) {
        if (!S.settings.gestureBrightness) { G.mode = 'none'; return; }
        G.mode = 'bright'; G.start = P.brightness;
      } else {
        if (!S.settings.gestureVolume) { G.mode = 'none'; return; }
        G.mode = 'volume'; G.start = P.volume;
      }
      G.x0 = e.clientX; G.y0 = e.clientY;
      return;
    }
    const fx = dx / gesture.clientWidth, fy = -dy / gesture.clientHeight;
    if (G.mode === 'seek') {
      const dur = getDur();
      const range = Math.min(90000, Math.max(dur, 10000));
      G.target = clamp(G.start + fx * range, 0, Math.max(0, dur - 300));
      const delta = G.target - G.start;
      showHud(delta >= 0 ? 'fast_forward' : 'fast_rewind', fmtSigned(delta), `${fmtTime(G.target)} / ${fmtTime(dur)}`, (G.target / dur) * 100);
      $('#t-pos').textContent = fmtTime(G.target);
      const pct = (G.target / dur) * 100;
      $('#seekbar .prog').style.width = pct + '%';
      $('#seekbar .knob').style.left = pct + '%';
      if (!P.sw) liveSeek(G.target);
    } else if (G.mode === 'bright') {
      setBrightness(G.start + fy * 1.6);
    } else if (G.mode === 'volume') {
      setVolume(G.start + fy * (S.settings.boost ? 2.2 : 1.2));
    }
  });

  let lastLive = 0;
  function liveSeek(ms) {
    const now = performance.now();
    if (now - lastLive < 90) return;
    lastLive = now;
    if (video.fastSeek) video.fastSeek(ms / 1000); else video.currentTime = ms / 1000;
  }

  function endMode() {
    const m = G.mode;
    G.mode = null;
    if (m === 'seek') {
      P.seeking = false;
      P.seekResume = G.wasPlaying;
      seekTo(G.target).then(() => { if (G.wasPlaying) video.play().catch(() => {}); P.seekResume = false; });
      hideHudSoon();
    } else if (m === 'bright' || m === 'volume') {
      hideHudSoon();
    } else if (m === 'long') {
      longPressEnd();
    } else if (m === 'pinch') {
      if (Math.abs(P.zoom - 1) < 0.06) { P.zoom = 1; P.panX = P.panY = 0; layoutVideo(); }
      hideHudSoon();
    }
    return m;
  }

  function pointerEnd(e, cancelled) {
    if (!G.pointers.has(e.pointerId)) return;
    G.pointers.delete(e.pointerId);
    if (G.mode === 'pinch') { if (G.pointers.size === 0) { endMode(); G.down = false; } return; }
    if (!G.down) return;
    G.down = false;
    clearTimeout(G.lp);
    const m = endMode();
    if (m || cancelled) return;
    if (performance.now() - G.t0 > 550) return;
    handleTap(e.clientX, e.clientY);
  }
  gesture.addEventListener('pointerup', (e) => pointerEnd(e, false));
  gesture.addEventListener('pointercancel', (e) => pointerEnd(e, true));
  gesture.addEventListener('contextmenu', (e) => { e.preventDefault(); if (!P.locked) moreMenu(e.clientX, e.clientY); });

  function handleTap(x, y) {
    if (P.locked) { showUnlock(); return; }
    const zone = zoneOf(x);
    const now = performance.now();
    const dt = S.settings.doubleClick || 'mx';
    if (now < G.chainUntil && zone === G.chainZone && zone !== 0) {
      G.chainUntil = now + 700;
      clearTimeout(G.single);
      G.lastTap = 0;
      doubleTap(zone);
      return;
    }
    if (G.lastTap && now - G.lastTap < 280 && Math.abs(x - G.lastX) < 40 && Math.abs(y - G.lastY) < 40) {
      clearTimeout(G.single);
      G.lastTap = 0;
      if (zone === 0 && dt === 'fullscreen') toggleFullscreen();
      else doubleTap(zone);
      if (zone !== 0) { G.chainZone = zone; G.chainUntil = now + 700; }
      return;
    }
    G.lastTap = now; G.lastX = x; G.lastY = y;
    clearTimeout(G.single);
    G.single = setTimeout(() => {
      G.lastTap = 0;
      if (S.settings.singleClick === 'play') togglePlay();
      else if (P.controls) { P.controls = true; keep = false; overBars = false; clearTimeout(hideTimer); hideControlsForce(); }
      else showControls();
    }, 280);
  }
  function hideControlsForce() {
    P.controls = false;
    $('#controls').classList.add('off');
    $('#player').classList.add('cursor-hidden');
    applySubStyle();
  }

  let rippleTimer = 0;
  function doubleTap(zone) {
    if (zone === 0) {
      togglePlay();
      showHud(video.paused ? 'pause' : 'play', video.paused ? '일시정지' : '재생', null, -1);
      hideHudSoon();
      return;
    }
    const sec = Number(S.settings.seekSeconds) || 10;
    if (G.chainZone !== zone || performance.now() > G.chainUntil) G.chainSec = 0;
    G.chainSec += sec;
    seekBy(zone * sec * 1000);
    const r = zone < 0 ? $('#seek-left') : $('#seek-right');
    const o = zone < 0 ? $('#seek-right') : $('#seek-left');
    o.classList.add('hidden');
    r.querySelector('.r-ic').innerHTML = icon(zone < 0 ? 'fast_rewind' : 'fast_forward');
    r.querySelector('.r-tx').textContent = `${G.chainSec}초`;
    r.classList.remove('hidden');
    clearTimeout(rippleTimer);
    rippleTimer = setTimeout(() => { r.classList.add('hidden'); G.chainSec = 0; }, 750);
  }

  function longPressStart() {
    P.longPress = true;
    const s = Math.min(4, Math.max(2, P.speed * 2));
    video.playbackRate = s;
    const b = $('#speed-badge');
    b.textContent = `${fmtSpeed(s)} 빨리 감기 ▶▶`;
    b.classList.remove('hidden');
  }
  function longPressEnd() {
    P.longPress = false;
    video.playbackRate = P.speed;
    $('#speed-badge').classList.add('hidden');
  }

  function setZoom(z) {
    P.zoom = clamp(z, 0.5, 3);
    layoutVideo();
    showHud('zoom', `확대 ${Math.round(P.zoom * 100)}%`, null, -1);
  }

  gesture.addEventListener('wheel', (e) => {
    e.preventDefault();
    if (P.locked) return;
    if (e.ctrlKey) {
      if (!S.settings.gestureZoom) return;
      setZoom(P.zoom * (e.deltaY < 0 ? 1.08 : 1 / 1.08));
      hideHudSoon();
      return;
    }
    if (S.settings.wheel === 'seek') {
      seekBy(e.deltaY < 0 ? 5000 : -5000);
      showHud(e.deltaY < 0 ? 'fast_forward' : 'fast_rewind', e.deltaY < 0 ? '+5초' : '-5초', fmtTime(getPos()), -1);
      hideHudSoon();
    } else {
      setVolume(P.volume + (e.deltaY < 0 ? 0.05 : -0.05));
    }
  }, { passive: false });

  // seek bar (mouse/touch drag)
  (() => {
    const sb = $('#seekbar');
    const tip = sb.querySelector('.tip');
    let dragging = false, target = 0, wasPlaying = false;
    const posAt = (x) => { const r = sb.getBoundingClientRect(); return clamp((x - r.left) / r.width, 0, 1) * getDur(); };
    sb.addEventListener('pointermove', (e) => {
      const dur = getDur();
      if (!dur) return;
      const t = posAt(e.clientX);
      const r = sb.getBoundingClientRect();
      tip.textContent = fmtTime(t);
      tip.style.left = clamp(e.clientX - r.left, 0, r.width) + 'px';
      tip.classList.remove('hidden');
      if (dragging) {
        target = t;
        const pct = (t / dur) * 100;
        sb.querySelector('.prog').style.width = pct + '%';
        sb.querySelector('.knob').style.left = pct + '%';
        $('#t-pos').textContent = fmtTime(t);
        if (!P.sw) liveSeek(t);
      }
    });
    sb.addEventListener('pointerleave', () => { if (!dragging) tip.classList.add('hidden'); });
    sb.addEventListener('pointerdown', (e) => {
      if (!getDur()) return;
      sb.setPointerCapture(e.pointerId);
      dragging = true;
      P.seeking = true;
      wasPlaying = !video.paused;
      target = posAt(e.clientX);
      const pct = (target / getDur()) * 100;
      sb.querySelector('.prog').style.width = pct + '%';
      sb.querySelector('.knob').style.left = pct + '%';
    });
    const end = () => {
      if (!dragging) return;
      dragging = false;
      P.seeking = false;
      tip.classList.add('hidden');
      P.seekResume = wasPlaying;
      seekTo(target).then(() => { P.seekResume = false; });
    };
    sb.addEventListener('pointerup', end);
    sb.addEventListener('pointercancel', end);
  })();

  // ------------------------------------------------------------ decoder, audio, subtitles
  function switchDecoder(toSw) {
    if (!P.item) return;
    if (toSw && !S.info.ffmpeg) { toast('FFmpeg가 없어 SW 디코더를 사용할 수 없습니다'); return; }
    const pos = getPos();
    const playing = !video.paused;
    P.triedSw = true;
    if (!toSw) P.swBurn = -1;
    startPlayback(toSw, pos, playing);
    showHud(null, toSw ? 'SW 디코더' : 'HW 디코더', toSw ? 'FFmpeg 소프트웨어 디코딩' : '하드웨어 가속 (내장 디코더)', -1);
    hideHudSoon(1100);
  }

  function audioLabel(a, k) {
    return [a.title, langName(a.lang)].filter(Boolean).join(' · ') || `트랙 ${k + 1}`;
  }
  function showAudioSheet() {
    const info = P.info;
    showSheet('오디오 트랙', (body) => {
      if (!info || !info.audio.length) { body.appendChild(el('div', 'hint', '오디오 트랙이 없습니다')); return; }
      info.audio.forEach((a, k) => {
        const native = NATIVE_AUDIO.has(a.codec);
        addItem(body, {
          label: audioLabel(a, k), sub: `${codecName(a.codec)} ${chName(a.channels)}${a.sampleRate ? ' · ' + Math.round(a.sampleRate / 1000) + 'kHz' : ''}${!native && !P.sw ? ' · SW 디코더로 재생' : ''}`,
          checked: k === P.audioIdx, onClick: () => selectAudio(k),
        });
      });
    });
  }
  function selectAudio(k) {
    if (!P.info) return;
    const a = P.info.audio[k];
    P.audioIdx = k;
    const pos = getPos(), playing = !video.paused;
    if (P.sw) { startPlayback(true, pos, playing); }
    else if (!NATIVE_AUDIO.has(a.codec)) { if (S.info.ffmpeg) startPlayback(true, pos, playing); }
    else if (video.audioTracks && video.audioTracks.length === P.info.audio.length) {
      for (let i = 0; i < video.audioTracks.length; i++) video.audioTracks[i].enabled = i === k;
    } else if (S.info.ffmpeg && P.info.audio.length > 1) {
      startPlayback(true, pos, playing);
    }
    toast('오디오: ' + audioLabel(a, k));
  }
  function cycleAudio() {
    if (!P.info || P.info.audio.length < 2) { toast('오디오 트랙이 하나뿐입니다'); return; }
    selectAudio((P.audioIdx + 1) % P.info.audio.length);
  }

  async function loadSubtitles(item, info, token) {
    let sc = { exact: [], others: [] };
    try { sc = await api.invoke('media:sidecars', item.path); } catch (e) { /* ignore */ }
    if (token !== P.token) return;
    P.otherSubs = sc.others;
    for (const f of sc.exact) {
      try {
        const bytes = await api.invoke('media:readSubtitle', f);
        for (const t of Subs.parse(bytes, basename(f), S.settings.subEncoding)) P.subTracks.push(Object.assign(t, { kind: 'ext', path: f }));
      } catch (e) { /* unreadable */ }
      if (token !== P.token) return;
    }
    if (P.subTracks.length) {
      selectSub(0);
      const t = P.subTracks[0];
      toast('자막: ' + t.file + (P.subTracks.length > 1 ? ` (${t.name})` : ''));
      return;
    }
    const emb = info ? info.subs.filter((s) => s.text) : [];
    const pick = emb.find((s) => /^(ko|kor)$/i.test(s.lang)) || emb.find((s) => s.def) || emb[0];
    if (pick) loadEmbedded(pick, token);
  }

  async function loadEmbedded(s, token) {
    const existing = P.subTracks.findIndex((t) => t.kind === 'emb' && t.embIndex === s.index);
    if (existing >= 0) { selectSub(existing); return; }
    toast('내장 자막을 불러오는 중…', 1500);
    try {
      const text = await api.invoke('media:extractSub', P.item.path, s.index);
      if (token !== undefined && token !== P.token) return;
      const tracks = Subs.parse(text, 'embedded.srt');
      if (!tracks.length) { toast('내장 자막에 내용이 없습니다'); return; }
      const t = Object.assign(tracks[0], { kind: 'emb', embIndex: s.index, file: `내장 자막 ${s.index + 1}`, name: [s.title, langName(s.lang)].filter(Boolean).join(' · ') });
      P.subTracks.push(t);
      selectSub(P.subTracks.length - 1);
    } catch (e) {
      toast('내장 자막을 읽을 수 없습니다');
    }
  }

  function selectSub(i) {
    P.subSel = i;
    P.shownCues = '__';
    if (P.swBurn >= 0) {
      P.swBurn = -1;
      if (P.sw) startPlayback(true, getPos(), !video.paused);
    }
  }
  function subsOff() {
    P.subSel = -1;
    P.shownCues = '__';
    if (P.swBurn >= 0) { P.swBurn = -1; startPlayback(true, getPos(), !video.paused); }
  }
  async function addSubtitleFile(path) {
    try {
      const bytes = await api.invoke('media:readSubtitle', path);
      const tracks = Subs.parse(bytes, basename(path), S.settings.subEncoding);
      if (!tracks.length) { toast('자막을 읽을 수 없습니다: ' + basename(path)); return; }
      const first = P.subTracks.length;
      tracks.forEach((t) => P.subTracks.push(Object.assign(t, { kind: 'ext', path })));
      selectSub(first);
      toast('자막: ' + basename(path));
    } catch (e) { toast('자막을 읽을 수 없습니다: ' + e.message); }
  }
  function cycleSub() {
    const n = P.subTracks.length;
    if (!n) { showSubSheet(); return; }
    const next = P.subSel + 1 >= n ? -1 : P.subSel + 1;
    if (next < 0) { subsOff(); toast('자막 끄기'); } else { selectSub(next); toast('자막: ' + P.subTracks[next].file + ' ' + (P.subTracks[next].name || '')); }
  }

  function showSubSheet() {
    const info = P.info;
    showSheet('자막', (body) => {
      addItem(body, { label: '자막 끄기', checked: P.subSel < 0 && P.swBurn < 0, onClick: subsOff });
      if (P.subTracks.length) groupTitle(body, '불러온 자막');
      P.subTracks.forEach((t, i) => addItem(body, {
        label: t.kind === 'emb' ? t.file : basename(t.path || t.file), sub: [t.name, `${t.cues.length}줄`].filter(Boolean).join(' · '),
        checked: i === P.subSel, onClick: () => selectSub(i),
      }));
      const emb = info ? info.subs : [];
      if (emb.length) groupTitle(body, '내장 자막');
      emb.forEach((s) => {
        const label = [s.title, langName(s.lang)].filter(Boolean).join(' · ') || `자막 ${s.index + 1}`;
        if (s.text) addItem(body, { label, sub: codecName(s.codec), checked: P.subTracks[P.subSel] && P.subTracks[P.subSel].embIndex === s.index, onClick: () => loadEmbedded(s) });
        else if (s.bitmap) addItem(body, {
          label, sub: `${codecName(s.codec)} 이미지 자막 · 영상에 입혀 표시 (SW)`, checked: P.swBurn === s.index, disabled: !S.info.ffmpeg,
          onClick: () => { P.subSel = -1; P.swBurn = s.index; startPlayback(true, getPos(), true); toast('이미지 자막을 영상에 입혀 재생합니다'); },
        });
        else addItem(body, { label, sub: codecName(s.codec) + ' (지원 안 됨)', disabled: true });
      });
      if (P.otherSubs.length) groupTitle(body, '같은 폴더의 다른 자막');
      P.otherSubs.forEach((f) => addItem(body, { label: basename(f), onClick: () => addSubtitleFile(f) }));
      groupTitle(body, '설정');
      addItem(body, { label: '자막 파일 열기…', onClick: async () => { const f = await api.invoke('dialog:openSubtitle', dirname(P.item.path)); if (f) addSubtitleFile(f); } });
      addItem(body, { label: `싱크 조절 (현재 ${(P.subDelay / 1000).toFixed(1)}초)`, sub: 'Z / X 키로도 조절', onClick: () => setTimeout(showSyncSheet, 0) });
      addItem(body, { label: '자막 스타일 (크기·색·위치)', onClick: () => setTimeout(showSubStyleSheet, 0) });
    });
  }

  function changeDelay(d) {
    P.subDelay = d === 0 ? 0 : P.subDelay + d;
    P.shownCues = '__';
    if (P.item) {
      S.history[P.item.path] = Object.assign(S.history[P.item.path] || {}, { subDelay: P.subDelay });
      api.invoke('history:save', P.item.path, { subDelay: P.subDelay });
    }
    const v = (P.subDelay >= 0 ? '+' : '') + (P.subDelay / 1000).toFixed(1) + '초';
    showHud('subtitles', `자막 싱크 ${v}`, P.subDelay > 0 ? '자막이 늦게 나옵니다' : P.subDelay < 0 ? '자막이 빨리 나옵니다' : '원래 타이밍', -1);
    hideHudSoon(1000);
    return v;
  }
  function showSyncSheet() {
    showSheet('자막 싱크', (body) => {
      const val = el('div', 'big-value', '');
      const hint = el('div', 'hint', '');
      const refresh = () => {
        val.textContent = (P.subDelay >= 0 ? '+' : '') + (P.subDelay / 1000).toFixed(1) + '초';
        hint.textContent = P.subDelay > 0 ? '자막이 늦게 나옵니다' : P.subDelay < 0 ? '자막이 빨리 나옵니다' : '원래 타이밍';
      };
      body.appendChild(val); body.appendChild(hint);
      const btns = el('div', 'btns');
      [[-1000, '-1초'], [-100, '-0.1초'], [100, '+0.1초'], [1000, '+1초'], [0, '초기화']].forEach(([d, t]) => {
        const b = el('button', 'btn ghost', t);
        b.onclick = () => { changeDelay(d); refresh(); };
        btns.appendChild(b);
      });
      body.appendChild(btns);
      body.appendChild(el('div', 'hint', '단축키: Z (빨리) / X (늦게) — 0.1초씩'));
      refresh();
    });
  }

  function showSubStyleSheet() {
    showSheet('자막 스타일', (body) => {
      const upd = (patch) => { saveSettingsDebounced(patch); applySubStyle(); };
      addRange(body, '글자 크기', 50, 250, 5, S.settings.subSize || 100, (v) => v + '%', (v) => upd({ subSize: v }));
      addRange(body, '위치 (아래 여백)', 0, 30, 1, S.settings.subBottom ?? 6, (v) => v + '%', (v) => upd({ subBottom: v }));
      groupTitle(body, '글자 색');
      const colors = [['#ffffff', '흰색'], ['#ffeb3b', '노란색'], ['#80deea', '하늘색'], ['#b2ff59', '연두색']];
      const btns = el('div', 'btns');
      colors.forEach(([c, n]) => {
        const b = el('button', 'btn ghost', `<span style="color:${c}">●</span> ${n}`);
        b.onclick = () => upd({ subColor: c });
        btns.appendChild(b);
      });
      body.appendChild(btns);
      addSwitch(body, '테두리', S.settings.subOutline !== false, (on) => upd({ subOutline: on }));
      addSwitch(body, '반투명 배경', !!S.settings.subBg, (on) => upd({ subBg: on }));
      addSelect(body, '자막 인코딩', [['auto', '자동 (UTF-8 / EUC-KR)'], ['utf-8', 'UTF-8'], ['euc-kr', 'EUC-KR (CP949)'], ['utf-16le', 'UTF-16'], ['shift_jis', 'Shift_JIS'], ['gbk', 'GBK']],
        S.settings.subEncoding || 'auto', (v) => { upd({ subEncoding: v }); toast('다음에 자막을 불러올 때 적용됩니다'); });
    });
  }

  // ------------------------------------------------------------ more menu & sheets
  function moreMenu(x, y) {
    if (!P.item) return;
    const r = $('#p-more').getBoundingClientRect();
    if (x === undefined) { x = r.right - 240; y = r.bottom + 4; }
    showMenu(x, y, [
      { label: `재생 속도 (${fmtSpeed(P.speed)})`, fn: showSpeedSheet },
      { label: `화면 비율 (${ASPECTS[P.aspect]})`, fn: cycleAspect },
      { label: P.abA < 0 ? 'A-B 구간 반복: A 지점 설정' : P.abB < 0 ? 'A-B 구간 반복: B 지점 설정' : 'A-B 구간 반복 해제', fn: abRepeat },
      { label: `반복 재생 (${REPEATS[P.repeat]})`, fn: cycleRepeat },
      { label: P.sleepAt ? `수면 타이머 (${Math.max(1, Math.round((P.sleepAt - Date.now()) / 60000))}분 남음)` : P.sleepEnd ? '수면 타이머 (영상 끝날 때)' : '수면 타이머', fn: showSleepSheet },
      { label: `항상 위에 표시 (${S.settings.alwaysOnTop ? '켜짐' : '꺼짐'})`, fn: toggleOnTop },
      { label: '팝업 재생 (PiP)', fn: togglePip },
      { label: '스크린샷 (C)', fn: screenshot },
      { label: P.sw ? 'HW 디코더로 전환' : 'SW 디코더로 전환', fn: () => switchDecoder(!P.sw) },
      { sep: true },
      { label: '동영상 정보 (I)', fn: showInfoSheet },
      { label: '파일 위치 열기', fn: () => api.invoke('shell:showItem', P.item.path) },
      { label: '설정', fn: showSettingsSheet },
      { label: '단축키 안내', fn: showKeysSheet },
    ]);
  }

  function showSpeedSheet() {
    showSheet('재생 속도', (body) => {
      const val = el('div', 'big-value', fmtSpeed(P.speed));
      body.appendChild(val);
      const r = addRange(body, '속도', 0.25, 4, 0.05, P.speed, fmtSpeed, (v) => { setSpeed(v, false); val.textContent = fmtSpeed(P.speed); });
      const btns = el('div', 'btns');
      [0.5, 0.75, 1, 1.25, 1.5, 2, 3].forEach((s) => {
        const b = el('button', 'btn ghost', fmtSpeed(s));
        b.onclick = () => { setSpeed(s, false); r.value = s; r.oninput(); };
        btns.appendChild(b);
      });
      body.appendChild(btns);
      body.appendChild(el('div', 'hint', '단축키: [ 느리게 · ] 빠르게 · \\ 원래 속도'));
    });
  }

  function cycleAspect() {
    P.aspect = (P.aspect + 1) % ASPECTS.length;
    P.zoom = 1; P.panX = P.panY = 0;
    layoutVideo();
    showHud('aspect', ASPECTS[P.aspect], null, -1);
    hideHudSoon(900);
  }
  function cycleRepeat() {
    P.repeat = (P.repeat + 1) % 3;
    showHud(P.repeat === 1 ? 'repeat_one' : 'repeat', '반복: ' + REPEATS[P.repeat], null, -1);
    hideHudSoon(900);
  }
  function abRepeat() {
    const pos = getPos();
    if (P.abA < 0) { P.abA = pos; toast(`A 지점: ${fmtTime(pos)} — B 지점에서 한 번 더 선택하세요 (B 키)`); }
    else if (P.abB < 0) {
      if (pos <= P.abA + 500) { toast('B 지점은 A 지점보다 뒤여야 합니다'); return; }
      P.abB = pos; seekTo(P.abA);
      toast(`A-B 반복: ${fmtTime(P.abA)} ~ ${fmtTime(P.abB)}`);
    } else { P.abA = P.abB = -1; toast('A-B 반복을 해제했습니다'); }
    updateAbUi();
  }
  function showSleepSheet() {
    showSheet('수면 타이머', (body) => {
      [[0, '끄기'], [15, '15분 후'], [30, '30분 후'], [45, '45분 후'], [60, '60분 후'], [90, '90분 후'], [-1, '현재 동영상이 끝나면']].forEach(([m, t]) => {
        addItem(body, {
          label: t, checked: (m === 0 && !P.sleepAt && !P.sleepEnd) || (m === -1 && P.sleepEnd),
          onClick: () => { P.sleepEnd = m === -1; P.sleepAt = m > 0 ? Date.now() + m * 60000 : 0; toast(m === 0 ? '수면 타이머를 껐습니다' : '수면 타이머: ' + t); },
        });
      });
    });
  }
  async function toggleOnTop() {
    const on = await api.invoke('win:alwaysOnTop', !S.settings.alwaysOnTop);
    S.settings.alwaysOnTop = on;
    toast(on ? '항상 위에 표시: 켜짐' : '항상 위에 표시: 꺼짐');
  }
  async function togglePip() {
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await video.requestPictureInPicture();
    } catch (e) { toast('팝업 재생을 시작할 수 없습니다'); }
  }
  async function toggleFullscreen() {
    const on = await api.invoke('win:fullscreen');
    setIcon('#p-full', on ? 'fullscreen_exit' : 'fullscreen');
  }
  async function screenshot() {
    if (!video.videoWidth) { toast('스크린샷을 찍을 수 없습니다'); return; }
    try {
      const c = document.createElement('canvas');
      c.width = video.videoWidth; c.height = video.videoHeight;
      c.getContext('2d').drawImage(video, 0, 0);
      const blob = await new Promise((r) => c.toBlob(r, 'image/png'));
      const bytes = new Uint8Array(await blob.arrayBuffer());
      const t = fmtTime(getPos()).replace(/:/g, '-');
      const file = await api.invoke('media:saveScreenshot', bytes, `${stripExt(P.item.name)}_${t}`);
      toast('스크린샷 저장: ' + file, 3500);
    } catch (e) { toast('스크린샷을 저장할 수 없습니다: ' + e.message); }
  }

  function showInfoSheet() {
    const i = P.info;
    showSheet('동영상 정보', (body) => {
      const rows = [['파일', P.item.name], ['위치', dirname(P.item.path)]];
      if (i) {
        rows.push(['크기', fmtSize(i.size)], ['재생 시간', fmtTime(i.duration)], ['형식', i.format]);
        if (i.video) rows.push(['영상', `${codecName(i.video.codec)} ${i.video.profile || ''}`], ['해상도', `${i.video.width} × ${i.video.height}`],
          ['프레임', i.video.fps ? i.video.fps.toFixed(3) + ' fps' : '-'], ['픽셀 형식', i.video.pixFmt]);
        i.audio.forEach((a, k) => rows.push([`음성 ${k + 1}${k === P.audioIdx ? ' (재생 중)' : ''}`, `${codecName(a.codec)} ${chName(a.channels)} ${langName(a.lang)} ${a.title}`]));
        i.subs.forEach((s, k) => rows.push([`자막 ${k + 1}`, `${codecName(s.codec)} ${langName(s.lang)} ${s.title}`]));
        if (i.bitRate) rows.push(['비트레이트', Math.round(i.bitRate / 1000) + ' kbps']);
      }
      rows.push(['디코더', P.sw ? `SW (FFmpeg${P.swCopy ? ', 영상 직접 전달' : ', 다시 인코딩'})` : 'HW (내장 디코더, 하드웨어 가속)']);
      if (video.videoWidth) rows.push(['출력', `${video.videoWidth} × ${video.videoHeight}`]);
      const t = P.subTracks[P.subSel];
      if (t) rows.push(['현재 자막', `${t.file} · ${t.cues.length}줄`]);
      const tbl = el('table', 'kbd-table');
      for (const [k, v] of rows) tbl.appendChild(el('tr', '', `<td>${esc(k)}</td><td>${esc(v)}</td>`));
      body.appendChild(tbl);
    });
  }

  function showKeysSheet() {
    showSheet('단축키 · 마우스 조작', (body) => {
      const rows = [
        ['클릭', '컨트롤 표시/숨기기'], ['더블클릭 왼쪽/오른쪽', `${S.settings.seekSeconds || 10}초 뒤로/앞으로 (연속 클릭 시 누적)`],
        ['더블클릭 가운데', S.settings.doubleClick === 'fullscreen' ? '전체 화면' : '재생/일시정지'],
        ['좌우 드래그', '탐색'], ['오른쪽 위아래 드래그', '볼륨'], ['왼쪽 위아래 드래그', '밝기'],
        ['길게 누르기', '2배속 (떼면 원래 속도)'], ['마우스 휠', S.settings.wheel === 'seek' ? '5초 탐색' : '볼륨'], ['Ctrl + 휠 / 두 손가락', '확대/축소'],
        ['오른쪽 클릭', '메뉴'], ['Space / K', '재생/일시정지'], ['← / →', `${S.settings.seekSeconds || 10}초 이동 (Shift: 60초)`],
        ['↑ / ↓', '볼륨'], ['F / Enter', '전체 화면'], ['Esc', '전체 화면 해제 / 뒤로'], ['M', '음소거'],
        ['[ / ] / \\', '속도 느리게 / 빠르게 / 원래대로'], ['N / P', '다음 / 이전 파일'], ['A', '오디오 트랙 바꾸기'],
        ['S', '자막 바꾸기'], ['Z / X', '자막 싱크 -0.1초 / +0.1초'], ['H', 'HW/SW 디코더 전환'], ['R', '화면 비율'],
        ['L', '화면 잠금'], ['B', 'A-B 구간 반복'], ['C', '스크린샷'], ['I', '동영상 정보'], ['T', '항상 위에 표시'],
        ['1 ~ 9', '10% ~ 90% 위치로 이동'], ['Ctrl + O', '파일 열기'],
      ];
      const tbl = el('table', 'kbd-table');
      for (const [k, v] of rows) tbl.appendChild(el('tr', '', `<td>${esc(k)}</td><td>${esc(v)}</td>`));
      body.appendChild(tbl);
    });
  }

  function showSettingsSheet() {
    showSheet('설정', (body) => {
      const set = (patch) => saveSettingsDebounced(patch);
      groupTitle(body, '재생');
      addSelect(body, '디코더', [['auto', '자동 (HW 우선, 안 되면 SW)'], ['hw', 'HW 디코더만'], ['sw', 'SW 디코더 (FFmpeg)']], S.settings.decoder || 'auto', (v) => set({ decoder: v }));
      addSelect(body, '이어보기', [['ask', '매번 묻기'], ['always', '항상 이어보기'], ['never', '처음부터 재생']], S.settings.resume || 'ask', (v) => set({ resume: v }));
      addSwitch(body, '다음 동영상 자동 재생', S.settings.autoplayNext !== false, (on) => set({ autoplayNext: on }));
      addSelect(body, '탐색 간격 (더블클릭·방향키)', [5, 10, 15, 20, 30, 60].map((s) => [s, s + '초']), S.settings.seekSeconds || 10, (v) => set({ seekSeconds: Number(v) }));
      addSelect(body, '한 번 클릭', [['mx', '컨트롤 표시/숨기기 (MX 방식)'], ['play', '재생/일시정지']], S.settings.singleClick || 'mx', (v) => set({ singleClick: v }));
      addSelect(body, '가운데 더블클릭', [['mx', '재생/일시정지 (MX 방식)'], ['fullscreen', '전체 화면']], S.settings.doubleClick || 'mx', (v) => set({ doubleClick: v }));
      addSelect(body, '마우스 휠', [['volume', '볼륨'], ['seek', '5초 탐색']], S.settings.wheel || 'volume', (v) => set({ wheel: v }));
      groupTitle(body, '제스처 (마우스 드래그·터치)');
      addSwitch(body, '좌우 드래그로 탐색', S.settings.gestureSeek !== false, (on) => set({ gestureSeek: on }));
      addSwitch(body, '오른쪽 위아래 드래그로 볼륨', S.settings.gestureVolume !== false, (on) => set({ gestureVolume: on }));
      addSwitch(body, '왼쪽 위아래 드래그로 밝기', S.settings.gestureBrightness !== false, (on) => set({ gestureBrightness: on }));
      addSwitch(body, '확대/축소 (Ctrl+휠, 두 손가락)', S.settings.gestureZoom !== false, (on) => set({ gestureZoom: on }));
      addSwitch(body, '길게 눌러 2배속', S.settings.longPressSpeed !== false, (on) => set({ longPressSpeed: on }));
      addSwitch(body, '볼륨 부스트 (최대 200%)', S.settings.boost !== false, (on) => { set({ boost: on }); if (!on && P.volume > 1) setVolume(1, false); applyVolume(); });
      groupTitle(body, '자막');
      addItem(body, { label: '자막 스타일 (크기·색·위치·인코딩)', onClick: () => setTimeout(showSubStyleSheet, 0) });
      groupTitle(body, '라이브러리 폴더');
      (S.settings.folders || []).forEach((f) => {
        const row = el('div', 'row', `<span class="lbl" style="word-break:break-all">${esc(f)}</span>`);
        const b = el('button', 'icon-btn', icon('close'));
        b.title = '목록에서 빼기';
        b.onclick = async () => { S.settings = await api.invoke('lib:removeFolder', f); row.remove(); loadLibrary(); };
        row.appendChild(b);
        body.appendChild(row);
      });
      const fb = el('div', 'btns');
      const add = el('button', 'btn ghost', '폴더 추가');
      add.onclick = async () => { await addFolder(); closeSheet(); showSettingsSheet(); };
      fb.appendChild(add);
      body.appendChild(fb);
      addSwitch(body, '썸네일 표시', S.settings.showThumbs !== false, (on) => { set({ showThumbs: on }); renderLibrary(); });
      groupTitle(body, '업데이트');
      if (S.update.state !== 'unsupported') {
        addSwitch(body, '자동 업데이트 (인터넷에 연결되면 새 버전을 받아 설치)', S.settings.autoUpdate !== false, (on) => set({ autoUpdate: on }));
      }
      const upd = addItem(body, { label: '지금 업데이트 확인', sub: updateText(S.update), keep: true, onClick: checkUpdate });
      const updSub = upd.querySelector('small');
      if (updSub) updSub.id = 'upd-sub';
      const inst = addItem(body, { label: '다시 시작해서 새 버전 설치', onClick: installUpdate });
      inst.id = 'upd-install';
      inst.classList.toggle('hidden', S.update.state !== 'downloaded');
      groupTitle(body, '기타');
      addItem(body, {
        label: '재생 기록 지우기', sub: '이어보기 위치와 NEW 표시를 초기화합니다',
        onClick: () => dialog('재생 기록 지우기', '모든 이어보기 위치와 재생 기록을 지울까요?', [{ label: '취소' },
          { label: '지우기', primary: true, fn: async () => { await api.invoke('history:clear'); S.history = {}; renderLibrary(); toast('재생 기록을 지웠습니다'); } }]),
      });
      addItem(body, { label: '단축키 · 마우스 조작 안내', onClick: () => setTimeout(showKeysSheet, 0) });
      body.appendChild(el('div', 'hint', `모아 플레이어 ${S.info.version} · FFmpeg ${S.info.ffmpeg ? '사용 가능 (SW 디코더·썸네일)' : '없음'}`));
    });
  }

  // ------------------------------------------------------------ auto update (installed Windows app)
  function updateText(u) {
    const cur = (u && u.current) || S.info.version || '';
    switch (u && u.state) {
      case 'checking': return '업데이트를 확인하는 중…';
      case 'none': return `최신 버전입니다 (${cur})`;
      case 'available': return `새 버전(${u.version}) 내려받는 중…`;
      case 'downloading': return `새 버전(${u.version || ''}) 내려받는 중… ${u.percent || 0}%`;
      case 'downloaded': return `새 버전(${u.version}) 준비됨 — 프로그램을 닫으면 설치됩니다`;
      case 'done': return `새 버전(${u.version})으로 업데이트했습니다`;
      case 'error': return u.message || '업데이트를 확인하지 못했습니다';
      case 'unsupported': return `현재 버전 ${cur} · 설치 프로그램으로 설치한 경우에만 자동 업데이트됩니다`;
      default: return `현재 버전 ${cur}`;
    }
  }
  function onUpdateStatus(u) {
    if (!u || !u.state) return;
    const prev = S.update || {};
    S.update = u;
    if (u.state === 'downloaded' && prev.state !== 'downloaded') toast(`새 버전(${u.version})을 받았습니다. 프로그램을 닫으면 자동으로 설치됩니다`, 6000);
    else if (u.state === 'done') toast(`새 버전(${u.version})으로 업데이트했습니다`, 5000);
    else if (u.state === 'error' && u.manual) toast(u.message, 5000);
    else if (u.state === 'none' && S.updateManual) toast(updateText(u));
    if (u.state !== 'checking' && u.state !== 'available' && u.state !== 'downloading') S.updateManual = false;
    const sub = $('#upd-sub');
    if (sub) sub.textContent = updateText(u);
    const inst = $('#upd-install');
    if (inst) inst.classList.toggle('hidden', u.state !== 'downloaded');
  }
  async function checkUpdate() {
    if (S.update.state === 'unsupported') { toast(updateText(S.update), 4000); return; }
    if (S.update.state === 'downloaded') { installUpdate(); return; }
    S.updateManual = true;
    onUpdateStatus({ state: 'checking', current: S.info.version });
    try { onUpdateStatus(await api.invoke('update:check')); } catch (e) { S.updateManual = false; toast('업데이트를 확인하지 못했습니다'); }
  }
  function installUpdate() {
    dialog('업데이트 설치', `새 버전(${S.update.version})을 설치하려면 프로그램을 다시 시작합니다.\n지금 다시 시작할까요?`, [
      { label: '나중에' },
      { label: '다시 시작', primary: true, fn: () => { saveHistory(); api.invoke('update:install'); } },
    ]);
  }

  // ------------------------------------------------------------ keyboard
  document.addEventListener('keydown', (e) => {
    const tag = (e.target.tagName || '').toLowerCase();
    if (!$('#dialog').classList.contains('hidden')) {
      if (e.key === 'Escape') { $('#dialog')._cancel(); e.preventDefault(); }
      else if (e.key === 'Enter' && tag !== 'button') { $('#dialog')._ok(); e.preventDefault(); }
      return;
    }
    if (tag === 'input' && e.target.type !== 'range') {
      if (e.key === 'Escape') e.target.blur();
      return;
    }
    if (e.key === 'Escape' && sheetOpen()) { closeSheet(); e.preventDefault(); return; }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'o') { openFiles(); e.preventDefault(); return; }
    const inPlayer = !$('#player').classList.contains('hidden');
    if (!inPlayer) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') { $('#search').focus(); e.preventDefault(); }
      else if (e.key === 'F5') { loadLibrary(); e.preventDefault(); }
      else if ((e.key === 'Backspace' || e.key === 'Escape') && S.folder) { S.folder = null; renderLibrary(); }
      return;
    }
    if (sheetOpen()) return;
    const k = e.key;
    const lower = k.length === 1 ? k.toLowerCase() : k;
    if (P.locked && lower !== 'l') { showUnlock(); return; }
    let handled = true;
    const sec = (Number(S.settings.seekSeconds) || 10) * 1000;
    switch (lower) {
      case 'MediaPlayPause': mediaCommand('toggle'); break;
      case 'MediaTrackNext': mediaCommand('next'); break;
      case 'MediaTrackPrevious': mediaCommand('prev'); break;
      case ' ': case 'k': togglePlay(); showHud(video.paused ? 'pause' : 'play', video.paused ? '일시정지' : '재생', null, -1); hideHudSoon(); break;
      case 'ArrowLeft': seekBy(e.shiftKey ? -60000 : -sec); showHud('fast_rewind', e.shiftKey ? '-60초' : `-${sec / 1000}초`, fmtTime(Math.max(0, getPos())), -1); hideHudSoon(); break;
      case 'ArrowRight': seekBy(e.shiftKey ? 60000 : sec); showHud('fast_forward', e.shiftKey ? '+60초' : `+${sec / 1000}초`, fmtTime(getPos()), -1); hideHudSoon(); break;
      case 'ArrowUp': setVolume(P.volume + 0.05); break;
      case 'ArrowDown': setVolume(P.volume - 0.05); break;
      case 'f': case 'Enter': toggleFullscreen(); break;
      case 'Escape':
        api.invoke('win:isFullscreen').then((fs) => { if (fs) toggleFullscreen(); else closePlayer(); });
        break;
      case 'm': P.muted = !P.muted; applyVolume(); showHud(P.muted ? 'volume_off' : 'volume_up', P.muted ? '음소거' : `볼륨 ${Math.round(P.volume * 100)}%`, null, -1); hideHudSoon(); break;
      case '[': setSpeed(P.speed - 0.1); break;
      case ']': setSpeed(P.speed + 0.1); break;
      case '\\': case 'Backspace': setSpeed(1); break;
      case 'n': case 'PageDown': skip(1); break;
      case 'p': case 'PageUp': skip(-1); break;
      case 'a': cycleAudio(); break;
      case 's': cycleSub(); break;
      case 'z': changeDelay(-100); break;
      case 'x': changeDelay(100); break;
      case 'h': switchDecoder(!P.sw); break;
      case 'r': cycleAspect(); break;
      case 'l': if (P.locked) unlock(); else lock(); break;
      case 'b': abRepeat(); break;
      case 'c': screenshot(); break;
      case 'i': showInfoSheet(); break;
      case 't': toggleOnTop(); break;
      case 'Home': seekTo(0); break;
      default:
        if (/^[1-9]$/.test(k) && getDur()) seekTo(getDur() * Number(k) / 10);
        else handled = false;
    }
    if (handled) { e.preventDefault(); if (lower !== 'Escape') showControls(); }
  });

  // ------------------------------------------------------------ drag & drop
  let dragDepth = 0;
  document.addEventListener('dragenter', (e) => { e.preventDefault(); dragDepth++; $('#drop-hint').classList.remove('hidden'); });
  document.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; $('#drop-hint').classList.add('hidden'); } });
  document.addEventListener('dragover', (e) => e.preventDefault());
  document.addEventListener('drop', async (e) => {
    e.preventDefault();
    dragDepth = 0;
    $('#drop-hint').classList.add('hidden');
    const files = [], dirs = [];
    let sub = null;
    const items = Array.from(e.dataTransfer.items || []);
    Array.from(e.dataTransfer.files).forEach((f, i) => {
      const p = api.pathForFile(f);
      if (!p) return;
      const entry = items[i] && items[i].webkitGetAsEntry ? items[i].webkitGetAsEntry() : null;
      if (entry && entry.isDirectory) dirs.push(p);
      else if (Subs.isSubtitle(p)) sub = p;
      else files.push(p);
    });
    if (sub && P.item && !files.length) { addSubtitleFile(sub); return; }
    for (const d of dirs) S.settings = await api.invoke('lib:addFolderPath', d);
    if (dirs.length) { toast('폴더를 라이브러리에 추가했습니다'); await loadLibrary(); }
    if (files.length) playFiles(files);
  });

  // ------------------------------------------------------------ wiring
  function wire() {
    setIcon('#lib-back', 'back'); setIcon('#btn-open', 'file'); setIcon('#btn-add-folder', 'add'); setIcon('#btn-sort', 'sort');
    setIcon('#btn-refresh', 'refresh'); setIcon('#btn-settings', 'settings'); setIcon('#fab', 'play');
    setIcon('.search-ic', 'search');
    setIcon('#p-back', 'back'); setIcon('#p-pip', 'pip'); setIcon('#p-audio', 'audiotrack'); setIcon('#p-sub', 'subtitles');
    setIcon('#p-more', 'more'); setIcon('#p-lock', 'lock_open'); setIcon('#p-prev', 'prev'); setIcon('#p-play', 'pause');
    setIcon('#p-next', 'next'); setIcon('#p-aspect', 'aspect'); setIcon('#p-full', 'fullscreen'); setIcon('#p-unlock', 'lock');
    setIcon('.sheet-close', 'close');

    $('#lib-back').onclick = () => { S.folder = null; renderLibrary(); };
    $('#btn-open').onclick = openFiles;
    $('#btn-add-folder').onclick = addFolder;
    $('#btn-refresh').onclick = () => { S.meta.clear(); loadLibrary(); };
    $('#btn-settings').onclick = showSettingsSheet;
    $('#btn-view').onclick = () => { saveSettingsDebounced({ view: S.settings.view === 'grid' ? 'list' : 'grid' }); renderLibrary(); };
    $('#btn-sort').onclick = (e) => {
      const r = e.currentTarget.getBoundingClientRect();
      const cur = S.settings.sort || 'name';
      const opt = (k, t) => ({ label: (cur === k ? '✓ ' : '   ') + t, fn: () => { saveSettingsDebounced({ sort: k }); renderLibrary(); } });
      showMenu(r.left - 150, r.bottom + 4, [opt('name', '이름'), opt('date', '날짜'), opt('size', '크기'), opt('duration', '재생 시간'), { sep: true },
        { label: (S.settings.sortDesc ? '✓ ' : '   ') + '내림차순', fn: () => { saveSettingsDebounced({ sortDesc: !S.settings.sortDesc }); renderLibrary(); } }]);
    };
    let st = 0;
    $('#search').addEventListener('input', (e) => { clearTimeout(st); st = setTimeout(() => { S.search = e.target.value; renderLibrary(); }, 150); });
    $('#fab').onclick = () => { const lp = lastPlayed(); if (lp) playFiles([lp.p]); };

    $('#p-back').onclick = closePlayer;
    $('#p-play').onclick = togglePlay;
    $('#p-prev').onclick = () => skip(-1);
    $('#p-next').onclick = () => skip(1);
    $('#p-lock').onclick = lock;
    $('#p-unlock').onclick = unlock;
    $('#p-aspect').onclick = cycleAspect;
    $('#p-full').onclick = toggleFullscreen;
    $('#p-sub').onclick = showSubSheet;
    $('#p-audio').onclick = showAudioSheet;
    $('#p-more').onclick = () => moreMenu();
    $('#p-pip').onclick = togglePip;
    $('#chip-dec').onclick = () => switchDecoder(!P.sw);
    $('#chip-speed').onclick = showSpeedSheet;
    $('#chip-ab').onclick = () => { P.abA = P.abB = -1; updateAbUi(); toast('A-B 반복을 해제했습니다'); };
    $('#t-dur').onclick = () => { P.showRemain = !P.showRemain; updateProgress(); };
    $('#p-mute').onclick = () => { P.muted = !P.muted; applyVolume(); };
    $('#vol-slider').oninput = (e) => setVolume(Number(e.target.value) / 100, false);
    $('#backdrop').onclick = () => { if ($('#dialog').classList.contains('hidden')) closeSheet(); };
    $('.sheet-close').onclick = closeSheet;

    api.on('open-files', (files) => playFiles(files));
    api.on('fullscreen', (on) => setIcon('#p-full', on ? 'fullscreen_exit' : 'fullscreen'));
    api.on('stream-error', (msg) => { if (P.sw && P.item) toast('SW 디코더 오류: ' + String(msg).slice(0, 160), 4000); });
    api.on('update-status', onUpdateStatus);
    api.on('media-command', mediaCommand);
  }

  function detectHevc() {
    if (S.settings.hevcNative === 'on') return true;
    if (S.settings.hevcNative === 'off') return false;
    try {
      const t = 'video/mp4; codecs="hvc1.1.6.L120.90"';
      return (window.MediaSource && MediaSource.isTypeSupported(t)) || video.canPlayType(t) === 'probably';
    } catch (e) { return false; }
  }

  async function init() {
    wire();
    [S.settings, S.info] = await Promise.all([api.invoke('settings:get'), api.invoke('app:info')]);
    S.hevc = detectHevc();
    P.volume = typeof S.settings.volume === 'number' ? S.settings.volume : 1;
    applyVolume();
    applySubStyle();
    updateSpeedChip();
    setupMediaSession();
    api.invoke('update:status').then(onUpdateStatus).catch(() => {});
    await loadLibrary();
    const pending = await api.invoke('app:takePendingFiles');
    if (pending && pending.length) playFiles(pending);
  }

  // test / debugging hook
  window.__moa = { S, P, get analyser() { return analyser; }, setVolume, playFiles, play, seekTo, getPos, closePlayer, switchDecoder, selectAudio, showSubSheet, showSettingsSheet };
  init();
})();
