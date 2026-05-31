import * as crypto from 'crypto';
import { ITEECryptoProvider } from './interfaces';
import { IKeyDerivationService } from './interfaces';
import { IEnclaveManager } from './interfaces';
import { SecureData } from '../core/types';

export class TEECryptoProvider implements ITEECryptoProvider {
  constructor(
    private readonly keyDerivationService: IKeyDerivationService,
    private readonly enclaveManager: IEnclaveManager
  ) {}

  public encryptInEnclave(enclaveId: string, plaintext: string): SecureData | null {
    if (!(this.enclaveManager as any).isEnclaveRunning(enclaveId)) {
      return null;
    }

    const key = this.keyDerivationService.getEnclaveKey(enclaveId);
    if (!key) return null;

    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    
    let encrypted = cipher.update(plaintext, 'utf8', 'hex');
    encrypted += cipher.final('hex');
    
    const tag = cipher.getAuthTag().toString('hex');

    return {
      encryptedData: encrypted,
      iv: iv.toString('hex'),
      tag,
      enclaveId,
      timestamp: Date.now()
    };
  }

  public decryptInEnclave(enclaveId: string, secureData: SecureData): string | null {
    if (!(this.enclaveManager as any).isEnclaveRunning(enclaveId)) {
      return null;
    }
    if (secureData.enclaveId !== enclaveId) return null;

    const key = this.keyDerivationService.getEnclaveKey(enclaveId);
    if (!key) return null;

    try {
      const iv = Buffer.from(secureData.iv, 'hex');
      const encrypted = Buffer.from(secureData.encryptedData, 'hex');
      const tag = Buffer.from(secureData.tag, 'hex');

      const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
      decipher.setAuthTag(tag);

      let decrypted = decipher.update(encrypted.toString('hex'), 'hex', 'utf8');
      decrypted += decipher.final('utf8');

      return decrypted;
    } catch {
      return null;
    }
  }

  public sign(data: string): string {
    const masterKey = (this.keyDerivationService as any).getMasterKey();
    return crypto
      .createHmac('sha256', masterKey)
      .update(data)
      .digest('hex');
  }

  public verifySignature(data: string, signature: string): boolean {
    const expectedSignature = this.sign(data);
    return crypto.timingSafeEqual(
      Buffer.from(signature, 'hex'),
      Buffer.from(expectedSignature, 'hex')
    );
  }
}

export const createTEECryptoProvider = (
  keyDerivationService: IKeyDerivationService,
  enclaveManager: IEnclaveManager
): TEECryptoProvider => {
  return new TEECryptoProvider(keyDerivationService, enclaveManager);
};
