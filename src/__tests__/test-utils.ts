import { ethers } from 'ethers';

export const CHAIN_IDS = {
  ETHEREUM: 1,
  BSC: 56,
  POLYGON: 137,
  ARBITRUM: 42161,
  OPTIMISM: 10,
};

export const TRANSFER_STATUSES = {
  PENDING: 'PENDING',
  LOCKED: 'LOCKED',
  VALIDATED: 'VALIDATED',
  MINTED: 'MINTED',
  CONFIRMED: 'CONFIRMED',
  FAILED: 'FAILED',
  REJECTED: 'REJECTED',
} as const;

export const PROPOSAL_STATUSES = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  EXECUTED: 'EXECUTED',
  REJECTED: 'REJECTED',
  EXPIRED: 'EXPIRED',
} as const;

export const PROPOSAL_TYPES = {
  TRANSFER: 'TRANSFER',
  APPROVE: 'APPROVE',
  EXECUTE: 'EXECUTE',
  UPDATE_OWNERS: 'UPDATE_OWNERS',
  CHANGE_THRESHOLD: 'CHANGE_THRESHOLD',
  CUSTOM: 'CUSTOM',
} as const;

export const DEFAULT_MULTISIG_OWNERS = [
  '0x742d35Cc6634C0532925a3b844Bc9e8588c10516',
  '0x8626f6940E2eb28930eFb4CeF49B2d1F2C9C1199',
  '0x1aE0EA34a72D944a8C7603FfB3eC30a6669E454C',
];

export const DEFAULT_THRESHOLD = 2;

export function generateCuid(): string {
  return `cl${Math.random().toString(36).substring(2, 10)}${Math.random().toString(36).substring(2, 6)}`;
}

export function generateAddress(): string {
  const wallet = ethers.Wallet.createRandom();
  return wallet.address;
}

export function generateTransactionHash(): string {
  return `0x${ethers.randomBytes(32).toString('hex')}`;
}

export function generateMessageHash(): string {
  return `0x${ethers.randomBytes(32).toString('hex')}`;
}

export function generateSignature(): string {
  const wallet = ethers.Wallet.createRandom();
  const message = ethers.randomBytes(32);
  const signature = wallet.signingKey.sign(ethers.hexlify(message)).serialized;
  return signature;
}

export function generateTimestamp(
  daysAgo: number = 0,
  hoursAgo: number = 0,
  minutesAgo: number = 0
): Date {
  const date = new Date();
  date.setDate(date.getDate() - daysAgo);
  date.setHours(date.getHours() - hoursAgo);
  date.setMinutes(date.getMinutes() - minutesAgo);
  return date;
}
