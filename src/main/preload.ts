import { contextBridge, ipcRenderer } from 'electron';
import type { IpcRendererApi } from '../shared/types';

const api: IpcRendererApi = {
  notes: {
    getAll: () => ipcRenderer.invoke('notes:getAll'),
    getById: (id: string) => ipcRenderer.invoke('notes:getById', id),
    getByPath: (path: string) => ipcRenderer.invoke('notes:getByPath', path),
    create: (note: any) => ipcRenderer.invoke('notes:create', note),
    update: (id: string, updates: any) => ipcRenderer.invoke('notes:update', id, updates),
    delete: (id: string) => ipcRenderer.invoke('notes:delete', id),
    saveContent: (id: string, content: string) => ipcRenderer.invoke('notes:saveContent', id, content),
  },
  links: {
    getAll: () => ipcRenderer.invoke('links:getAll'),
    getBacklinks: (noteId: string) => ipcRenderer.invoke('links:getBacklinks', noteId),
    getForwardLinks: (noteId: string) => ipcRenderer.invoke('links:getForwardLinks', noteId),
  },
  graph: {
    getGraphData: () => ipcRenderer.invoke('graph:getGraphData'),
  },
  search: {
    query: (q: string, options?: any) => ipcRenderer.invoke('search:query', q, options),
  },
  vault: {
    setPath: (path: string) => ipcRenderer.invoke('vault:setPath', path),
    getPath: () => ipcRenderer.invoke('vault:getPath'),
    rescan: () => ipcRenderer.invoke('vault:rescan'),
    onNoteChanged: (callback: any) => {
      const handler = (_event: any, note: any) => callback(_event, note);
      ipcRenderer.on('vault:note-changed', handler);
      return () => ipcRenderer.removeListener('vault:note-changed', handler);
    },
    onNoteDeleted: (callback: any) => {
      const handler = (_event: any, notePath: string) => callback(_event, notePath);
      ipcRenderer.on('vault:note-deleted', handler);
      return () => ipcRenderer.removeListener('vault:note-deleted', handler);
    },
  },
  settings: {
    get: () => ipcRenderer.invoke('settings:get'),
    update: (settings: any) => ipcRenderer.invoke('settings:update', settings),
  },
  export: {
    exportNote: (id: string, format: 'txt' | 'html' | 'pdf') =>
      ipcRenderer.invoke('export:exportNote', id, format),
    exportDomain: (noteIds: string[], format: 'markdown') =>
      ipcRenderer.invoke('export:exportDomain', noteIds, format),
    exportGraphPNG: (svgData: string) => ipcRenderer.invoke('export:exportGraphPNG', svgData),
  },
  dialog: {
    openFile: (options?: any) => ipcRenderer.invoke('dialog:openFile', options),
    openDirectory: (options?: any) => ipcRenderer.invoke('dialog:openDirectory', options),
    saveFile: (options?: any) => ipcRenderer.invoke('dialog:saveFile', options),
  },
};

contextBridge.exposeInMainWorld('api', api);

export type { IpcRendererApi };
