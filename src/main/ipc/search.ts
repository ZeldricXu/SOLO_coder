import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { IPCResponse, SearchResult } from '@shared/types';
import type { SearchService } from '../services/SearchService';

export function registerSearchIPCHandlers(searchService: SearchService): void {
  typedIpcMain.handle(IPC_CHANNELS.SEARCH.QUERY, async (_event, query: string, options?: {
    tags?: string[];
    sortBy?: 'relevance' | 'date';
    limit?: number;
  }): Promise<IPCResponse<SearchResult[]>> => {
    try {
      const results = await searchService.search(query, options);
      return { success: true, data: results };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'SEARCH_QUERY_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.SEARCH.REINDEX, async (): Promise<IPCResponse<void>> => {
    try {
      await searchService.reindexAll();
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'SEARCH_REINDEX_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.SEARCH.INDEX_DOCUMENT, async (_event, doc: any, content: string): Promise<IPCResponse<void>> => {
    try {
      await searchService.updateIndex(doc, content);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'SEARCH_INDEX_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.SEARCH.REMOVE_DOCUMENT, async (_event, docId: string): Promise<IPCResponse<void>> => {
    try {
      await searchService.removeDocument(docId);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'SEARCH_REMOVE_ERROR' };
    }
  });
}
