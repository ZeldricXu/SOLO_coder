import { PinoLogger } from '@infrastructure/adapters/pinoLogger.adapter';
import { NodeCacheAdapter } from '@infrastructure/adapters/nodeCache.adapter';
import { ViemChainAdapter } from '@infrastructure/adapters/viemChain.adapter';
import { IpfsStorageAdapter } from '@infrastructure/adapters/ipfsStorage.adapter';
import { ViemHdWallet } from '@infrastructure/adapters/hdWallet.adapter';
import { ViemEventDecoder } from '@infrastructure/adapters/eventDecoder.adapter';
import {
  createContainer,
  createDefaultMultisigStrategy,
  type AppContainer,
  type ModuleConfig,
} from './container';

const defaultConfig: ModuleConfig = {
  chains: [
    { chainId: 1, rpcUrl: 'https://eth.llamarpc.com', name: 'Ethereum Mainnet' },
    { chainId: 5, rpcUrl: 'https://eth-sepolia.public.blastapi.io', name: 'Sepolia Testnet' },
    { chainId: 137, rpcUrl: 'https://polygon.llamarpc.com', name: 'Polygon Mainnet' },
    { chainId: 42161, rpcUrl: 'https://arbitrum.llamarpc.com', name: 'Arbitrum One' },
  ],
  storage: {
    providers: [
      {
        name: 'ipfs-local',
        type: 'ipfs',
        endpoint: 'http://localhost:5001',
        gateway: 'https://ipfs.io',
      },
    ],
    defaultProvider: 'ipfs-local',
  },
  bridge: {
    lockContractAddresses: {
      1: '0x0000000000000000000000000000000000000001',
      5: '0x0000000000000000000000000000000000000002',
      137: '0x0000000000000000000000000000000000000003',
      42161: '0x0000000000000000000000000000000000000004',
    },
    mintContractAddresses: {
      1: '0x0000000000000000000000000000000000000005',
      5: '0x0000000000000000000000000000000000000006',
      137: '0x0000000000000000000000000000000000000007',
      42161: '0x0000000000000000000000000000000000000008',
    },
    requiredConfirmations: 10,
    supportedChains: [1, 5, 137, 42161],
  },
  multisig: {
    defaultThreshold: 2,
    defaultOwners: [
      '0x0000000000000000000000000000000000000001',
      '0x0000000000000000000000000000000000000002',
      '0x0000000000000000000000000000000000000003',
    ],
  },
  gas: {
    defaultSpeed: 'standard',
    defaultBufferPercentage: 10,
  },
  cache: {
    enabled: true,
    defaultTTL: 30,
    namespace: 'blockchain-platform',
  },
};

export function createDefaultConfig(): ModuleConfig {
  return { ...defaultConfig };
}

export async function bootstrap(customConfig?: Partial<ModuleConfig>): Promise<AppContainer> {
  const config: ModuleConfig = {
    ...defaultConfig,
    ...customConfig,
    chains: customConfig?.chains || defaultConfig.chains,
    storage: customConfig?.storage || defaultConfig.storage,
    bridge: customConfig?.bridge || defaultConfig.bridge,
    multisig: customConfig?.multisig || defaultConfig.multisig,
    gas: customConfig?.gas || defaultConfig.gas,
    cache: customConfig?.cache || defaultConfig.cache,
  };

  const logger = new PinoLogger('blockchain-platform', 'info', true);
  logger.info('Bootstrapping application...');

  const cache = new NodeCacheAdapter({
    defaultTTL: config.cache.defaultTTL,
    namespace: config.cache.namespace,
  });

  const chainClientFactory = ViemChainAdapter.createFactory();
  const storageProviderFactory = IpfsStorageAdapter.createFactory();
  const hdWalletFactory = ViemHdWallet.createFactory();
  const eventDecoder = ViemEventDecoder.create();
  const multisigStrategy = createDefaultMultisigStrategy(
    config.multisig.defaultOwners,
    config.multisig.defaultThreshold
  );

  const container = createContainer(
    config,
    logger,
    cache,
    chainClientFactory,
    storageProviderFactory,
    hdWalletFactory,
    eventDecoder,
    multisigStrategy
  );

  logger.info('Application bootstrapped successfully');

  return container;
}

export async function shutdown(container: AppContainer): Promise<void> {
  container.logger.info('Shutting down application...');

  try {
    await container.cache.clear();
  } catch (error) {
    container.logger.error('Error clearing cache during shutdown', { error });
  }

  container.logger.info('Application shutdown complete');
}
