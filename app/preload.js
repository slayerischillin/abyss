const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('abyssWin', {
  min: () => ipcRenderer.send('win', 'min'),
  max: () => ipcRenderer.send('win', 'max'),
  close: () => ipcRenderer.send('win', 'close'),
  onState: fn => ipcRenderer.on('win-state', (_, s) => fn(s)),
  upd: {
    get: () => ipcRenderer.invoke('upd-get'),
    set: f => ipcRenderer.invoke('upd-set', f),
    check: () => ipcRenderer.send('upd-check'),
    apply: () => ipcRenderer.send('upd-apply'),
    reset: () => ipcRenderer.send('upd-reset'),
    on: fn => ipcRenderer.on('update', (_, s) => fn(s))
  }
});
