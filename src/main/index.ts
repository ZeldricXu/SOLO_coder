import { app, BrowserWindow, ipcMain, screen, dialog } from 'electron';
import path from 'path';
import { setupIPCHandlers } from './ipc';
import { DatabaseService } from './services/database';
import { SearchEngineService } from './services/search-engine';
import { SyncService } from './services/sync';
import { AIService } from './services/ai';
import { SecureStorageService } from './services/secure-storage';

let mainWindow: BrowserWindow | null = null;

const createWindow = () => {
  const { width, height } = screen.getPrimaryDisplay().workAreaSize;

  mainWindow = new BrowserWindow({
    width: Math.min(width, 1400),
    height: Math.min(height, 900),
    minWidth: 800,
    minHeight: 600,
    frame: false,
    titleBarStyle: 'hiddenInset',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js'),
    },
    show: false,
    backgroundColor: '#ffffff',
  });

  mainWindow.once('ready-to-show', () => {
    mainWindow?.show();
  });

  if (process.env.NODE_ENV === 'development') {
    mainWindow.loadURL('http://localhost:3000');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
};

const initializeServices = async () => {
  try {
    const dbPath = path.join(app.getPath('userData'), 'noteflow.db');
    const dbService = DatabaseService.getInstance(dbPath);
    dbService.initialize();

    const secureStorage = SecureStorageService.getInstance();
    secureStorage.initialize(dbService);

    const searchService = SearchEngineService.getInstance();
    searchService.initialize(dbService);

    const syncService = SyncService.getInstance();
    await syncService.initialize(dbService);

    const aiService = AIService.getInstance();
    await aiService.initialize();

    setupIPCHandlers(dbService, searchService, syncService, aiService, secureStorage);

    console.log('All services initialized successfully');
    console.log(`Secure Storage Encryption: ${secureStorage.isEncryptionAvailable() ? 'Available' : 'Not Available (stored plain text)'}`);
  } catch (error) {
    console.error('Failed to initialize services:', error);
    dialog.showErrorBox(
      '初始化错误',
      `无法初始化应用服务: ${(error as Error).message}`
    );
  }
};

app.whenReady().then(async () => {
  await initializeServices();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', async () => {
  const syncService = SyncService.getInstance();
  syncService.stopAutoSync();

  const dbService = DatabaseService.getInstance();
  if (dbService) {
    dbService.close();
  }
});

ipcMain.handle('app:quit', () => {
  app.quit();
});

ipcMain.handle('app:minimize', () => {
  mainWindow?.minimize();
});

ipcMain.handle('app:maximize', () => {
  if (mainWindow?.isMaximized()) {
    mainWindow.unmaximize();
  } else {
    mainWindow?.maximize();
  }
});

ipcMain.handle('window:getBounds', () => {
  return mainWindow?.getBounds() || null;
});

ipcMain.handle('window:resize', (_, width: number, height: number) => {
  mainWindow?.setSize(width, height);
});
