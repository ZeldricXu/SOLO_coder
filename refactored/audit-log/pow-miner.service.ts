import { IPowMiner } from './interfaces';
import { IHashProvider } from './interfaces';

export class PowMiner implements IPowMiner {
  private difficulty: number = 4;

  constructor(private readonly hashProvider: IHashProvider) {}

  public mine(entryData: string, previousHash: string, difficulty?: number): { hash: string; nonce: number } {
    const diff = difficulty ?? this.difficulty;
    let nonce = 0;
    let hash: string;

    do {
      hash = this.hashProvider.calculateHash(entryData, nonce, previousHash);
      nonce++;
    } while (!this.hashProvider.hashMeetsDifficulty(hash, diff));

    return { hash, nonce: nonce - 1 };
  }

  public setDifficulty(difficulty: number): void {
    this.difficulty = Math.max(1, Math.min(difficulty, 8));
  }

  public getDifficulty(): number {
    return this.difficulty;
  }
}

export const createPowMiner = (hashProvider: IHashProvider): PowMiner => {
  return new PowMiner(hashProvider);
};
