const { launch, media, shot } = require('./launch');
(async () => {
  const { app, win, logs } = await launch({ fresh: false });
  await win.waitForSelector('.folder');
  await win.click('#btn-settings'); await win.waitForTimeout(500);
  await win.screenshot({ path: shot('12-settings.png') });
  await win.keyboard.press('Escape');
  await win.click('.folder:has-text("Movies")'); await win.waitForTimeout(300);
  await win.click('#btn-view'); await win.waitForTimeout(3000);
  await win.screenshot({ path: shot('13-list-view.png') });
  // corrupt file -> error dialog
  require('fs').writeFileSync(shot('broken.mp4'), Buffer.alloc(200000, 7));
  await win.evaluate((f) => window.__moa.playFiles([f]), shot('broken.mp4'));
  await win.waitForTimeout(5000);
  await win.screenshot({ path: shot('14-error.png') });
  console.log('dialog:', await win.evaluate(() => document.querySelector('#dialog').innerText.replace(/\n/g, ' | ')));
  // resume dialog: play the MP4 that has history from the gesture test
  await win.keyboard.press('Escape'); await win.waitForTimeout(300);
  await win.evaluate((f) => window.__moa.playFiles([f]), media('드라마 폴더/드라마 1화.mp4'));
  await win.waitForTimeout(2000);
  console.log('resume:', await win.evaluate(() => document.querySelector('#dialog').classList.contains('hidden') ? 'none' : document.querySelector('#dialog').innerText.replace(/\n/g, ' | ')));
  await win.screenshot({ path: shot('15-resume.png') });
  await win.keyboard.press('Enter'); await win.waitForTimeout(1500);
  console.log('after resume pos:', await win.evaluate(() => Math.round(window.__moa.getPos())));
  console.log('logs:', logs.slice(0, 10));
  await app.close();
})().catch((e) => { console.error('TEST FAILED', e); process.exit(1); });
