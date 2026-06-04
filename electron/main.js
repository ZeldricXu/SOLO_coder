const { app, BrowserWindow, ipcMain, dialog, protocol, session } = require('electron');
const path = require('path');
const fs = require('fs');

let mainWindow;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true
    }
  });

  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Cross-Origin-Opener-Policy': ['same-origin'],
        'Cross-Origin-Embedder-Policy': ['require-corp'],
        'Cross-Origin-Resource-Policy': ['cross-origin']
      }
    });
  });

  mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
}

protocol.registerSchemesAsPrivileged([
  {
    scheme: 'local-file',
    privileges: {
      stream: true,
      secure: true,
      standard: true,
      supportFetchAPI: true,
      corsEnabled: true
    }
  }
]);

ipcMain.handle('read-file', async (event, filePath) => {
  const content = await fs.promises.readFile(filePath);
  if (Buffer.isBuffer(content)) {
    const isText = !content.includes(0x00);
    return isText ? content.toString('utf-8') : content;
  }
  return content;
});

ipcMain.handle('write-file', async (event, { path: filePath, data }) => {
  await fs.promises.writeFile(filePath, data);
  return true;
});

ipcMain.handle('open-file-dialog', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile'],
    filters: [
      { name: 'Data Files', extensions: ['csv', 'json', 'parquet'] },
      { name: 'CSV', extensions: ['csv'] },
      { name: 'JSON', extensions: ['json'] },
      { name: 'Parquet', extensions: ['parquet'] },
      { name: 'All Files', extensions: ['*'] }
    ]
  });
  if (result.canceled) return null;
  return result.filePaths[0];
});

ipcMain.handle('save-file-dialog', async (event, { filename, data }) => {
  const result = await dialog.showSaveDialog(mainWindow, {
    defaultPath: filename
  });
  if (result.canceled) return null;
  await fs.promises.writeFile(result.filePath, data);
  return result.filePath;
});

app.whenReady().then(() => {
  protocol.handle('local-file', (request) => {
    const filePath = decodeURIComponent(request.url.replace('local-file://', ''));
    return net.fetch(`file://${filePath}`);
  });
  createWindow();
});

app.on('window-all-closed', () => {
  app.quit();
});
