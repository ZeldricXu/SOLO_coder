import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ChannelType, IChannelAdapter } from '../../src/types';
import { createMockChannelAdapter } from '../utils/mocks';

const { makeMockAdapter } = vi.hoisted(() => ({
  makeMockAdapter: (channel: string, healthy = true) => ({
    send: vi.fn().mockResolvedValue({
      channel,
      provider: 'builtin',
      status: 'sent',
      message_id: `builtin-${channel}`,
      sent_at: new Date(),
    }),
    healthCheck: vi.fn().mockResolvedValue(healthy),
    getStatus: vi.fn().mockResolvedValue({
      name: channel,
      available: healthy,
      last_checked: new Date(),
    }),
    getName: vi.fn().mockReturnValue(channel),
  }),
}));

vi.mock('../../src/utils/logger', () => ({
  logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

vi.mock('../../src/adapters/EmailAdapter', () => ({
  EmailAdapter: vi.fn().mockImplementation(() => makeMockAdapter('email')),
}));
vi.mock('../../src/adapters/SMSAdapter', () => ({
  SMSAdapter: vi.fn().mockImplementation(() => makeMockAdapter('sms')),
}));
vi.mock('../../src/adapters/PushAdapter', () => ({
  PushAdapter: vi.fn().mockImplementation(() => makeMockAdapter('push')),
}));
vi.mock('../../src/adapters/SlackAdapter', () => ({
  SlackAdapter: vi.fn().mockImplementation(() => makeMockAdapter('slack')),
}));
vi.mock('../../src/adapters/WeChatAdapter', () => ({
  WeChatAdapter: vi.fn().mockImplementation(() => makeMockAdapter('wechat')),
  FeishuAdapter: vi.fn().mockImplementation(() => makeMockAdapter('feishu')),
}));
vi.mock('../../src/adapters/WebhookAdapter', () => ({
  WebhookAdapter: vi.fn().mockImplementation(() => makeMockAdapter('webhook')),
}));

const ALL_CHANNELS: ChannelType[] = [
  'email',
  'sms',
  'push',
  'slack',
  'wechat',
  'feishu',
  'webhook',
];

describe('AdapterManager', () => {
  let AdapterManager: any;
  let registerAdapter: any;
  let getRegisteredChannels: any;
  let discoverAndLoadAdapters: any;

  beforeEach(async () => {
    vi.useRealTimers();
    vi.resetModules();

    const mod = await import('../../src/adapters/AdapterManager');
    AdapterManager = mod.AdapterManager;
    registerAdapter = mod.registerAdapter;
    getRegisteredChannels = mod.getRegisteredChannels;
    discoverAndLoadAdapters = mod.discoverAndLoadAdapters;

    (AdapterManager as any).instance = null;
  });

  describe('dynamic adapter discovery', () => {
    it('falls back to builtins when external packages are not installed', async () => {
      const manager = AdapterManager.getInstance();
      await manager.initialize(true);

      const channels = manager.getLoadedChannels();
      expect(channels.length).toBeGreaterThan(0);
      expect(channels).toEqual(expect.arrayContaining(ALL_CHANNELS));
    });

    it('discoverAndLoadAdapters registers nothing when no packages are installed', async () => {
      await discoverAndLoadAdapters();
      expect(getRegisteredChannels()).toEqual([]);
    });
  });

  describe('manual adapter registration', () => {
    it('allows manual registration of an adapter', () => {
      const manager = AdapterManager.getInstance();
      const adapter = createMockChannelAdapter('email');

      manager.registerAdapter('email', adapter);

      expect(manager.getAdapter('email')).toBe(adapter);
    });

    it('overwrites previously registered adapter for the same channel', () => {
      const manager = AdapterManager.getInstance();
      const first = createMockChannelAdapter('email');
      const second = createMockChannelAdapter('email');

      manager.registerAdapter('email', first);
      manager.registerAdapter('email', second);

      expect(manager.getAdapter('email')).toBe(second);
    });
  });

  describe('builtin adapter loading', () => {
    it('loads all builtin adapters when builtinFallback is true', async () => {
      const manager = AdapterManager.getInstance();
      await manager.initialize(true);

      for (const channel of ALL_CHANNELS) {
        expect(manager.isChannelAvailable(channel)).toBe(true);
        const adapter = manager.getAdapter(channel);
        expect(adapter).toBeDefined();
        expect(adapter.getName()).toBe(channel);
      }
    });

    it('skips builtin adapters when builtinFallback is false', async () => {
      const manager = AdapterManager.getInstance();
      await manager.initialize(false);

      expect(manager.getLoadedChannels()).toEqual([]);
    });
  });

  describe('getAdapter', () => {
    it('returns the registered adapter for a channel', () => {
      const manager = AdapterManager.getInstance();
      const adapter = createMockChannelAdapter('slack');
      manager.registerAdapter('slack', adapter);

      expect(manager.getAdapter('slack')).toBe(adapter);
    });

    it('returns undefined for an unregistered channel', () => {
      const manager = AdapterManager.getInstance();

      expect(manager.getAdapter('email')).toBeUndefined();
    });
  });

  describe('getAvailableAdapters', () => {
    it('returns all registered adapter instances', () => {
      const manager = AdapterManager.getInstance();
      const emailAdapter = createMockChannelAdapter('email');
      const smsAdapter = createMockChannelAdapter('sms');
      manager.registerAdapter('email', emailAdapter);
      manager.registerAdapter('sms', smsAdapter);

      const adapters = manager.getAvailableAdapters();

      expect(adapters).toEqual(expect.arrayContaining([emailAdapter, smsAdapter]));
      expect(adapters.length).toBe(2);
    });

    it('returns empty array when no adapters are registered', () => {
      const manager = AdapterManager.getInstance();

      expect(manager.getAvailableAdapters()).toEqual([]);
    });
  });

  describe('healthCheckAll', () => {
    it('delegates health check to each registered adapter', async () => {
      const manager = AdapterManager.getInstance();
      const healthyAdapter = createMockChannelAdapter('email', true);
      const unhealthyAdapter = createMockChannelAdapter('sms', false);
      manager.registerAdapter('email', healthyAdapter);
      manager.registerAdapter('sms', unhealthyAdapter);

      const results = await manager.healthCheckAll();

      expect(results.get('email')).toBe(true);
      expect(results.get('sms')).toBe(false);
      expect(healthyAdapter.healthCheck).toHaveBeenCalledOnce();
      expect(unhealthyAdapter.healthCheck).toHaveBeenCalledOnce();
    });

    it('returns false for adapters whose healthCheck throws', async () => {
      const manager = AdapterManager.getInstance();
      const adapter = createMockChannelAdapter('push');
      (adapter.healthCheck as any).mockRejectedValue(new Error('timeout'));
      manager.registerAdapter('push', adapter);

      const results = await manager.healthCheckAll();

      expect(results.get('push')).toBe(false);
    });

    it('returns empty map when no adapters are registered', async () => {
      const manager = AdapterManager.getInstance();

      const results = await manager.healthCheckAll();

      expect(results.size).toBe(0);
    });
  });

  describe('isChannelAvailable', () => {
    it('returns true for a registered channel', () => {
      const manager = AdapterManager.getInstance();
      manager.registerAdapter('wechat', createMockChannelAdapter('wechat'));

      expect(manager.isChannelAvailable('wechat')).toBe(true);
    });

    it('returns false for an unregistered channel', () => {
      const manager = AdapterManager.getInstance();

      expect(manager.isChannelAvailable('feishu')).toBe(false);
    });
  });

  describe('getLoadedChannels', () => {
    it('returns all loaded channel names', () => {
      const manager = AdapterManager.getInstance();
      manager.registerAdapter('email', createMockChannelAdapter('email'));
      manager.registerAdapter('sms', createMockChannelAdapter('sms'));
      manager.registerAdapter('push', createMockChannelAdapter('push'));

      const channels = manager.getLoadedChannels();

      expect(channels).toEqual(expect.arrayContaining(['email', 'sms', 'push']));
      expect(channels.length).toBe(3);
    });

    it('returns empty array when no adapters are loaded', () => {
      const manager = AdapterManager.getInstance();

      expect(manager.getLoadedChannels()).toEqual([]);
    });
  });

  describe('standalone registerAdapter factory', () => {
    it('registers a factory function in the global registry', () => {
      const mockModule = {
        createAdapter: () => createMockChannelAdapter('webhook'),
        channelName: 'webhook' as ChannelType,
      };
      registerAdapter('webhook', () => mockModule);

      expect(getRegisteredChannels()).toContain('webhook');
    });

    it('allows multiple channels to be registered', () => {
      registerAdapter('email', () => ({
        createAdapter: () => createMockChannelAdapter('email'),
        channelName: 'email' as ChannelType,
      }));
      registerAdapter('sms', () => ({
        createAdapter: () => createMockChannelAdapter('sms'),
        channelName: 'sms' as ChannelType,
      }));

      const channels = getRegisteredChannels();
      expect(channels).toContain('email');
      expect(channels).toContain('sms');
    });
  });

  describe('initialize idempotency', () => {
    it('does not re-initialize on subsequent calls', async () => {
      const manager = AdapterManager.getInstance();
      await manager.initialize(true);

      const channelsAfterFirst = manager.getLoadedChannels();

      await manager.initialize(true);

      const channelsAfterSecond = manager.getLoadedChannels();

      expect(channelsAfterFirst).toEqual(channelsAfterSecond);
    });

    it('second initialize call is a no-op even with different builtinFallback', async () => {
      const manager = AdapterManager.getInstance();
      await manager.initialize(false);

      expect(manager.getLoadedChannels()).toEqual([]);

      await manager.initialize(true);

      expect(manager.getLoadedChannels()).toEqual([]);
    });
  });

  describe('table-driven: each channel type loads independently', () => {
    it.each(ALL_CHANNELS.map((c) => [c]))(
      'channel "%s" can be loaded independently',
      async (channel) => {
        const ch = channel as ChannelType;
        const manager = AdapterManager.getInstance();
        const adapter = createMockChannelAdapter(ch);
        registerAdapter(ch, () => ({
          createAdapter: () => adapter,
          channelName: ch,
        }));
        await manager.initialize(false);

        expect(manager.isChannelAvailable(ch)).toBe(true);
        expect(manager.getAdapter(ch)).toBe(adapter);
        expect(manager.getAdapter(ch)?.getName()).toBe(ch);
      },
    );
  });
});
