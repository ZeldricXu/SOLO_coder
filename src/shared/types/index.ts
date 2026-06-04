export * from './document';
export * from './git';
export * from './plugin';
import type { Tag } from './document';

export interface Template {
  id: string;
  name: string;
  description?: string;
  content: string;
  variables: TemplateVariable[];
  tags: string[];
  createdAt: Date;
  updatedAt: Date;
}

export interface AppSettings {
  theme: 'dark' | 'light' | 'system';
  editorTheme: 'dark' | 'light' | 'system';
  language: 'zh-CN' | 'en-US';
  defaultEditorMode: 'split' | 'source' | 'preview' | 'wysiwyg';
  editorFontFamily: string;
  fontSize: number;
  lineHeight: number;
  showLineNumbers: boolean;
  tabSize: number;
  autoSave: boolean;
  autoSaveInterval: number;
  gitAutoCommit: boolean;
  gitAutoCommitInterval: number;
  searchIncludeContent: boolean;
  searchHighlight: boolean;
  repositoryPath: string;
  backupEnabled: boolean;
  backupInterval: number;
  graphNodeSize: number;
  graphLinkDistance: number;
  graphChargeStrength: number;
  searchResultLimit: number;
  searchSortBy: 'relevance' | 'date' | 'title';
  recentFilesLimit: number;
}

export interface TemplateVariable {
  name: string;
  label?: string;
  description?: string;
  defaultValue?: string;
  requiresInput: boolean;
}

export interface RenderedTemplate {
  title: string;
  content: string;
  variables: TemplateVariable[];
}

export interface AppStats {
  totalDocuments: number;
  totalWords: number;
  totalTags: number;
  totalLinks: number;
  totalBacklinks: number;
  todayEdited: number;
  last7DaysActivity: number[];
  recentDocuments: Document[];
  topTags: Tag[];
}

export type IPCResponse<T> = {
  success: true;
  data: T;
} | {
  success: false;
  error: string;
  code?: string;
};
