import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { DatabaseService } from '../services/DatabaseService';
import type { FileService } from '../services/FileService';
import type { SearchService } from '../services/SearchService';
import type { Document } from '@shared/types';
import { generateDocId, generateHash } from '@shared/utils/markdown';
import { joinPaths, isMarkdownFile } from '@shared/utils/path';

export function registerDocumentHandlers(
  dbService: DatabaseService,
  fileService: FileService,
  searchService: SearchService
) {
  typedIpcMain.handle(
    IPC_CHANNELS.DOCUMENT.CREATE,
    async (_event, data: { title: string; content?: string; tags?: string[] }) => {
      try {
        const { title, content = '', tags = [] } = data;

        const existing = await dbService.getDocumentByTitle(title);
        if (existing) {
          return { success: false, error: '文档标题已存在' };
        }

        const filename = `${title}.md`;
        const filePath = joinPaths(fileService.getRepoPath(), filename);

        const savedContent = content || `# ${title}\n\n`;
        await fileService.writeFile(filePath, savedContent);

        const doc: Document = {
          id: generateDocId(title),
          title,
          content: savedContent,
          tags,
          filename,
          filePath,
          wordCount: content?.length || 0,
          hash: generateHash(savedContent),
          createdAt: new Date(),
          updatedAt: new Date(),
          backlinks: [],
          outline: [],
        };

        const savedDoc = await dbService.upsertDocument(filePath, savedContent);
        await searchService.updateIndex(savedDoc, savedContent);

        return { success: true, data: savedDoc };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : '创建文档失败',
        };
      }
    }
  );

  typedIpcMain.handle(IPC_CHANNELS.DOCUMENT.GET, async (_event, docId: string) => {
    try {
      const doc = await dbService.getDocument(docId);
      if (!doc) {
        return { success: false, error: '文档不存在', data: null };
      }

      try {
        const content = await fileService.readFile(doc.filePath);
        return {
          success: true,
          data: { ...doc, content },
        };
      } catch (readError) {
        return {
          success: true,
          data: doc,
        };
      }
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '获取文档失败',
        data: null,
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DOCUMENT.LIST, async () => {
    try {
      const docs = await dbService.listDocuments();
      const sortedDocs = docs.sort(
        (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
      );
      return { success: true, data: sortedDocs };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '获取文档列表失败',
        data: [],
      };
    }
  });

  typedIpcMain.handle(
    IPC_CHANNELS.DOCUMENT.UPDATE,
    async (_event, docId: string, updates: Partial<Document>) => {
      try {
        const existingDoc = await dbService.getDocument(docId);
        if (!existingDoc) {
          return { success: false, error: '文档不存在' };
        }

        if (updates.content !== undefined) {
          await fileService.writeFile(existingDoc.filePath, updates.content);
          const savedDoc = await dbService.upsertDocument(existingDoc.filePath, updates.content);
          await searchService.updateIndex(savedDoc, updates.content);
          return { success: true, data: savedDoc };
        }

        const updatedDoc = { ...existingDoc, ...updates, updatedAt: new Date() };
        await dbService.updateDocument(updatedDoc);
        return { success: true, data: updatedDoc };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : '更新文档失败',
        };
      }
    }
  );

  typedIpcMain.handle(IPC_CHANNELS.DOCUMENT.DELETE, async (_event, docId: string) => {
    try {
      const doc = await dbService.getDocument(docId);
      if (!doc) {
        return { success: false, error: '文档不存在' };
      }

      try {
        await fileService.deleteFile(doc.filePath);
      } catch (fileError) {
        console.warn('删除文件失败，继续删除数据库记录:', fileError);
      }

      await dbService.deleteDocument(docId);
      await searchService.removeDocument(docId);

      return { success: true, data: true };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '删除文档失败',
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.TAG.LIST, async () => {
    try {
      const tags = await dbService.listTags();
      return { success: true, data: tags };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '获取标签列表失败',
        data: [],
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.SETTINGS.GET, async () => {
    try {
      const stored = dbService.getSetting('settings');
      const settings = stored ? JSON.parse(stored) : {};
      return { success: true, data: settings };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '获取设置失败',
        data: null,
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.SETTINGS.SET, async (_event, settings: any) => {
    try {
      dbService.setSetting('settings', JSON.stringify(settings));
      return { success: true, data: undefined };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '保存设置失败',
      };
    }
  });
}
