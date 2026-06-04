import { shell } from 'electron';
import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { IPCResponse, AppSettings } from '@shared/types';
import type { DatabaseService } from '../services/DatabaseService';
import type { FileService } from '../services/FileService';
import type { GitService } from '../services/GitService';
import type { SearchService } from '../services/SearchService';
import { registerFileIPCHandlers } from './file';
import { registerGitIPCHandlers } from './git';
import { registerDbIPCHandlers } from './db';
import { registerSearchIPCHandlers } from './search';
import { registerDocumentHandlers } from './document';
import { getDefaultRepoPath } from '@shared/utils/path';

const DEFAULT_SETTINGS: AppSettings = {
  theme: 'system',
  editorTheme: 'system',
  language: 'zh-CN',
  defaultEditorMode: 'split',
  editorFontFamily: 'JetBrains Mono',
  fontSize: 14,
  lineHeight: 1.6,
  showLineNumbers: true,
  tabSize: 2,
  autoSave: true,
  autoSaveInterval: 30000,
  gitAutoCommit: true,
  gitAutoCommitInterval: 300000,
  searchIncludeContent: true,
  searchHighlight: true,
  repositoryPath: getDefaultRepoPath(),
  backupEnabled: false,
  backupInterval: 3600000,
  graphNodeSize: 20,
  graphLinkDistance: 150,
  graphChargeStrength: -300,
  searchResultLimit: 50,
  searchSortBy: 'relevance',
  recentFilesLimit: 20,
};

export function registerIPCHandlers(
  dbService: DatabaseService,
  fileService: FileService,
  gitService: GitService,
  searchService: SearchService
): void {
  registerFileIPCHandlers(fileService);
  registerGitIPCHandlers(gitService);
  registerDbIPCHandlers(dbService, fileService, searchService);
  registerSearchIPCHandlers(searchService);
  registerDocumentHandlers(dbService, fileService, searchService);

  typedIpcMain.handle(IPC_CHANNELS.APP.GET_INITIALIZED, (): IPCResponse<boolean> => {
    try {
      const initialized = dbService.getSetting('initialized') === 'true';
      return { success: true, data: initialized };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'APP_INIT_CHECK_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.APP.INITIALIZE, async (_event, settings?: Partial<AppSettings>): Promise<IPCResponse<void>> => {
    try {
      await fileService.ensureRepo();
      await gitService.init();
      
      const mergedSettings: AppSettings = { ...DEFAULT_SETTINGS, ...settings };
      dbService.setSetting('settings', JSON.stringify(mergedSettings));
      dbService.setSetting('initialized', 'true');
      
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'APP_INIT_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.APP.GET_SETTINGS, (): IPCResponse<AppSettings> => {
    try {
      const stored = dbService.getSetting('settings');
      const settings = stored ? JSON.parse(stored) : DEFAULT_SETTINGS;
      return { success: true, data: { ...DEFAULT_SETTINGS, ...settings } };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'APP_GET_SETTINGS_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.APP.SET_SETTINGS, (_event, settings: Partial<AppSettings>): IPCResponse<void> => {
    try {
      const stored = dbService.getSetting('settings');
      const current = stored ? JSON.parse(stored) : DEFAULT_SETTINGS;
      const merged = { ...DEFAULT_SETTINGS, ...current, ...settings };
      dbService.setSetting('settings', JSON.stringify(merged));
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'APP_SET_SETTINGS_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.APP.OPEN_EXTERNAL, async (_event, url: string): Promise<IPCResponse<void>> => {
    try {
      await shell.openExternal(url);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'APP_OPEN_EXTERNAL_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.APP.SHOW_ITEM_IN_FOLDER, (_event, path: string): IPCResponse<void> => {
    try {
      shell.showItemInFolder(path);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'APP_SHOW_ITEM_ERROR' };
    }
  });
}
