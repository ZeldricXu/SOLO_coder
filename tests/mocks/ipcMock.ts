import type { Note, NoteLink, GraphData, SearchResult, AppSettings, IpcRendererApi } from '@shared/types';

const mockNotes: Note[] = [
  {
    id: 'note-1',
    title: '知识图谱概述',
    path: 'ideas/知识图谱概述.md',
    content: '# 知识图谱概述\n\n知识图谱是一种用图模型来描述知识的技术。',
    frontmatter: { tags: ['ideas', 'knowledge-graph'] },
    tags: ['ideas', 'knowledge-graph'],
    createdAt: Date.now() - 86400000 * 5,
    updatedAt: Date.now() - 86400000,
  },
  {
    id: 'note-2',
    title: '双链笔记',
    path: 'ideas/双链笔记.md',
    content: '# 双链笔记\n\n双链笔记允许笔记之间建立双向引用关系。\n\n[[知识图谱概述]]',
    frontmatter: { tags: ['ideas', 'note-taking'] },
    tags: ['ideas', 'note-taking'],
    createdAt: Date.now() - 86400000 * 3,
    updatedAt: Date.now() - 3600000,
  },
  {
    id: 'note-3',
    title: '项目管理方法',
    path: 'work/项目管理方法.md',
    content: '# 项目管理方法\n\n有效的项目管理是确保项目成功的关键。',
    frontmatter: { tags: ['work', 'project-management'] },
    tags: ['work', 'project-management'],
    createdAt: Date.now() - 86400000 * 7,
    updatedAt: Date.now() - 86400000 * 2,
  },
];

const mockLinks: NoteLink[] = [
  {
    id: 'link-1',
    sourceId: 'note-2',
    targetId: 'note-1',
    sourcePath: 'ideas/双链笔记.md',
    targetPath: 'ideas/知识图谱概述.md',
    linkText: '知识图谱概述',
    context: '双链笔记允许笔记之间建立双向引用关系。 [[知识图谱概述]]',
    createdAt: Date.now(),
  },
];

const mockGraphData: GraphData = {
  nodes: [
    { id: 'note-1', label: '知识图谱概述', path: 'ideas/知识图谱概述.md', tags: ['ideas'], size: 15, cluster: 'ideas' },
    { id: 'note-2', label: '双链笔记', path: 'ideas/双链笔记.md', tags: ['ideas'], size: 12, cluster: 'ideas' },
    { id: 'note-3', label: '项目管理方法', path: 'work/项目管理方法.md', tags: ['work'], size: 10, cluster: 'work' },
  ],
  edges: [
    { id: 'edge-1', source: 'note-2', target: 'note-1', weight: 1 },
  ],
};

const mockSettings: AppSettings = {
  vaultPath: '',
  theme: 'dark',
  layouts: [],
  activePlugins: ['backlinks', 'tags', 'command-palette'],
};

export const createMockIpc = (overrides: Partial<IpcRendererApi> = {}): IpcRendererApi => {
  let currentSettings = { ...mockSettings };
  let notes = [...mockNotes];
  let links = [...mockLinks];
  
  return {
    notes: {
      getAll: jest.fn().mockImplementation(() => Promise.resolve([...notes])),
      getById: jest.fn().mockImplementation((id: string) => 
        Promise.resolve(notes.find(n => n.id === id) || null)
      ),
      getByPath: jest.fn().mockImplementation((path: string) =>
        Promise.resolve(notes.find(n => n.path === path) || null)
      ),
      create: jest.fn().mockImplementation((note: Partial<Note>) => {
        const newNote = {
          id: `note-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
          title: note.title || 'Untitled',
          path: note.path || 'untitled.md',
          content: note.content || '',
          frontmatter: note.frontmatter || {},
          tags: note.tags || [],
          createdAt: Date.now(),
          updatedAt: Date.now(),
        } as Note;
        notes.push(newNote);
        return Promise.resolve(newNote);
      }),
      update: jest.fn().mockImplementation((id: string, updates: Partial<Note>) => {
        const index = notes.findIndex(n => n.id === id);
        if (index !== -1) {
          notes[index] = { ...notes[index], ...updates, updatedAt: Date.now() } as Note;
          return Promise.resolve(notes[index]);
        }
        return Promise.resolve(null);
      }),
      delete: jest.fn().mockImplementation((id: string) => {
        const index = notes.findIndex(n => n.id === id);
        if (index !== -1) {
          notes.splice(index, 1);
          return Promise.resolve(true);
        }
        return Promise.resolve(false);
      }),
      saveContent: jest.fn().mockResolvedValue(true),
    },
    links: {
      getAll: jest.fn().mockImplementation(() => Promise.resolve([...links])),
      getBacklinks: jest.fn().mockImplementation((noteId: string) =>
        Promise.resolve(links.filter(l => l.targetId === noteId))
      ),
      getForwardLinks: jest.fn().mockImplementation((noteId: string) =>
        Promise.resolve(links.filter(l => l.sourceId === noteId))
      ),
    },
    graph: {
      getGraphData: jest.fn().mockImplementation(() => {
        const graphNodes = notes.map(n => ({
          id: n.id,
          label: n.title,
          path: n.path,
          tags: n.tags,
          size: 10 + Math.random() * 10,
          cluster: n.tags?.[0] || 'default',
        }));
        const graphEdges = links.map(l => ({
          id: l.id,
          source: l.sourceId,
          target: l.targetId,
          weight: 1,
        }));
        return Promise.resolve({ nodes: graphNodes, edges: graphEdges });
      }),
    },
    search: {
      query: jest.fn().mockImplementation((q: string) => {
        if (!q || q.trim() === '') {
          return Promise.resolve([]);
        }
        const results: SearchResult[] = notes
          .filter(n => n.title.includes(q) || n.content.includes(q) || n.tags?.some(t => t.includes(q)))
          .map(n => ({
            id: n.id,
            title: n.title,
            path: n.path,
            score: 1,
            fields: { title: n.title, content: n.content },
            highlight: { title: n.title, content: n.content.slice(0, 100) },
          }));
        return Promise.resolve(results);
      }),
    },
    vault: {
      setPath: jest.fn().mockResolvedValue(true),
      getPath: jest.fn().mockResolvedValue('/mock/vault'),
      rescan: jest.fn().mockResolvedValue(undefined),
      onNoteChanged: jest.fn().mockReturnValue(() => {}),
      onNoteDeleted: jest.fn().mockReturnValue(() => {}),
    },
    settings: {
      get: jest.fn().mockImplementation(() => Promise.resolve({ ...currentSettings })),
      update: jest.fn().mockImplementation((updates: Partial<AppSettings>) => {
        currentSettings = { ...currentSettings, ...updates };
        return Promise.resolve({ ...currentSettings });
      }),
    },
    export: {
      exportNote: jest.fn().mockResolvedValue('/mock/path/note.md'),
      exportDomain: jest.fn().mockResolvedValue('/mock/path/domain'),
      exportGraphPNG: jest.fn().mockResolvedValue('/mock/path/graph.png'),
    },
    dialog: {
      openFile: jest.fn().mockResolvedValue('/mock/path/file.md'),
      openDirectory: jest.fn().mockResolvedValue('/mock/vault'),
      saveFile: jest.fn().mockResolvedValue('/mock/path/export.md'),
    },
    ...overrides,
  };
};

export const mockIpc = createMockIpc();
export default mockIpc;
export { mockNotes, mockLinks, mockGraphData, mockSettings };
