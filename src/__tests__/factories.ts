import {
  generateCuid,
  generateAddress,
  generateTimestamp,
  CHAIN_IDS,
} from './test-utils';

export class TestDataFactory {
  private counter: number = 0;

  private getNextCounter(): number {
    return ++this.counter;
  }

  createAddress(overrides: Partial<any> = {}): any {
    const address = generateAddress();
    const now = new Date();

    return {
      id: overrides.id || generateCuid(),
      address: overrides.address || address,
      chainId: overrides.chainId ?? CHAIN_IDS.ETHEREUM,
      derivationPath: overrides.derivationPath || "m/44'/60'/0'/0/0",
      walletType: overrides.walletType || 'hd',
      label: overrides.label || `Test Wallet ${this.getNextCounter()}`,
      metadata: overrides.metadata || { description: 'Test address' },
      isActive: overrides.isActive ?? true,
      createdAt: overrides.createdAt || now,
      updatedAt: overrides.updatedAt || now,
    };
  }

  createAddressWithTags(overrides: Partial<any> = {}, tags: string[] = ['test']): any {
    const address = this.createAddress(overrides);
    return {
      ...address,
      AddressTag: tags.map((tag, index) => ({
        id: generateCuid(),
        addressId: address.id,
        tag,
        createdAt: generateTimestamp(0, index),
      })),
    };
  }

  createInvalidAddress(): any {
    return {
      id: generateCuid(),
      address: 'invalid_address',
      chainId: 0,
      derivationPath: '',
      walletType: 'invalid',
      isActive: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    };
  }

  createAddressList(count: number, overrides: Partial<any> = {}): any[] {
    return Array.from({ length: count }, () => this.createAddress(overrides));
  }

  createCrossChainTransfer(overrides: Partial<any> = {}): any {
    const now = new Date();

    return {
      id: overrides.id || generateCuid(),
      sourceChainId: overrides.sourceChainId ?? CHAIN_IDS.ETHEREUM,
      targetChainId: overrides.targetChainId ?? CHAIN_IDS.BSC,
      sourceAddress: overrides.sourceAddress || generateAddress(),
      targetAddress: overrides.targetAddress || generateAddress(),
      amount: overrides.amount || '1000000000000000000',
      tokenAddress: overrides.tokenAddress || generateAddress(),
      status: overrides.status || 'PENDING',
      sourceTxHash: overrides.sourceTxHash,
      targetTxHash: overrides.targetTxHash,
      messageHash: overrides.messageHash,
      signatures: overrides.signatures || [],
      createdAt: overrides.createdAt || now,
      updatedAt: overrides.updatedAt || now,
    };
  }

  createPendingTransfer(overrides: Partial<any> = {}): any {
    return this.createCrossChainTransfer({
      ...overrides,
      status: 'PENDING',
    });
  }

  createLockedTransfer(overrides: Partial<any> = {}): any {
    const { generateTransactionHash, generateMessageHash, generateSignature, DEFAULT_MULTISIG_OWNERS } = require('./test-utils');
    return this.createCrossChainTransfer({
      ...overrides,
      status: 'LOCKED',
      sourceTxHash: generateTransactionHash(),
      messageHash: generateMessageHash(),
      signatures: [
        {
          signer: DEFAULT_MULTISIG_OWNERS[0],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
        {
          signer: DEFAULT_MULTISIG_OWNERS[1],
          signature: generateSignature(),
          timestamp: Date.now(),
        },
      ],
    });
  }

  createValidatedTransfer(overrides: Partial<any> = {}): any {
    return this.createCrossChainTransfer({
      ...overrides,
      status: 'VALIDATED',
    });
  }

  createMintedTransfer(overrides: Partial<any> = {}): any {
    const { generateTransactionHash } = require('./test-utils');
    return this.createCrossChainTransfer({
      ...overrides,
      status: 'MINTED',
      targetTxHash: generateTransactionHash(),
    });
  }

  createTransferList(count: number, overrides: Partial<any> = {}): any[] {
    return Array.from({ length: count }, () => this.createCrossChainTransfer(overrides));
  }

  createInvalidTransfer(): any {
    return {
      id: generateCuid(),
      sourceChainId: 0,
      targetChainId: 0,
      sourceAddress: 'invalid_address',
      targetAddress: '',
      amount: '-100',
      status: 'INVALID',
      createdAt: new Date(),
      updatedAt: new Date(),
    };
  }

  createMultisigProposal(overrides: Partial<any> = {}): any {
    const { generateAddress, generateTransactionHash, DEFAULT_THRESHOLD, DEFAULT_MULTISIG_OWNERS } = require('./test-utils');
    const now = new Date();

    return {
      id: overrides.id || generateCuid(),
      walletId: overrides.walletId || `wallet_${Math.random().toString(36).substring(2, 10)}`,
      chainId: overrides.chainId ?? CHAIN_IDS.ETHEREUM,
      nonce: overrides.nonce ?? 0,
      type: overrides.type || 'TRANSFER',
      data: overrides.data || {
        to: generateAddress(),
        value: '1000000000000000000',
        data: '0x',
        operation: 0,
      },
      threshold: overrides.threshold ?? DEFAULT_THRESHOLD,
      requiredSigners: overrides.requiredSigners ?? DEFAULT_MULTISIG_OWNERS.length,
      signatures: overrides.signatures || [],
      status: overrides.status || 'PENDING',
      executedTxHash: overrides.executedTxHash,
      createdAt: overrides.createdAt || now,
      updatedAt: overrides.updatedAt || now,
    };
  }

  createPendingProposal(overrides: Partial<any> = {}): any {
    return this.createMultisigProposal({
      ...overrides,
      status: 'PENDING',
      signatures: [],
    });
  }

  createPartiallySignedProposal(overrides: Partial<any> = {}, signerCount: number = 1): any {
    const { generateSignature, DEFAULT_MULTISIG_OWNERS } = require('./test-utils');
    const signatures = DEFAULT_MULTISIG_OWNERS.slice(0, signerCount).map((signer) => ({
      signer,
      signature: generateSignature(),
      timestamp: Date.now(),
    }));

    return this.createMultisigProposal({
      ...overrides,
      status: 'PENDING',
      signatures,
    });
  }

  createApprovedProposal(overrides: Partial<any> = {}): any {
    const { generateSignature, DEFAULT_MULTISIG_OWNERS, DEFAULT_THRESHOLD } = require('./test-utils');
    const signatures = DEFAULT_MULTISIG_OWNERS.slice(0, DEFAULT_THRESHOLD).map((signer) => ({
      signer,
      signature: generateSignature(),
      timestamp: Date.now(),
    }));

    return this.createMultisigProposal({
      ...overrides,
      status: 'APPROVED',
      signatures,
    });
  }

  createExecutedProposal(overrides: Partial<any> = {}): any {
    const { generateTransactionHash } = require('./test-utils');
    return this.createApprovedProposal({
      ...overrides,
      status: 'EXECUTED',
      executedTxHash: generateTransactionHash(),
    });
  }

  createRejectedProposal(overrides: Partial<any> = {}): any {
    return this.createMultisigProposal({
      ...overrides,
      status: 'REJECTED',
    });
  }

  createProposalList(count: number, overrides: Partial<any> = {}): any[] {
    return Array.from({ length: count }, () => this.createMultisigProposal(overrides));
  }

  createInvalidProposal(): any {
    return {
      id: generateCuid(),
      walletId: '',
      chainId: 0,
      nonce: -1,
      type: 'INVALID',
      data: { to: 'invalid_address' },
      threshold: 0,
      requiredSigners: 0,
      signatures: [],
      status: 'INVALID',
      createdAt: new Date(),
      updatedAt: new Date(),
    };
  }

  createSignature(signer?: string): any {
    const { generateSignature, DEFAULT_MULTISIG_OWNERS } = require('./test-utils');
    return {
      signer: signer || DEFAULT_MULTISIG_OWNERS[0],
      signature: generateSignature(),
      timestamp: Date.now(),
    };
  }
}

export const testDataFactory = new TestDataFactory();
export default testDataFactory;
