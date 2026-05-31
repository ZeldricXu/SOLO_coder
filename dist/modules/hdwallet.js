"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.hdWalletManager = exports.HDWalletManager = void 0;
const bip39 = __importStar(require("bip39"));
const bip32_1 = require("bip32");
const ecc = __importStar(require("tiny-secp256k1"));
const ethers_1 = require("ethers");
const config_1 = require("../config");
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
const bip32 = (0, bip32_1.BIP32Factory)(ecc);
class HDWalletManager {
    wallets;
    derivedAddresses;
    addressBook;
    logger;
    constructor() {
        this.wallets = new Map();
        this.derivedAddresses = new Map();
        this.addressBook = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'HDWalletManager' });
    }
    generateMnemonic(strength = 256) {
        this.logger.info('Generating mnemonic', { strength });
        return bip39.generateMnemonic(strength);
    }
    validateMnemonic(mnemonic) {
        return bip39.validateMnemonic(mnemonic);
    }
    async createHDWallet(params) {
        this.logger.info('Creating HD wallet');
        let { mnemonic, passphrase, basePath = config_1.DEFAULT_HD_PATH, walletId } = params;
        if (!mnemonic) {
            mnemonic = this.generateMnemonic();
        }
        if (!this.validateMnemonic(mnemonic)) {
            throw new Error('Invalid mnemonic phrase');
        }
        const seed = await bip39.mnemonicToSeed(mnemonic, passphrase);
        try {
            bip32.fromSeed(seed);
        }
        catch (error) {
            throw new Error('Invalid mnemonic or passphrase');
        }
        walletId = walletId || (0, utils_1.generateId)('hdwallet');
        if (this.wallets.has(walletId)) {
            throw new Error(`Wallet ID already exists: ${walletId}`);
        }
        this.wallets.set(walletId, { mnemonic, passphrase, basePath });
        this.logger.info('HD wallet created', { walletId, basePath });
        return { walletId, mnemonic, basePath };
    }
    removeHDWallet(walletId) {
        const removed = this.wallets.delete(walletId);
        if (removed) {
            const derivedAddresses = Array.from(this.derivedAddresses.values()).filter((a) => a.path.startsWith(this.wallets.get(walletId)?.basePath || ''));
            derivedAddresses.forEach((a) => this.derivedAddresses.delete(a.path));
            this.logger.info('HD wallet removed', { walletId, derivedAddressCount: derivedAddresses.length });
        }
        return removed;
    }
    listHDWallets() {
        return Array.from(this.wallets.keys());
    }
    async deriveAddresses(walletId, options = {}) {
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
        const derived = [];
        for (let i = index; i < index + count; i++) {
            const path = `${wallet.basePath}/${i}`;
            const child = root.derivePath(path);
            const privateKey = child.privateKey?.toString('hex');
            if (!privateKey) {
                throw new Error(`Failed to derive private key for path: ${path}`);
            }
            const ethWallet = new ethers_1.Wallet(privateKey);
            const address = {
                path,
                address: (0, utils_1.normalizeAddress)(ethWallet.address),
                publicKey: ethWallet.signingKey.publicKey,
                privateKey: includePrivateKey ? `0x${privateKey}` : undefined,
                chainCode: child.chainCode.toString('hex'),
                index: i,
                label: i === index ? label : undefined,
                tags: i === index ? tags : [],
                createdAt: (0, utils_1.now)(),
            };
            this.derivedAddresses.set(path, address);
            derived.push(address);
            events_1.eventBus.emit(events_1.EVENTS.ADDRESS_DERIVED, { walletId, address: address.address, path });
        }
        this.logger.info('Addresses derived', { walletId, count: derived.length, startIndex: index });
        return derived;
    }
    getDerivedAddress(path) {
        return this.derivedAddresses.get(path);
    }
    listDerivedAddresses(walletId) {
        let addresses = Array.from(this.derivedAddresses.values());
        if (walletId) {
            const wallet = this.wallets.get(walletId);
            if (wallet) {
                addresses = addresses.filter((a) => a.path.startsWith(wallet.basePath));
            }
        }
        return addresses.sort((a, b) => a.index - b.index);
    }
    getAddressByPath(path) {
        return this.derivedAddresses.get(path);
    }
    getAddressByIndex(walletId, index) {
        const wallet = this.wallets.get(walletId);
        if (!wallet)
            return undefined;
        return this.derivedAddresses.get(`${wallet.basePath}/${index}`);
    }
    updateAddressMetadata(path, updates) {
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
    addToAddressBook(params) {
        const { address, label, tags = [], notes, chainId } = params;
        this.logger.info('Adding to address book', { address, label });
        const normalizedAddress = (0, utils_1.normalizeAddress)(address);
        if (!(0, ethers_1.isAddress)(normalizedAddress)) {
            throw new Error(`Invalid address: ${address}`);
        }
        const existingEntry = Array.from(this.addressBook.values()).find((e) => (0, utils_1.normalizeAddress)(e.address) === normalizedAddress && e.chainId === chainId);
        if (existingEntry) {
            throw new Error(`Address already in address book: ${address}`);
        }
        const entry = {
            id: (0, utils_1.generateId)('ab'),
            address: normalizedAddress,
            label,
            tags,
            notes,
            chainId,
            createdAt: (0, utils_1.now)(),
            updatedAt: (0, utils_1.now)(),
        };
        this.addressBook.set(entry.id, entry);
        this.logger.info('Address added to address book', { id: entry.id, address: normalizedAddress });
        return entry;
    }
    removeFromAddressBook(entryId) {
        return this.addressBook.delete(entryId);
    }
    getAddressBookEntry(entryId) {
        return this.addressBook.get(entryId);
    }
    listAddressBook(params) {
        let entries = Array.from(this.addressBook.values());
        if (params?.chainId !== undefined) {
            entries = entries.filter((e) => e.chainId === params?.chainId);
        }
        if (params?.tag) {
            entries = entries.filter((e) => e.tags.includes(params.tag));
        }
        if (params?.search) {
            const searchLower = params.search.toLowerCase();
            entries = entries.filter((e) => e.label.toLowerCase().includes(searchLower) ||
                e.address.toLowerCase().includes(searchLower) ||
                e.tags.some((t) => t.toLowerCase().includes(searchLower)));
        }
        return entries.sort((a, b) => a.label.localeCompare(b.label));
    }
    updateAddressBookEntry(entryId, updates) {
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
        entry.updatedAt = (0, utils_1.now)();
        this.logger.info('Address book entry updated', { entryId, ...updates });
        return entry;
    }
    searchAddresses(query) {
        const queryLower = query.toLowerCase();
        const results = [];
        for (const addr of this.derivedAddresses.values()) {
            if (addr.address.toLowerCase().includes(queryLower) ||
                addr.label?.toLowerCase().includes(queryLower) ||
                addr.tags.some((t) => t.toLowerCase().includes(queryLower))) {
                results.push({ type: 'derived', data: addr });
            }
        }
        for (const entry of this.addressBook.values()) {
            if (entry.address.toLowerCase().includes(queryLower) ||
                entry.label.toLowerCase().includes(queryLower) ||
                entry.tags.some((t) => t.toLowerCase().includes(queryLower)) ||
                entry.notes?.toLowerCase().includes(queryLower)) {
                results.push({ type: 'addressbook', data: entry });
            }
        }
        return results;
    }
    async signWithDerivedAddress(path, message) {
        const address = this.derivedAddresses.get(path);
        if (!address) {
            throw new Error(`Address not found for path: ${path}`);
        }
        if (!address.privateKey) {
            throw new Error('Private key not available for this address');
        }
        const wallet = new ethers_1.Wallet(address.privateKey);
        const signature = await wallet.signMessage(message);
        return {
            signature,
            address: address.address,
        };
    }
    getWalletExtendedPublicKey(walletId) {
        const wallet = this.wallets.get(walletId);
        if (!wallet) {
            throw new Error(`HD wallet not found: ${walletId}`);
        }
        // In a real implementation, we would derive and return the xpub
        // For this implementation, we'll return a placeholder
        return `xpub_${(0, utils_1.generateId)('xpub')}`;
    }
}
exports.HDWalletManager = HDWalletManager;
exports.hdWalletManager = new HDWalletManager();
//# sourceMappingURL=hdwallet.js.map