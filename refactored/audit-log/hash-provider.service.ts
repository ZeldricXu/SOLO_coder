import * as crypto from 'crypto';
import { IHashProvider } from './interfaces';

export class HashProvider implements IHashProvider {
  public calculateHash(data: string, nonce: number, previousHash: string): string {
    return crypto
      .createHash('sha256')
      .update(data + nonce + previousHash)
      .digest('hex');
  }

  public hashMeetsDifficulty(hash: string, difficulty: number): boolean {
    const prefix = '0'.repeat(difficulty);
    return hash.startsWith(prefix);
  }
}

export const createHashProvider = (): HashProvider => {
  return new HashProvider();
};
