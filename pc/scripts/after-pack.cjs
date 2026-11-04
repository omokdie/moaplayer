// electron-builder afterPack hook (package.json "build.afterPack"): runs after the app folder is
// assembled and before the installer is made. Drops the WebGPU shader compiler (~25 MB) that a video
// player never uses — video decoding and drawing go through D3D11 (d3dcompiler_47.dll stays).
const fs = require('fs');
const path = require('path');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return;
  for (const name of ['dxcompiler.dll', 'dxil.dll']) {
    const file = path.join(context.appOutDir, name);
    if (fs.existsSync(file)) {
      const mb = (fs.statSync(file).size / 1048576).toFixed(1);
      fs.rmSync(file);
      console.log(`  • removed ${name} (${mb} MB, WebGPU only)`);
    }
  }
};
