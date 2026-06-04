export const ROUTES = {
  DASHBOARD: '/',
  EDITOR: '/editor',
  EDITOR_DOCUMENT: '/editor/:docId',
  GRAPH: '/graph',
  SEARCH: '/search',
  GIT: '/git',
  TEMPLATES: '/templates',
  IMPORT: '/import',
  PLUGINS: '/plugins',
  SETTINGS: '/settings',
  SETTINGS_GENERAL: '/settings/general',
  SETTINGS_GIT: '/settings/git',
  SETTINGS_EDITOR: '/settings/editor',
  SETTINGS_STORAGE: '/settings/storage',
  SETTINGS_PLUGINS: '/settings/plugins',
} as const;

export type AppRoute = typeof ROUTES[keyof typeof ROUTES];

export const NAV_ITEMS = [
  { path: ROUTES.DASHBOARD, icon: 'LayoutDashboard', label: '仪表盘' },
  { path: ROUTES.EDITOR, icon: 'FileText', label: '编辑器' },
  { path: ROUTES.GRAPH, icon: 'Network', label: '知识图谱' },
  { path: ROUTES.SEARCH, icon: 'Search', label: '搜索' },
  { path: ROUTES.GIT, icon: 'GitBranch', label: '版本管理' },
  { path: ROUTES.TEMPLATES, icon: 'FileTemplate', label: '模板库' },
  { path: ROUTES.IMPORT, icon: 'Upload', label: '导入' },
  { path: ROUTES.PLUGINS, icon: 'Puzzle', label: '插件' },
  { path: ROUTES.SETTINGS, icon: 'Settings', label: '设置' },
] as const;
