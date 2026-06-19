import { app, BrowserWindow } from 'electron';
import path from 'path';
import fs from 'fs';
import { getDatabase, closeDatabase } from './db';
import { NoteService } from './db/noteService';
import { SettingsService } from './db/settingsService';
import { VaultService } from './services/vaultService';
import { SearchService } from './services/searchService';
import { AttachmentService } from './services/attachmentService';
import { registerAllIpcHandlers } from './ipc';

let mainWindow: BrowserWindow | null = null;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 800,
    minHeight: 600,
    title: '智能笔记与知识图谱',
    backgroundColor: '#1a1a2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

app.whenReady().then(() => {
  getDatabase();

  const settings = SettingsService.get();

  if (settings.vaultPath && fs.existsSync(settings.vaultPath)) {
    VaultService.init(settings.vaultPath);
    AttachmentService.init(settings.vaultPath);
    SearchService.init(NoteService.getAll());
  }

  registerAllIpcHandlers({ getWindow: () => mainWindow });
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  closeDatabase();
  VaultService.close();
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
