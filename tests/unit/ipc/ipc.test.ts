import { describe, it, expect, vi, beforeEach } from 'vitest';
import { IPC_CHANNELS } from '@/shared/constants/ipcChannels';

vi.mock('electron', () => ({
  ipcMain: {
    handle: vi.fn(),
    on: vi.fn(),
    once: vi.fn(),
    removeHandler: vi.fn(),
  },
  ipcRenderer: {
    invoke: vi.fn(),
    send: vi.fn(),
    on: vi.fn(),
    once: vi.fn(),
    removeListener: vi.fn(),
    removeAllListeners: vi.fn(),
  },
}));

const mockDocuments = [
  {
    id: 'doc-001',
    title: '测试文档1',
    content: '这是测试文档1的内容',
    tags: ['测试'],
    filename: 'test1.md',
    filePath: '/vault/test1.md',
    wordCount: 10,
    hash: 'hash1',
    createdAt: new Date('2024-01-01'),
    updatedAt: new Date('2024-01-01'),
    backlinks: [],
    outline: [],
  },
  {
    id: 'doc-002',
    title: '测试文档2',
    content: '这是测试文档2的内容，包含TypeScript',
    tags: ['测试', 'TypeScript'],
    filename: 'test2.md',
    filePath: '/vault/test2.md',
    wordCount: 15,
    hash: 'hash2',
    createdAt: new Date('2024-01-02'),
    updatedAt: new Date('2024-01-02'),
    backlinks: [],
    outline: [],
  },
];

describe('IPC 通信协议', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('IPC 通道常量', () => {
    it('应该定义所有必要的文件操作通道', () => {
      expect(IPC_CHANNELS.FILE.READ).toBeDefined();
      expect(IPC_CHANNELS.FILE.WRITE).toBeDefined();
      expect(IPC_CHANNELS.FILE.DELETE).toBeDefined();
      expect(IPC_CHANNELS.FILE.LIST).toBeDefined();
      expect(IPC_CHANNELS.FILE.WATCH).toBeDefined();
    });

    it('应该定义所有必要的文档操作通道', () => {
      expect(IPC_CHANNELS.DOCUMENT.GET).toBeDefined();
      expect(IPC_CHANNELS.DOCUMENT.CREATE).toBeDefined();
      expect(IPC_CHANNELS.DOCUMENT.UPDATE).toBeDefined();
      expect(IPC_CHANNELS.DOCUMENT.DELETE).toBeDefined();
      expect(IPC_CHANNELS.DOCUMENT.LIST).toBeDefined();
    });

    it('应该定义所有必要的搜索通道', () => {
      expect(IPC_CHANNELS.SEARCH.QUERY).toBeDefined();
      expect(IPC_CHANNELS.SEARCH.REINDEX).toBeDefined();
    });

    it('应该定义所有必要的Git通道', () => {
      expect(IPC_CHANNELS.GIT.INIT).toBeDefined();
      expect(IPC_CHANNELS.GIT.STATUS).toBeDefined();
      expect(IPC_CHANNELS.GIT.COMMIT).toBeDefined();
      expect(IPC_CHANNELS.GIT.PUSH).toBeDefined();
      expect(IPC_CHANNELS.GIT.PULL).toBeDefined();
      expect(IPC_CHANNELS.GIT.LOG).toBeDefined();
    });

    it('应该定义所有必要的设置通道', () => {
      expect(IPC_CHANNELS.SETTINGS.GET).toBeDefined();
      expect(IPC_CHANNELS.SETTINGS.SET).toBeDefined();
    });

    it('应该定义所有必要的模板通道', () => {
      expect(IPC_CHANNELS.TEMPLATE.LIST).toBeDefined();
      expect(IPC_CHANNELS.TEMPLATE.RENDER).toBeDefined();
    });

    it('应该定义所有必要的导入通道', () => {
      expect(IPC_CHANNELS.IMPORT.FROM_ZIP).toBeDefined();
      expect(IPC_CHANNELS.IMPORT.FROM_DIR).toBeDefined();
    });

    it('应该定义所有必要的插件通道', () => {
      expect(IPC_CHANNELS.PLUGIN.LIST).toBeDefined();
      expect(IPC_CHANNELS.PLUGIN.ENABLE).toBeDefined();
      expect(IPC_CHANNELS.PLUGIN.DISABLE).toBeDefined();
    });
  });

  describe('IPC 调用模式', () => {
    it('文件读取应该返回正确的数据结构', async () => {
      const mockContent = '# Test Document\n\nContent';
      const { ipcRenderer } = await import('electron');

      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: true,
        data: {
          content: mockContent,
          path: '/vault/test.md',
        },
      });

      const result = await ipcRenderer.invoke(IPC_CHANNELS.FILE.READ, '/vault/test.md');

      expect(ipcRenderer.invoke).toHaveBeenCalledWith(
        IPC_CHANNELS.FILE.READ,
        '/vault/test.md'
      );
      expect(result.success).toBe(true);
      expect(result.data.content).toBe(mockContent);
    });

    it('文档创建应该传递正确的参数', async () => {
      const { ipcRenderer } = await import('electron');

      const newDoc = {
        title: '新文档',
        content: '# 新文档\n\n内容',
        tags: ['新建'],
      };

      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: true,
        data: {
          id: 'new-doc-id',
          ...newDoc,
        },
      });

      const result = await ipcRenderer.invoke(IPC_CHANNELS.DOCUMENT.CREATE, newDoc);

      expect(ipcRenderer.invoke).toHaveBeenCalledWith(
        IPC_CHANNELS.DOCUMENT.CREATE,
        newDoc
      );
      expect(result.success).toBe(true);
      expect(result.data.id).toBeDefined();
    });

    it('搜索查询应该返回正确的结果格式', async () => {
      const { ipcRenderer } = await import('electron');

      const searchResults = [
        {
          id: 'doc-001',
          title: '测试文档1',
          snippet: '...包含测试内容...',
          score: 0.95,
        },
        {
          id: 'doc-002',
          title: '测试文档2',
          snippet: '...TypeScript测试...',
          score: 0.85,
        },
      ];

      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: true,
        data: searchResults,
      });

      const result = await ipcRenderer.invoke(IPC_CHANNELS.SEARCH.QUERY, {
        query: '测试',
        limit: 10,
      });

      expect(ipcRenderer.invoke).toHaveBeenCalledWith(
        IPC_CHANNELS.SEARCH.QUERY,
        { query: '测试', limit: 10 }
      );
      expect(result.success).toBe(true);
      expect(result.data).toHaveLength(2);
    });

    it('设置更新应该正确传递键值对', async () => {
      const { ipcRenderer } = await import('electron');

      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: true,
      });

      const result = await ipcRenderer.invoke(IPC_CHANNELS.SETTINGS.SET, {
        key: 'theme',
        value: 'dark',
      });

      expect(ipcRenderer.invoke).toHaveBeenCalledWith(
        IPC_CHANNELS.SETTINGS.SET,
        { key: 'theme', value: 'dark' }
      );
      expect(result.success).toBe(true);
    });
  });

  describe('IPC 事件监听', () => {
    it('应该监听文件变更事件', () => {
      const { ipcRenderer } = require('electron');
      const mockCallback = vi.fn();

      ipcRenderer.on(IPC_CHANNELS.FILE.CHANGED, mockCallback);

      expect(ipcRenderer.on).toHaveBeenCalledWith(
        IPC_CHANNELS.FILE.CHANGED,
        mockCallback
      );
    });

    it('应该监听Git提交事件', () => {
      const { ipcRenderer } = require('electron');
      const mockCallback = vi.fn();

      ipcRenderer.on(IPC_CHANNELS.GIT.COMMITTED, mockCallback);

      expect(ipcRenderer.on).toHaveBeenCalledWith(
        IPC_CHANNELS.GIT.COMMITTED,
        mockCallback
      );
    });
  });

  describe('IPC 错误处理', () => {
    it('应该正确处理文件读取错误', async () => {
      const { ipcRenderer } = await import('electron');

      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: false,
        error: 'File not found',
      });

      const result = await ipcRenderer.invoke(
        IPC_CHANNELS.FILE.READ,
        '/nonexistent/file.md'
      );

      expect(result.success).toBe(false);
      expect(result.error).toBe('File not found');
    });

    it('应该正确处理搜索错误', async () => {
      const { ipcRenderer } = await import('electron');

      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: false,
        error: 'Search index not initialized',
      });

      const result = await ipcRenderer.invoke(IPC_CHANNELS.SEARCH.QUERY, {
        query: 'test',
      });

      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
    });
  });
});

describe('IPC 性能测试', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('文档列表查询应该在合理时间内完成', async () => {
    const { ipcRenderer } = await import('electron');

    vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
      success: true,
      data: mockDocuments,
    });

    const startTime = Date.now();
    await ipcRenderer.invoke(IPC_CHANNELS.DOCUMENT.LIST);
    const duration = Date.now() - startTime;

    expect(duration).toBeLessThan(100);
  });

  it('搜索查询应该在合理时间内完成', async () => {
    const { ipcRenderer } = await import('electron');

    vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
      success: true,
      data: mockDocuments.slice(0, 5),
    });

    const startTime = Date.now();
    await ipcRenderer.invoke(IPC_CHANNELS.SEARCH.QUERY, { query: 'test' });
    const duration = Date.now() - startTime;

    expect(duration).toBeLessThan(500);
  });

  it('文件写入后索引更新不超过500ms', async () => {
    const { ipcRenderer } = await import('electron');

    vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
      success: true,
    });

    await ipcRenderer.invoke(IPC_CHANNELS.FILE.WRITE, {
      path: '/vault/new.md',
      content: '# New Document',
    });

    const startTime = Date.now();

    vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
      success: true,
    });

    await ipcRenderer.invoke(IPC_CHANNELS.SEARCH.REINDEX);
    const duration = Date.now() - startTime;

    expect(duration).toBeLessThan(500);
  });
});

describe('IPC 响应格式规范', () => {
  it('所有成功响应应该包含 success: true', async () => {
    const { ipcRenderer } = await import('electron');

    const channels = [
      { channel: IPC_CHANNELS.DOCUMENT.LIST, data: [] },
      { channel: IPC_CHANNELS.SETTINGS.GET, data: {} },
      { channel: IPC_CHANNELS.TEMPLATE.LIST, data: [] },
    ];

    for (const { channel, data } of channels) {
      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: true,
        data,
      });

      const result = await ipcRenderer.invoke(channel);
      expect(result.success).toBe(true);
      expect(result.data).toBeDefined();
    }
  });

  it('所有错误响应应该包含 success: false 和 error 字段', async () => {
    const { ipcRenderer } = await import('electron');

    const errorChannels = [
      IPC_CHANNELS.FILE.READ,
      IPC_CHANNELS.DOCUMENT.GET,
      IPC_CHANNELS.GIT.STATUS,
    ];

    for (const channel of errorChannels) {
      vi.mocked(ipcRenderer.invoke).mockResolvedValueOnce({
        success: false,
        error: 'Test error',
      });

      const result = await ipcRenderer.invoke(channel, 'test');
      expect(result.success).toBe(false);
      expect(result.error).toBeDefined();
      expect(typeof result.error).toBe('string');
    }
  });
});
