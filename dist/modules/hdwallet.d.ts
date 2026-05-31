import { DerivedAddress } from '../types';
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
export declare class HDWalletManager {
    private wallets;
    private derivedAddresses;
    private addressBook;
    private logger;
    constructor();
    generateMnemonic(strength?: number): string;
    validateMnemonic(mnemonic: string): boolean;
    createHDWallet(params: {
        mnemonic?: string;
        passphrase?: string;
        basePath?: string;
        walletId?: string;
    }): Promise<{
        walletId: string;
        mnemonic: string;
        basePath: string;
    }>;
    removeHDWallet(walletId: string): boolean;
    listHDWallets(): string[];
    deriveAddresses(walletId: string, options?: DerivationOptions): Promise<DerivedAddress[]>;
    getDerivedAddress(path: string): DerivedAddress | undefined;
    listDerivedAddresses(walletId?: string): DerivedAddress[];
    getAddressByPath(path: string): DerivedAddress | undefined;
    getAddressByIndex(walletId: string, index: number): DerivedAddress | undefined;
    updateAddressMetadata(path: string, updates: {
        label?: string;
        tags?: string[];
    }): DerivedAddress;
    addToAddressBook(params: {
        address: string;
        label: string;
        tags?: string[];
        notes?: string;
        chainId?: number;
    }): AddressBookEntry;
    removeFromAddressBook(entryId: string): boolean;
    getAddressBookEntry(entryId: string): AddressBookEntry | undefined;
    listAddressBook(params?: {
        chainId?: number;
        tag?: string;
        search?: string;
    }): AddressBookEntry[];
    updateAddressBookEntry(entryId: string, updates: {
        label?: string;
        tags?: string[];
        notes?: string;
    }): AddressBookEntry;
    searchAddresses(query: string): Array<{
        type: 'derived' | 'addressbook';
        data: DerivedAddress | AddressBookEntry;
    }>;
    signWithDerivedAddress(path: string, message: string): Promise<{
        signature: string;
        address: string;
    }>;
    getWalletExtendedPublicKey(walletId: string): string;
}
export declare const hdWalletManager: HDWalletManager;
//# sourceMappingURL=hdwallet.d.ts.map