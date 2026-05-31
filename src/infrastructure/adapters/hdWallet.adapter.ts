import { mnemonicToAccount, generateMnemonic, english, type HDAccount } from 'viem/accounts';
import type { HdWalletPort, SignerPort } from '@core/ports/wallet.port';
import type { ChainId, Address, HexString } from '@shared/types';
import type { Transaction, TransactionSignature } from '@core/domain/blockchain';

const CHAIN_TO_COIN_TYPE: Record<number, number> = {
  1: 60,
  5: 1,
  137: 60,
  42161: 60,
};

function generateBip39Mnemonic(): string {
  return generateMnemonic(english, 128);
}

export class ViemHdWallet implements HdWalletPort {
  private rootMnemonic: string;
  private seedFingerprint: string;

  constructor(mnemonic?: string) {
    this.rootMnemonic = mnemonic || generateBip39Mnemonic();
    this.seedFingerprint = Buffer.from(this.rootMnemonic).toString('hex').slice(0, 16);
  }

  private getCoinType(chainId: ChainId): number {
    return CHAIN_TO_COIN_TYPE[chainId] || 60;
  }

  private derivePath(
    chainId: ChainId,
    index: number,
    isChange = false
  ): string {
    const coinType = this.getCoinType(chainId);
    const change = isChange ? 1 : 0;
    return `m/44'/${coinType}'/0'/${change}/${index}`;
  }

  async deriveAddress(
    chainId: ChainId,
    index: number,
    isChange = false
  ): Promise<{ address: Address; path: string }> {
    const path = this.derivePath(chainId, index, isChange);
    const account = mnemonicToAccount(this.rootMnemonic, { path: path as `m/44'/60'/${string}` });
    return { address: account.address as Address, path };
  }

  getSigner(path: string): SignerPort {
    const account = mnemonicToAccount(this.rootMnemonic, { path: path as `m/44'/60'/${string}` });
    return new ViemSigner(account);
  }

  getSeedFingerprint(): string {
    return this.seedFingerprint;
  }

  static createFactory(): (mnemonic?: string) => HdWalletPort {
    return (mnemonic) => new ViemHdWallet(mnemonic);
  }
}

export class ViemSigner implements SignerPort {
  constructor(private readonly account: HDAccount) {}

  async getAddress(): Promise<Address> {
    return this.account.address as Address;
  }

  async getChainId(): Promise<ChainId> {
    return 1;
  }

  async signMessage(message: string | Uint8Array): Promise<HexString> {
    if (typeof message === 'string') {
      return this.account.signMessage({ message }) as Promise<HexString>;
    }
    return this.account.signMessage({ message: { raw: message } }) as Promise<HexString>;
  }

  async signTransaction(transaction: Omit<Transaction, 'signature'>): Promise<TransactionSignature> {
    const txRequest: Parameters<typeof this.account.signTransaction>[0] = {
      to: transaction.to as `0x${string}` | undefined,
      value: transaction.value,
      data: transaction.data as `0x${string}` | undefined,
      nonce: transaction.nonce,
      gas: transaction.gasLimit,
      chainId: transaction.chainId,
      ...(transaction.type === 2
        ? {
            maxFeePerGas: transaction.maxFeePerGas,
            maxPriorityFeePerGas: transaction.maxPriorityFeePerGas,
          }
        : {
            gasPrice: transaction.gasPrice,
          }),
    };

    const signature = await this.account.signTransaction(txRequest);
    
    const r = `0x${signature.slice(2, 66)}` as HexString;
    const s = `0x${signature.slice(66, 130)}` as HexString;
    const v = BigInt(parseInt(signature.slice(130, 132), 16));

    return { r, s, v };
  }

  async signTypedData(typedData: unknown): Promise<HexString> {
    const data = typedData as {
      domain: Record<string, unknown>;
      types: Record<string, unknown>;
      primaryType: string;
      message: Record<string, unknown>;
    };
    return this.account.signTypedData(data) as Promise<HexString>;
  }
}
