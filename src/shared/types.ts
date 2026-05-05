export type NoteId = string;
export type FolderId = string;
export type TagId = string;
export type SyncStatus = 'synced' | 'pending' | 'error';
export type ContentType = 'markdown' | 'rich-text';

export interface Note {
  note_id: NoteId;
  title: string;
  content: string;
  content_type: ContentType;
  tags: string[];
  folder_id: FolderId | null;
  created_at: string;
  updated_at: string;
  word_count: number;
  ai_summary: string | null;
  sync_status: SyncStatus;
  version: number;
}

export interface Folder {
  folder_id: FolderId;
  name: string;
  parent_id: FolderId | null;
  order_index: number;
  created_at: string;
  updated_at: string;
}

export interface Tag {
  tag_id: TagId;
  name: string;
  color: string;
  created_at: string;
}

export interface SearchResult {
  note_id: NoteId;
  title: string;
  preview: string;
  score: number;
  tags: string[];
  updated_at: string;
}

export interface IPCResponse<T = unknown> {
  success: boolean;
  data?: T;
  error?: string;
}

export interface SyncConfig {
  api_url: string;
  api_key: string;
  auto_sync: boolean;
  sync_interval: number;
}

export interface AIConfig {
  api_url: string;
  api_key: string;
  model: string;
  max_tokens: number;
}

export interface AppSettings {
  theme: 'light' | 'dark' | 'system';
  language: string;
  auto_save: boolean;
  sync_config: SyncConfig | null;
  ai_config: AIConfig | null;
}
