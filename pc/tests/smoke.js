const { launch, media, shot } = require('./launch');
(async () => {
  const { app, win, logs } = await launch();
  await win.waitForSelector('.folder', { timeout: 20000 });
  await win.waitForTimeout(800);
  await win.screenshot({ path: shot('01-folders.png') });
  const folders = await win.$$eval('.folder .f-name', (e) => e.map((x) => x.textContent));
  console.log('folders:', folders);
  await win.click('.folder:has-text("드라마 폴더")');
  await win.waitForSelector('.vcard');
  await win.waitForTimeout(4000);
  await win.screenshot({ path: shot('02-videos.png') });
  const cards = await win.$$eval('.vcard', (e) => e.map((x) => x.querySelector('.v-title').textContent + ' | ' + x.querySelector('.v-info').textContent + ' | ' + (x.querySelector('.dur') || {}).textContent));
  console.log('cards:', cards);
  // play the MP4 with the .smi sidecar
  await win.click('.vcard:has-text("드라마 1화")');
  await win.waitForTimeout(3500);
  const st = await win.evaluate(() => ({ t: document.querySelector('#video').currentTime, paused: document.querySelector('#video').paused, sw: window.__moa.P.sw, subs: window.__moa.P.subTracks.map((t) => t.file + ':' + t.name + ':' + t.cues.length), subHtml: document.querySelector('#subs').innerText, err: document.querySelector('#video').error && document.querySelector('#video').error.message, vw: document.querySelector('#video').videoWidth }));
  console.log('player state:', JSON.stringify(st));
  await win.mouse.move(640, 300); await win.mouse.move(650, 310);
  await win.waitForTimeout(300);
  await win.screenshot({ path: shot('03-player.png') });
  console.log('logs:', logs.slice(0, 20));
  await app.close();
})().catch((e) => { console.error('TEST FAILED', e); process.exit(1); });
