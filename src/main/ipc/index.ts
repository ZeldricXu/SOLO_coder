import { ipcMain, IpcMainInvokeEvent } from 'electron';
import { IPC_CHANNELS, IPCNoteCreateParams, IPCNoteUpdateParams, IPCSearchParams, IPCSyncConfig, IPCAIConfig, IPCConflictResolution } from '../../shared/ipc-channels';
import { Note, Folder, Tag, SearchResult, AppSettings, SyncConfig, AIConfig, IPCResponse } from '../../shared/types';
import { DatabaseService } from '../services/database';
import { SearchEngineService } from '../services/search-engine';
import { SyncService, ConflictResolution } from '../services/sync';
import { AIService } from '../services/ai';
import { SecureStorageService } from '../services/secure-storage';

type HandlerFunction = (event: IpcMainInvokeEvent, ...args: unknown[]) => Promise<unknown> | unknown;

function wrapHandler(handler: HandlerFunction): HandlerFunction {
  return async (event: IpcMainInvokeEvent, ...args: unknown[]) => {
    try {
      const result = await handler(event, ...args);
      return { success: true, data: result };
    } catch (error) {
      console.error('IPC Handler error:', error);
      return { success: false, error: (error as Error).message };
    }
  };
}

export function setupIPCHandlers(
  dbService: DatabaseService,
  searchService: SearchEngineService,
  syncService: SyncService,
  aiService: AIService,
  secureStorage: SecureStorageService
): void {

  ipcMain.handle(IPC_CHANNELS.NOTE_CREATE, wrapHandler((_event, params: IPCNoteCreateParams) => {
    return dbService.createNote({
      title: params.title,
      content: params.content,
      content_type: params.content_type,
      folder_id: params.folder_id || null,
      tags: params.tags || [],
      word_count: params.content ? countWords(params.content) : 0,
      ai_summary: null,
      sync_status: 'pending',
    });
  }));

  ipcMain.handle(IPC_CHANNELS.NOTE_UPDATE, wrapHandler((_event, params: IPCNoteUpdateParams) => {
    const updates: Partial<Note> = {};
    
    if (params.title !== undefined) updates.title = params.title;
    if (params.content !== undefined) {
      updates.content = params.content;
      updates.word_count = countWords(params.content);
    }
    if (params.content_type !== undefined) updates.content_type = params.content_type;
    if (params.folder_id !== undefined) updates.folder_id = params.folder_id;
    if (params.tags !== undefined) updates.tags = params.tags;

    return dbService.updateNote(params.note_id, updates);
  }));

  ipcMain.handle(IPC_CHANNELS.NOTE_DELETE, wrapHandler((_event, noteId: string) => {
    return dbService.deleteNote(noteId);
  }));

  ipcMain.handle(IPC_CHANNELS.NOTE_GET, wrapHandler((_event, noteId: string) => {
    return dbService.getNoteById(noteId);
  }));

  ipcMain.handle(IPC_CHANNELS.NOTE_LIST, wrapHandler((_event, folderId?: string, limit?: number, offset?: number) => {
    return dbService.getNotes(folderId, limit, offset);
  }));

  ipcMain.handle(IPC_CHANNELS.NOTE_COUNT, wrapHandler((_event, folderId?: string) => {
    return dbService.getNotesCount(folderId);
  }));

  ipcMain.handle(IPC_CHANNELS.FOLDER_CREATE, wrapHandler((_event, params: { name: string; parent_id?: string; order_index?: number }) => {
    return dbService.createFolder({
      name: params.name,
      parent_id: params.parent_id || null,
      order_index: params.order_index || 0,
    });
  }));

  ipcMain.handle(IPC_CHANNELS.FOLDER_UPDATE, wrapHandler((_event, folderId: string, updates: Partial<Folder>) => {
    return dbService.updateFolder(folderId, updates);
  }));

  ipcMain.handle(IPC_CHANNELS.FOLDER_DELETE, wrapHandler((_event, folderId: string) => {
    return dbService.deleteFolder(folderId);
  }));

  ipcMain.handle(IPC_CHANNELS.FOLDER_GET, wrapHandler((_event, folderId: string) => {
    return dbService.getFolderById(folderId);
  }));

  ipcMain.handle(IPC_CHANNELS.FOLDER_LIST, wrapHandler((_event, parentId?: string) => {
    return dbService.getFolders(parentId);
  }));

  ipcMain.handle(IPC_CHANNELS.TAG_CREATE, wrapHandler((_event, params: { name: string; color?: string }) => {
    return dbService.createTag({
      name: params.name,
      color: params.color || '#3b82f6',
    });
  }));

  ipcMain.handle(IPC_CHANNELS.TAG_UPDATE, wrapHandler((_event, tagId: string, updates: Partial<Tag>) => {
    return dbService.updateTag(tagId, updates);
  }));

  ipcMain.handle(IPC_CHANNELS.TAG_DELETE, wrapHandler((_event, tagId: string) => {
    return dbService.deleteTag(tagId);
  }));

  ipcMain.handle(IPC_CHANNELS.TAG_GET, wrapHandler((_event, tagId: string) => {
    return dbService.getTagById(tagId);
  }));

  ipcMain.handle(IPC_CHANNELS.TAG_LIST, wrapHandler(() => {
    return dbService.getTags();
  }));

  ipcMain.handle(IPC_CHANNELS.SEARCH_QUERY, wrapHandler((_event, params: IPCSearchParams) => {
    return searchService.query({
      keyword: params.keyword,
      tags: params.tags,
      folder_id: params.folder_id,
      limit: params.limit,
    });
  }));

  ipcMain.handle(IPC_CHANNELS.SEARCH_REBUILD_INDEX, wrapHandler(() => {
    return searchService.rebuildIndex();
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_START, wrapHandler(async () => {
    return syncService.startSync();
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_STATUS, wrapHandler(() => {
    return syncService.getStatus();
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_CONFIG_GET, wrapHandler(() => {
    return syncService.getConfig();
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_CONFIG_SET, wrapHandler(async (_event, config: IPCSyncConfig) => {
    return syncService.saveConfig({
      api_url: config.api_url,
      api_key: config.api_key,
      auto_sync: config.auto_sync,
      sync_interval: config.sync_interval,
    });
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_CONFIG_CLEAR, wrapHandler(async () => {
    return syncService.clearConfig();
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_CONFLICTS_GET, wrapHandler(() => {
    return syncService.getPendingConflicts();
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_CONFLICT_RESOLVE, wrapHandler(async (_event, noteId: string, resolution: IPCConflictResolution) => {
    const conflictResolution = resolution === 'keep_local' 
      ? ConflictResolution.KEEP_LOCAL
      : resolution === 'use_remote'
      ? ConflictResolution.USE_REMOTE
      : ConflictResolution.MERGE;
    
    return syncService.resolveConflict(noteId, conflictResolution);
  }));

  ipcMain.handle(IPC_CHANNELS.SYNC_CONFLICTS_CLEAR, wrapHandler(() => {
    syncService.clearAllConflicts();
    return true;
  }));

  ipcMain.handle(IPC_CHANNELS.AI_SUMMARY_GENERATE, wrapHandler(async (_event, content: string) => {
    return aiService.generateSummary(content);
  }));

  ipcMain.handle(IPC_CHANNELS.AI_CONFIG_GET, wrapHandler(() => {
    return aiService.getConfig();
  }));

  ipcMain.handle(IPC_CHANNELS.AI_CONFIG_SET, wrapHandler(async (_event, config: IPCAIConfig) => {
    return aiService.configure({
      api_url: config.api_url,
      api_key: config.api_key,
      model: config.model || 'gpt-3.5-turbo',
      max_tokens: config.max_tokens || 500,
    });
  }));

  ipcMain.handle(IPC_CHANNELS.AI_CONFIG_CLEAR, wrapHandler(async () => {
    return aiService.clearConfig();
  }));

  ipcMain.handle(IPC_CHANNELS.SECURE_STORAGE_STATUS, wrapHandler(() => {
    return {
      encryptionAvailable: secureStorage.isEncryptionAvailable(),
    };
  }));

  ipcMain.handle(IPC_CHANNELS.SETTINGS_GET, wrapHandler(() => {
    return dbService.getSettings();
  }));

  ipcMain.handle(IPC_CHANNELS.SETTINGS_SET, wrapHandler((_event, settings: Partial<AppSettings>) => {
    dbService.updateSettings(settings);
    return true;
  }));
}

function countWords(content: string): number {
  if (!content) return 0;
  
  const chineseChars = content.match(/[\u4e00-\u9fa5]/g) || [];
  const englishWords = content.match(/[a-zA-Z]+/g) || [];
  const numbers = content.match(/\d+/g) || [];
  
  return chineseChars.length + englishWords.length + numbers.length;
}
