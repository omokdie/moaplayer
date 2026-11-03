const { launch, media, shot } = require('./launch');
const state = (win) => win.evaluate(() => {
  const v = document.querySelector('#video'), P = window.__moa.P;
  return { title: document.querySelector('#p-title').textContent, pos: Math.round(window.__moa.getPos()), sw: P.sw, copy: P.swCopy,
    chip: document.querySelector('#chip-dec').textContent, paused: v.paused, vw: v.videoWidth, vh: v.videoHeight, err: v.error && v.error.code,
    audio: P.audioIdx, subs: P.subTracks.map((t) => `${t.file}(${t.cues.length})`), sel: P.subSel, subText: document.querySelector('#subs').innerText };
});
(async () => {
  const { app, win, logs } = await launch({ fresh: true });
  await win.waitForSelector('.folder');
  // 1) MKV with AC3 + AAC + embedded SRT -> should switch to SW automatically (video copied)
  await win.evaluate((f) => window.__moa.playFiles([f]), media('드라마 폴더/드라마 2화 (AC3 멀티오디오).mkv'));
  await win.waitForTimeout(5000);
  console.log('MKV/AC3 :', JSON.stringify(await state(win)));
  await win.screenshot({ path: shot('04-mkv-sw.png') });
  // seek in SW copy mode to 30s, check offset accuracy
  await win.evaluate(() => window.__moa.seekTo(30000));
  await win.waitForTimeout(3500);
  console.log('MKV seek30:', JSON.stringify(await state(win)));
  // switch audio to track 2 (AAC)
  await win.evaluate(() => window.__moa.selectAudio(1));
  await win.waitForTimeout(3000);
  console.log('MKV aud2 :', JSON.stringify(await state(win)));
  // audio level through the boost graph (volume 150%)
  await win.evaluate(() => window.__moa.setVolume(1.5, false));
  await win.waitForTimeout(800);
  const level = await win.evaluate(() => { const a = window.__moa.analyser; if (!a) return 'no-graph'; const d = new Float32Array(a.fftSize); a.getFloatTimeDomainData(d); return Math.max(...d.map(Math.abs)).toFixed(3); });
  console.log('audio peak via WebAudio (boost):', level);
  // 2) AVI XviD/MP3 -> SW encode
  await win.evaluate((f) => window.__moa.playFiles([f]), media('Movies/옛날 영화.avi'));
  await win.waitForTimeout(5000);
  console.log('AVI      :', JSON.stringify(await state(win)));
  // 3) HEVC MKV
  await win.evaluate((f) => window.__moa.playFiles([f]), media('Movies/HEVC 샘플.mkv'));
  await win.waitForTimeout(6000);
  console.log('HEVC     :', JSON.stringify(await state(win)));
  await win.screenshot({ path: shot('05-hevc.png') });
  // 4) portrait video layout
  await win.evaluate((f) => window.__moa.playFiles([f]), media('Movies/세로 영상.mp4'));
  await win.waitForTimeout(2500);
  console.log('portrait :', JSON.stringify(await state(win)), await win.evaluate(() => document.querySelector('#video').style.cssText));
  console.log('playlist :', await win.evaluate(() => window.__moa.P.list.map((v) => v.name).join(' / ') + ' idx=' + window.__moa.P.index));
  console.log('logs:', logs.slice(0, 15));
  await app.close();
})().catch((e) => { console.error('TEST FAILED', e); process.exit(1); });
