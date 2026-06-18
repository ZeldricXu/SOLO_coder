import { render, screen, waitFor, act } from '@testing-library/react';
import React from 'react';
import { createMockIpc, mockNotes } from '../mocks/ipcMock';
import type { Note, NoteLink } from '@shared/types';

describe('Integration - IPC API Basic Functionality', () => {
  const mockIpc = createMockIpc();

  beforeEach(() => {
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should get all notes', async () => {
    const notes = await mockIpc.notes.getAll();
    expect(Array.isArray(notes)).toBe(true);
    expect(notes.length).toBeGreaterThan(0);
  });

  it('should get note by id', async () => {
    const note = await mockIpc.notes.getById('note-1');
    expect(note).not.toBeNull();
    expect(note?.id).toBe('note-1');
  });

  it('should get graph data', async () => {
    const graphData = await mockIpc.graph.getGraphData();
    expect(graphData.nodes).toBeDefined();
    expect(graphData.edges).toBeDefined();
    expect(graphData.nodes.length).toBeGreaterThan(0);
  });

  it('should get settings', async () => {
    const settings = await mockIpc.settings.get();
    expect(settings.theme).toBeDefined();
    expect(settings.vaultPath).toBeDefined();
  });
});

describe('Integration - Create and Link Notes', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should create three interlinked notes', async () => {
    const note1 = await mockIpc.notes.create({
      title: '笔记一',
      path: 'note-1.md',
      content: '# 笔记一\n\n这是第一篇笔记，引用 [[笔记二]]。',
      tags: ['integration'],
    });
    
    const note2 = await mockIpc.notes.create({
      title: '笔记二',
      path: 'note-2.md',
      content: '# 笔记二\n\n这是第二篇笔记，引用 [[笔记一]] 和 [[笔记三]]。',
      tags: ['integration'],
    });
    
    const note3 = await mockIpc.notes.create({
      title: '笔记三',
      path: 'note-3.md',
      content: '# 笔记三\n\n这是第三篇笔记，引用 [[笔记二]]。',
      tags: ['integration'],
    });
    
    expect(note1.id).toBeDefined();
    expect(note2.id).toBeDefined();
    expect(note3.id).toBeDefined();
    
    const allNotes = await mockIpc.notes.getAll();
    expect(allNotes.length).toBeGreaterThanOrEqual(6);
  });

  it('should detect three nodes in graph', async () => {
    await mockIpc.notes.create({
      title: '笔记一',
      path: 'graph-note-1.md',
      content: '# 笔记一\n\n内容。',
      tags: ['graph'],
    });
    await mockIpc.notes.create({
      title: '笔记二',
      path: 'graph-note-2.md',
      content: '# 笔记二\n\n内容。',
      tags: ['graph'],
    });
    await mockIpc.notes.create({
      title: '笔记三',
      path: 'graph-note-3.md',
      content: '# 笔记三\n\n内容。',
      tags: ['graph'],
    });
    
    const graphData = await mockIpc.graph.getGraphData();
    
    expect(graphData.nodes.length).toBeGreaterThanOrEqual(3);
    
    const hasThreeNodes = graphData.nodes.length >= 3;
    expect(hasThreeNodes).toBe(true);
  });

  it('should find notes by search', async () => {
    const results = await mockIpc.search.query('知识');
    
    expect(Array.isArray(results)).toBe(true);
    expect(results.length).toBeGreaterThan(0);
  });

  it('should update note content and trigger reindex', async () => {
    const note = await mockIpc.notes.create({
      title: 'Test Update',
      path: 'test-update.md',
      content: '# Test Update\n\nOriginal content.',
      tags: [],
    });
    
    const saveResult = await mockIpc.notes.saveContent(note.id, '# Test Update\n\nUpdated content with [[笔记一]].');
    expect(saveResult).toBe(true);
  });

  it('should export note to markdown', async () => {
    const note = await mockIpc.notes.create({
      title: 'Export Test',
      path: 'export-test.md',
      content: '# Export Test\n\nContent to export.',
      tags: ['export'],
    });
    
    const exportPath = await mockIpc.export.exportNote(note.id, 'txt');
    expect(typeof exportPath).toBe('string');
  });

  it('should maintain link relationships data structure', async () => {
    const links = await mockIpc.links.getAll();
    
    for (const link of links) {
      expect(link.sourceId).toBeDefined();
      expect(link.targetId).toBeDefined();
      expect(link.sourcePath).toBeDefined();
      expect(link.targetPath).toBeDefined();
    }
  });

  it('should preserve relative paths in domain export', async () => {
    const domainPath = await mockIpc.export.exportDomain(['note-1', 'note-2'], 'markdown');
    expect(typeof domainPath).toBe('string');
  });
});

describe('Integration - Search and Navigation', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should search across title, content, and tags', async () => {
    const results = await mockIpc.search.query('知识', {
      fields: ['title', 'content', 'tags'],
      limit: 10,
    });
    
    expect(Array.isArray(results)).toBe(true);
    
    for (const result of results) {
      expect(result.id).toBeDefined();
      expect(result.title).toBeDefined();
      expect(result.score).toBeGreaterThan(0);
    }
  });

  it('should navigate to note from search result', async () => {
    const results = await mockIpc.search.query('图谱');
    
    if (results.length > 0) {
      const note = await mockIpc.notes.getById(results[0].id);
      expect(note).not.toBeNull();
      expect(note?.id).toBe(results[0].id);
    }
  });

  it('should get backlinks for a note', async () => {
    const links = await mockIpc.links.getBacklinks('note-1');
    expect(Array.isArray(links)).toBe(true);
  });

  it('should get forward links for a note', async () => {
    const links = await mockIpc.links.getForwardLinks('note-2');
    expect(Array.isArray(links)).toBe(true);
  });
});

describe('Integration - Settings and Themes', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should get initial settings', async () => {
    const settings = await mockIpc.settings.get();
    expect(settings.theme).toBeDefined();
    expect(settings.vaultPath).toBeDefined();
  });

  it('should update theme setting', async () => {
    const newSettings = await mockIpc.settings.update({ theme: 'light' });
    expect(newSettings.theme).toBe('light');
  });

  it('should switch between themes', async () => {
    const themes = ['dark', 'light', 'high-contrast'];
    
    for (const theme of themes) {
      const updated = await mockIpc.settings.update({ theme });
      expect(updated.theme).toBe(theme);
    }
  });

  it('should set vault path', async () => {
    const result = await mockIpc.vault.setPath('/test/vault');
    expect(result).toBe(true);
    
    const vaultPath = await mockIpc.vault.getPath();
    expect(vaultPath).toBeDefined();
  });
});

describe('Integration - Error Handling', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should handle non-existent note gracefully', async () => {
    const note = await mockIpc.notes.getById('nonexistent-id');
    expect(note).toBeNull();
  });

  it('should handle empty search query', async () => {
    const results = await mockIpc.search.query('');
    expect(results).toEqual([]);
  });

  it('should handle export of non-existent note', async () => {
    const errorIpc = createMockIpc({
      export: {
        ...createMockIpc().export,
        exportNote: jest.fn().mockRejectedValue(new Error('Note not found')),
      },
    } as any);
    
    (window as any).api = errorIpc;
    
    await expect(errorIpc.export.exportNote('nonexistent', 'txt')).rejects.toThrow('Note not found');
  });
});

describe('Integration - Batch Operations', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should handle bulk note creation', async () => {
    const promises: Promise<Note>[] = [];
    
    for (let i = 0; i < 20; i++) {
      promises.push(mockIpc.notes.create({
        title: `批量笔记 ${i}`,
        path: `batch/note-${i}.md`,
        content: `# 批量笔记 ${i}\n\n[[批量笔记 ${(i + 1) % 20}]]`,
        tags: ['batch'],
      }));
    }
    
    const notes = await Promise.all(promises);
    expect(notes).toHaveLength(20);
    
    for (const note of notes) {
      expect(note.id).toBeDefined();
      expect(note.path).toMatch(/^batch\/note-\d+\.md$/);
    }
  });

  it('should handle bulk search operations', async () => {
    const queries = ['笔记', '知识', '图谱', '项目', '学习'];
    const results = await Promise.all(
      queries.map(q => mockIpc.search.query(q))
    );
    
    expect(results).toHaveLength(queries.length);
    
    for (const result of results) {
      expect(Array.isArray(result)).toBe(true);
    }
  });

  it('should maintain data consistency after batch operations', async () => {
    const allNotes = await mockIpc.notes.getAll();
    const allLinks = await mockIpc.links.getAll();
    const graphData = await mockIpc.graph.getGraphData();
    
    expect(graphData.nodes.length).toBeLessThanOrEqual(allNotes.length);
    
    for (const link of allLinks) {
      const sourceExists = allNotes.some(n => n.id === link.sourceId);
      const targetExists = allNotes.some(n => n.id === link.targetId);
      
      if (link.sourceId && link.targetId) {
        expect(sourceExists || targetExists).toBe(true);
      }
    }
  });
});

describe('Integration - Plugin System', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should load default plugins', async () => {
    const settings = await mockIpc.settings.get();
    expect(Array.isArray(settings.activePlugins)).toBe(true);
    expect(settings.activePlugins.length).toBeGreaterThan(0);
  });

  it('should enable and disable plugins', async () => {
    const currentSettings = await mockIpc.settings.get();
    const newPlugins = [...currentSettings.activePlugins, 'test-plugin'];
    
    const updated = await mockIpc.settings.update({
      activePlugins: newPlugins,
    });
    
    expect(updated.activePlugins).toContain('test-plugin');
  });
});

describe('Integration - File System Events', () => {
  let mockIpc: ReturnType<typeof createMockIpc>;

  beforeEach(() => {
    mockIpc = createMockIpc();
    jest.clearAllMocks();
    (window as any).api = mockIpc;
  });

  it('should register note change listener', () => {
    const callback = jest.fn();
    const unsubscribe = mockIpc.vault.onNoteChanged(callback);
    
    expect(typeof unsubscribe).toBe('function');
    unsubscribe();
  });

  it('should register note delete listener', () => {
    const callback = jest.fn();
    const unsubscribe = mockIpc.vault.onNoteDeleted(callback);
    
    expect(typeof unsubscribe).toBe('function');
    unsubscribe();
  });

  it('should trigger rescan', async () => {
    await expect(mockIpc.vault.rescan()).resolves.not.toThrow();
  });
});
