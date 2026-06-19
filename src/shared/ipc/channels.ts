import type {
  Note,
  NoteLink,
  BrokenLink,
  LinkSuggestion,
  GraphData,
  FocusGraphOptions,
  AttachmentFile,
  SearchResult,
  SearchOptions,
  AppSettings,
} from '../types';

export const enum IpcChannelName {
  NOTES_GET_ALL = 'notes:getAll',
  NOTES_GET_BY_ID = 'notes:getById',
  NOTES_GET_BY_PATH = 'notes:getByPath',
  NOTES_CREATE = 'notes:create',
  NOTES_UPDATE = 'notes:update',
  NOTES_DELETE = 'notes:delete',
  NOTES_SAVE_CONTENT = 'notes:saveContent',
  NOTES_FIND_SIMILAR = 'notes:findSimilarNotes',
  NOTES_UPDATE_LINK_TARGET = 'notes:updateLinkTarget',
  NOTES_SCAN_BROKEN_LINKS = 'notes:scanBrokenLinks',

  LINKS_GET_ALL = 'links:getAll',
  LINKS_GET_BACKLINKS = 'links:getBacklinks',
  LINKS_GET_FORWARD_LINKS = 'links:getForwardLinks',
  LINKS_MIGRATE_BACKLINKS = 'links:migrateBacklinks',

  VAULT_SET_PATH = 'vault:setPath',
  VAULT_GET_PATH = 'vault:getPath',
  VAULT_RESCAN = 'vault:rescan',
  VAULT_NOTE_CHANGED = 'vault:note-changed',
  VAULT_NOTE_DELETED = 'vault:note-deleted',

  ATTACHMENTS_LIST = 'attachments:list',
  ATTACHMENTS_UPLOAD = 'attachments:upload',
  ATTACHMENTS_DELETE = 'attachments:delete',
  ATTACHMENTS_RENAME = 'attachments:rename',
  ATTACHMENTS_GET_THUMBNAIL = 'attachments:getThumbnail',
  ATTACHMENTS_GET_ASSETS_PATH = 'attachments:getAssetsPath',

  SEARCH_QUERY = 'search:query',

  SETTINGS_GET = 'settings:get',
  SETTINGS_UPDATE = 'settings:update',

  EXPORT_NOTE = 'export:exportNote',
  EXPORT_DOMAIN = 'export:exportDomain',
  EXPORT_GRAPH_PNG = 'export:exportGraphPNG',

  DIALOG_OPEN_FILE = 'dialog:openFile',
  DIALOG_OPEN_DIRECTORY = 'dialog:openDirectory',
  DIALOG_SAVE_FILE = 'dialog:saveFile',

  THEME_GET = 'theme:get',
  THEME_SET = 'theme:set',

  GRAPH_GET_DATA = 'graph:getGraphData',
  GRAPH_GET_FOCUS_DATA = 'graph:getFocusGraphData',
}

export interface IpcChannels {
  [IpcChannelName.NOTES_GET_ALL]: { request: void; response: Note[] };
  [IpcChannelName.NOTES_GET_BY_ID]: { request: [id: string]; response: Note | null };
  [IpcChannelName.NOTES_GET_BY_PATH]: { request: [pathStr: string]; response: Note | null };
  [IpcChannelName.NOTES_CREATE]: { request: [note: Partial<Note> & { content: string }]; response: Note };
  [IpcChannelName.NOTES_UPDATE]: { request: [id: string, updates: Partial<Note>]; response: Note | null };
  [IpcChannelName.NOTES_DELETE]: { request: [id: string]; response: boolean };
  [IpcChannelName.NOTES_SAVE_CONTENT]: { request: [id: string, content: string]; response: boolean };
  [IpcChannelName.NOTES_FIND_SIMILAR]: { request: [title: string, threshold?: number]; response: LinkSuggestion[] };
  [IpcChannelName.NOTES_UPDATE_LINK_TARGET]: { request: [sourceNoteId: string, oldTarget: string, newTargetId: string]; response: { success: boolean; newContent?: string } };
  [IpcChannelName.NOTES_SCAN_BROKEN_LINKS]: { request: [noteId?: string]; response: BrokenLink[] };

  [IpcChannelName.LINKS_GET_ALL]: { request: void; response: NoteLink[] };
  [IpcChannelName.LINKS_GET_BACKLINKS]: { request: [noteId: string]; response: NoteLink[] };
  [IpcChannelName.LINKS_GET_FORWARD_LINKS]: { request: [noteId: string]; response: NoteLink[] };
  [IpcChannelName.LINKS_MIGRATE_BACKLINKS]: { request: [oldNoteId: string, newNoteId: string]; response: number };

  [IpcChannelName.VAULT_SET_PATH]: { request: [vaultPath: string]; response: boolean };
  [IpcChannelName.VAULT_GET_PATH]: { request: void; response: string };
  [IpcChannelName.VAULT_RESCAN]: { request: void; response: void };
  [IpcChannelName.VAULT_NOTE_CHANGED]: { request: void; response: Note };
  [IpcChannelName.VAULT_NOTE_DELETED]: { request: void; response: string };

  [IpcChannelName.ATTACHMENTS_LIST]: { request: void; response: AttachmentFile[] };
  [IpcChannelName.ATTACHMENTS_UPLOAD]: { request: [fileData: string | { name: string; type: string; size: number; data: Buffer }, targetDir?: string]; response: AttachmentFile | { success: boolean; relativePath: string; attachment: AttachmentFile } };
  [IpcChannelName.ATTACHMENTS_DELETE]: { request: [attachmentId: string]; response: boolean };
  [IpcChannelName.ATTACHMENTS_RENAME]: { request: [attachmentId: string, newName: string]; response: AttachmentFile | null };
  [IpcChannelName.ATTACHMENTS_GET_THUMBNAIL]: { request: [attachmentId: string]; response: string | null };
  [IpcChannelName.ATTACHMENTS_GET_ASSETS_PATH]: { request: void; response: string };

  [IpcChannelName.SEARCH_QUERY]: { request: [q: string, options?: SearchOptions]; response: SearchResult[] };

  [IpcChannelName.SETTINGS_GET]: { request: void; response: AppSettings };
  [IpcChannelName.SETTINGS_UPDATE]: { request: [updates: Partial<AppSettings>]; response: AppSettings };

  [IpcChannelName.EXPORT_NOTE]: { request: [id: string, format: 'txt' | 'html' | 'pdf']; response: string };
  [IpcChannelName.EXPORT_DOMAIN]: { request: [noteIds: string[], format: 'markdown']; response: string };
  [IpcChannelName.EXPORT_GRAPH_PNG]: { request: [svgData: string]; response: string };

  [IpcChannelName.DIALOG_OPEN_FILE]: { request: [options?: any]; response: string | null };
  [IpcChannelName.DIALOG_OPEN_DIRECTORY]: { request: [options?: any]; response: string | null };
  [IpcChannelName.DIALOG_SAVE_FILE]: { request: [options?: any]; response: string | null };

  [IpcChannelName.THEME_GET]: { request: void; response: string };
  [IpcChannelName.THEME_SET]: { request: [theme: string]; response: string };

  [IpcChannelName.GRAPH_GET_DATA]: { request: void; response: GraphData };
  [IpcChannelName.GRAPH_GET_FOCUS_DATA]: { request: [options: FocusGraphOptions]; response: GraphData };
}
