import { app, BrowserWindow, Menu, ipcMain } from 'electron';
import path from 'path';
import { DatabaseService } from './services/DatabaseService';
import { FileService, FileChangeEvent } from './services/FileService';
import { GitService } from './services/GitService';
import { SearchService } from './services/SearchService';
import { TemplateService } from './services/TemplateService';
import { ImportService } from './services/ImportService';
import { PluginService } from './services/PluginService';
import { registerIPCHandlers } from './ipc';
import { registerTemplateHandlers } from './ipc/template';
import { registerImportHandlers } from './ipc/import';
import { registerPluginHandlers } from './ipc/plugin';
import { registerExportHandlers } from './ipc/export';
import { getDefaultRepoPath, joinPaths } from '@shared/utils/path';
import type { AppSettings } from '@shared/types';

let mainWindow: BrowserWindow | null = null;
let dbService: DatabaseService | null = null;
let fileService: FileService | null = null;
let gitService: GitService | null = null;
let searchService: SearchService | null = null;
let templateService: TemplateService | null = null;
let importService: ImportService | null = null;
let pluginService: PluginService | null = null;

const isDev = process.env.NODE_ENV === 'development';
const repoPath = getDefaultRepoPath();
const dbPath = joinPaths(repoPath, '.knowledgeforge', 'app.db');

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1024,
    minHeight: 768,
    backgroundColor: '#0F172A',
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 16, y: 16 },
    webPreferences: {
      preload: path.join(__dirname, '../preload/preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
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

function createMenu(): void {
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: app.name,
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    {
      label: '文件',
      submenu: [
        {
          label: '新建文档',
          accelerator: 'CmdOrCtrl+N',
          click: () => mainWindow?.webContents.send('menu:new-document'),
        },
        {
          label: '新建文件夹',
          accelerator: 'CmdOrCtrl+Shift+N',
          click: () => mainWindow?.webContents.send('menu:new-folder'),
        },
        { type: 'separator' },
        {
          label: '打开',
          accelerator: 'CmdOrCtrl+O',
          click: () => mainWindow?.webContents.send('menu:open'),
        },
        {
          label: '保存',
          accelerator: 'CmdOrCtrl+S',
          click: () => mainWindow?.webContents.send('menu:save'),
        },
        { type: 'separator' },
        { role: 'close' },
      ],
    },
    {
      label: '编辑',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
        { type: 'separator' },
        {
          label: '搜索',
          accelerator: 'CmdOrCtrl+F',
          click: () => mainWindow?.webContents.send('menu:search'),
        },
      ],
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    {
      label: 'Git',
      submenu: [
        {
          label: '提交',
          accelerator: 'CmdOrCtrl+Shift+S',
          click: () => mainWindow?.webContents.send('menu:git-commit'),
        },
        {
          label: '推送',
          accelerator: 'CmdOrCtrl+Shift+P',
          click: () => mainWindow?.webContents.send('menu:git-push'),
        },
        {
          label: '拉取',
          accelerator: 'CmdOrCtrl+Shift+U',
          click: () => mainWindow?.webContents.send('menu:git-pull'),
        },
      ],
    },
    {
      label: '窗口',
      submenu: [
        { role: 'minimize' },
        { role: 'zoom' },
        { type: 'separator' },
        { role: 'front' },
      ],
    },
    {
      label: '帮助',
      submenu: [
        { role: 'help' },
        {
          label: '文档',
          click: () => mainWindow?.webContents.send('menu:help'),
        },
      ],
    },
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

async function initializeServices(): Promise<void> {
  const fs = require('fs');
  const dbDir = path.dirname(dbPath);
  if (!fs.existsSync(dbDir)) {
    fs.mkdirSync(dbDir, { recursive: true });
  }
  if (!fs.existsSync(repoPath)) {
    fs.mkdirSync(repoPath, { recursive: true });
  }

  dbService = new DatabaseService(dbPath, repoPath);
  fileService = new FileService(repoPath);
  templateService = new TemplateService(repoPath);
  importService = new ImportService(repoPath);
  pluginService = new PluginService(repoPath);
  
  const settingsStr = dbService.getSetting('settings');
  let settings: Partial<AppSettings> = {};
  if (settingsStr) {
    try {
      settings = JSON.parse(settingsStr);
    } catch {}
  }
  
  gitService = new GitService(repoPath, settings);
  searchService = new SearchService(dbService, fileService);

  registerIPCHandlers(dbService, fileService, gitService, searchService);
  registerTemplateHandlers(templateService, dbService);
  registerImportHandlers(importService, dbService, fileService);
  registerPluginHandlers(pluginService);
  registerExportHandlers(dbService, repoPath);

  try {
    await pluginService.initialize();
  } catch (e) {
    console.error('Failed to initialize plugin service:', e);
  }

  try {
    await searchService.loadFromDB();
  } catch (e) {
    console.error('Failed to load search index:', e);
  }

  fileService.on('change', async (event: FileChangeEvent) => {
    if (event.type === 'add' || event.type === 'change') {
      try {
        const content = await fileService!.readFile(event.path);
        const doc = dbService!.upsertDocument(event.path, content);
        await searchService!.updateIndex(doc, content);
        
        mainWindow?.webContents.send('file:changed', event);
        
        if (gitService?.getConfig().autoCommit) {
          await gitService.autoCommit([event.path]);
        }
      } catch (e) {
        console.error('Error handling file change:', e);
      }
    } else if (event.type === 'delete') {
      const doc = dbService!.getDocumentByPath(event.path);
      if (doc) {
        dbService!.deleteDocument(doc.id);
        await searchService!.removeDocument(doc.id);
      }
      mainWindow?.webContents.send('file:changed', event);
    }
  });

  await fileService.startWatcher();
}

async function scanExistingFiles(): Promise<void> {
  if (!fileService || !dbService || !searchService) return;

  try {
    const files = await fileService.listFiles();
    for (const filePath of files) {
      try {
        const existing = dbService.getDocumentByPath(filePath);
        const content = await fileService.readFile(filePath);
        
        if (!existing) {
          const doc = dbService.upsertDocument(filePath, content);
          await searchService.updateIndex(doc, content);
        } else {
          const newHash = require('@shared/utils/markdown').generateHash(content);
          if (newHash !== existing.hash) {
            const doc = dbService.upsertDocument(filePath, content);
            await searchService.updateIndex(doc, content);
          }
        }
      } catch (e) {
        console.error(`Error processing ${filePath}:`, e);
      }
    }
  } catch (e) {
    console.error('Error scanning files:', e);
  }
}

app.whenReady().then(async () => {
  try {
    await initializeServices();
    createMenu();
    createWindow();
    await scanExistingFiles();

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
      }
    });
  } catch (e) {
    console.error('Failed to initialize app:', e);
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', async () => {
  await fileService?.stopWatcher();
  dbService?.close();
});

app.on('web-contents-created', (_event, contents) => {
  contents.on('will-navigate', (e, navigationUrl) => {
    const parsedUrl = new URL(navigationUrl);
    if (parsedUrl.origin !== 'file://' && parsedUrl.origin !== 'http://localhost:5173') {
      e.preventDefault();
    }
  });

  contents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http:') || url.startsWith('https:')) {
      require('electron').shell.openExternal(url);
    }
    return { action: 'deny' };
  });
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('Unhandled Rejection at:', promise, 'reason:', reason);
});

process.on('uncaughtException', (error) => {
  console.error('Uncaught Exception:', error);
});
