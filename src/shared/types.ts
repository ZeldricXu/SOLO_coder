export interface Note {
  id: string;
  title: string;
  path: string;
  content: string;
  frontmatter: Record<string, any>;
  tags: string[];
  createdAt: number;
  updatedAt: number;
}

export interface NoteLink {
  id: string;
  sourceId: string;
  targetId: string;
  sourcePath: string;
  targetPath: string;
  linkText: string;
  context: string;
  createdAt: number;
}

export interface BrokenLink {
  id: string;
  sourceId: string;
  sourcePath: string;
  targetText: string;
  originalLink: string;
  displayText: string;
  context: string;
  suggestions: LinkSuggestion[];
}

export interface LinkSuggestion {
  noteId: string;
  title: string;
  path: string;
  similarity: number;
  similarityType: 'levenshtein' | 'jaccard' | 'combined';
}

export interface GraphNode {
  id: string;
  label: string;
  path: string;
  tags: string[];
  size: number;
  cluster: string;
  nodeType?: 'note' | 'tag' | 'external';
  x?: number;
  y?: number;
  vx?: number;
  vy?: number;
  fx?: number | null;
  fy?: number | null;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  weight: number;
}

export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface FocusGraphOptions {
  centerNoteId: string;
  depth: number;
  includeTags: boolean;
  includeExternal: boolean;
}

export interface AttachmentFile {
  id: string;
  name: string;
  path: string;
  relativePath: string;
  size: number;
  type: 'image' | 'pdf' | 'document' | 'other';
  mimeType: string;
  createdAt: number;
  updatedAt: number;
  thumbnail?: string;
}

export interface SearchResult {
  id: string;
  title: string;
  path: string;
  score: number;
  fields: {
    title?: string;
    content?: string;
    tags?: string[];
  };
  highlight?: {
    title?: string;
    content?: string;
  };
}

export interface Theme {
  id: string;
  name: string;
  variables: Record<string, string>;
}

export interface PluginDefinition {
  id: string;
  name: string;
  version: string;
  description: string;
  activate: (context: PluginContext) => void;
  deactivate?: () => void;
}

export interface PluginContext {
  registerRenderer: (type: string, renderer: any) => void;
  registerCommand: (command: PluginCommand) => void;
  registerSidebarWidget: (widget: SidebarWidget) => void;
  store: {
    getState: () => any;
    setState: (state: any) => void;
  };
  ipc: IpcRendererApi;
}

export interface PluginCommand {
  id: string;
  label: string;
  shortcut?: string;
  execute: () => void | Promise<void>;
}

export interface SidebarWidget {
  id: string;
  title: string;
  icon?: string;
  component: React.ComponentType;
  defaultOpen?: boolean;
}

export interface PanelLayout {
  id: string;
  title: string;
  position: 'left' | 'right' | 'bottom' | 'center' | 'top';
  size: { width?: number; height?: number };
  visible: boolean;
  order: number;
}

export interface AppSettings {
  vaultPath: string;
  theme: string;
  layouts: PanelLayout[];
  activePlugins: string[];
  linkFixThreshold: number;
  focusGraphDefaultDepth: number;
}

export interface IpcRendererApi {
  notes: {
    getAll: () => Promise<Note[]>;
    getById: (id: string) => Promise<Note | null>;
    getByPath: (path: string) => Promise<Note | null>;
    create: (note: Partial<Note> & { content: string }) => Promise<Note>;
    update: (id: string, updates: Partial<Note>) => Promise<Note | null>;
    delete: (id: string) => Promise<boolean>;
    saveContent: (id: string, content: string) => Promise<boolean>;
    findSimilarNotes: (title: string, threshold?: number) => Promise<LinkSuggestion[]>;
    updateLinkTarget: (sourceNoteId: string, oldTarget: string, newTargetId: string) => Promise<{ success: boolean; newContent?: string }>;
    scanBrokenLinks: (noteId?: string) => Promise<BrokenLink[]>;
  };
  links: {
    getAll: () => Promise<NoteLink[]>;
    getBacklinks: (noteId: string) => Promise<NoteLink[]>;
    getForwardLinks: (noteId: string) => Promise<NoteLink[]>;
    migrateBacklinks: (oldNoteId: string, newNoteId: string) => Promise<number>;
  };
  graph: {
    getGraphData: () => Promise<GraphData>;
    getFocusGraphData: (options: FocusGraphOptions) => Promise<GraphData>;
  };
  search: {
    query: (q: string, options?: SearchOptions) => Promise<SearchResult[]>;
  };
  vault: {
    setPath: (path: string) => Promise<boolean>;
    getPath: () => Promise<string>;
    rescan: () => Promise<void>;
    onNoteChanged: (callback: (event: any, note: Note) => void) => () => void;
    onNoteDeleted: (callback: (event: any, path: string) => void) => () => void;
  };
  attachments: {
    list: () => Promise<AttachmentFile[]>;
    upload: (fileData: string | { name: string; type: string; size: number; data: Buffer }, targetDir?: string) => Promise<AttachmentFile | { success: boolean; relativePath: string; attachment: AttachmentFile }>;
    delete: (attachmentId: string) => Promise<boolean>;
    rename: (attachmentId: string, newName: string) => Promise<AttachmentFile | null>;
    getThumbnail: (attachmentId: string) => Promise<string | null>;
    getAssetsPath: () => Promise<string>;
  };
  settings: {
    get: () => Promise<AppSettings>;
    update: (settings: Partial<AppSettings>) => Promise<AppSettings>;
  };
  export: {
    exportNote: (id: string, format: 'txt' | 'html' | 'pdf') => Promise<string>;
    exportDomain: (noteIds: string[], format: 'markdown') => Promise<string>;
    exportGraphPNG: (svgData: string) => Promise<string>;
  };
  dialog: {
    openFile: (options?: any) => Promise<string | null>;
    openDirectory: (options?: any) => Promise<string | null>;
    saveFile: (options?: any) => Promise<string | null>;
  };
  theme: {
    get: () => Promise<string>;
    set: (theme: string) => Promise<string>;
  };
}

export interface SearchOptions {
  fields?: ('title' | 'content' | 'tags')[];
  limit?: number;
  highlight?: boolean;
}
