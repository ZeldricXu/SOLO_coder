import * as crypto from 'crypto';
import { IKeyDerivationService } from './interfaces';

export class KeyDerivationService implements IKeyDerivationService {
  private readonly enclaveKeys: Map<string, Buffer> = new Map();
  private readonly masterKey: Buffer;

  constructor(masterKey: string) {
    this.masterKey = crypto.scryptSync(masterKey, 'tee-salt', 32);
  }

  public deriveEnclaveKey(enclaveId: string): Buffer {
    const key = crypto
      .createHmac('sha256', this.masterKey)
      .update(enclaveId)
      .digest();
    
    this.enclaveKeys.set(enclaveId, key);
    return key;
  }

  public getEnclaveKey(enclaveId: string): Buffer | null {
    return this.enclaveKeys.get(enclaveId) || null;
  }

  public removeEnclaveKey(enclaveId: string): boolean {
    return this.enclaveKeys.delete(enclaveId);
  }

  public getMasterKey(): Buffer {
    return this.masterKey;
  }
}

export const createKeyDerivationService = (masterKey: string): KeyDerivationService => {
  return new KeyDerivationService(masterKey);
};
