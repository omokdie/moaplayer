'use strict';
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const CHANNELS = new Set([
  'app:info', 'app:takePendingFiles', 'settings:get', 'settings:set', 'history:all', 'history:save', 'history:remove',
  'history:clear', 'lib:scan', 'lib:addFolder', 'lib:addFolderPath', 'lib:removeFolder', 'lib:renameFolder', 'lib:deleteFolder', 'lib:folderInfo', 'media:probe', 'media:thumb',
  'media:sidecars', 'media:siblings', 'media:readSubtitle', 'media:extractSub', 'media:keyframes', 'media:stopStream',
  'media:saveScreenshot', 'media:delete', 'media:rename', 'dialog:openFiles', 'dialog:openSubtitle',
  'win:fullscreen', 'win:isFullscreen', 'win:alwaysOnTop', 'win:setTitle', 'win:playerState', 'power:playing', 'shell:showItem',
  'update:status', 'update:check', 'update:install',
]);
const EVENTS = new Set(['open-files', 'fullscreen', 'stream-error', 'update-status', 'media-command']);

contextBridge.exposeInMainWorld('moa', {
  invoke: async (ch, ...args) => {
    if (!CHANNELS.has(ch)) throw new Error('blocked channel ' + ch);
    const r = await ipcRenderer.invoke(ch, ...args);
    if (r && typeof r === 'object' && r.__error) throw new Error(r.__error);
    return r;
  },
  on: (ch, fn) => {
    if (!EVENTS.has(ch)) return;
    ipcRenderer.on(ch, (e, ...a) => fn(...a));
  },
  pathForFile: (file) => {
    try { return webUtils.getPathForFile(file); } catch (e) { return ''; }
  },
});
