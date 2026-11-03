const { launch, media, shot } = require('./launch');
const st = (win) => win.evaluate(() => ({ pos: Math.round(window.__moa.getPos()), vol: window.__moa.P.volume, bright: window.__moa.P.brightness,
  rate: document.querySelector('#video').playbackRate, zoom: window.__moa.P.zoom, hud: document.querySelector('#hud').classList.contains('hidden') ? '' : document.querySelector('#hud').innerText.replace(/\n/g, ' '),
  ripple: document.querySelector('#seek-right').classList.contains('hidden') ? '' : document.querySelector('#seek-right').innerText, ctr: !document.querySelector('#controls').classList.contains('off'),
  badge: document.querySelector('#speed-badge').classList.contains('hidden') ? '' : document.querySelector('#speed-badge').innerText, locked: window.__moa.P.locked, catchup: !!window.__moa.P.catchup }));
(async () => {
  const { app, win, logs } = await launch();
  await win.waitForSelector('.folder');
  // exact seek in SW copy mode (MKV/AC3): ask for 30s
  await win.evaluate((f) => window.__moa.playFiles([f]), media('드라마 폴더/드라마 2화 (AC3 멀티오디오).mkv'));
  await win.waitForTimeout(3500);
  await win.evaluate(() => window.__moa.seekTo(30000));
  await win.waitForTimeout(400);
  console.log('during catch-up:', JSON.stringify(await st(win)));
  await win.waitForTimeout(3500);
  const real = await win.evaluate(() => Math.round(window.__moa.P.swOffset + document.querySelector('#video').currentTime * 1000));
  console.log('after seek30 (copy mode): pos', (await st(win)).pos, 'real', real, 'offset', await win.evaluate(() => window.__moa.P.swOffset));
  // now the MP4 (HW) for gestures
  await win.evaluate((f) => window.__moa.playFiles([f]), media('드라마 폴더/드라마 1화.mp4'));
  await win.waitForTimeout(2500);
  const m = win.mouse;
  // vertical drag on the right half -> volume
  await m.move(1000, 500); await m.down(); for (let y = 500; y >= 300; y -= 20) await m.move(1000, y); 
  console.log('volume drag :', JSON.stringify(await st(win)));
  await win.screenshot({ path: shot('06-volume-hud.png') });
  await m.up(); await win.waitForTimeout(900);
  // vertical drag on the left half -> brightness down
  await m.move(250, 300); await m.down(); for (let y = 300; y <= 480; y += 20) await m.move(250, y); 
  console.log('bright drag :', JSON.stringify(await st(win)));
  await m.up(); await win.waitForTimeout(900);
  // horizontal drag -> seek
  const before = (await st(win)).pos;
  await m.move(400, 380); await m.down(); for (let x = 400; x <= 800; x += 25) await m.move(x, 380);
  const mid = await st(win);
  console.log('seek drag   :', JSON.stringify(mid));
  await win.screenshot({ path: shot('07-seek-hud.png') });
  await m.up(); await win.waitForTimeout(1200);
  console.log('after seek  : before', before, 'now', (await st(win)).pos);
  // double click right -> +10s, triple -> +20s (chain)
  const p0 = (await st(win)).pos;
  await m.click(1100, 380); await win.waitForTimeout(90); await m.click(1100, 380); await win.waitForTimeout(150); await m.click(1100, 380);
  await win.waitForTimeout(200);
  const dt = await st(win);
  console.log('double+tap  : from', p0, '->', dt.pos, 'ripple', dt.ripple);
  await win.screenshot({ path: shot('08-double-tap.png') });
  await win.waitForTimeout(1200);
  // long press -> 2x
  await m.move(640, 380); await m.down(); await win.waitForTimeout(900);
  console.log('long press  :', JSON.stringify(await st(win)));
  await m.up(); await win.waitForTimeout(300);
  console.log('released    :', JSON.stringify(await st(win)));
  // ctrl + wheel -> zoom, wheel -> volume
  await m.move(640, 380); await win.keyboard.down('Control'); await m.wheel(0, -100); await m.wheel(0, -100); await win.keyboard.up('Control');
  console.log('ctrl+wheel  :', JSON.stringify(await st(win)));
  await m.wheel(0, 100);
  console.log('wheel down  :', JSON.stringify(await st(win)));
  // keyboard: space, right arrow, ]
  await win.keyboard.press('Space'); await win.waitForTimeout(200);
  console.log('space paused:', await win.evaluate(() => document.querySelector('#video').paused));
  await win.keyboard.press('Space'); await win.keyboard.press(']'); await win.keyboard.press(']');
  console.log('speed keys  :', JSON.stringify(await st(win)));
  await win.keyboard.press('\\');
  // lock
  await win.keyboard.press('l'); await win.waitForTimeout(200);
  await m.click(640, 380); await win.waitForTimeout(400);
  console.log('locked      :', JSON.stringify(await st(win)));
  await win.screenshot({ path: shot('09-locked.png') });
  await win.click('#p-unlock'); await win.waitForTimeout(300);
  // sheets
  await m.move(600, 300); await m.move(610, 310); await win.waitForTimeout(300);
  await win.click('#p-sub'); await win.waitForTimeout(500);
  await win.screenshot({ path: shot('10-sub-sheet.png') });
  await win.keyboard.press('Escape'); await win.waitForTimeout(200);
  await m.move(600, 300); await m.move(610, 310);
  await win.click('#p-more'); await win.waitForTimeout(300);
  await win.screenshot({ path: shot('11-more-menu.png') });
  await win.keyboard.press('Escape');
  // screenshot key
  await win.keyboard.press('c'); await win.waitForTimeout(1200);
  console.log('toast       :', await win.evaluate(() => document.querySelector('#toast').innerText));
  console.log('logs:', logs.slice(0, 15));
  await app.close();
})().catch((e) => { console.error('TEST FAILED', e); process.exit(1); });
