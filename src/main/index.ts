import { app, BrowserWindow, ipcMain, dialog } from 'electron';
import path from 'path';
import { getDatabase, closeDatabase } from './db';
import { NoteService } from './db/noteService';
import { LinkService } from './db/linkService';
import { SettingsService } from './db/settingsService';
import { VaultService } from './services/vaultService';
import { SearchService } from './services/searchService';
import { ExportService } from './services/exportService';
import { LinkRepairService } from './services/linkRepairService';
import { AttachmentService } from './services/attachmentService';
import fs from 'fs';

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
  
  registerIpcHandlers();
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

function registerIpcHandlers() {
  ipcMain.handle('notes:getAll', () => {
    return NoteService.getAll();
  });

  ipcMain.handle('notes:getById', (_event, id: string) => {
    return NoteService.getById(id);
  });

  ipcMain.handle('notes:getByPath', (_event, pathStr: string) => {
    return NoteService.getByPath(pathStr);
  });

  ipcMain.handle('notes:create', (_event, note: any) => {
    return NoteService.create(note);
  });

  ipcMain.handle('notes:update', (_event, id: string, updates: any) => {
    return NoteService.update(id, updates);
  });

  ipcMain.handle('notes:delete', (_event, id: string) => {
    return NoteService.delete(id);
  });

  ipcMain.handle('notes:saveContent', (_event, id: string, content: string) => {
    const note = NoteService.getById(id);
    if (!note) return false;
    
    const result = NoteService.saveContent(id, content);
    if (result) {
      const updated = NoteService.getById(id)!;
      VaultService.extractAndSaveLinks(updated);
      SearchService.updateNote(updated);
      
      const vaultPath = VaultService.getVaultPath();
      if (vaultPath) {
        const fullPath = path.join(vaultPath, note.path);
        let fileContent = '';
        if (note.frontmatter && Object.keys(note.frontmatter).length > 0) {
          fileContent += '---\n';
          for (const [key, value] of Object.entries(note.frontmatter)) {
            fileContent += `${key}: ${value}\n`;
          }
          fileContent += '---\n\n';
        }
        fileContent += content;
        fs.writeFileSync(fullPath, fileContent, 'utf-8');
      }
    }
    return result;
  });

  ipcMain.handle('notes:findSimilarNotes', (_event, title: string, threshold?: number) => {
    return LinkRepairService.findSimilarNotes(title, threshold);
  });

  ipcMain.handle('notes:updateLinkTarget', (_event, sourceNoteId: string, oldTarget: string, newTargetId: string) => {
    return LinkRepairService.updateLinkTarget(sourceNoteId, oldTarget, newTargetId);
  });

  ipcMain.handle('notes:scanBrokenLinks', (_event, noteId?: string) => {
    return LinkRepairService.scanBrokenLinks(noteId);
  });

  ipcMain.handle('links:getAll', () => {
    return LinkService.getAll();
  });

  ipcMain.handle('links:getBacklinks', (_event, noteId: string) => {
    return LinkService.getBacklinks(noteId);
  });

  ipcMain.handle('links:getForwardLinks', (_event, noteId: string) => {
    return LinkService.getForwardLinks(noteId);
  });

  ipcMain.handle('links:migrateBacklinks', (_event, oldNoteId: string, newNoteId: string) => {
    return LinkRepairService.migrateBacklinks(oldNoteId, newNoteId);
  });

  ipcMain.handle('graph:getGraphData', () => {
    return LinkService.getGraphData();
  });

  ipcMain.handle('graph:getFocusGraphData', (_event, options: any) => {
    return LinkService.getFocusGraphData(options);
  });

  ipcMain.handle('search:query', (_event, q: string, options?: any) => {
    return SearchService.query(q, options);
  });

  ipcMain.handle('vault:setPath', async (_event, vaultPath: string) => {
    const success = VaultService.setVaultPath(vaultPath);
    if (success) {
      SettingsService.setVaultPath(vaultPath);
      AttachmentService.init(vaultPath);
      setTimeout(() => {
        SearchService.rebuildIndex(NoteService.getAll());
      }, 500);
    }
    return success;
  });

  ipcMain.handle('vault:getPath', () => {
    return VaultService.getVaultPath() || SettingsService.getVaultPath();
  });

  ipcMain.handle('vault:rescan', async () => {
    VaultService.rescan();
    SearchService.rebuildIndex(NoteService.getAll());
  });

  ipcMain.handle('attachments:list', () => {
    return AttachmentService.list();
  });

  ipcMain.handle('attachments:upload', async (_event, fileData: any, targetDir?: string) => {
    if (typeof fileData === 'string') {
      return AttachmentService.upload(fileData, targetDir);
    } else if (fileData && fileData.name && fileData.data) {
      const buffer = Buffer.from(fileData.data);
      return AttachmentService.uploadFromData(fileData.name, buffer, targetDir);
    }
    throw new Error('Invalid upload data');
  });

  ipcMain.handle('attachments:delete', (_event, attachmentId: string) => {
    return AttachmentService.delete(attachmentId);
  });

  ipcMain.handle('attachments:rename', (_event, attachmentId: string, newName: string) => {
    return AttachmentService.rename(attachmentId, newName);
  });

  ipcMain.handle('attachments:getThumbnail', (_event, attachmentId: string) => {
    return AttachmentService.getThumbnail(attachmentId);
  });

  ipcMain.handle('attachments:getAssetsPath', () => {
    return AttachmentService.getAssetsPath();
  });

  ipcMain.handle('settings:get', () => {
    return SettingsService.get();
  });

  ipcMain.handle('settings:update', (_event, updates: any) => {
    return SettingsService.update(updates);
  });

  ipcMain.handle('export:exportNote', async (_event, id: string, format: 'txt' | 'html' | 'pdf') => {
    return ExportService.exportNote(id, format);
  });

  ipcMain.handle('export:exportDomain', async (_event, noteIds: string[], format: 'markdown') => {
    return ExportService.exportDomain(noteIds, format);
  });

  ipcMain.handle('export:exportGraphPNG', async (_event, svgData: string) => {
    return ExportService.exportGraphPNG(svgData);
  });

  ipcMain.handle('dialog:openFile', async (_event, options?: any) => {
    const result = await dialog.showOpenDialog(mainWindow!, {
      ...options,
      properties: ['openFile'],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  ipcMain.handle('dialog:openDirectory', async (_event, options?: any) => {
    const result = await dialog.showOpenDialog(mainWindow!, {
      ...options,
      properties: ['openDirectory'],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  ipcMain.handle('dialog:saveFile', async (_event, options?: any) => {
    const result = await dialog.showSaveDialog(mainWindow!, options);
    return result.canceled ? null : result.filePath;
  });
}
