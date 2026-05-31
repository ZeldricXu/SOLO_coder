import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type { ChainInteractionProvider, ChainInteractionPort } from '@core/ports/chainInteraction.port';
import type { DecentralizedStoragePort } from '@core/ports/storage.port';
import type { HdWalletPort, AddressBookPort } from '@core/ports/wallet.port';
import type { EventDecoderPort } from '@core/ports/eventListener.port';
import type { BridgeValidatorPort } from '@core/ports/crossChain.port';
import type { TransactionBuilderPort, MultisigStrategy } from '@core/ports/transactionBuilder.port';
import type { GasEstimatorPort, GasOptimizationPort } from '@core/ports/gasEstimator.port';
import { GasEstimatorService } from '@core/usecases/gasEstimator.usecase';
import { TransactionBuilderService } from '@core/usecases/transactionBuilder.usecase';
import { MultisigCoordinatorService } from '@core/usecases/multisigCoordinator.usecase';
import { CrossChainBridgeService } from '@core/usecases/crossChainBridge.usecase';
import { AddressManagerService } from '@core/usecases/addressManager.usecase';
import { DecentralizedStorageService } from '@core/usecases/decentralizedStorage.usecase';
import { ContractEventListenerService } from '@core/usecases/eventListener.usecase';
import { ChainInteractionService } from '@core/usecases/chainInteraction.usecase';
import { BridgeValidatorService } from '@infrastructure/adapters/bridgeValidator.adapter';
import type { GasEstimatorDependencies } from '@core/ports/gasEstimator.port';
import type { ChainId, Address, Hash, HexString, WeiAmount, GasAmount } from '@shared/types';
import { z } from 'zod';

export interface ModuleConfig {
  chains: Array<{
    chainId: ChainId;
    rpcUrl: string;
    name?: string;
  }>;
  storage: {
    providers: Array<{
      name: string;
      type: 'ipfs' | 'arweave';
      endpoint?: string;
      gateway?: string;
      apiKey?: string;
    }>;
    defaultProvider: string;
  };
  bridge: {
    lockContractAddresses: Record<ChainId, Address>;
    mintContractAddresses: Record<ChainId, Address>;
    requiredConfirmations: number;
    supportedChains: ChainId[];
  };
  multisig: {
    defaultThreshold: number;
    defaultOwners: Address[];
  };
  gas: {
    defaultSpeed: 'slow' | 'standard' | 'fast' | 'instant';
    defaultBufferPercentage: number;
  };
  cache: {
    enabled: boolean;
    defaultTTL: number;
    namespace: string;
  };
}

const configSchema = z.object({
  chains: z.array(
    z.object({
      chainId: z.number().int().positive(),
      rpcUrl: z.string().url(),
      name: z.string().optional(),
    })
  ),
  storage: z.object({
    providers: z.array(
      z.object({
        name: z.string(),
        type: z.enum(['ipfs', 'arweave']),
        endpoint: z.string().url().optional(),
        gateway: z.string().url().optional(),
        apiKey: z.string().optional(),
      })
    ),
    defaultProvider: z.string(),
  }),
  bridge: z.object({
    lockContractAddresses: z.record(z.number(), z.string()),
    mintContractAddresses: z.record(z.number(), z.string()),
    requiredConfirmations: z.number().int().min(1),
    supportedChains: z.array(z.number().int().positive()),
  }),
  multisig: z.object({
    defaultThreshold: z.number().int().min(1),
    defaultOwners: z.array(z.string()),
  }),
  gas: z.object({
    defaultSpeed: z.enum(['slow', 'standard', 'fast', 'instant']),
    defaultBufferPercentage: z.number().min(0).max(100),
  }),
  cache: z.object({
    enabled: z.boolean(),
    defaultTTL: z.number().int().positive(),
    namespace: z.string(),
  }),
});

export interface AppContainer {
  logger: Logger;
  cache: CachePort;
  chainInteraction: ChainInteractionService;
  transactionBuilder: TransactionBuilderPort;
  gasEstimator: GasEstimatorPort & GasOptimizationPort;
  multisigCoordinator: MultisigCoordinatorService;
  crossChainBridge: CrossChainBridgeService;
  addressManager: AddressBookPort & AddressManagerService;
  storage: DecentralizedStorageService;
  eventListener: ContractEventListenerService;
  config: ModuleConfig;
}

export function createContainer(
  config: ModuleConfig,
  logger: Logger,
  cache: CachePort,
  chainClientFactory: (config: { chainId: ChainId; rpcUrl: string }) => ChainInteractionPort,
  storageProviderFactory: (config: { type: string; endpoint?: string; gateway?: string; apiKey?: string }) => DecentralizedStoragePort,
  hdWalletFactory: (mnemonic?: string) => HdWalletPort,
  eventDecoder: EventDecoderPort,
  defaultMultisigStrategy: MultisigStrategy
): AppContainer {
  const validated = configSchema.parse(config);
  logger.info('Creating application container with validated config');

  const chainInteraction = new ChainInteractionService(
    chainClientFactory,
    logger.child({ module: 'chain-interaction' }),
    { chains: validated.chains },
    validated.cache.enabled ? cache : undefined,
    validated.cache.defaultTTL
  );

  const transactionBuilder = new TransactionBuilderService(
    logger.child({ module: 'transaction-builder' })
  );
  transactionBuilder.setMultisigStrategy(defaultMultisigStrategy);
  transactionBuilder.setGasOptimizationConfig({
    enabled: true,
    speed: validated.gas.defaultSpeed,
    gasLimitBuffer: validated.gas.defaultBufferPercentage,
  });

  const gasDeps: GasEstimatorDependencies = {
    getBlockNumber: async () => chainInteraction.getBlockNumber(validated.chains[0]?.chainId || 1),
    getBlock: async (blockNumber: bigint) => {
      const chainId = validated.chains[0]?.chainId || 1;
      const block = await chainInteraction.getBlock(chainId, blockNumber);
      return block ? {
        baseFeePerGas: block.baseFeePerGas,
        gasUsed: block.gasUsed,
        gasLimit: block.gasLimit,
      } : null;
    },
    getFeePerGas: async () => chainInteraction.getFeePerGas(validated.chains[0]?.chainId || 1),
    estimateGas: async (tx) => chainInteraction.estimateGas(validated.chains[0]?.chainId || 1, tx),
    getHistoricalData: async (chainId, limit) => gasEstimator.getHistoricalPrices(chainId, limit),
  };

  const gasEstimator = new GasEstimatorService(
    gasDeps,
    logger.child({ module: 'gas-estimator' }),
    validated.cache.enabled ? cache : undefined,
    {
      defaultSpeed: validated.gas.defaultSpeed,
      defaultBufferPercentage: validated.gas.defaultBufferPercentage,
      cacheTTL: validated.cache.defaultTTL,
    }
  );

  const multisigCoordinator = new MultisigCoordinatorService(
    transactionBuilder,
    defaultMultisigStrategy,
    logger.child({ module: 'multisig-coordinator' })
  );

  const bridgeValidator = BridgeValidatorService.create(
    chainInteraction,
    {
      lockContractAddresses: validated.bridge.lockContractAddresses,
      mintContractAddresses: validated.bridge.mintContractAddresses,
    }
  );

  const crossChainBridge = new CrossChainBridgeService(
    chainInteraction,
    transactionBuilder,
    bridgeValidator,
    logger.child({ module: 'cross-chain-bridge' }),
    {
      requiredConfirmations: validated.bridge.requiredConfirmations,
      supportedChains: validated.bridge.supportedChains,
    },
    validated.cache.enabled ? cache : undefined
  );

  const addressManager = new AddressManagerService(
    hdWalletFactory,
    logger.child({ module: 'address-manager' })
  );

  const storage = new DecentralizedStorageService(
    storageProviderFactory,
    logger.child({ module: 'storage' }),
    validated.storage,
    validated.cache.enabled ? cache : undefined
  );

  const eventListener = new ContractEventListenerService(
    chainInteraction,
    eventDecoder,
    logger.child({ module: 'event-listener' }),
    { maxSubscriptions: 100 },
    validated.cache.enabled ? cache : undefined
  );

  logger.info('Application container created successfully');

  return {
    logger,
    cache,
    chainInteraction,
    transactionBuilder,
    gasEstimator,
    multisigCoordinator,
    crossChainBridge,
    addressManager,
    storage,
    eventListener,
    config: validated,
  };
}

export function createDefaultMultisigStrategy(
  owners: Address[],
  threshold: number
): MultisigStrategy {
  return {
    id: 'default-multisig',
    name: 'Default Multisig Strategy',
    threshold,
    owners,
    async validateSignatures(transactionHash, signatures) {
      if (signatures.size < threshold) return false;
      for (const [signer] of signatures) {
        if (!owners.some(o => o.toLowerCase() === signer.toLowerCase())) {
          return false;
        }
      }
      return true;
    },
    async combineSignatures(signatures) {
      let combined = '0x';
      for (const [, sig] of signatures) {
        combined += sig.r.replace('0x', '') + sig.s.replace('0x', '') + sig.v.toString(16).padStart(2, '0');
      }
      return combined as HexString;
    },
  };
}
