import type { Logger } from '@shared/logger';
import type { CachePort } from '@shared/cache';
import type { ChainInteractionPort, ChainInteractionProvider } from '@core/ports/chainInteraction.port';
import type { DecentralizedStoragePort } from '@core/ports/storage.port';
import type { HdWalletPort, SignerPort } from '@core/ports/wallet.port';
import type { EventDecoderPort } from '@core/ports/eventListener.port';
import type { BridgeValidatorPort } from '@core/ports/crossChain.port';
import type { Block, Transaction, TransactionReceipt, LogEntry, StorageContent, PinStatus } from '@core/domain/blockchain';
import type { ChainId, Address, Hash, HexString, GasAmount, WeiAmount } from '@shared/types';

export class MockLogger implements Logger {
  info = jest.fn();
  warn = jest.fn();
  error = jest.fn();
  debug = jest.fn();
  child = jest.fn().mockReturnValue(this);
}

export class MockCache implements CachePort {
  private store = new Map<string, unknown>();

  async get<T = unknown>(key: string): Promise<T | null> {
    return (this.store.get(key) as T) || null;
  }

  async set<T = unknown>(key: string, value: T): Promise<void> {
    this.store.set(key, value);
  }

  async delete(key: string): Promise<void> {
    this.store.delete(key);
  }

  async exists(key: string): Promise<boolean> {
    return this.store.has(key);
  }

  async clear(): Promise<void> {
    this.store.clear();
  }
}

export class MockChainAdapter implements ChainInteractionPort {
  mockBlockNumber = BigInt(1000000);
  mockBalance = BigInt('1000000000000000000');
  mockNonce = 0;
  mockGasPrice = BigInt('30000000000');
  mockBaseFee = BigInt('20000000000');
  mockPriorityFee = BigInt('2000000000');
  mockEstimatedGas = BigInt(21000);

  async getChainId(): Promise<ChainId> {
    return 1;
  }

  async getBlockNumber(): Promise<bigint> {
    return this.mockBlockNumber;
  }

  async getBlock(blockHashOrNumber: Hash | bigint): Promise<Block | null> {
    return {
      hash: `0x${Buffer.from(String(blockHashOrNumber)).toString('hex').padStart(64, '0')}` as Hash,
      number: typeof blockHashOrNumber === 'bigint' ? blockHashOrNumber : this.mockBlockNumber,
      timestamp: BigInt(Math.floor(Date.now() / 1000)),
      chainId: 1,
      parentHash: '0x' + '0'.repeat(64) as Hash,
      gasLimit: BigInt(30000000),
      gasUsed: BigInt(15000000),
      baseFeePerGas: this.mockBaseFee,
      transactions: [],
    };
  }

  async getBalance(address: Address, blockTag?: 'latest' | 'pending' | bigint): Promise<WeiAmount> {
    return this.mockBalance;
  }

  async getNonce(address: Address, blockTag?: 'latest' | 'pending'): Promise<number> {
    return this.mockNonce;
  }

  async getTransaction(hash: Hash): Promise<Transaction | null> {
    return {
      hash,
      from: '0x0000000000000000000000000000000000000001' as Address,
      to: '0x0000000000000000000000000000000000000002' as Address,
      value: BigInt(0),
      data: '0x' as HexString,
      nonce: 0,
      gasLimit: BigInt(21000),
      gasPrice: this.mockGasPrice,
      chainId: 1,
      type: 0,
    };
  }

  async getTransactionReceipt(hash: Hash): Promise<TransactionReceipt | null> {
    return {
      transactionHash: hash,
      blockHash: '0x' + '0'.repeat(64) as Hash,
      blockNumber: this.mockBlockNumber,
      status: 'success',
      gasUsed: BigInt(21000),
      effectiveGasPrice: this.mockGasPrice,
      cumulativeGasUsed: BigInt(21000),
      logs: [],
    };
  }

  async getGasPrice(): Promise<WeiAmount> {
    return this.mockGasPrice;
  }

  async getFeePerGas(): Promise<{ baseFeePerGas: WeiAmount; maxPriorityFeePerGas: WeiAmount }> {
    return {
      baseFeePerGas: this.mockBaseFee,
      maxPriorityFeePerGas: this.mockPriorityFee,
    };
  }

  async estimateGas(): Promise<GasAmount> {
    return this.mockEstimatedGas;
  }

  async call(): Promise<HexString> {
    return '0x' as HexString;
  }

  async sendRawTransaction(): Promise<Hash> {
    return '0x' + '1'.repeat(64) as Hash;
  }

  async getLogs(): Promise<LogEntry[]> {
    return [];
  }

  async subscribeToLogs(): Promise<() => void> {
    return () => {};
  }

  async subscribeToNewBlocks(): Promise<() => void> {
    return () => {};
  }

  async waitForTransaction(hash: Hash): Promise<TransactionReceipt | null> {
    return this.getTransactionReceipt(hash);
  }
}

export class MockChainProvider implements ChainInteractionProvider {
  private clients = new Map<ChainId, ChainInteractionPort>();

  constructor(defaultClient?: ChainInteractionPort) {
    if (defaultClient) {
      this.clients.set(1, defaultClient);
    }
  }

  getClient(chainId: ChainId): ChainInteractionPort {
    const client = this.clients.get(chainId);
    if (!client) {
      const newClient = new MockChainAdapter();
      this.clients.set(chainId, newClient);
      return newClient;
    }
    return client;
  }

  addClient(chainId: ChainId, client: ChainInteractionPort): void {
    this.clients.set(chainId, client);
  }

  removeClient(chainId: ChainId): void {
    this.clients.delete(chainId);
  }

  getSupportedChains(): ChainId[] {
    return Array.from(this.clients.keys());
  }
}

export class MockStorageAdapter implements DecentralizedStoragePort {
  private store = new Map<string, StorageContent>();
  private pins = new Map<string, PinStatus>();

  async upload(content: Uint8Array, contentType = 'application/octet-stream'): Promise<StorageContent> {
    const cid = `bafy${Buffer.from(content).toString('hex').slice(0, 56)}`;
    const result: StorageContent = {
      cid,
      content,
      size: content.length,
      contentType,
      createdAt: new Date().toISOString(),
    };
    this.store.set(cid, result);
    return result;
  }

  async uploadJSON<T = unknown>(data: T): Promise<StorageContent> {
    const content = new TextEncoder().encode(JSON.stringify(data));
    return this.upload(content, 'application/json');
  }

  async download(cid: string): Promise<Uint8Array> {
    const stored = this.store.get(cid);
    return stored?.content || new Uint8Array();
  }

  async downloadJSON<T = unknown>(cid: string): Promise<T> {
    const content = await this.download(cid);
    return JSON.parse(new TextDecoder().decode(content)) as T;
  }

  async pin(cid: string): Promise<PinStatus> {
    const status: PinStatus = {
      cid,
      status: 'pinned',
      peers: [],
      createdAt: new Date().toISOString(),
    };
    this.pins.set(cid, status);
    return status;
  }

  async unpin(cid: string): Promise<boolean> {
    return this.pins.delete(cid);
  }

  async getPinStatus(cid: string): Promise<PinStatus | null> {
    return this.pins.get(cid) || null;
  }

  async listPins(): Promise<PinStatus[]> {
    return Array.from(this.pins.values());
  }

  getGatewayUrl(cid: string): string {
    return `https://ipfs.io/ipfs/${cid}`;
  }
}

export class MockHdWallet implements HdWalletPort {
  async deriveAddress(chainId: ChainId, index: number, isChange = false): Promise<{ address: Address; path: string }> {
    const path = `m/44'/60'/0'/${isChange ? 1 : 0}/${index}`;
    const address = `0x${Buffer.from(path).toString('hex').slice(0, 40)}` as Address;
    return { address, path };
  }

  getSigner(path: string): SignerPort {
    return new MockSigner();
  }

  getSeedFingerprint(): string {
    return 'mockfingerprint';
  }
}

export class MockSigner implements SignerPort {
  async getAddress(): Promise<Address> {
    return '0x0000000000000000000000000000000000000001' as Address;
  }

  async getChainId(): Promise<ChainId> {
    return 1;
  }

  async signMessage(): Promise<HexString> {
    return '0x' + 'a'.repeat(130) as HexString;
  }

  async signTransaction(): Promise<{ r: HexString; s: HexString; v: bigint }> {
    return {
      r: '0x' + 'r'.repeat(64) as HexString,
      s: '0x' + 's'.repeat(64) as HexString,
      v: BigInt(27),
    };
  }

  async signTypedData(): Promise<HexString> {
    return '0x' + 'b'.repeat(130) as HexString;
  }
}

export class MockEventDecoder implements EventDecoderPort {
  decodeLog = jest.fn().mockReturnValue(null);
  encodeTopics = jest.fn().mockReturnValue(['0x' + '0'.repeat(64)]);
  getEventSignature = jest.fn().mockReturnValue('0x' + '0'.repeat(64));
}

export class MockBridgeValidator implements BridgeValidatorPort {
  validateLockTransaction = jest.fn().mockResolvedValue(true);
  validateMintTransaction = jest.fn().mockResolvedValue(true);
  verifyMessageIntegrity = jest.fn().mockResolvedValue(true);
}
