'use strict';
// FFmpeg helpers: locating binaries, probing, thumbnails, subtitle extraction and the
// software-decoding (transcoding) stream used for the "SW" decoder mode.
const fs = require('fs');
const path = require('path');
const { spawn, execFile } = require('child_process');

let ffmpegPath = null, ffprobePath = null;

function findBinaries(resourcesPath, appDir) {
  const exe = process.platform === 'win32' ? '.exe' : '';
  const candidates = [];
  if (process.env.MOA_FFMPEG_DIR) candidates.push(process.env.MOA_FFMPEG_DIR);
  if (resourcesPath) candidates.push(path.join(resourcesPath, 'ffmpeg'));
  if (appDir) candidates.push(path.join(appDir, 'ffmpeg'));
  for (const dir of candidates) {
    const f = path.join(dir, 'ffmpeg' + exe), p = path.join(dir, 'ffprobe' + exe);
    if (fs.existsSync(f) && fs.existsSync(p)) { ffmpegPath = f; ffprobePath = p; return true; }
  }
  // fall back to PATH
  ffmpegPath = 'ffmpeg' + exe;
  ffprobePath = 'ffprobe' + exe;
  return false;
}

function available() {
  return new Promise((resolve) => {
    execFile(ffprobePath, ['-version'], { windowsHide: true, timeout: 8000 }, (err) => resolve(!err));
  });
}

function run(bin, args, { maxBuffer = 64 * 1024 * 1024, timeout = 0, encoding = 'utf8' } = {}) {
  return new Promise((resolve, reject) => {
    execFile(bin, args, { windowsHide: true, maxBuffer, timeout, encoding }, (err, stdout, stderr) => {
      if (err) { err.stderr = stderr; reject(err); } else resolve(stdout);
    });
  });
}

function num(x) { const n = parseFloat(x); return Number.isFinite(n) ? n : 0; }
function fps(r) {
  if (!r || r === '0/0') return 0;
  const [a, b] = String(r).split('/').map(Number);
  return b ? a / b : a;
}

const TEXT_SUBS = new Set(['subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text', 'microdvd', 'subviewer', 'sami', 'jacosub', 'realtext', 'stl', 'mpl2', 'vplayer', 'pjs']);
const BITMAP_SUBS = new Set(['hdmv_pgs_subtitle', 'dvd_subtitle', 'dvb_subtitle', 'xsub']);

async function probe(file) {
  const out = await run(ffprobePath, ['-v', 'error', '-show_format', '-show_streams', '-of', 'json', file], { timeout: 30000 });
  const j = JSON.parse(out);
  const streams = j.streams || [];
  const fmt = j.format || {};
  const info = {
    duration: num(fmt.duration) * 1000,
    format: fmt.format_name || '',
    size: num(fmt.size),
    bitRate: num(fmt.bit_rate),
    title: (fmt.tags && (fmt.tags.title || fmt.tags.TITLE)) || '',
    video: null, audio: [], subs: [],
  };
  let a = 0, s = 0;
  for (const st of streams) {
    const tags = st.tags || {};
    const disp = st.disposition || {};
    if (st.codec_type === 'video' && !disp.attached_pic && !info.video) {
      info.video = {
        codec: st.codec_name, profile: st.profile || '', width: st.width || 0, height: st.height || 0,
        pixFmt: st.pix_fmt || '', fps: fps(st.avg_frame_rate || st.r_frame_rate), bitRate: num(st.bit_rate),
        sar: st.sample_aspect_ratio || '1:1', rotation: rotationOf(st),
      };
      if (!info.duration && st.duration) info.duration = num(st.duration) * 1000;
    } else if (st.codec_type === 'audio') {
      info.audio.push({
        index: a++, codec: st.codec_name, profile: st.profile || '', channels: st.channels || 0,
        layout: st.channel_layout || '', sampleRate: num(st.sample_rate), lang: tags.language || '',
        title: tags.title || tags.handler_name || '', def: !!disp.default, bitRate: num(st.bit_rate),
      });
    } else if (st.codec_type === 'subtitle') {
      info.subs.push({
        index: s++, codec: st.codec_name, lang: tags.language || '', title: tags.title || '',
        def: !!disp.default, forced: !!disp.forced, text: TEXT_SUBS.has(st.codec_name), bitmap: BITMAP_SUBS.has(st.codec_name),
      });
    }
  }
  return info;
}

function rotationOf(st) {
  if (st.tags && st.tags.rotate) return num(st.tags.rotate);
  for (const sd of st.side_data_list || []) if (sd.rotation !== undefined) return num(sd.rotation);
  return 0;
}

async function thumbnail(file, out, durationMs) {
  const t = durationMs > 0 ? Math.min(durationMs * 0.12, 40000) / 1000 : 3;
  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-ss', t.toFixed(2), '-i', file,
    '-frames:v', '1', '-vf', 'scale=384:-2:flags=bicubic', '-q:v', '5', out];
  try {
    await run(ffmpegPath, args, { timeout: 30000 });
  } catch (e) {
    // very short files: try from the start
    await run(ffmpegPath, ['-hide_banner', '-loglevel', 'error', '-nostdin', '-y', '-i', file, '-frames:v', '1',
      '-vf', 'scale=384:-2', '-q:v', '5', out], { timeout: 30000 });
  }
  return fs.existsSync(out);
}

/** Time (ms) of the video key frame at or before `ms` — where a stream-copy seek really starts. */
/**
 * Key frames of the first video stream around `ms`: one quick ffprobe pass that seeks (via the
 * container index) to the key frame at or before ms-before and reads packets up to ms+after.
 * The first key frame in the list is therefore always at or before ms-before (when the file has one).
 * Returns {keys: [ms...] ascending, from, to} — the covered range in ms.
 */
async function keyframesAround(file, ms, before = 6000, after = 6000) {
  const from = Math.max(0, ms - before) / 1000, to = Math.max(0, ms + after) / 1000;
  const out = await run(ffprobePath, ['-v', 'error', '-select_streams', 'v:0', '-read_intervals', `${from.toFixed(3)}%${to.toFixed(3)}`,
    '-show_entries', 'packet=pts_time,flags', '-of', 'csv=p=0', file], { timeout: 15000 });
  const keys = [];
  let first = Infinity;
  for (const line of out.split(/\r?\n/)) {
    const i = line.indexOf(',');
    const t = parseFloat(i >= 0 ? line.slice(0, i) : line);
    if (!Number.isFinite(t)) continue;
    const v = Math.round(t * 1000);
    if (v < first) first = v;
    if (i >= 0 && line.indexOf('K', i) > i) keys.push(v);
  }
  keys.sort((a, b) => a - b);
  return { keys, from: Number.isFinite(first) ? Math.min(first, Math.round(from * 1000)) : Math.round(from * 1000), to: Math.round(to * 1000) };
}

async function extractSubtitle(file, subIndex) {
  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-i', file, '-map', `0:s:${subIndex}`, '-c:s', 'srt', '-f', 'srt', 'pipe:1'];
  return run(ffmpegPath, args, { timeout: 10 * 60 * 1000 });
}

/**
 * Builds the ffmpeg arguments for the SW (software decoding) stream: decode with FFmpeg and
 * re-encode to H.264/AAC fragmented MP4 that the built-in player can always show.
 */
function streamArgs({ file, start = 0, audio = 0, videoCopy = false, burnSub = -1, hevcTag = false, maxHeight = 0 }) {
  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin'];
  if (start > 0) args.push('-ss', (start / 1000).toFixed(3));
  args.push('-i', file);
  let vf = [];
  if (burnSub >= 0) {
    const scale = maxHeight > 0 ? `,scale=-2:'min(${maxHeight},ih)'` : '';
    args.push('-filter_complex', `[0:v:0][0:s:${burnSub}]overlay${scale},format=yuv420p[v]`, '-map', '[v]');
    videoCopy = false;
  } else {
    args.push('-map', '0:v:0?');
    if (!videoCopy) {
      if (maxHeight > 0) vf.push(`scale=-2:'min(${maxHeight},ih)'`);
      vf.push('format=yuv420p');
    }
  }
  args.push('-map', `0:a:${audio}?`);
  if (videoCopy) {
    args.push('-c:v', 'copy');
    if (hevcTag) args.push('-tag:v', 'hvc1');
  } else {
    if (vf.length) args.push('-vf', vf.join(','));
    // zerolatency: no look-ahead / B-frames, so the first picture comes out right after a seek
    args.push('-c:v', 'libx264', '-preset', 'veryfast', '-tune', 'zerolatency', '-crf', '20', '-g', '48', '-bf', '0');
  }
  args.push('-c:a', 'aac', '-b:a', '192k', '-ac', '2', '-ar', '48000', '-sn', '-dn',
    '-max_muxing_queue_size', '4096', '-f', 'mp4', '-movflags', 'frag_keyframe+empty_moov+default_base_moof',
    // short fragments: the player can start as soon as ~0.3 s is ready instead of a whole key-frame interval
    '-frag_duration', '300000', 'pipe:1');
  return args;
}

function spawnStream(opts) {
  return spawn(ffmpegPath, streamArgs(opts), { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
}

module.exports = {
  findBinaries, available, probe, thumbnail, extractSubtitle, keyframesAround, spawnStream, streamArgs,
  get ffmpegPath() { return ffmpegPath; }, get ffprobePath() { return ffprobePath; },
};
