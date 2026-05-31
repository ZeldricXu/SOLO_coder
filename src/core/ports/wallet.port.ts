import type { ChainId, Address, Hash, HexString, WeiAmount, GasAmount } from '@shared/types';
import type { Transaction, TransactionSignature } from '@core/domain/blockchain';

export interface SignerPort {
  getAddress(): Promise<Address>;
  getChainId(): Promise<ChainId>;
  signMessage(message: string | Uint8Array): Promise<HexString>;
  signTransaction(transaction: Omit<Transaction, 'signature'>): Promise<TransactionSignature>;
  signTypedData(typedData: unknown): Promise<HexString>;
}

export interface HdWalletPort {
  deriveAddress(
    chainId: ChainId,
    index: number,
    isChange?: boolean
  ): Promise<{ address: Address; path: string }>;
  getSigner(path: string): SignerPort;
  getSeedFingerprint(): string;
}

export interface MultisigCoordinatorPort {
  createProposal(
    walletAddress: Address,
    chainId: ChainId,
    to: Address,
    value: WeiAmount,
    data: HexString,
    meta?: Record<string, unknown>
  ): Promise<{
    proposalId: string;
    nonce: bigint;
    transactionHash: Hash;
  }>;
  collectSignature(
    proposalId: string,
    signer: Address,
    signature: HexString
  ): Promise<{
    proposalId: string;
    currentSignatures: number;
    threshold: number;
    isReady: boolean;
  }>;
  executeProposal(proposalId: string): Promise<{
    transactionHash: Hash;
    nonce: bigint;
  }>;
  getProposal(proposalId: string): Promise<{
    id: string;
    walletAddress: Address;
    to: Address;
    value: WeiAmount;
    data: HexString;
    nonce: bigint;
    signatures: Map<Address, HexString>;
    status: 'pending' | 'ready' | 'executing' | 'executed' | 'failed';
  } | null>;
}

export interface AddressBookPort {
  addAddress(
    address: Address,
    chainId: ChainId,
    label?: string,
    tags?: string[]
  ): Promise<string>;
  removeAddress(id: string): Promise<boolean>;
  updateAddress(
    id: string,
    updates: { label?: string; tags?: string[] }
  ): Promise<boolean>;
  findByAddress(address: Address, chainId: ChainId): Promise<{
    id: string;
    address: Address;
    chainId: ChainId;
    label?: string;
    tags: string[];
  } | null>;
  listAddresses(filters?: {
    chainId?: ChainId;
    tag?: string;
    search?: string;
  }): Promise<Array<{
    id: string;
    address: Address;
    chainId: ChainId;
    label?: string;
    tags: string[];
  }>>;
}
