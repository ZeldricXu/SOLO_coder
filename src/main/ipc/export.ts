import { dialog } from 'electron';
import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { ExportOptions } from '@main/services/ExportService';
import { ExportService } from '@main/services/ExportService';
import type { Document } from '@shared/types';
import { DatabaseService } from '@main/services/DatabaseService';

export function registerExportHandlers(dbService: DatabaseService, repoPath: string): void {
  typedIpcMain.handle(
    IPC_CHANNELS.EXPORT.STATIC_SITE,
    async (_event, options: ExportOptions) => {
      try {
        const { success, data: documents, error } = await dbService.listDocuments();
        if (!success) {
          return { success: false, error: error || '获取文档列表失败' };
        }

        let actualOutputPath = options.outputPath;
        if (!actualOutputPath) {
          const result = await dialog.showOpenDialog({
            title: '选择导出目录',
            properties: ['openDirectory', 'createDirectory'],
          });
          if (result.canceled || !result.filePaths[0]) {
            return { success: false, error: '用户取消导出' };
          }
          actualOutputPath = result.filePaths[0];
        }

        const exportService = new ExportService(repoPath, (progress) => {
          _event.sender.send('export:progress', progress);
        });

        await exportService.exportStaticSite(
          documents as Document[],
          {
            ...options,
            outputPath: actualOutputPath,
          }
        );

        return { success: true, data: { outputPath: actualOutputPath } };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : '导出失败',
        };
      }
    }
  );

  typedIpcMain.handle(
    IPC_CHANNELS.EXPORT.MARKDOWN,
    async (_event, docId: string, outputPath?: string) => {
      try {
        const { success, data: doc, error } = await dbService.getDocument(docId);
        if (!success || !doc) {
          return { success: false, error: error || '文档不存在' };
        }

        const document = doc as Document;
        let actualOutputPath = outputPath;

        if (!actualOutputPath) {
          const result = await dialog.showSaveDialog({
            title: '导出 Markdown',
            defaultPath: `${document.title}.md`,
            filters: [{ name: 'Markdown 文件', extensions: ['md'] }],
          });
          if (result.canceled || !result.filePath) {
            return { success: false, error: '用户取消导出' };
          }
          actualOutputPath = result.filePath;
        }

        const fs = await import('fs/promises');
        await fs.writeFile(actualOutputPath, document.content || '', 'utf-8');

        return { success: true, data: { outputPath: actualOutputPath } };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : '导出失败',
        };
      }
    }
  );
}
