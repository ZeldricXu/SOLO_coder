const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  readFile: (path) => ipcRenderer.invoke('read-file', path),
  writeFile: (path, data) => ipcRenderer.invoke('write-file', { path, data }),
  openFileDialog: () => ipcRenderer.invoke('open-file-dialog'),
  saveFileDialog: (filename, data) => ipcRenderer.invoke('save-file-dialog', { filename, data }),
  isElectron: () => true
});
