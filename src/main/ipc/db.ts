import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { IPCResponse, Document, Tag, AppStats, DocumentCreateInput, Backlink } from '@shared/types';
import type { DatabaseService } from '../services/DatabaseService';
import type { FileService } from '../services/FileService';
import type { SearchService } from '../services/SearchService';

export function registerDbIPCHandlers(
  dbService: DatabaseService,
  fileService: FileService,
  searchService: SearchService
): void {
  typedIpcMain.handle(IPC_CHANNELS.DB.DOCUMENT_UPSERT, async (_event, filePath: string, content: string): Promise<IPCResponse<Document & { tags: string[] }>> => {
    try {
      const doc = dbService.upsertDocument(filePath, content);
      await searchService.updateIndex(doc, content);
      return { success: true, data: doc };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_UPSERT_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.DOCUMENT_GET, (_event, id: string): IPCResponse<(Document & { tags: string[] }) | null> => {
    try {
      const doc = dbService.getDocument(id);
      return { success: true, data: doc };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_GET_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.DOCUMENT_GET_BY_PATH, (_event, path: string): IPCResponse<(Document & { tags: string[] }) | null> => {
    try {
      const doc = dbService.getDocumentByPath(path);
      return { success: true, data: doc };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_GET_BY_PATH_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.DOCUMENT_LIST, (_event, options?: {
    limit?: number;
    offset?: number;
    tag?: string;
    sortBy?: 'updated_at' | 'created_at' | 'title';
    sortOrder?: 'ASC' | 'DESC';
  }): IPCResponse<(Document & { tags: string[] })[]> => {
    try {
      const docs = dbService.listDocuments(options);
      return { success: true, data: docs };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_LIST_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.DOCUMENT_DELETE, (_event, id: string): IPCResponse<void> => {
    try {
      dbService.deleteDocument(id);
      searchService.removeDocument(id);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_DELETE_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.DOCUMENT_SEARCH, (_event, keyword: string): IPCResponse<(Document & { tags: string[] })[]> => {
    try {
      const docs = dbService.searchDocuments(keyword);
      return { success: true, data: docs };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_SEARCH_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.TAG_LIST, (): IPCResponse<Tag[]> => {
    try {
      const tags = dbService.listTags();
      return { success: true, data: tags };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_TAG_LIST_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.TAG_GET_DOCUMENTS, (_event, tagName: string): IPCResponse<(Document & { tags: string[] })[]> => {
    try {
      const docs = dbService.getDocumentsByTag(tagName);
      return { success: true, data: docs };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_TAG_DOCS_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.BACKLINK_GET_TO, (_event, docId: string): IPCResponse<Array<Backlink & { fromDoc: Document }>> => {
    try {
      const backlinks = dbService.getBacklinksTo(docId);
      return { success: true, data: backlinks };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_BACKLINK_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.DB.STATS_GET, (): IPCResponse<AppStats> => {
    try {
      const stats = dbService.getStats();
      return { success: true, data: stats };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'DB_STATS_ERROR' };
    }
  });
}
