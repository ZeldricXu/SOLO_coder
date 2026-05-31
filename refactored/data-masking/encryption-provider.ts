import * as crypto from 'crypto';
import { IDataMaskingEncryptionProvider } from './interfaces';

export class DataMaskingEncryptionProvider implements IDataMaskingEncryptionProvider {
  private readonly encryptionKey: Buffer;

  constructor(encryptionKey: string) {
    this.encryptionKey = crypto.scryptSync(encryptionKey, 'salt', 32);
  }

  public encrypt(value: string): string {
    const iv = crypto.randomBytes(16);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.encryptionKey, iv);
    
    let encrypted = cipher.update(value, 'utf8', 'hex');
    encrypted += cipher.final('hex');
    
    const authTag = cipher.getAuthTag().toString('hex');
    
    return `${iv.toString('hex')}:${encrypted}:${authTag}`;
  }

  public decrypt(encryptedValue: string): string {
    const [ivHex, encryptedHex, authTagHex] = encryptedValue.split(':');
    
    const iv = Buffer.from(ivHex, 'hex');
    const encrypted = Buffer.from(encryptedHex, 'hex');
    const authTag = Buffer.from(authTagHex, 'hex');

    const decipher = crypto.createDecipheriv('aes-256-gcm', this.encryptionKey, iv);
    decipher.setAuthTag(authTag);

    let decrypted = decipher.update(encrypted.toString('hex'), 'hex', 'utf8');
    decrypted += decipher.final('utf8');

    return decrypted;
  }
}

export const createDataMaskingEncryptionProvider = (encryptionKey: string): DataMaskingEncryptionProvider => {
  return new DataMaskingEncryptionProvider(encryptionKey);
};
