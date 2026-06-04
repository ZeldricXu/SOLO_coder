import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type {
  IPCResponse,
  Document,
  Tag,
  AppStats,
  AppSettings,
  Backlink,
  DocumentCreateInput,
  GitConfig,
  GitStatus,
  GitCommit,
  DiffHunk,
  SearchResult,
  TemplateVariable,
} from '@shared/types';

export interface IPCChannelDef<Args extends any[], Return> {
  args: Args;
  return: Return;
}

export interface IPCApi {
  [IPC_CHANNELS.FILE.READ]: IPCChannelDef<[filePath: string], IPCResponse<string>>;
  [IPC_CHANNELS.FILE.WRITE]: IPCChannelDef<[filePath: string, content: string], IPCResponse<string>>;
  [IPC_CHANNELS.FILE.DELETE]: IPCChannelDef<[filePath: string], IPCResponse<void>>;
  [IPC_CHANNELS.FILE.LIST]: IPCChannelDef<[dirPath?: string], IPCResponse<string[]>>;
  [IPC_CHANNELS.FILE.RENAME]: IPCChannelDef<[oldPath: string, newPath: string], IPCResponse<string>>;
  [IPC_CHANNELS.FILE.EXISTS]: IPCChannelDef<[filePath: string], IPCResponse<boolean>>;

  [IPC_CHANNELS.DOCUMENT.CREATE]: IPCChannelDef<
    [data: { title: string; content?: string; tags?: string[] }],
    IPCResponse<Document & { tags: string[] }>
  >;
  [IPC_CHANNELS.DOCUMENT.GET]: IPCChannelDef<[docId: string], IPCResponse<Document | null>>;
  [IPC_CHANNELS.DOCUMENT.LIST]: IPCChannelDef<[], IPCResponse<Document[]>>;
  [IPC_CHANNELS.DOCUMENT.UPDATE]: IPCChannelDef<
    [docId: string, updates: Partial<Document>],
    IPCResponse<Document & { tags: string[] }>
  >;
  [IPC_CHANNELS.DOCUMENT.DELETE]: IPCChannelDef<[docId: string], IPCResponse<boolean>>;

  [IPC_CHANNELS.TAG.LIST]: IPCChannelDef<[], IPCResponse<Tag[]>>;

  [IPC_CHANNELS.SETTINGS.GET]: IPCChannelDef<[], IPCResponse<AppSettings | null>>;
  [IPC_CHANNELS.SETTINGS.SET]: IPCChannelDef<[settings: Partial<AppSettings>], IPCResponse<void>>;

  [IPC_CHANNELS.GIT.INIT]: IPCChannelDef<[], IPCResponse<void>>;
  [IPC_CHANNELS.GIT.STATUS]: IPCChannelDef<[], IPCResponse<GitStatus[]>>;
  [IPC_CHANNELS.GIT.COMMIT]: IPCChannelDef<[message: string], IPCResponse<string>>;
  [IPC_CHANNELS.GIT.PUSH]: IPCChannelDef<[], IPCResponse<void>>;
  [IPC_CHANNELS.GIT.PULL]: IPCChannelDef<[], IPCResponse<void>>;
  [IPC_CHANNELS.GIT.LOG]: IPCChannelDef<[maxCount?: number], IPCResponse<GitCommit[]>>;
  [IPC_CHANNELS.GIT.DIFF]: IPCChannelDef<[filepath: string], IPCResponse<DiffHunk[]>>;
  [IPC_CHANNELS.GIT.CLONE]: IPCChannelDef<[url: string, branch?: string], IPCResponse<void>>;
  [IPC_CHANNELS.GIT.CONFIG_GET]: IPCChannelDef<[], IPCResponse<GitConfig>>;
  [IPC_CHANNELS.GIT.CONFIG_SET]: IPCChannelDef<[config: Partial<GitConfig>], IPCResponse<void>>;
  [IPC_CHANNELS.GIT.REMOTE_SET]: IPCChannelDef<[url: string], IPCResponse<void>>;

  [IPC_CHANNELS.DB.DOCUMENT_UPSERT]: IPCChannelDef<
    [filePath: string, content: string],
    IPCResponse<Document & { tags: string[] }>
  >;
  [IPC_CHANNELS.DB.DOCUMENT_GET]: IPCChannelDef<
    [id: string],
    IPCResponse<(Document & { tags: string[] }) | null>
  >;
  [IPC_CHANNELS.DB.DOCUMENT_GET_BY_PATH]: IPCChannelDef<
    [path: string],
    IPCResponse<(Document & { tags: string[] }) | null>
  >;
  [IPC_CHANNELS.DB.DOCUMENT_LIST]: IPCChannelDef<
    [options?: {
      limit?: number;
      offset?: number;
      tag?: string;
      sortBy?: 'updated_at' | 'created_at' | 'title';
      sortOrder?: 'ASC' | 'DESC';
    }],
    IPCResponse<(Document & { tags: string[] })[]>
  >;
  [IPC_CHANNELS.DB.DOCUMENT_DELETE]: IPCChannelDef<[id: string], IPCResponse<void>>;
  [IPC_CHANNELS.DB.DOCUMENT_SEARCH]: IPCChannelDef<
    [keyword: string],
    IPCResponse<(Document & { tags: string[] })[]>
  >;
  [IPC_CHANNELS.DB.TAG_LIST]: IPCChannelDef<[], IPCResponse<Tag[]>>;
  [IPC_CHANNELS.DB.TAG_GET_DOCUMENTS]: IPCChannelDef<
    [tagName: string],
    IPCResponse<(Document & { tags: string[] })[]>
  >;
  [IPC_CHANNELS.DB.BACKLINK_GET_TO]: IPCChannelDef<
    [docId: string],
    IPCResponse<Array<Backlink & { fromDoc: Document }>>
  >;
  [IPC_CHANNELS.DB.STATS_GET]: IPCChannelDef<[], IPCResponse<AppStats>>;

  [IPC_CHANNELS.SEARCH.QUERY]: IPCChannelDef<
    [query: string, options?: {
      tags?: string[];
      sortBy?: 'relevance' | 'date';
      limit?: number;
    }],
    IPCResponse<SearchResult[]>
  >;
  [IPC_CHANNELS.SEARCH.REINDEX]: IPCChannelDef<[], IPCResponse<void>>;
  [IPC_CHANNELS.SEARCH.INDEX_DOCUMENT]: IPCChannelDef<
    [doc: DocumentCreateInput, content: string],
    IPCResponse<void>
  >;
  [IPC_CHANNELS.SEARCH.REMOVE_DOCUMENT]: IPCChannelDef<[docId: string], IPCResponse<void>>;

  [IPC_CHANNELS.TEMPLATE.LIST]: IPCChannelDef<[], IPCResponse<any[]>>;
  [IPC_CHANNELS.TEMPLATE.GET]: IPCChannelDef<[templateId: string], IPCResponse<any>>;
  [IPC_CHANNELS.TEMPLATE.GET_VARIABLES]: IPCChannelDef<
    [templateId: string],
    IPCResponse<TemplateVariable[]>
  >;
  [IPC_CHANNELS.TEMPLATE.RENDER]: IPCChannelDef<
    [templateId: string, variables?: Record<string, string>],
    IPCResponse<string>
  >;
  [IPC_CHANNELS.TEMPLATE.CREATE]: IPCChannelDef<
    [templateId: string, variables?: Record<string, string>, customTitle?: string],
    IPCResponse<Document & { tags: string[] }>
  >;
  [IPC_CHANNELS.TEMPLATE.SAVE]: IPCChannelDef<
    [template: any],
    IPCResponse<any>
  >;
  [IPC_CHANNELS.TEMPLATE.DELETE]: IPCChannelDef<[templateId: string], IPCResponse<boolean>>;

  [IPC_CHANNELS.IMPORT.FROM_ZIP]: IPCChannelDef<
    [zipPath: string, source: 'notion' | 'yuque' | 'markdown', options?: any],
    IPCResponse<{ success: boolean; documents: Document[]; error?: string }>
  >;
  [IPC_CHANNELS.IMPORT.FROM_DIR]: IPCChannelDef<
    [dirPath: string, options?: any],
    IPCResponse<{ success: boolean; documents: Document[]; error?: string }>
  >;
  [IPC_CHANNELS.IMPORT.SELECT_FILE]: IPCChannelDef<[], IPCResponse<string | null>>;
  [IPC_CHANNELS.IMPORT.SELECT_DIR]: IPCChannelDef<[], IPCResponse<string | null>>;

  [IPC_CHANNELS.PLUGIN.LIST]: IPCChannelDef<[], IPCResponse<any[]>>;
  [IPC_CHANNELS.PLUGIN.INSTALL]: IPCChannelDef<[], IPCResponse<void>>;
  [IPC_CHANNELS.PLUGIN.UNINSTALL]: IPCChannelDef<[pluginId: string], IPCResponse<void>>;
  [IPC_CHANNELS.PLUGIN.ENABLE]: IPCChannelDef<[pluginId: string], IPCResponse<void>>;
  [IPC_CHANNELS.PLUGIN.DISABLE]: IPCChannelDef<[pluginId: string], IPCResponse<void>>;
  [IPC_CHANNELS.PLUGIN.GET_SETTINGS]: IPCChannelDef<[pluginId: string], IPCResponse<any>>;
  [IPC_CHANNELS.PLUGIN.SET_SETTINGS]: IPCChannelDef<
    [pluginId: string, settings: any],
    IPCResponse<void>
  >;

  [IPC_CHANNELS.EXPORT.STATIC_SITE]: IPCChannelDef<
    [options: { outputPath?: string; theme?: 'light' | 'dark'; includeGraph?: boolean }],
    IPCResponse<{ outputPath: string }>
  >;
  [IPC_CHANNELS.EXPORT.MARKDOWN]: IPCChannelDef<
    [docId: string, outputPath?: string],
    IPCResponse<{ outputPath: string }>
  >;

  [IPC_CHANNELS.APP.GET_INITIALIZED]: IPCChannelDef<[], IPCResponse<boolean>>;
  [IPC_CHANNELS.APP.INITIALIZE]: IPCChannelDef<
    [settings?: Partial<AppSettings>],
    IPCResponse<void>
  >;
  [IPC_CHANNELS.APP.GET_SETTINGS]: IPCChannelDef<[], IPCResponse<AppSettings>>;
  [IPC_CHANNELS.APP.SET_SETTINGS]: IPCChannelDef<
    [settings: Partial<AppSettings>],
    IPCResponse<void>
  >;
  [IPC_CHANNELS.APP.OPEN_EXTERNAL]: IPCChannelDef<[url: string], IPCResponse<void>>;
  [IPC_CHANNELS.APP.SHOW_ITEM_IN_FOLDER]: IPCChannelDef<[path: string], IPCResponse<void>>;
}

export type IPCChannelKey = keyof IPCApi;

export type IPCChannelArgs<K extends IPCChannelKey> = IPCApi[K]['args'];
export type IPCChannelReturn<K extends IPCChannelKey> = IPCApi[K]['return'];

export interface IPCHandler<K extends IPCChannelKey> {
  (
    event: Electron.IpcMainInvokeEvent,
    ...args: IPCChannelArgs<K>
  ): Promise<IPCChannelReturn<K>> | IPCChannelReturn<K>;
}

export interface TypedIpcMain {
  handle<K extends IPCChannelKey>(
    channel: K,
    handler: IPCHandler<K>
  ): void;
  removeHandler<K extends IPCChannelKey>(channel: K): void;
}

export interface TypedIpcRenderer {
  invoke<K extends IPCChannelKey>(
    channel: K,
    ...args: IPCChannelArgs<K>
  ): Promise<IPCChannelReturn<K>>;
}



export function invokeIPC<K extends IPCChannelKey>(
  channel: K,
  ...args: IPCChannelArgs<K>
): Promise<IPCChannelReturn<K>> {
  return window.electron.ipc.invoke(channel, ...args);
}
