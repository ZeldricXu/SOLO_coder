import axios, { AxiosInstance } from 'axios';
import { Note, SyncConfig, IPCResponse } from '../../shared/types';
import { DatabaseService } from './database';
import { SecureStorageService } from './secure-storage';
import { VersionMerge, MergeResult, NoteVersion, MergeOptions } from '../../shared/utils/version-merge';
import { MarkdownParser } from '../../shared/utils/markdown-parser';

export interface SyncResult {
  success: boolean;
  syncedNotes: number;
  failedNotes: number;
  errors: string[];
  conflicts: SyncConflict[];
}

export interface SyncConflict {
  note_id: string;
  title: string;
  local_version: number;
  remote_version: number;
  local_updated_at: string;
  remote_updated_at: string;
  remote_note: RemoteNoteData;
  mergeResult?: MergeResult;
}

export interface RemoteNoteData {
  note_id: string;
  title: string;
  content: string;
  content_type: 'markdown' | 'rich-text';
  tags: string[];
  folder_id: string | null;
  created_at: string;
  updated_at: string;
  word_count: number;
  ai_summary: string | null;
  version: number;
}

export enum ConflictResolution {
  KEEP_LOCAL = 'keep_local',
  USE_REMOTE = 'use_remote',
  MERGE = 'merge',
}

export class SyncService {
  private static instance: SyncService;
  private dbService: DatabaseService | null = null;
  private secureStorage: SecureStorageService | null = null;
  private apiClient: AxiosInstance | null = null;
  private syncIntervalId: NodeJS.Timeout | null = null;
  private config: {
    api_url: string;
    api_key: string;
    auto_sync?: boolean;
    sync_interval?: number;
  } | null = null;
  private isSyncing: boolean = false;
  private pendingConflicts: Map<string, SyncConflict> = new Map();
  private versionMerge: VersionMerge;
  private markdownParser: MarkdownParser;

  private constructor() {
    this.versionMerge = new VersionMerge();
    this.markdownParser = new MarkdownParser();
  }

  public static getInstance(): SyncService {
    if (!SyncService.instance) {
      SyncService.instance = new SyncService();
    }
    return SyncService.instance;
  }

  public async initialize(dbService: DatabaseService): Promise<void> {
    this.dbService = dbService;
    this.secureStorage = SecureStorageService.getInstance();
    
    await this.loadConfig();
    console.log('SyncService initialized with VersionMerge');
  }

  private async loadConfig(): Promise<void> {
    if (!this.secureStorage) return;
    
    const savedConfig = await this.secureStorage.getSyncConfig();
    if (savedConfig) {
      this.configure(savedConfig);
    }
  }

  public configure(config: {
    api_url: string;
    api_key: string;
    auto_sync?: boolean;
    sync_interval?: number;
  }): void {
    this.config = config;
    
    this.apiClient = axios.create({
      baseURL: config.api_url,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.api_key}`,
      },
    });

    if (this.syncIntervalId) {
      clearInterval(this.syncIntervalId);
    }

    if (config.auto_sync && config.sync_interval && config.sync_interval > 0) {
      this.syncIntervalId = setInterval(() => {
        this.startSync();
      }, config.sync_interval * 60 * 1000);
    }
  }

  public async saveConfig(config: {
    api_url: string;
    api_key: string;
    auto_sync?: boolean;
    sync_interval?: number;
  }): Promise<boolean> {
    if (!this.secureStorage) {
      this.secureStorage = SecureStorageService.getInstance();
    }

    this.configure(config);

    try {
      await this.secureStorage.storeSyncConfig({
        api_url: config.api_url,
        api_key: config.api_key,
        auto_sync: config.auto_sync,
        sync_interval: config.sync_interval,
      });
      return true;
    } catch (error) {
      console.error('Failed to save sync config:', error);
      return false;
    }
  }

  public async clearConfig(): Promise<boolean> {
    if (!this.secureStorage) {
      this.secureStorage = SecureStorageService.getInstance();
    }

    this.config = null;
    this.apiClient = null;

    if (this.syncIntervalId) {
      clearInterval(this.syncIntervalId);
      this.syncIntervalId = null;
    }

    try {
      await this.secureStorage.deleteSyncConfig();
      return true;
    } catch (error) {
      console.error('Failed to clear sync config:', error);
      return false;
    }
  }

  public getConfig(): SyncConfig | null {
    if (!this.config) return null;
    return {
      api_url: this.config.api_url,
      api_key: '********',
      auto_sync: this.config.auto_sync || false,
      sync_interval: this.config.sync_interval || 30,
    };
  }

  public hasPendingConflicts(): boolean {
    return this.pendingConflicts.size > 0;
  }

  public getPendingConflicts(): SyncConflict[] {
    return Array.from(this.pendingConflicts.values());
  }

  public getConflict(noteId: string): SyncConflict | undefined {
    return this.pendingConflicts.get(noteId);
  }

  public clearConflict(noteId: string): void {
    this.pendingConflicts.delete(noteId);
  }

  public clearAllConflicts(): void {
    this.pendingConflicts.clear();
  }

  public async startSync(): Promise<SyncResult> {
    if (this.isSyncing) {
      return {
        success: false,
        syncedNotes: 0,
        failedNotes: 0,
        errors: ['Sync is already in progress'],
        conflicts: [],
      };
    }

    if (!this.apiClient || !this.dbService) {
      return {
        success: false,
        syncedNotes: 0,
        failedNotes: 0,
        errors: ['Sync not configured or database not initialized'],
        conflicts: [],
      };
    }

    this.isSyncing = true;
    const result: SyncResult = {
      success: true,
      syncedNotes: 0,
      failedNotes: 0,
      errors: [],
      conflicts: [],
    };

    try {
      const pendingNotes = this.dbService.getNotesForSync();

      for (const note of pendingNotes) {
        try {
          const remoteNote = await this.getRemoteNote(note.note_id);
          
          if (remoteNote) {
            const conflict = this.checkVersionConflict(note, remoteNote);
            
            if (conflict) {
              const mergeResult = this.analyzeConflict(note, remoteNote);
              conflict.mergeResult = mergeResult;
              
              this.pendingConflicts.set(note.note_id, conflict);
              result.conflicts.push(conflict);
              continue;
            }
          }

          await this.syncOneNote(note);
          this.dbService.updateNote(note.note_id, { sync_status: 'synced' });
          result.syncedNotes++;
        } catch (error) {
          result.failedNotes++;
          result.errors.push(`Note ${note.note_id}: ${(error as Error).message}`);
          this.dbService.updateNote(note.note_id, { sync_status: 'error' });
        }
      }

      await this.downloadUpdates(result);

    } catch (error) {
      result.success = false;
      result.errors.push(`Sync failed: ${(error as Error).message}`);
    } finally {
      this.isSyncing = false;
    }

    return result;
  }

  private analyzeConflict(localNote: Note, remoteNote: RemoteNoteData): MergeResult {
    const localVersion: NoteVersion = {
      note_id: localNote.note_id,
      title: localNote.title,
      content: localNote.content,
      tags: localNote.tags,
      version: localNote.version,
      updated_at: localNote.updated_at,
    };

    const remoteVersion: NoteVersion = {
      note_id: remoteNote.note_id,
      title: remoteNote.title,
      content: remoteNote.content,
      tags: remoteNote.tags,
      version: remoteNote.version,
      updated_at: remoteNote.updated_at,
    };

    const mergeOptions: MergeOptions = {
      preserveBlockStructure: true,
      preferLocal: false,
      enableSmartMerge: true,
      markConflicts: false,
    };

    return this.versionMerge.merge(localVersion, remoteVersion, mergeOptions);
  }

  private async getRemoteNote(noteId: string): Promise<RemoteNoteData | null> {
    if (!this.apiClient) return null;

    try {
      const response = await this.apiClient.get(`/notes/${noteId}`);
      return response.data as RemoteNoteData;
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  private checkVersionConflict(localNote: Note, remoteNote: RemoteNoteData): SyncConflict | null {
    if (remoteNote.version > localNote.version) {
      return {
        note_id: localNote.note_id,
        title: localNote.title,
        local_version: localNote.version,
        remote_version: remoteNote.version,
        local_updated_at: localNote.updated_at,
        remote_updated_at: remoteNote.updated_at,
        remote_note: remoteNote,
      };
    }
    return null;
  }

  public async resolveConflict(
    noteId: string,
    resolution: ConflictResolution
  ): Promise<IPCResponse> {
    if (!this.dbService) {
      return { success: false, error: 'Database not initialized' };
    }

    const conflict = this.pendingConflicts.get(noteId);
    if (!conflict) {
      return { success: false, error: 'No pending conflict for this note' };
    }

    try {
      switch (resolution) {
        case ConflictResolution.KEEP_LOCAL:
          const localNote = this.dbService.getNoteById(noteId);
          if (localNote) {
            const bumpedNote = this.dbService.updateNote(noteId, {
              version: conflict.remote_version + 1,
            });
            await this.syncOneNote(bumpedNote);
            this.dbService.updateNote(noteId, { sync_status: 'synced' });
          }
          break;

        case ConflictResolution.USE_REMOTE:
          this.applyRemoteNote(conflict.remote_note);
          break;

        case ConflictResolution.MERGE:
          const mergeResult = this.mergeNotesSmart(conflict);
          if (mergeResult) {
            const mergedNote = this.dbService.updateNote(noteId, {
              title: mergeResult.title,
              content: mergeResult.content,
              tags: mergeResult.tags,
              word_count: mergeResult.wordCount,
              version: Math.max(conflict.local_version, conflict.remote_version) + 1,
              sync_status: 'pending',
            });
            await this.syncOneNote(mergedNote);
          }
          break;
      }

      this.pendingConflicts.delete(noteId);
      return { success: true };
    } catch (error) {
      return { success: false, error: (error as Error).message };
    }
  }

  private applyRemoteNote(remoteNote: RemoteNoteData): void {
    if (!this.dbService) return;

    const localNote = this.dbService.getNoteById(remoteNote.note_id);

    if (!localNote) {
      this.dbService.createNote({
        title: remoteNote.title,
        content: remoteNote.content,
        content_type: remoteNote.content_type,
        folder_id: remoteNote.folder_id,
        tags: remoteNote.tags,
        word_count: remoteNote.word_count,
        ai_summary: remoteNote.ai_summary,
        sync_status: 'synced',
      });
    } else {
      this.dbService.updateNote(remoteNote.note_id, {
        title: remoteNote.title,
        content: remoteNote.content,
        content_type: remoteNote.content_type,
        folder_id: remoteNote.folder_id,
        tags: remoteNote.tags,
        word_count: remoteNote.word_count,
        ai_summary: remoteNote.ai_summary,
        sync_status: 'synced',
      });
    }
  }

  private mergeNotesSmart(conflict: SyncConflict): { 
    title: string; 
    content: string; 
    tags: string[];
    wordCount: number;
  } | null {
    if (!this.dbService) return null;

    const localNote = this.dbService.getNoteById(conflict.note_id);
    if (!localNote) {
      this.applyRemoteNote(conflict.remote_note);
      return null;
    }

    const localVersion: NoteVersion = {
      note_id: localNote.note_id,
      title: localNote.title,
      content: localNote.content,
      tags: localNote.tags,
      version: localNote.version,
      updated_at: localNote.updated_at,
    };

    const remoteVersion: NoteVersion = {
      note_id: conflict.remote_note.note_id,
      title: conflict.remote_note.title,
      content: conflict.remote_note.content,
      tags: conflict.remote_note.tags,
      version: conflict.remote_note.version,
      updated_at: conflict.remote_note.updated_at,
    };

    const mergeOptions: MergeOptions = {
      preserveBlockStructure: true,
      preferLocal: false,
      enableSmartMerge: true,
      markConflicts: false,
    };

    const mergeResult = this.versionMerge.merge(localVersion, remoteVersion, mergeOptions);

    const mergedTitle = this.resolveTitleConflict(
      localNote.title,
      conflict.remote_note.title
    );

    const mergedTags = this.versionMerge.mergeTags(
      localNote.tags,
      conflict.remote_note.tags
    );

    const wordCount = this.markdownParser.serialize([
      { type: 'paragraph', children: [{ text: mergeResult.mergedContent }] }
    ]).wordCount;

    return {
      title: mergedTitle,
      content: mergeResult.mergedContent,
      tags: mergedTags,
      wordCount,
    };
  }

  private resolveTitleConflict(localTitle: string, remoteTitle: string): string {
    if (localTitle === remoteTitle) {
      return localTitle;
    }

    const localLength = localTitle.length;
    const remoteLength = remoteTitle.length;

    if (localLength === 0) return remoteTitle;
    if (remoteLength === 0) return localTitle;

    if (localTitle.includes(remoteTitle)) {
      return localTitle;
    }
    if (remoteTitle.includes(localTitle)) {
      return remoteTitle;
    }

    const localLower = localTitle.toLowerCase();
    const remoteLower = remoteTitle.toLowerCase();

    if (localLower.startsWith(remoteLower) || remoteLower.startsWith(localLower)) {
      return localTitle.length > remoteTitle.length ? localTitle : remoteTitle;
    }

    return localTitle;
  }

  private mergeNotes(conflict: SyncConflict): void {
    if (!this.dbService) return;

    const localNote = this.dbService.getNoteById(conflict.note_id);
    if (!localNote) {
      this.applyRemoteNote(conflict.remote_note);
      return;
    }

    const mergedResult = this.mergeNotesSmart(conflict);
    if (mergedResult) {
      this.dbService.updateNote(conflict.note_id, {
        title: mergedResult.title,
        content: mergedResult.content,
        tags: mergedResult.tags,
        word_count: mergedResult.wordCount,
        version: Math.max(localNote.version, conflict.remote_version) + 1,
        sync_status: 'pending',
      });
    }
  }

  private async syncOneNote(note: Note): Promise<void> {
    if (!this.apiClient) {
      throw new Error('API client not initialized');
    }

    const payload = {
      note_id: note.note_id,
      title: note.title,
      content: note.content,
      content_type: note.content_type,
      tags: note.tags,
      folder_id: note.folder_id,
      created_at: note.created_at,
      updated_at: note.updated_at,
      word_count: note.word_count,
      ai_summary: note.ai_summary,
      version: note.version,
    };

    try {
      await this.apiClient.post('/notes/sync', payload);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.status === 409) {
        throw new Error('Version conflict - note may have been modified on server');
      }
      throw error;
    }
  }

  private async downloadUpdates(result: SyncResult): Promise<void> {
    if (!this.apiClient || !this.dbService) {
      return;
    }

    try {
      const response = await this.apiClient.get('/notes/updates', {
        params: {
          since: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString(),
        },
      });

      const serverNotes = response.data.notes as Array<RemoteNoteData & { deleted_at: string | null }>;

      for (const serverNote of serverNotes) {
        const localNote = this.dbService.getNoteById(serverNote.note_id);

        if (serverNote.deleted_at) {
          if (localNote) {
            this.dbService.deleteNote(serverNote.note_id);
          }
          continue;
        }

        if (!localNote) {
          this.dbService.createNote({
            title: serverNote.title,
            content: serverNote.content,
            content_type: serverNote.content_type,
            folder_id: serverNote.folder_id,
            tags: serverNote.tags,
            word_count: serverNote.word_count,
            ai_summary: serverNote.ai_summary,
            sync_status: 'synced',
          });
          result.syncedNotes++;
        } else if (serverNote.version > localNote.version) {
          const conflict = this.checkVersionConflict(localNote, serverNote);
          
          if (conflict) {
            const mergeResult = this.analyzeConflict(localNote, serverNote);
            conflict.mergeResult = mergeResult;
            
            this.pendingConflicts.set(serverNote.note_id, conflict);
            result.conflicts.push(conflict);
          } else {
            this.dbService.updateNote(serverNote.note_id, {
              title: serverNote.title,
              content: serverNote.content,
              content_type: serverNote.content_type,
              folder_id: serverNote.folder_id,
              tags: serverNote.tags,
              word_count: serverNote.word_count,
              ai_summary: serverNote.ai_summary,
              sync_status: 'synced',
            });
            result.syncedNotes++;
          }
        }
      }
    } catch (error) {
      console.error('Failed to download updates:', error);
    }
  }

  public async uploadNote(noteId: string): Promise<IPCResponse> {
    if (!this.dbService) {
      return { success: false, error: 'Database not initialized' };
    }

    const note = this.dbService.getNoteById(noteId);
    if (!note) {
      return { success: false, error: 'Note not found' };
    }

    try {
      await this.syncOneNote(note);
      this.dbService.updateNote(noteId, { sync_status: 'synced' });
      return { success: true, data: { noteId } };
    } catch (error) {
      return { success: false, error: (error as Error).message };
    }
  }

  public async downloadNote(noteId: string): Promise<IPCResponse<Note>> {
    if (!this.apiClient) {
      return { success: false, error: 'Sync not configured' };
    }

    try {
      const response = await this.apiClient.get(`/notes/${noteId}`);
      const serverNote = response.data as RemoteNoteData;

      if (!this.dbService) {
        return { success: false, error: 'Database not initialized' };
      }

      const localNote = this.dbService.getNoteById(noteId);
      let note: Note;

      if (!localNote) {
        note = this.dbService.createNote({
          title: serverNote.title,
          content: serverNote.content,
          content_type: serverNote.content_type,
          folder_id: serverNote.folder_id,
          tags: serverNote.tags,
          word_count: serverNote.word_count,
          ai_summary: serverNote.ai_summary,
          sync_status: 'synced',
        });
      } else {
        note = this.dbService.updateNote(noteId, {
          title: serverNote.title,
          content: serverNote.content,
          content_type: serverNote.content_type,
          folder_id: serverNote.folder_id,
          tags: serverNote.tags,
          word_count: serverNote.word_count,
          ai_summary: serverNote.ai_summary,
          sync_status: 'synced',
        });
      }

      return { success: true, data: note };
    } catch (error) {
      return { success: false, error: (error as Error).message };
    }
  }

  public getStatus(): { 
    isSyncing: boolean; 
    config: SyncConfig | null;
    conflictCount: number;
  } {
    return {
      isSyncing: this.isSyncing,
      config: this.getConfig(),
      conflictCount: this.pendingConflicts.size,
    };
  }

  public stopAutoSync(): void {
    if (this.syncIntervalId) {
      clearInterval(this.syncIntervalId);
      this.syncIntervalId = null;
    }
  }
}
