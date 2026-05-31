import * as crypto from 'crypto';
import { KeyShard } from './types';

export interface ShardVerificationResult {
  isValid: boolean;
  shardId: string;
  expectedHash: string;
  actualHash: string;
}

export interface RecoveryResult {
  success: boolean;
  secret?: string;
  usedShards: string[];
  message: string;
}

export class KeyShardingModule {
  private shards: Map<string, KeyShard[]> = new Map();
  private readonly PRIME = BigInt('0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F');

  public generateShards(
    secret: string,
    totalShares: number,
    threshold: number,
    ownerId: string
  ): KeyShard[] | null {
    if (threshold < 2 || threshold > totalShares) return null;
    if (totalShares < 2) return null;

    const secretBytes = Buffer.from(secret, 'utf8');
    const secretInt = this.bufferToBigInt(secretBytes);

    if (secretInt >= this.PRIME) return null;

    const coefficients = this.generatePolynomial(secretInt, threshold - 1);
    const shards: KeyShard[] = [];

    for (let i = 1; i <= totalShares; i++) {
      const x = BigInt(i);
      const y = this.evaluatePolynomial(coefficients, x);
      
      const shardData = this.encodeShard(x, y);
      
      const shard: KeyShard = {
        id: crypto.randomUUID(),
        shardIndex: i,
        shardData,
        ownerId,
        threshold,
        totalShares,
        createdAt: Date.now()
      };

      shards.push(shard);
    }

    const secretHash = crypto.createHash('sha256').update(secret).digest('hex');
    this.shards.set(secretHash, shards);

    return shards;
  }

  public recoverSecret(shards: KeyShard[]): RecoveryResult {
    if (shards.length === 0) {
      return { success: false, usedShards: [], message: 'No shards provided' };
    }

    const threshold = shards[0].threshold;
    const totalShares = shards[0].totalShares;

    if (shards.length < threshold) {
      return {
        success: false,
        usedShards: shards.map(s => s.id),
        message: `Need at least ${threshold} shards, only ${shards.length} provided`
      };
    }

    const inconsistent = shards.some(s => s.threshold !== threshold || s.totalShares !== totalShares);
    if (inconsistent) {
      return {
        success: false,
        usedShards: shards.map(s => s.id),
        message: 'Shards have inconsistent threshold or totalShares values'
      };
    }

    const uniqueIndices = new Set(shards.map(s => s.shardIndex));
    if (uniqueIndices.size !== shards.length) {
      return {
        success: false,
        usedShards: shards.map(s => s.id),
        message: 'Duplicate shard indices detected'
      };
    }

    try {
      const points = shards.map(shard => this.decodeShard(shard.shardData));
      const secretInt = this.lagrangeInterpolation(points, BigInt(0));
      const secret = this.bigIntToBuffer(secretInt).toString('utf8');

      return {
        success: true,
        secret,
        usedShards: shards.map(s => s.id),
        message: 'Secret recovered successfully'
      };
    } catch (error) {
      return {
        success: false,
        usedShards: shards.map(s => s.id),
        message: `Recovery failed: ${error instanceof Error ? error.message : 'Unknown error'}`
      };
    }
  }

  public verifyShard(shard: KeyShard, secretHash: string): ShardVerificationResult {
    const storedShards = this.shards.get(secretHash);
    if (!storedShards) {
      return { isValid: false, shardId: shard.id, expectedHash: '', actualHash: '' };
    }

    const storedShard = storedShards.find(s => s.id === shard.id);
    if (!storedShard) {
      return { isValid: false, shardId: shard.id, expectedHash: '', actualHash: '' };
    }

    const actualHash = crypto.createHash('sha256').update(shard.shardData).digest('hex');
    const expectedHash = crypto.createHash('sha256').update(storedShard.shardData).digest('hex');

    return {
      isValid: actualHash === expectedHash,
      shardId: shard.id,
      expectedHash,
      actualHash
    };
  }

  public verifyShardIntegrity(shard: KeyShard): boolean {
    try {
      const [xStr, yStr] = shard.shardData.split('-');
      if (!xStr || !yStr) return false;

      const x = BigInt(`0x${xStr}`);
      const y = BigInt(`0x${yStr}`);

      return x > 0 && x < this.PRIME && y >= 0 && y < this.PRIME;
    } catch {
      return false;
    }
  }

  public getShardsByOwner(ownerId: string): KeyShard[] {
    const allShards = Array.from(this.shards.values()).flat();
    return allShards.filter(shard => shard.ownerId === ownerId);
  }

  public getShardsBySecretHash(secretHash: string): KeyShard[] | undefined {
    return this.shards.get(secretHash);
  }

  public deleteShards(secretHash: string): boolean {
    return this.shards.delete(secretHash);
  }

  public deleteShard(secretHash: string, shardId: string): boolean {
    const shards = this.shards.get(secretHash);
    if (!shards) return false;

    const index = shards.findIndex(s => s.id === shardId);
    if (index === -1) return false;

    shards.splice(index, 1);
    if (shards.length === 0) {
      this.shards.delete(secretHash);
    } else {
      this.shards.set(secretHash, shards);
    }

    return true;
  }

  public rotateShards(
    secret: string,
    oldSecretHash: string,
    newTotalShares: number,
    newThreshold: number,
    ownerId: string
  ): KeyShard[] | null {
    if (!this.shards.has(oldSecretHash)) return null;

    this.shards.delete(oldSecretHash);
    return this.generateShards(secret, newTotalShares, newThreshold, ownerId);
  }

  public combineSecrets(
    shardsList: KeyShard[][],
    combiner: (secrets: string[]) => string
  ): RecoveryResult | null {
    const recoveries = shardsList.map(shards => this.recoverSecret(shards));
    
    if (recoveries.some(r => !r.success || !r.secret)) {
      return null;
    }

    const secrets = recoveries.map(r => r.secret!);
    const combinedSecret = combiner(secrets);

    return {
      success: true,
      secret: combinedSecret,
      usedShards: recoveries.flatMap(r => r.usedShards),
      message: 'Secrets combined successfully'
    };
  }

  public generateAndDistribute(
    secret: string,
    totalShares: number,
    threshold: number,
    ownerIds: string[]
  ): Map<string, KeyShard> | null {
    if (ownerIds.length !== totalShares) return null;

    const shards = this.generateShards(secret, totalShares, threshold, 'system');
    if (!shards) return null;

    const distribution = new Map<string, KeyShard>();
    shards.forEach((shard, index) => {
      shard.ownerId = ownerIds[index];
      distribution.set(ownerIds[index], shard);
    });

    return distribution;
  }

  public getStats() {
    const allShards = Array.from(this.shards.values()).flat();
    return {
      secretGroups: this.shards.size,
      totalShards: allShards.length,
      uniqueOwners: new Set(allShards.map(s => s.ownerId)).size
    };
  }

  private generatePolynomial(secret: bigint, degree: number): bigint[] {
    const coefficients: bigint[] = [secret];
    
    for (let i = 0; i < degree; i++) {
      coefficients.push(this.randomBigInt());
    }

    return coefficients;
  }

  private evaluatePolynomial(coefficients: bigint[], x: bigint): bigint {
    let result = BigInt(0);
    
    for (let i = coefficients.length - 1; i >= 0; i--) {
      result = (result * x + coefficients[i]) % this.PRIME;
    }

    return result;
  }

  private lagrangeInterpolation(points: [bigint, bigint][], x: bigint): bigint {
    let result = BigInt(0);
    const n = points.length;

    for (let i = 0; i < n; i++) {
      const [xi, yi] = points[i];
      let numerator = BigInt(1);
      let denominator = BigInt(1);

      for (let j = 0; j < n; j++) {
        if (i === j) continue;
        const [xj] = points[j];
        numerator = (numerator * (x - xj)) % this.PRIME;
        denominator = (denominator * (xi - xj)) % this.PRIME;
      }

      const invDenominator = this.modularInverse(denominator, this.PRIME);
      const term = (yi * numerator * invDenominator) % this.PRIME;
      result = (result + term) % this.PRIME;
    }

    return result;
  }

  private modularInverse(a: bigint, p: bigint): bigint {
    return this.powerMod(a, p - BigInt(2), p);
  }

  private powerMod(base: bigint, exponent: bigint, modulus: bigint): bigint {
    let result = BigInt(1);
    base = base % modulus;

    while (exponent > 0) {
      if (exponent % BigInt(2) === BigInt(1)) {
        result = (result * base) % modulus;
      }
      exponent = exponent / BigInt(2);
      base = (base * base) % modulus;
    }

    return result;
  }

  private randomBigInt(): bigint {
    const bytes = crypto.randomBytes(32);
    let result = BigInt(0);
    
    for (let i = 0; i < bytes.length; i++) {
      result = (result * BigInt(256)) + BigInt(bytes[i]);
    }

    return result % this.PRIME;
  }

  private bufferToBigInt(buffer: Buffer): bigint {
    let result = BigInt(0);
    for (let i = 0; i < buffer.length; i++) {
      result = (result * BigInt(256)) + BigInt(buffer[i]);
    }
    return result;
  }

  private bigIntToBuffer(value: bigint): Buffer {
    const hex = value.toString(16).padStart(64, '0');
    return Buffer.from(hex, 'hex');
  }

  private encodeShard(x: bigint, y: bigint): string {
    const xHex = x.toString(16).padStart(64, '0');
    const yHex = y.toString(16).padStart(64, '0');
    return `${xHex}-${yHex}`;
  }

  private decodeShard(shardData: string): [bigint, bigint] {
    const [xHex, yHex] = shardData.split('-');
    return [BigInt(`0x${xHex}`), BigInt(`0x${yHex}`)];
  }
}

export const createKeySharding = (): KeyShardingModule => {
  return new KeyShardingModule();
};
