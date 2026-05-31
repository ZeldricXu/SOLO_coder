import type { Logger } from '@shared/logger';
import type { HdWalletPort, AddressBookPort, SignerPort } from '@core/ports/wallet.port';
import type { DerivedAddress, HdWallet } from '@core/domain/blockchain';
import type { ChainId, Address, UUID, HexString } from '@shared/types';
import { NotFoundError, ConflictError, ValidationError } from '@shared/errors';
import { z } from 'zod';

export class AddressManagerService implements AddressBookPort {
  private wallets: Map<UUID, HdWallet> = new Map();
  private derivedAddresses: Map<UUID, DerivedAddress> = new Map();
  private addressIndex: Map<string, UUID> = new Map();

  constructor(
    private readonly hdWalletFactory: (mnemonic?: string) => HdWalletPort,
    private readonly logger: Logger
  ) {}

  private generateId(): UUID {
    return `id_${Date.now()}_${Math.random().toString(36).slice(2, 10)}` as UUID;
  }

  private getAddressKey(address: Address, chainId: ChainId): string {
    return `${chainId}:${address.toLowerCase()}`;
  }

  async createWallet(mnemonic?: string): Promise<HdWallet> {
    const hdWallet = this.hdWalletFactory(mnemonic);

    const wallet: HdWallet = {
      id: this.generateId(),
      seedFingerprint: hdWallet.getSeedFingerprint(),
      purpose: 44,
      coinType: 60,
      account: 0,
      createdAt: new Date().toISOString(),
    };

    this.wallets.set(wallet.id, wallet);
    this.logger.info('Created HD wallet', { walletId: wallet.id });

    return wallet;
  }

  async deriveAddress(
    walletId: UUID,
    chainId: ChainId,
    index: number,
    isChange = false,
    label?: string,
    tags: string[] = []
  ): Promise<DerivedAddress> {
    const wallet = this.wallets.get(walletId);
    if (!wallet) {
      throw new NotFoundError('HdWallet', walletId);
    }

    const hdWallet = this.hdWalletFactory();
    const { address, path } = await hdWallet.deriveAddress(chainId, index, isChange);

    const addressKey = this.getAddressKey(address, chainId);
    if (this.addressIndex.has(addressKey)) {
      throw new ConflictError(`Address ${address} already exists for chain ${chainId}`);
    }

    const now = new Date().toISOString();
    const derivedAddress: DerivedAddress = {
      id: crypto.randomUUID(),
      walletId,
      address,
      path,
      chainId,
      label,
      tags: [],
      createdAt: now,
      updatedAt: now,
    };

    this.derivedAddresses.set(derivedAddress.id, derivedAddress);
    this.addressIndex.set(addressKey, derivedAddress.id);

    this.logger.info('Derived address', {
      walletId,
      address,
      chainId,
      path,
    });

    return derivedAddress;
  }

  async getSigner(walletId: UUID, path: string): Promise<SignerPort> {
    const wallet = this.wallets.get(walletId);
    if (!wallet) {
      throw new NotFoundError('HdWallet', walletId);
    }

    const hdWallet = this.hdWalletFactory();
    return hdWallet.getSigner(path);
  }

  async addAddress(
    address: Address,
    chainId: ChainId,
    label?: string,
    tags: string[] = []
  ): Promise<string> {
    const addressSchema = z.string().regex(/^0x[a-fA-F0-9]{40}$/, 'Invalid address format');
    const result = addressSchema.safeParse(address);
    if (!result.success) {
      throw new ValidationError({ address: ['Invalid address format'] });
    }

    const addressKey = this.getAddressKey(address, chainId);
    if (this.addressIndex.has(addressKey)) {
      const existingId = this.addressIndex.get(addressKey)!;
      const existing = this.derivedAddresses.get(existingId);
      if (existing) {
        this.logger.debug('Address already exists, returning existing ID', { address, chainId });
        return existing.id;
      }
    }

    const now = new Date().toISOString();
    const derivedAddress: DerivedAddress = {
      id: this.generateId(),
      walletId: 'external' as UUID,
      address,
      path: 'external',
      chainId,
      label,
      tags,
      createdAt: now,
      updatedAt: now,
    };

    this.derivedAddresses.set(derivedAddress.id, derivedAddress);
    this.addressIndex.set(addressKey, derivedAddress.id);

    this.logger.info('Added address to book', { address, chainId, label });

    return derivedAddress.id;
  }

  async removeAddress(id: string): Promise<boolean> {
    const address = this.derivedAddresses.get(id as UUID);
    if (!address) {
      return false;
    }

    const addressKey = this.getAddressKey(address.address, address.chainId);
    this.addressIndex.delete(addressKey);
    const deleted = this.derivedAddresses.delete(id as UUID);

    if (deleted) {
      this.logger.info('Removed address from book', { id, address: address.address });
    }

    return deleted;
  }

  async updateAddress(
    id: string,
    updates: { label?: string; tags?: string[] }
  ): Promise<boolean> {
    const address = this.derivedAddresses.get(id as UUID);
    if (!address) {
      return false;
    }

    if (updates.label !== undefined) {
      address.label = updates.label;
    }
    if (updates.tags !== undefined) {
      address.tags = updates.tags;
    }

    address.updatedAt = new Date().toISOString();

    this.logger.info('Updated address', { id, updates });

    return true;
  }

  async findByAddress(address: Address, chainId: ChainId): Promise<{
    id: string;
    address: Address;
    chainId: ChainId;
    label?: string;
    tags: string[];
  } | null> {
    const addressKey = this.getAddressKey(address, chainId);
    const id = this.addressIndex.get(addressKey);

    if (!id) return null;

    const derivedAddress = this.derivedAddresses.get(id);
    if (!derivedAddress) return null;

    return {
      id: derivedAddress.id,
      address: derivedAddress.address,
      chainId: derivedAddress.chainId,
      label: derivedAddress.label,
      tags: [...derivedAddress.tags],
    };
  }

  async listAddresses(filters?: {
    chainId?: ChainId;
    tag?: string;
    search?: string;
    walletId?: UUID;
  }): Promise<Array<{
    id: string;
    address: Address;
    chainId: ChainId;
    label?: string;
    tags: string[];
  }>> {
    return Array.from(this.derivedAddresses.values())
      .filter(addr => {
        if (filters?.chainId && addr.chainId !== filters.chainId) return false;
        if (filters?.walletId && addr.walletId !== filters.walletId) return false;
        if (filters?.tag && !addr.tags.includes(filters.tag)) return false;
        if (filters?.search) {
          const searchLower = filters.search.toLowerCase();
          const matchesLabel = addr.label?.toLowerCase().includes(searchLower);
          const matchesAddress = addr.address.toLowerCase().includes(searchLower);
          const matchesTag = addr.tags.some(t => t.toLowerCase().includes(searchLower));
          if (!matchesLabel && !matchesAddress && !matchesTag) return false;
        }
        return true;
      })
      .map(addr => ({
        id: addr.id,
        address: addr.address,
        chainId: addr.chainId,
        label: addr.label,
        tags: [...addr.tags],
      }));
  }

  async getWallet(walletId: UUID): Promise<HdWallet | null> {
    return this.wallets.get(walletId) || null;
  }

  async listWallets(): Promise<HdWallet[]> {
    return Array.from(this.wallets.values());
  }

  async getDerivedAddressesForWallet(walletId: UUID): Promise<DerivedAddress[]> {
    return Array.from(this.derivedAddresses.values()).filter(
      addr => addr.walletId === walletId
    );
  }

  async verifyAddressOwnership(
    address: Address,
    chainId: ChainId,
    message: string,
    signature: HexString
  ): Promise<boolean> {
    const addressKey = this.getAddressKey(address, chainId);
    const id = this.addressIndex.get(addressKey);

    if (!id) {
      this.logger.warn('Address not found for ownership verification', { address, chainId });
      return false;
    }

    const derivedAddress = this.derivedAddresses.get(id);
    if (!derivedAddress || derivedAddress.walletId === 'external') {
      return false;
    }

    try {
      const signer = await this.getSigner(derivedAddress.walletId, derivedAddress.path);
      const expectedAddress = await signer.getAddress();

      return expectedAddress.toLowerCase() === address.toLowerCase();
    } catch (error) {
      this.logger.error('Error verifying address ownership', { error, address });
      return false;
    }
  }
}
