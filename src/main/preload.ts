import { contextBridge, ipcRenderer } from 'electron';
import type { IpcRendererApi } from '@shared/types';
import { IpcChannelName } from '@shared/ipc/channels';

const api: IpcRendererApi = {
  notes: {
    getAll: () => ipcRenderer.invoke(IpcChannelName.NOTES_GET_ALL),
    getById: (id: string) => ipcRenderer.invoke(IpcChannelName.NOTES_GET_BY_ID, id),
    getByPath: (path: string) => ipcRenderer.invoke(IpcChannelName.NOTES_GET_BY_PATH, path),
    create: (note: any) => ipcRenderer.invoke(IpcChannelName.NOTES_CREATE, note),
    update: (id: string, updates: any) => ipcRenderer.invoke(IpcChannelName.NOTES_UPDATE, id, updates),
    delete: (id: string) => ipcRenderer.invoke(IpcChannelName.NOTES_DELETE, id),
    saveContent: (id: string, content: string) => ipcRenderer.invoke(IpcChannelName.NOTES_SAVE_CONTENT, id, content),
    findSimilarNotes: (title: string, threshold?: number) =>
      ipcRenderer.invoke(IpcChannelName.NOTES_FIND_SIMILAR, title, threshold),
    updateLinkTarget: (sourceNoteId: string, oldTarget: string, newTargetId: string) =>
      ipcRenderer.invoke(IpcChannelName.NOTES_UPDATE_LINK_TARGET, sourceNoteId, oldTarget, newTargetId),
    scanBrokenLinks: (noteId?: string) => ipcRenderer.invoke(IpcChannelName.NOTES_SCAN_BROKEN_LINKS, noteId),
  },
  links: {
    getAll: () => ipcRenderer.invoke(IpcChannelName.LINKS_GET_ALL),
    getBacklinks: (noteId: string) => ipcRenderer.invoke(IpcChannelName.LINKS_GET_BACKLINKS, noteId),
    getForwardLinks: (noteId: string) => ipcRenderer.invoke(IpcChannelName.LINKS_GET_FORWARD_LINKS, noteId),
    migrateBacklinks: (oldNoteId: string, newNoteId: string) =>
      ipcRenderer.invoke(IpcChannelName.LINKS_MIGRATE_BACKLINKS, oldNoteId, newNoteId),
  },
  graph: {
    getGraphData: () => ipcRenderer.invoke(IpcChannelName.GRAPH_GET_DATA),
    getFocusGraphData: (options: any) => ipcRenderer.invoke(IpcChannelName.GRAPH_GET_FOCUS_DATA, options),
  },
  search: {
    query: (q: string, options?: any) => ipcRenderer.invoke(IpcChannelName.SEARCH_QUERY, q, options),
  },
  vault: {
    setPath: (path: string) => ipcRenderer.invoke(IpcChannelName.VAULT_SET_PATH, path),
    getPath: () => ipcRenderer.invoke(IpcChannelName.VAULT_GET_PATH),
    rescan: () => ipcRenderer.invoke(IpcChannelName.VAULT_RESCAN),
    onNoteChanged: (callback: any) => {
      const handler = (_event: any, note: any) => callback(_event, note);
      ipcRenderer.on(IpcChannelName.VAULT_NOTE_CHANGED, handler);
      return () => ipcRenderer.removeListener(IpcChannelName.VAULT_NOTE_CHANGED, handler);
    },
    onNoteDeleted: (callback: any) => {
      const handler = (_event: any, notePath: string) => callback(_event, notePath);
      ipcRenderer.on(IpcChannelName.VAULT_NOTE_DELETED, handler);
      return () => ipcRenderer.removeListener(IpcChannelName.VAULT_NOTE_DELETED, handler);
    },
  },
  attachments: {
    list: () => ipcRenderer.invoke(IpcChannelName.ATTACHMENTS_LIST),
    upload: (fileData: string | { name: string; type: string; size: number; data: Buffer }, targetDir?: string) =>
      ipcRenderer.invoke(IpcChannelName.ATTACHMENTS_UPLOAD, fileData, targetDir),
    delete: (attachmentId: string) => ipcRenderer.invoke(IpcChannelName.ATTACHMENTS_DELETE, attachmentId),
    rename: (attachmentId: string, newName: string) =>
      ipcRenderer.invoke(IpcChannelName.ATTACHMENTS_RENAME, attachmentId, newName),
    getThumbnail: (attachmentId: string) =>
      ipcRenderer.invoke(IpcChannelName.ATTACHMENTS_GET_THUMBNAIL, attachmentId),
    getAssetsPath: () => ipcRenderer.invoke(IpcChannelName.ATTACHMENTS_GET_ASSETS_PATH),
  },
  settings: {
    get: () => ipcRenderer.invoke(IpcChannelName.SETTINGS_GET),
    update: (settings: any) => ipcRenderer.invoke(IpcChannelName.SETTINGS_UPDATE, settings),
  },
  export: {
    exportNote: (id: string, format: 'txt' | 'html' | 'pdf') =>
      ipcRenderer.invoke(IpcChannelName.EXPORT_NOTE, id, format),
    exportDomain: (noteIds: string[], format: 'markdown') =>
      ipcRenderer.invoke(IpcChannelName.EXPORT_DOMAIN, noteIds, format),
    exportGraphPNG: (svgData: string) => ipcRenderer.invoke(IpcChannelName.EXPORT_GRAPH_PNG, svgData),
  },
  dialog: {
    openFile: (options?: any) => ipcRenderer.invoke(IpcChannelName.DIALOG_OPEN_FILE, options),
    openDirectory: (options?: any) => ipcRenderer.invoke(IpcChannelName.DIALOG_OPEN_DIRECTORY, options),
    saveFile: (options?: any) => ipcRenderer.invoke(IpcChannelName.DIALOG_SAVE_FILE, options),
  },
  theme: {
    get: () => ipcRenderer.invoke(IpcChannelName.THEME_GET),
    set: (theme: string) => ipcRenderer.invoke(IpcChannelName.THEME_SET, theme),
  },
};

contextBridge.exposeInMainWorld('api', api);

export type { IpcRendererApi };
