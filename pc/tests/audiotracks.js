const { launch, media, shot } = require('./launch');
(async () => {
  const { app, win, logs } = await launch();
  await win.waitForSelector('.folder');
  await win.evaluate((f) => window.__moa.playFiles([f]), media('dual-audio.mp4'));
  await win.waitForTimeout(2500);
  const info = () => win.evaluate(() => { const v = document.querySelector('#video'); return { sw: window.__moa.P.sw, idx: window.__moa.P.audioIdx, tracks: v.audioTracks ? Array.from(v.audioTracks).map((t) => `${t.language}:${t.enabled}`) : 'n/a', pos: Math.round(window.__moa.getPos()) }; });
  console.log('start  :', JSON.stringify(await info()));
  const freq = async () => win.evaluate(async () => {
    window.__moa.setVolume(1.2, false); // forces the WebAudio graph
    await new Promise((r) => setTimeout(r, 600));
    const a = window.__moa.analyser; if (!a) return 'no graph';
    const d = new Float32Array(a.frequencyBinCount); a.getFloatFrequencyData(d);
    let best = 0; for (let i = 1; i < d.length; i++) if (d[i] > d[best]) best = i;
    return Math.round(best * a.context.sampleRate / a.fftSize) + 'Hz';
  });
  console.log('tone   :', await freq());
  await win.evaluate(() => window.__moa.selectAudio(1));
  await win.waitForTimeout(1500);
  console.log('after  :', JSON.stringify(await info()), 'tone', await freq());
  console.log('logs:', logs.slice(0, 5));
  await app.close();
})().catch((e) => { console.error('TEST FAILED', e); process.exit(1); });
