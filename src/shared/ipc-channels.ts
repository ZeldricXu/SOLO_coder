export const IPC_CHANNELS = {
  NOTE_CREATE: 'note:create',
  NOTE_UPDATE: 'note:update',
  NOTE_DELETE: 'note:delete',
  NOTE_GET: 'note:get',
  NOTE_LIST: 'note:list',
  NOTE_COUNT: 'note:count',

  FOLDER_CREATE: 'folder:create',
  FOLDER_UPDATE: 'folder:update',
  FOLDER_DELETE: 'folder:delete',
  FOLDER_GET: 'folder:get',
  FOLDER_LIST: 'folder:list',

  TAG_CREATE: 'tag:create',
  TAG_UPDATE: 'tag:update',
  TAG_DELETE: 'tag:delete',
  TAG_GET: 'tag:get',
  TAG_LIST: 'tag:list',
  TAG_ADD_TO_NOTE: 'tag:addToNote',
  TAG_REMOVE_FROM_NOTE: 'tag:removeFromNote',

  SEARCH_QUERY: 'search:query',
  SEARCH_REBUILD_INDEX: 'search:rebuildIndex',

  SYNC_START: 'sync:start',
  SYNC_STATUS: 'sync:status',
  SYNC_CONFIG_GET: 'sync:config:get',
  SYNC_CONFIG_SET: 'sync:config:set',
  SYNC_CONFIG_CLEAR: 'sync:config:clear',
  SYNC_CONFLICTS_GET: 'sync:conflicts:get',
  SYNC_CONFLICT_RESOLVE: 'sync:conflict:resolve',
  SYNC_CONFLICTS_CLEAR: 'sync:conflicts:clear',

  AI_SUMMARY_GENERATE: 'ai:generateSummary',
  AI_CONFIG_GET: 'ai:config:get',
  AI_CONFIG_SET: 'ai:config:set',
  AI_CONFIG_CLEAR: 'ai:config:clear',

  SECURE_STORAGE_STATUS: 'secure:status',

  SETTINGS_GET: 'settings:get',
  SETTINGS_SET: 'settings:set',

  APP_QUIT: 'app:quit',
  APP_MINIMIZE: 'app:minimize',
  APP_MAXIMIZE: 'app:maximize',

  WINDOW_RESIZE: 'window:resize',
  WINDOW_GET_BOUNDS: 'window:getBounds',
};

export interface IPCNoteCreateParams {
  title: string;
  content: string;
  content_type: 'markdown' | 'rich-text';
  folder_id?: string;
  tags?: string[];
}

export interface IPCNoteUpdateParams {
  note_id: string;
  title?: string;
  content?: string;
  content_type?: 'markdown' | 'rich-text';
  folder_id?: string;
  tags?: string[];
}

export interface IPCSearchParams {
  keyword: string;
  tags?: string[];
  folder_id?: string;
  limit?: number;
}

export interface IPCSyncConfig {
  api_url: string;
  api_key: string;
  auto_sync?: boolean;
  sync_interval?: number;
}

export interface IPCAIConfig {
  api_url: string;
  api_key: string;
  model?: string;
  max_tokens?: number;
}

export interface IPCSyncConflict {
  note_id: string;
  title: string;
  local_version: number;
  remote_version: number;
  local_updated_at: string;
  remote_updated_at: string;
  remote_note: {
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
  };
}

export type IPCConflictResolution = 'keep_local' | 'use_remote' | 'merge';
