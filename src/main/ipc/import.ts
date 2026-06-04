import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { ImportService } from '../services/ImportService';
import type { DatabaseService } from '../services/DatabaseService';
import type { FileService } from '../services/FileService';

export function registerImportHandlers(
  importService: ImportService,
  dbService: DatabaseService,
  fileService: FileService
) {
  typedIpcMain.handle(
    IPC_CHANNELS.IMPORT.FROM_ZIP,
    async (_event, zipPath: string, source: 'notion' | 'yuque' | 'markdown', options?: any) => {
      try {
        const result = await importService.importFromZip(zipPath, source, options);

        if (result.success && result.documents.length > 0) {
          for (const doc of result.documents) {
            try {
              await dbService.upsertDocument(doc);
            } catch (error) {
              console.error(`索引文档失败 ${doc.title}:`, error);
            }
          }
          await fileService.scanFiles();
        }

        return { success: result.success, data: result };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : 'ZIP导入失败',
        };
      }
    }
  );

  typedIpcMain.handle(
    IPC_CHANNELS.IMPORT.FROM_DIR,
    async (_event, dirPath: string, options?: any) => {
      try {
        const result = await importService.importFromDirectory(dirPath, options);

        if (result.success && result.documents.length > 0) {
          for (const doc of result.documents) {
            try {
              await dbService.upsertDocument(doc);
            } catch (error) {
              console.error(`索引文档失败 ${doc.title}:`, error);
            }
          }
          await fileService.scanFiles();
        }

        return { success: result.success, data: result };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : '目录导入失败',
        };
      }
    }
  );

  typedIpcMain.handle(IPC_CHANNELS.IMPORT.SELECT_FILE, async () => {
    try {
      const { dialog } = require('electron');
      const result = await dialog.showOpenDialog({
        properties: ['openFile'],
        filters: [
          { name: 'ZIP 归档', extensions: ['zip'] },
          { name: 'Markdown 文件', extensions: ['md', 'markdown'] },
          { name: '所有文件', extensions: ['*'] },
        ],
      });

      if (result.canceled || result.filePaths.length === 0) {
        return { success: false, data: null };
      }

      return { success: true, data: result.filePaths[0] };
    } catch (error) {
      return { success: false, error: error instanceof Error ? error.message : '选择文件失败' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.IMPORT.SELECT_DIR, async () => {
    try {
      const { dialog } = require('electron');
      const result = await dialog.showOpenDialog({
        properties: ['openDirectory'],
      });

      if (result.canceled || result.filePaths.length === 0) {
        return { success: false, data: null };
      }

      return { success: true, data: result.filePaths[0] };
    } catch (error) {
      return { success: false, error: error instanceof Error ? error.message : '选择目录失败' };
    }
  });
}
