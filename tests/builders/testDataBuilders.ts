import type { ChainId, Address, Hash, HexString, WeiAmount, UUID } from '@shared/types';
import type {
  CrossChainMessage,
  Transaction,
  TransactionSignature,
  GasEstimate,
} from '@core/domain/blockchain';
import type {
  AtomicOperation,
  CrossChainProof,
} from '@core/ports/crossChain.port';
import type {
  MultisigStrategy,
  TransactionBuildParams,
  BuiltTransaction,
} from '@core/ports/transactionBuilder.port';

export class AddressBuilder {
  static random(): Address {
    return `0x${Math.random().toString(16).slice(2, 42).padStart(40, '0')}` as Address;
  }

  static fromSeed(seed: number): Address {
    return `0x${seed.toString(16).padStart(40, '0')}` as Address;
  }
}

export class HashBuilder {
  static random(): Hash {
    return `0x${Math.random().toString(16).slice(2, 66).padStart(64, '0')}` as Hash;
  }

  static fromSeed(seed: number): Hash {
    return `0x${seed.toString(16).padStart(64, '0')}` as Hash;
  }
}

export class HexStringBuilder {
  static random(length = 64): HexString {
    let result = '0x';
    for (let i = 0; i < length; i++) {
      result += Math.floor(Math.random() * 16).toString(16);
    }
    return result as HexString;
  }

  static fromSeed(seed: number, length = 64): HexString {
    return `0x${seed.toString(16).padStart(length, '0')}` as HexString;
  }

  static signature(): HexString {
    return `0x${'a'.repeat(130)}` as HexString;
  }
}

export class TransactionBuilder {
  private chainId: ChainId = 1;
  private from: Address = AddressBuilder.fromSeed(1);
  private to: Address = AddressBuilder.fromSeed(2);
  private value: WeiAmount = BigInt(0);
  private data: HexString = '0x' as HexString;
  private nonce: number = 0;
  private gasLimit: bigint = BigInt(21000);
  private type?: 0 | 1 | 2;
  private maxFeePerGas?: WeiAmount;
  private maxPriorityFeePerGas?: WeiAmount;
  private gasPrice?: WeiAmount;

  withChainId(chainId: ChainId): this {
    this.chainId = chainId;
    return this;
  }

  withFrom(from: Address): this {
    this.from = from;
    return this;
  }

  withTo(to: Address): this {
    this.to = to;
    return this;
  }

  withValue(value: WeiAmount): this {
    this.value = value;
    return this;
  }

  withData(data: HexString): this {
    this.data = data;
    return this;
  }

  withNonce(nonce: number): this {
    this.nonce = nonce;
    return this;
  }

  withGasLimit(gasLimit: bigint): this {
    this.gasLimit = gasLimit;
    return this;
  }

  withType(type: 0 | 1 | 2): this {
    this.type = type;
    return this;
  }

  withEIP1559Fees(maxFeePerGas: WeiAmount, maxPriorityFeePerGas: WeiAmount): this {
    this.type = 2;
    this.maxFeePerGas = maxFeePerGas;
    this.maxPriorityFeePerGas = maxPriorityFeePerGas;
    return this;
  }

  withLegacyGasPrice(gasPrice: WeiAmount): this {
    this.type = 0;
    this.gasPrice = gasPrice;
    return this;
  }

  build(): TransactionBuildParams {
    const base: TransactionBuildParams = {
      chainId: this.chainId,
      from: this.from,
      to: this.to,
      value: this.value,
      data: this.data,
      nonce: this.nonce,
      gasLimit: this.gasLimit,
      type: this.type,
    };

    if (this.type === 2) {
      base.maxFeePerGas = this.maxFeePerGas || BigInt('30000000000');
      base.maxPriorityFeePerGas = this.maxPriorityFeePerGas || BigInt('2000000000');
    } else if (this.type === 0 || this.type === 1) {
      base.gasPrice = this.gasPrice || BigInt('30000000000');
    }

    return base;
  }

  static default(): TransactionBuilder {
    return new TransactionBuilder();
  }

  static etherTransfer(): TransactionBuilder {
    return new TransactionBuilder()
      .withValue(BigInt('1000000000000000000'))
      .withType(2)
      .withEIP1559Fees(BigInt('30000000000'), BigInt('2000000000'));
  }
}

export class CrossChainMessageBuilder {
  private sourceChainId: ChainId = 1;
  private targetChainId: ChainId = 5;
  private sourceAddress: Address = AddressBuilder.fromSeed(1);
  private targetAddress: Address = AddressBuilder.fromSeed(2);
  private amount: WeiAmount = BigInt('1000000000000000000');
  private data: HexString = '0x' as HexString;
  private status: 'pending' | 'confirmed' | 'executed' | 'failed' = 'pending';

  withSourceChain(chainId: ChainId): this {
    this.sourceChainId = chainId;
    return this;
  }

  withTargetChain(chainId: ChainId): this {
    this.targetChainId = chainId;
    return this;
  }

  withSourceAddress(address: Address): this {
    this.sourceAddress = address;
    return this;
  }

  withTargetAddress(address: Address): this {
    this.targetAddress = address;
    return this;
  }

  withAmount(amount: WeiAmount): this {
    this.amount = amount;
    return this;
  }

  withStatus(status: 'pending' | 'confirmed' | 'executed' | 'failed'): this {
    this.status = status;
    return this;
  }

  build(): CrossChainMessage {
    const encoder = new TextEncoder();
    const content = `${this.sourceChainId}-${this.targetChainId}-${this.sourceAddress}-${this.targetAddress}-${this.amount}-${this.data}`;
    const bytes = encoder.encode(content);
    let hash = 0;
    for (let i = 0; i < bytes.length; i++) {
      hash = ((hash << 5) - hash + bytes[i]) | 0;
    }
    const messageHash = `0x${Math.abs(hash).toString(16).padStart(64, '0')}` as Hash;

    return {
      id: `msg_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` as UUID,
      sourceChainId: this.sourceChainId,
      targetChainId: this.targetChainId,
      sourceAddress: this.sourceAddress,
      targetAddress: this.targetAddress,
      amount: this.amount,
      data: this.data,
      messageHash,
      status: this.status,
      createdAt: new Date().toISOString(),
    };
  }

  static default(): CrossChainMessageBuilder {
    return new CrossChainMessageBuilder();
  }
}

export class AtomicOperationBuilder {
  private sourceChainId: ChainId = 1;
  private targetChainId: ChainId = 5;
  private sourceAddress: Address = AddressBuilder.fromSeed(1);
  private targetAddress: Address = AddressBuilder.fromSeed(2);
  private amount: WeiAmount = BigInt('1000000000000000000');
  private status: 'pending' | 'source_executed' | 'target_executing' | 'completed' | 'rolled_back' = 'pending';

  withSourceChain(chainId: ChainId): this {
    this.sourceChainId = chainId;
    return this;
  }

  withTargetChain(chainId: ChainId): this {
    this.targetChainId = chainId;
    return this;
  }

  withSourceAddress(address: Address): this {
    this.sourceAddress = address;
    return this;
  }

  withTargetAddress(address: Address): this {
    this.targetAddress = address;
    return this;
  }

  withAmount(amount: WeiAmount): this {
    this.amount = amount;
    return this;
  }

  withStatus(status: 'pending' | 'source_executed' | 'target_executing' | 'completed' | 'rolled_back'): this {
    this.status = status;
    return this;
  }

  build(): AtomicOperation {
    return {
      id: `op_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` as UUID,
      sourceOperation: {
        chainId: this.sourceChainId,
        type: 'lock',
        address: this.sourceAddress,
        amount: this.amount,
      },
      targetOperation: {
        chainId: this.targetChainId,
        type: 'mint',
        address: this.targetAddress,
        amount: this.amount,
      },
      status: this.status,
      createdAt: Date.now(),
    };
  }

  static default(): AtomicOperationBuilder {
    return new AtomicOperationBuilder();
  }
}

export class CrossChainProofBuilder {
  private messageHash: Hash = HashBuilder.random();
  private sourceChainId: ChainId = 1;
  private targetChainId: ChainId = 5;

  withMessageHash(hash: Hash): this {
    this.messageHash = hash;
    return this;
  }

  withSourceChain(chainId: ChainId): this {
    this.sourceChainId = chainId;
    return this;
  }

  withTargetChain(chainId: ChainId): this {
    this.targetChainId = chainId;
    return this;
  }

  build(): CrossChainProof {
    return {
      messageHash: this.messageHash,
      sourceChainId: this.sourceChainId,
      targetChainId: this.targetChainId,
      proof: [
        HexStringBuilder.fromSeed(1, 64),
        HexStringBuilder.fromSeed(2, 64),
        HexStringBuilder.fromSeed(3, 64),
      ],
      blockHash: HashBuilder.fromSeed(1),
      transactionHash: HashBuilder.fromSeed(2),
      timestamp: Date.now(),
    };
  }

  static default(): CrossChainProofBuilder {
    return new CrossChainProofBuilder();
  }
}

export class MultisigStrategyBuilder {
  private id: string = 'test-strategy';
  private name: string = 'Test Multisig Strategy';
  private threshold: number = 2;
  private owners: Address[] = [
    AddressBuilder.fromSeed(1),
    AddressBuilder.fromSeed(2),
    AddressBuilder.fromSeed(3),
  ];
  private validateFn: (hash: Hash, sigs: Map<Address, TransactionSignature>) => Promise<boolean> = 
    async () => true;
  private combineFn: (sigs: Map<Address, TransactionSignature>) => Promise<HexString> = 
    async () => HexStringBuilder.signature();

  withId(id: string): this {
    this.id = id;
    return this;
  }

  withThreshold(threshold: number): this {
    this.threshold = threshold;
    return this;
  }

  withOwners(owners: Address[]): this {
    this.owners = owners;
    return this;
  }

  withValidateFn(fn: (hash: Hash, sigs: Map<Address, TransactionSignature>) => Promise<boolean>): this {
    this.validateFn = fn;
    return this;
  }

  withCombineFn(fn: (sigs: Map<Address, TransactionSignature>) => Promise<HexString>): this {
    this.combineFn = fn;
    return this;
  }

  build(): MultisigStrategy {
    return {
      id: this.id,
      name: this.name,
      threshold: this.threshold,
      owners: this.owners,
      validateSignatures: this.validateFn,
      combineSignatures: this.combineFn,
    };
  }

  static default(): MultisigStrategyBuilder {
    return new MultisigStrategyBuilder();
  }

  static simple2of3(): MultisigStrategy {
    return new MultisigStrategyBuilder()
      .withThreshold(2)
      .withOwners([
        AddressBuilder.fromSeed(1),
        AddressBuilder.fromSeed(2),
        AddressBuilder.fromSeed(3),
      ])
      .build();
  }
}

export class GasEstimateBuilder {
  private gasLimit: bigint = BigInt(21000);
  private baseFeePerGas: WeiAmount = BigInt('20000000000');
  private maxPriorityFeePerGas: WeiAmount = BigInt('2000000000');
  private confidence: number = 0.9;

  withGasLimit(limit: bigint): this {
    this.gasLimit = limit;
    return this;
  }

  withBaseFee(fee: WeiAmount): this {
    this.baseFeePerGas = fee;
    return this;
  }

  withPriorityFee(fee: WeiAmount): this {
    this.maxPriorityFeePerGas = fee;
    return this;
  }

  withConfidence(confidence: number): this {
    this.confidence = confidence;
    return this;
  }

  build(): GasEstimate {
    const maxFeePerGas = this.baseFeePerGas * BigInt(2) + this.maxPriorityFeePerGas;
    return {
      gasLimit: this.gasLimit,
      baseFeePerGas: this.baseFeePerGas,
      maxPriorityFeePerGas: this.maxPriorityFeePerGas,
      maxFeePerGas,
      estimatedCost: maxFeePerGas * this.gasLimit,
      confidence: this.confidence,
      timestamp: new Date().toISOString(),
    };
  }

  static default(): GasEstimateBuilder {
    return new GasEstimateBuilder();
  }

  static standardTransfer(): GasEstimate {
    return new GasEstimateBuilder()
      .withGasLimit(BigInt(21000))
      .withBaseFee(BigInt('20000000000'))
      .withPriorityFee(BigInt('2000000000'))
      .build();
  }
}

export class TransactionSignatureBuilder {
  static default(): TransactionSignature {
    return {
      r: `0x${'r'.repeat(64)}` as HexString,
      s: `0x${'s'.repeat(64)}` as HexString,
      v: BigInt(27),
    };
  }

  static withV(v: bigint): TransactionSignature {
    return {
      ...TransactionSignatureBuilder.default(),
      v,
    };
  }
}

export function createMockChainProvider(): {
  getClient: jest.Mock;
  addClient: jest.Mock;
  removeClient: jest.Mock;
  getSupportedChains: jest.Mock;
  mockBalance: WeiAmount;
  mockBlockNumber: bigint;
} {
  const mockBalance = BigInt('100000000000000000000');
  const mockBlockNumber = BigInt(1000000);
  const clients = new Map();

  const mockClient = {
    getBalance: jest.fn().mockResolvedValue(mockBalance),
    getBlockNumber: jest.fn().mockResolvedValue(mockBlockNumber),
    getBlock: jest.fn().mockResolvedValue({
      baseFeePerGas: BigInt('20000000000'),
      gasUsed: BigInt(15000000),
      gasLimit: BigInt(30000000),
    }),
    getNonce: jest.fn().mockResolvedValue(0),
    estimateGas: jest.fn().mockResolvedValue(BigInt(21000)),
  };

  const getClient = jest.fn().mockReturnValue(mockClient);
  const addClient = jest.fn().mockImplementation((chainId, client) => clients.set(chainId, client));
  const removeClient = jest.fn().mockImplementation((chainId) => clients.delete(chainId));
  const getSupportedChains = jest.fn().mockReturnValue([1, 5, 137, 42161]);

  return {
    getClient,
    addClient,
    removeClient,
    getSupportedChains,
    mockBalance,
    mockBlockNumber,
  };
}

export function createMockValidator(): {
  verifyMessageIntegrity: jest.Mock;
  validateLockTransaction: jest.Mock;
  validateMintTransaction: jest.Mock;
} {
  return {
    verifyMessageIntegrity: jest.fn().mockResolvedValue(true),
    validateLockTransaction: jest.fn().mockResolvedValue(true),
    validateMintTransaction: jest.fn().mockResolvedValue(true),
  };
}

export const TestTiming = {
  async advanceTime(ms: number): Promise<void> {
    jest.advanceTimersByTime(ms);
    await new Promise(resolve => setImmediate(resolve));
  },

  useFakeTimers(): void {
    jest.useFakeTimers();
  },

  useRealTimers(): void {
    jest.useRealTimers();
  },
};
