import { ethers } from 'ethers';
import { config } from '../config';

export class CryptoUtils {
  static generateMnemonic(): string {
    return ethers.Mnemonic.entropyToPhrase(ethers.randomBytes(32));
  }

  static createWallet(mnemonic?: string, passphrase?: string): ethers.HDNodeWallet {
    const mnemonicPhrase = mnemonic || config.wallet.mnemonic;
    const hdNode = ethers.HDNodeWallet.fromPhrase(
      mnemonicPhrase,
      passphrase || config.wallet.passphrase
    );
    return hdNode;
  }

  static derivePath(
    chainId: number,
    accountIndex: number = 0,
    addressIndex: number = 0
  ): string {
    const coinType = this.getCoinType(chainId);
    return `m/44'/${coinType}'/${accountIndex}'/0/${addressIndex}`;
  }

  static getCoinType(chainId: number): number {
    const coinTypes: Record<number, number> = {
      1: 60,
      3: 60,
      4: 60,
      5: 60,
      42: 60,
      56: 60,
      97: 60,
      137: 60,
      80001: 60,
      42161: 60,
      421613: 60,
      10: 60,
      69: 60,
    };
    return coinTypes[chainId] || 60;
  }

  static deriveAddress(
    chainId: number,
    accountIndex: number = 0,
    addressIndex: number = 0,
    mnemonic?: string,
    passphrase?: string
  ): { address: string; privateKey: string; path: string } {
    const wallet = this.createWallet(mnemonic, passphrase);
    const path = this.derivePath(chainId, accountIndex, addressIndex);
    const derivedWallet = wallet.derivePath(path);
    
    return {
      address: derivedWallet.address,
      privateKey: derivedWallet.privateKey,
      path,
    };
  }

  static generateMessageHash(message: string): string {
    return ethers.keccak256(ethers.toUtf8Bytes(message));
  }

  static signMessage(message: string, privateKey: string): string {
    const wallet = new ethers.Wallet(privateKey);
    return wallet.signMessageSync(message);
  }

  static verifySignature(message: string, signature: string, expectedAddress: string): boolean {
    const recoveredAddress = ethers.verifyMessage(message, signature);
    return recoveredAddress.toLowerCase() === expectedAddress.toLowerCase();
  }

  static verifyTypedData(
    domain: ethers.TypedDataDomain,
    types: Record<string, ethers.TypedDataField[]>,
    value: Record<string, any>,
    signature: string,
    expectedAddress: string
  ): boolean {
    const recoveredAddress = ethers.verifyTypedData(domain, types, value, signature);
    return recoveredAddress.toLowerCase() === expectedAddress.toLowerCase();
  }

  static isValidAddress(address: string): boolean {
    return ethers.isAddress(address);
  }

  static checksumAddress(address: string): string {
    return ethers.getAddress(address);
  }

  static toWei(amount: string | number, unit: ethers.Numeric = 'ether'): bigint {
    return ethers.parseEther(amount.toString());
  }

  static fromWei(amount: bigint | string, unit: ethers.Numeric = 'ether'): string {
    return ethers.formatEther(amount);
  }

  static randomAddress(): string {
    return ethers.Wallet.createRandom().address;
  }

  static encodeFunctionData(
    abi: ethers.InterfaceAbi,
    functionName: string,
    params: any[]
  ): string {
    const iface = new ethers.Interface(abi);
    return iface.encodeFunctionData(functionName, params);
  }

  static decodeFunctionData(
    abi: ethers.InterfaceAbi,
    functionName: string,
    data: string
  ): ethers.Result {
    const iface = new ethers.Interface(abi);
    return iface.decodeFunctionData(functionName, data);
  }
}

export default CryptoUtils;
