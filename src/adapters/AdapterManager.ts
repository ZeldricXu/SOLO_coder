import { IChannelAdapter, ChannelType, ChannelStatus } from '../types';
import { logger } from '../utils/logger';

export interface AdapterModule {
  createAdapter: () => IChannelAdapter;
  channelName: ChannelType;
}

const ADAPTER_PACKAGE_MAP: Record<string, string> = {
  email: '@notify/adapter-email',
  sms: '@notify/adapter-sms',
  push: '@notify/adapter-push',
  slack: '@notify/adapter-slack',
  wechat: '@notify/adapter-wechat',
  feishu: '@notify/adapter-feishu',
  webhook: '@notify/adapter-webhook',
};

const ADAPTER_REGISTRY = new Map<ChannelType, () => AdapterModule>();

export function registerAdapter(channel: ChannelType, factory: () => AdapterModule): void {
  ADAPTER_REGISTRY.set(channel, factory);
  logger.info('Adapter factory registered', { channel });
}

export function getRegisteredChannels(): ChannelType[] {
  return Array.from(ADAPTER_REGISTRY.keys());
}

function loadAdapterFromPackage(channel: string): AdapterModule | null {
  const packageName = ADAPTER_PACKAGE_MAP[channel];
  if (!packageName) {
    logger.warn('No adapter package mapping found', { channel });
    return null;
  }

  try {
    const resolvedPath = require.resolve(packageName);
    const mod = require(resolvedPath);
    const adapterModule: AdapterModule = mod.default || mod;
    logger.info('Adapter loaded from package', { channel, packageName });
    return adapterModule;
  } catch (err: any) {
    if (err.code === 'MODULE_NOT_FOUND') {
      logger.info('Adapter package not installed, skipping', { channel, packageName });
    } else {
      logger.error('Failed to load adapter package', { channel, packageName, error: err.message });
    }
    return null;
  }
}

export function loadBuiltinAdapters(): void {
  const builtinMap: Record<string, () => Promise<AdapterModule>> = {
    email: async () => {
      const { EmailAdapter } = await import('./EmailAdapter');
      return { createAdapter: () => new EmailAdapter(), channelName: 'email' as ChannelType };
    },
    sms: async () => {
      const { SMSAdapter } = await import('./SMSAdapter');
      return { createAdapter: () => new SMSAdapter(), channelName: 'sms' as ChannelType };
    },
    push: async () => {
      const { PushAdapter } = await import('./PushAdapter');
      return { createAdapter: () => new PushAdapter(), channelName: 'push' as ChannelType };
    },
    slack: async () => {
      const { SlackAdapter } = await import('./SlackAdapter');
      return { createAdapter: () => new SlackAdapter(), channelName: 'slack' as ChannelType };
    },
    wechat: async () => {
      const { WeChatAdapter } = await import('./WeChatAdapter');
      return { createAdapter: () => new WeChatAdapter(), channelName: 'wechat' as ChannelType };
    },
    feishu: async () => {
      const { FeishuAdapter } = await import('./WeChatAdapter');
      return { createAdapter: () => new FeishuAdapter(), channelName: 'feishu' as ChannelType };
    },
    webhook: async () => {
      const { WebhookAdapter } = await import('./WebhookAdapter');
      return { createAdapter: () => new WebhookAdapter(), channelName: 'webhook' as ChannelType };
    },
  };

  for (const [channel, factory] of Object.entries(builtinMap)) {
    registerAdapter(channel as ChannelType, () => {
      let cached: AdapterModule | null = null;
      const adapterModule: AdapterModule = {
        get createAdapter() {
          if (!cached) {
            throw new Error(`Builtin adapter ${channel} not yet loaded. Call loadBuiltinAdaptersAsync() first.`);
          }
          return cached.createAdapter;
        },
        get channelName() {
          if (!cached) {
            throw new Error(`Builtin adapter ${channel} not yet loaded. Call loadBuiltinAdaptersAsync() first.`);
          }
          return cached.channelName;
        },
      };
      void factory().then((mod) => { cached = mod; });
      return adapterModule;
    });
  }
}

export async function discoverAndLoadAdapters(): Promise<void> {
  for (const [channel, packageName] of Object.entries(ADAPTER_PACKAGE_MAP)) {
    const adapterModule = loadAdapterFromPackage(channel);
    if (adapterModule) {
      registerAdapter(channel as ChannelType, () => adapterModule);
    }
  }
}

export class AdapterManager {
  private adapters: Map<ChannelType, IChannelAdapter> = new Map();
  private static instance: AdapterManager;
  private loaded: boolean = false;

  private constructor() {}

  public static getInstance(): AdapterManager {
    if (!AdapterManager.instance) {
      AdapterManager.instance = new AdapterManager();
    }
    return AdapterManager.instance;
  }

  public async initialize(builtinFallback: boolean = true): Promise<void> {
    if (this.loaded) return;

    await discoverAndLoadAdapters();

    if (builtinFallback) {
      await this.loadBuiltinAdaptersAsync();
    }

    for (const [channel, factory] of ADAPTER_REGISTRY) {
      try {
        const adapterModule = factory();
        const adapter = adapterModule.createAdapter();
        this.adapters.set(channel, adapter);
        logger.info('Channel adapter initialized', { channel });
      } catch (err) {
        logger.error('Failed to initialize adapter', { channel, error: err });
      }
    }

    this.loaded = true;
    logger.info('All channel adapters initialized', {
      channels: Array.from(this.adapters.keys()),
    });
  }

  private async loadBuiltinAdaptersAsync(): Promise<void> {
    const builtinMap: Record<string, () => Promise<AdapterModule>> = {
      email: async () => {
        const { EmailAdapter } = await import('./EmailAdapter');
        return { createAdapter: () => new EmailAdapter(), channelName: 'email' as ChannelType };
      },
      sms: async () => {
        const { SMSAdapter } = await import('./SMSAdapter');
        return { createAdapter: () => new SMSAdapter(), channelName: 'sms' as ChannelType };
      },
      push: async () => {
        const { PushAdapter } = await import('./PushAdapter');
        return { createAdapter: () => new PushAdapter(), channelName: 'push' as ChannelType };
      },
      slack: async () => {
        const { SlackAdapter } = await import('./SlackAdapter');
        return { createAdapter: () => new SlackAdapter(), channelName: 'slack' as ChannelType };
      },
      wechat: async () => {
        const { WeChatAdapter } = await import('./WeChatAdapter');
        return { createAdapter: () => new WeChatAdapter(), channelName: 'wechat' as ChannelType };
      },
      feishu: async () => {
        const { FeishuAdapter } = await import('./WeChatAdapter');
        return { createAdapter: () => new FeishuAdapter(), channelName: 'feishu' as ChannelType };
      },
      webhook: async () => {
        const { WebhookAdapter } = await import('./WebhookAdapter');
        return { createAdapter: () => new WebhookAdapter(), channelName: 'webhook' as ChannelType };
      },
    };

    for (const [channel, loader] of Object.entries(builtinMap)) {
      if (!ADAPTER_REGISTRY.has(channel as ChannelType)) {
        try {
          const adapterModule = await loader();
          registerAdapter(channel as ChannelType, () => adapterModule);
          logger.info('Builtin adapter loaded as fallback', { channel });
        } catch (err) {
          logger.warn('Builtin adapter load failed, skipping', { channel, error: err });
        }
      }
    }
  }

  public registerAdapter(channel: ChannelType, adapter: IChannelAdapter): void {
    this.adapters.set(channel, adapter);
    logger.info('Adapter manually registered', { channel });
  }

  public getAdapter(channel: ChannelType): IChannelAdapter | undefined {
    return this.adapters.get(channel);
  }

  public getAvailableAdapters(): IChannelAdapter[] {
    return Array.from(this.adapters.values());
  }

  public async healthCheckAll(): Promise<Map<ChannelType, boolean>> {
    const results: Map<ChannelType, boolean> = new Map();
    
    for (const [channel, adapter] of this.adapters) {
      try {
        const healthy = await adapter.healthCheck();
        results.set(channel, healthy);
        logger.debug(`Health check for ${channel}: ${healthy ? 'OK' : 'FAIL'}`);
      } catch (err) {
        results.set(channel, false);
        logger.error(`Health check failed for ${channel}`, err);
      }
    }

    return results;
  }

  public async getAllStatuses(): Promise<ChannelStatus[]> {
    const statuses: ChannelStatus[] = [];
    
    for (const adapter of this.adapters.values()) {
      try {
        const status = await adapter.getStatus();
        statuses.push(status);
      } catch (err) {
        logger.error('Failed to get adapter status', err);
      }
    }

    return statuses;
  }

  public isChannelAvailable(channel: ChannelType): boolean {
    return this.adapters.has(channel);
  }

  public getLoadedChannels(): ChannelType[] {
    return Array.from(this.adapters.keys());
  }
}
