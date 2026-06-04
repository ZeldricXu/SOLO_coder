export interface PluginManifest {
  id: string;
  name: string;
  version: string;
  description: string;
  author: string;
  main: string;
  permissions: PluginPermission[];
  extensionPoints: ExtensionPoint[];
}

export type PluginPermission = 
  | 'read:documents'
  | 'write:documents'
  | 'search:documents'
  | 'git:read'
  | 'git:write'
  | 'settings:read'
  | 'settings:write';

export type ExtensionPointType = 
  | 'editor:component'
  | 'sidebar:panel'
  | 'import:filter'
  | 'export:format'
  | 'ai:service'
  | 'command';

export interface ExtensionPoint {
  type: ExtensionPointType;
  id: string;
  name: string;
  description?: string;
}

export interface PluginInfo extends PluginManifest {
  enabled: boolean;
  installedAt: Date;
  path: string;
  hasUpdate: boolean;
  latestVersion?: string;
}

export interface EditorExtension {
  id: string;
  pluginId: string;
  name: string;
  component: string;
  position: 'toolbar' | 'sidebar' | 'inline';
}

export interface SidebarPanel {
  id: string;
  pluginId: string;
  name: string;
  icon: string;
  component: string;
}

export interface ImportFilter {
  id: string;
  pluginId: string;
  name: string;
  fileExtensions: string[];
  handler: string;
}

export interface ExportFormat {
  id: string;
  pluginId: string;
  name: string;
  fileExtension: string;
  handler: string;
}

export interface AIService {
  id: string;
  pluginId: string;
  name: string;
  features: ('autocomplete' | 'summarize' | 'translate' | 'chat')[];
  handler: string;
}
