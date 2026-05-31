import { KeyShard, ModuleResult } from '../../types';
interface KeyInfo {
    keyId: string;
    name: string;
    type: 'aes' | 'rsa' | 'ecdsa' | 'ed25519';
    totalShards: number;
    threshold: number;
    createdAt: string;
    status: 'active' | 'revoked' | 'expired';
    metadata: Record<string, unknown>;
}
export declare class KeyShardingManagement {
    private shards;
    private keys;
    private keyMaterial;
    constructor();
    generateKey(name: string, type: KeyInfo['type'], totalShards: number, threshold: number, metadata?: Record<string, unknown>): ModuleResult<{
        keyInfo: KeyInfo;
        shards: KeyShard[];
    }>;
    distributeShard(keyId: string, shardId: string, owner: string): ModuleResult<KeyShard>;
    recoverKey(shards: KeyShard[]): ModuleResult<{
        key: Buffer;
        keyId: string;
    }>;
    getKeyInfo(keyId: string): ModuleResult<KeyInfo | null>;
    listKeys(): ModuleResult<KeyInfo[]>;
    getShards(keyId: string, includeValues?: boolean): ModuleResult<KeyShard[]>;
    getShardByOwner(owner: string): ModuleResult<KeyShard[]>;
    verifyShard(shard: KeyShard): ModuleResult<boolean>;
    revokeKey(keyId: string): ModuleResult<boolean>;
    rotateKey(keyId: string, newTotalShards?: number, newThreshold?: number): ModuleResult<{
        keyInfo: KeyInfo;
        shards: KeyShard[];
    }>;
    reEncryptShard(keyId: string, shardId: string, oldOwner: string, newOwner: string): ModuleResult<KeyShard>;
    deleteKey(keyId: string): ModuleResult<boolean>;
    private generateKeyMaterial;
    private generateShamirShares;
    private reconstructSecret;
    private modPow;
    private modInverse;
    private randomBigInt;
    private encodeShare;
    private decodeShare;
    private reEncodeShare;
    exportKeyShards(keyId: string): ModuleResult<string>;
}
export {};
