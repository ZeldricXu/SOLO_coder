import * as bip39 from 'bip39';
import { BIP32Factory } from 'bip32';
import * as ecc from 'tiny-secp256k1';
import { Wallet, isAddress } from 'ethers';
import { DerivedAddress } from '../types';
import { DEFAULT_HD_PATH } from '../config';
import { generateId, now, normalizeAddress } from '../common/utils';
import { eventBus, EVENTS } from '../common/events';
import { LoggerContext } from '../common/logger';

const bip32 = BIP32Factory(ecc);

export interface AddressBookEntry {
  id: string;
  address: string;
  label: string;
  tags: string[];
  notes?: string;
  chainId?: number;
  createdAt: string;
  updatedAt: string;
}

export interface HDWalletConfig {
  mnemonic: string;
  passphrase?: string;
  basePath: string;
}

export interface DerivationOptions {
  index?: number;
  count?: number;
  includePrivateKey?: boolean;
  label?: string;
  tags?: string[];
}

export class HDWalletManager {
  private wallets: Map<string, HDWalletConfig>;
  private derivedAddresses: Map<string, DerivedAddress>;
  private addressBook: Map<string, AddressBookEntry>;
  private logger: LoggerContext;

  constructor() {
    this.wallets = new Map();
    this.derivedAddresses = new Map();
    this.addressBook = new Map();
    this.logger = new LoggerContext({ module: 'HDWalletManager' });
  }

  generateMnemonic(strength: number = 256): string {
    this.logger.info('Generating mnemonic', { strength });
    return bip39.generateMnemonic(strength);
  }

  validateMnemonic(mnemonic: string): boolean {
    return bip39.validateMnemonic(mnemonic);
  }

  async createHDWallet(params: {
    mnemonic?: string;
    passphrase?: string;
    basePath?: string;
    walletId?: string;
  }): Promise<{ walletId: string; mnemonic: string; basePath: string }> {
    this.logger.info('Creating HD wallet');

    let { mnemonic, passphrase, basePath = DEFAULT_HD_PATH, walletId } = params;

    if (!mnemonic) {
      mnemonic = this.generateMnemonic();
    }

    if (!this.validateMnemonic(mnemonic)) {
      throw new Error('Invalid mnemonic phrase');
    }

    const seed = await bip39.mnemonicToSeed(mnemonic, passphrase);
    try {
      bip32.fromSeed(seed);
    } catch (error) {
      throw new Error('Invalid mnemonic or passphrase');
    }

    walletId = walletId || generateId('hdwallet');

    if (this.wallets.has(walletId)) {
      throw new Error(`Wallet ID already exists: ${walletId}`);
    }

    this.wallets.set(walletId, { mnemonic, passphrase, basePath });

    this.logger.info('HD wallet created', { walletId, basePath });

    return { walletId, mnemonic, basePath };
  }

  removeHDWallet(walletId: string): boolean {
    const removed = this.wallets.delete(walletId);
    if (removed) {
      const derivedAddresses = Array.from(this.derivedAddresses.values()).filter(
        (a) => a.path.startsWith(this.wallets.get(walletId)?.basePath || '')
      );
      derivedAddresses.forEach((a) => this.derivedAddresses.delete(a.path));
      this.logger.info('HD wallet removed', { walletId, derivedAddressCount: derivedAddresses.length });
    }
    return removed;
  }

  listHDWallets(): string[] {
    return Array.from(this.wallets.keys());
  }

  async deriveAddresses(
    walletId: string,
    options: DerivationOptions = {}
  ): Promise<DerivedAddress[]> {
    this.logger.info('Deriving addresses', { walletId, ...options });

    const wallet = this.wallets.get(walletId);
    if (!wallet) {
      throw new Error(`HD wallet not found: ${walletId}`);
    }

    const { index = 0, count = 1, includePrivateKey = false, label, tags = [] } = options;

    if (count <= 0) {
      throw new Error('Count must be greater than 0');
    }

    if (index < 0) {
      throw new Error('Index must be non-negative');
    }

    const seed = await bip39.mnemonicToSeed(wallet.mnemonic, wallet.passphrase);
    const root = bip32.fromSeed(seed);

    const derived: DerivedAddress[] = [];

    for (let i = index; i < index + count; i++) {
      const path = `${wallet.basePath}/${i}`;
      const child = root.derivePath(path);

      const privateKey = child.privateKey?.toString('hex');
      if (!privateKey) {
        throw new Error(`Failed to derive private key for path: ${path}`);
      }

      const ethWallet = new Wallet(privateKey);
      const address: DerivedAddress = {
        path,
        address: normalizeAddress(ethWallet.address),
        publicKey: ethWallet.signingKey.publicKey,
        privateKey: includePrivateKey ? `0x${privateKey}` : undefined,
        chainCode: child.chainCode.toString('hex'),
        index: i,
        label: i === index ? label : undefined,
        tags: i === index ? tags : [],
        createdAt: now(),
      };

      this.derivedAddresses.set(path, address);
      derived.push(address);

      eventBus.emit(EVENTS.ADDRESS_DERIVED, { walletId, address: address.address, path });
    }

    this.logger.info('Addresses derived', { walletId, count: derived.length, startIndex: index });
    return derived;
  }

  getDerivedAddress(path: string): DerivedAddress | undefined {
    return this.derivedAddresses.get(path);
  }

  listDerivedAddresses(walletId?: string): DerivedAddress[] {
    let addresses = Array.from(this.derivedAddresses.values());

    if (walletId) {
      const wallet = this.wallets.get(walletId);
      if (wallet) {
        addresses = addresses.filter((a) => a.path.startsWith(wallet.basePath));
      }
    }

    return addresses.sort((a, b) => a.index - b.index);
  }

  getAddressByPath(path: string): DerivedAddress | undefined {
    return this.derivedAddresses.get(path);
  }

  getAddressByIndex(walletId: string, index: number): DerivedAddress | undefined {
    const wallet = this.wallets.get(walletId);
    if (!wallet) return undefined;
    return this.derivedAddresses.get(`${wallet.basePath}/${index}`);
  }

  updateAddressMetadata(
    path: string,
    updates: { label?: string; tags?: string[] }
  ): DerivedAddress {
    const address = this.derivedAddresses.get(path);
    if (!address) {
      throw new Error(`Address not found for path: ${path}`);
    }

    if (updates.label !== undefined) {
      address.label = updates.label;
    }

    if (updates.tags !== undefined) {
      address.tags = updates.tags;
    }

    this.logger.info('Address metadata updated', { path, ...updates });
    return address;
  }

  addToAddressBook(params: {
    address: string;
    label: string;
    tags?: string[];
    notes?: string;
    chainId?: number;
  }): AddressBookEntry {
    const { address, label, tags = [], notes, chainId } = params;

    this.logger.info('Adding to address book', { address, label });

    const normalizedAddress = normalizeAddress(address);

    if (!isAddress(normalizedAddress)) {
      throw new Error(`Invalid address: ${address}`);
    }

    const existingEntry = Array.from(this.addressBook.values()).find(
      (e) => normalizeAddress(e.address) === normalizedAddress && e.chainId === chainId
    );

    if (existingEntry) {
      throw new Error(`Address already in address book: ${address}`);
    }

    const entry: AddressBookEntry = {
      id: generateId('ab'),
      address: normalizedAddress,
      label,
      tags,
      notes,
      chainId,
      createdAt: now(),
      updatedAt: now(),
    };

    this.addressBook.set(entry.id, entry);
    this.logger.info('Address added to address book', { id: entry.id, address: normalizedAddress });

    return entry;
  }

  removeFromAddressBook(entryId: string): boolean {
    return this.addressBook.delete(entryId);
  }

  getAddressBookEntry(entryId: string): AddressBookEntry | undefined {
    return this.addressBook.get(entryId);
  }

  listAddressBook(params?: {
    chainId?: number;
    tag?: string;
    search?: string;
  }): AddressBookEntry[] {
    let entries = Array.from(this.addressBook.values());

    if (params?.chainId !== undefined) {
      entries = entries.filter((e) => e.chainId === params?.chainId);
    }

    if (params?.tag) {
      entries = entries.filter((e) => e.tags.includes(params.tag!));
    }

    if (params?.search) {
      const searchLower = params.search.toLowerCase();
      entries = entries.filter(
        (e) =>
          e.label.toLowerCase().includes(searchLower) ||
          e.address.toLowerCase().includes(searchLower) ||
          e.tags.some((t) => t.toLowerCase().includes(searchLower))
      );
    }

    return entries.sort((a, b) => a.label.localeCompare(b.label));
  }

  updateAddressBookEntry(
    entryId: string,
    updates: {
      label?: string;
      tags?: string[];
      notes?: string;
    }
  ): AddressBookEntry {
    const entry = this.addressBook.get(entryId);
    if (!entry) {
      throw new Error(`Address book entry not found: ${entryId}`);
    }

    if (updates.label !== undefined) {
      entry.label = updates.label;
    }

    if (updates.tags !== undefined) {
      entry.tags = updates.tags;
    }

    if (updates.notes !== undefined) {
      entry.notes = updates.notes;
    }

    entry.updatedAt = now();
    this.logger.info('Address book entry updated', { entryId, ...updates });

    return entry;
  }

  searchAddresses(query: string): Array<{ type: 'derived' | 'addressbook'; data: DerivedAddress | AddressBookEntry }> {
    const queryLower = query.toLowerCase();
    const results: Array<{ type: 'derived' | 'addressbook'; data: DerivedAddress | AddressBookEntry }> = [];

    for (const addr of this.derivedAddresses.values()) {
      if (
        addr.address.toLowerCase().includes(queryLower) ||
        addr.label?.toLowerCase().includes(queryLower) ||
        addr.tags.some((t) => t.toLowerCase().includes(queryLower))
      ) {
        results.push({ type: 'derived', data: addr });
      }
    }

    for (const entry of this.addressBook.values()) {
      if (
        entry.address.toLowerCase().includes(queryLower) ||
        entry.label.toLowerCase().includes(queryLower) ||
        entry.tags.some((t) => t.toLowerCase().includes(queryLower)) ||
        entry.notes?.toLowerCase().includes(queryLower)
      ) {
        results.push({ type: 'addressbook', data: entry });
      }
    }

    return results;
  }

  async signWithDerivedAddress(
    path: string,
    message: string
  ): Promise<{ signature: string; address: string }> {
    const address = this.derivedAddresses.get(path);
    if (!address) {
      throw new Error(`Address not found for path: ${path}`);
    }

    if (!address.privateKey) {
      throw new Error('Private key not available for this address');
    }

    const wallet = new Wallet(address.privateKey);
    const signature = await wallet.signMessage(message);

    return {
      signature,
      address: address.address,
    };
  }

  getWalletExtendedPublicKey(walletId: string): string {
    const wallet = this.wallets.get(walletId);
    if (!wallet) {
      throw new Error(`HD wallet not found: ${walletId}`);
    }

    // In a real implementation, we would derive and return the xpub
    // For this implementation, we'll return a placeholder
    return `xpub_${generateId('xpub')}`;
  }
}

export const hdWalletManager = new HDWalletManager();
