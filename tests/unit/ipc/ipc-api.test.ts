import { describe, it, expect, assertType } from 'vitest';
import type { IPCApi, IPCChannelArgs, IPCChannelReturn, IPCChannelKey } from '@/shared/ipc-api';
import { IPC_CHANNELS } from '@/shared/constants/ipcChannels';
import type { IPCResponse, Document, Tag, AppStats, AppSettings, GitConfig, GitStatus, GitCommit, DiffHunk, SearchResult } from '@/shared/types';

describe('IPC 类型安全', () => {
  describe('类型定义', () => {
    it('应该为所有IPC通道定义类型', () => {
      const allChannels = Object.values(IPC_CHANNELS).flatMap(category =>
        Object.values(category)
      );

      for (const channel of allChannels) {
        assertType<keyof IPCApi>(channel);
      }

      expect(true).toBe(true);
    });

    it('应该包含所有必需的通道', () => {
      const expectedChannels = [
        IPC_CHANNELS.FILE.READ,
        IPC_CHANNELS.FILE.WRITE,
        IPC_CHANNELS.DOCUMENT.CREATE,
        IPC_CHANNELS.DOCUMENT.GET,
        IPC_CHANNELS.DOCUMENT.LIST,
        IPC_CHANNELS.DOCUMENT.UPDATE,
        IPC_CHANNELS.DOCUMENT.DELETE,
        IPC_CHANNELS.DB.DOCUMENT_UPSERT,
        IPC_CHANNELS.DB.DOCUMENT_GET,
        IPC_CHANNELS.DB.DOCUMENT_LIST,
        IPC_CHANNELS.DB.DOCUMENT_DELETE,
        IPC_CHANNELS.DB.TAG_LIST,
        IPC_CHANNELS.DB.STATS_GET,
        IPC_CHANNELS.SEARCH.QUERY,
        IPC_CHANNELS.SEARCH.REINDEX,
        IPC_CHANNELS.GIT.INIT,
        IPC_CHANNELS.GIT.STATUS,
        IPC_CHANNELS.GIT.COMMIT,
        IPC_CHANNELS.TEMPLATE.LIST,
        IPC_CHANNELS.TEMPLATE.GET,
        IPC_CHANNELS.APP.GET_SETTINGS,
        IPC_CHANNELS.APP.SET_SETTINGS,
        IPC_CHANNELS.EXPORT.STATIC_SITE,
        IPC_CHANNELS.EXPORT.MARKDOWN,
      ];

      for (const channel of expectedChannels) {
        assertType<keyof IPCApi>(channel);
      }

      expect(true).toBe(true);
    });
  });

  describe('参数类型', () => {
    it('FILE.READ 应该接受string参数', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.FILE.READ>;
      assertType<[filePath: string]>({} as Args);
    });

    it('FILE.WRITE 应该接受两个string参数', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.FILE.WRITE>;
      assertType<[filePath: string, content: string]>({} as Args);
    });

    it('DOCUMENT.CREATE 应该接受创建参数对象', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.DOCUMENT.CREATE>;
      assertType<[data: { title: string; content?: string; tags?: string[] }]>({} as Args);
    });

    it('DOCUMENT.UPDATE 应该接受id和更新对象', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.DOCUMENT.UPDATE>;
      assertType<[docId: string, updates: Partial<Document>]>({} as Args);
    });

    it('DB.DOCUMENT_LIST 应该接受可选的分页参数', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.DB.DOCUMENT_LIST>;
      assertType<[options?: {
        limit?: number;
        offset?: number;
        tag?: string;
        sortBy?: 'updated_at' | 'created_at' | 'title';
        sortOrder?: 'ASC' | 'DESC';
      }]>({} as Args);
    });

    it('SEARCH.QUERY 应该接受查询字符串和可选参数', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.SEARCH.QUERY>;
      assertType<[query: string, options?: {
        tags?: string[];
        sortBy?: 'relevance' | 'date';
        limit?: number;
      }]>({} as Args);
    });

    it('GIT.COMMIT 应该接受message参数', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.GIT.COMMIT>;
      assertType<[message: string]>({} as Args);
    });

    it('APP.SET_SETTINGS 应该接受Partial<AppSettings>', () => {
      type Args = IPCChannelArgs<typeof IPC_CHANNELS.APP.SET_SETTINGS>;
      assertType<[settings: Partial<AppSettings>]>({} as Args);
    });
  });

  describe('返回类型', () => {
    it('FILE.READ 应该返回IPCResponse<string>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.FILE.READ>;
      assertType<IPCResponse<string>>({} as Return);
    });

    it('DOCUMENT.GET 应该返回IPCResponse<Document | null>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.DOCUMENT.GET>;
      assertType<IPCResponse<Document | null>>({} as Return);
    });

    it('DOCUMENT.LIST 应该返回IPCResponse<Document[]>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.DOCUMENT.LIST>;
      assertType<IPCResponse<Document[]>>({} as Return);
    });

    it('DB.DOCUMENT_UPSERT 应该返回带tags的Document', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.DB.DOCUMENT_UPSERT>;
      assertType<IPCResponse<Document & { tags: string[] }>>({} as Return);
    });

    it('DB.TAG_LIST 应该返回IPCResponse<Tag[]>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.DB.TAG_LIST>;
      assertType<IPCResponse<Tag[]>>({} as Return);
    });

    it('DB.STATS_GET 应该返回IPCResponse<AppStats>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.DB.STATS_GET>;
      assertType<IPCResponse<AppStats>>({} as Return);
    });

    it('SEARCH.QUERY 应该返回IPCResponse<SearchResult[]>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.SEARCH.QUERY>;
      assertType<IPCResponse<SearchResult[]>>({} as Return);
    });

    it('GIT.STATUS 应该返回IPCResponse<GitStatus[]>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.GIT.STATUS>;
      assertType<IPCResponse<GitStatus[]>>({} as Return);
    });

    it('GIT.LOG 应该返回IPCResponse<GitCommit[]>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.GIT.LOG>;
      assertType<IPCResponse<GitCommit[]>>({} as Return);
    });

    it('APP.GET_SETTINGS 应该返回IPCResponse<AppSettings>', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.APP.GET_SETTINGS>;
      assertType<IPCResponse<AppSettings>>({} as Return);
    });

    it('EXPORT.STATIC_SITE 应该返回输出路径', () => {
      type Return = IPCChannelReturn<typeof IPC_CHANNELS.EXPORT.STATIC_SITE>;
      assertType<IPCResponse<{ outputPath: string }>>({} as Return);
    });
  });

  describe('invokeIPC 函数', () => {
    it('应该是一个函数', () => {
      // 测试类型存在，不实际调用
      type InvokeType = <K extends IPCChannelKey>(
        channel: K,
        ...args: IPCChannelArgs<K>
      ) => Promise<IPCChannelReturn<K>>;

      const invoke: InvokeType = async () => ({} as any);
      expect(typeof invoke).toBe('function');
    });
  });

  describe('类型约束', () => {
    it('应该阻止使用不存在的通道', () => {
      // 这是一个编译时测试，如果类型系统工作正常，这段代码不会编译
      // @ts-expect-error - 测试不存在的通道应该报错
      type InvalidChannel = IPCChannelArgs<'nonexistent:channel'>;
      expect(true).toBe(true);
    });

    it('应该阻止传递错误类型的参数', () => {
      // 这是一个编译时测试
      const testFn = (channel: typeof IPC_CHANNELS.FILE.READ, ...args: IPCChannelArgs<typeof channel>) => {};

      // @ts-expect-error - 测试传递错误参数类型应该报错
      testFn(IPC_CHANNELS.FILE.READ, 123);

      expect(true).toBe(true);
    });

    it('应该阻止传递错误数量的参数', () => {
      // 这是一个编译时测试
      const testFn = (channel: typeof IPC_CHANNELS.FILE.WRITE, ...args: IPCChannelArgs<typeof channel>) => {};

      // @ts-expect-error - 测试传递错误参数数量应该报错
      testFn(IPC_CHANNELS.FILE.WRITE, 'path');

      expect(true).toBe(true);
    });
  });
});
