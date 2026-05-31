"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.KeyShardingManagement = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const crypto = __importStar(require("crypto"));
const KeyShardSchema = zod_1.z.object({
    shardId: zod_1.z.string(),
    keyId: zod_1.z.string(),
    index: zod_1.z.number().int().nonnegative(),
    value: zod_1.z.string(),
    owner: zod_1.z.string(),
    createdAt: zod_1.z.string().datetime(),
});
const PRIME = BigInt('0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F');
class KeyShardingManagement {
    shards = new Map();
    keys = new Map();
    keyMaterial = new Map();
    constructor() { }
    generateKey(name, type, totalShards, threshold, metadata = {}) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            if (threshold < 2) {
                return (0, utils_1.createErrorResult)('Threshold must be at least 2', 'INVALID_THRESHOLD', traceId);
            }
            if (threshold > totalShards) {
                return (0, utils_1.createErrorResult)('Threshold cannot exceed total shards', 'INVALID_THRESHOLD', traceId);
            }
            const keyId = (0, utils_1.generateId)('key');
            const keyMaterial = this.generateKeyMaterial(type);
            const shares = this.generateShamirShares(keyMaterial, totalShards, threshold);
            const shards = shares.map((share, index) => ({
                shardId: (0, utils_1.generateId)('shd'),
                keyId,
                index: index + 1,
                value: this.encodeShare(share),
                owner: '',
                createdAt: (0, utils_1.getCurrentTimestamp)(),
            }));
            const keyInfo = {
                keyId,
                name,
                type,
                totalShards,
                threshold,
                createdAt: (0, utils_1.getCurrentTimestamp)(),
                status: 'active',
                metadata,
            };
            this.keys.set(keyId, keyInfo);
            this.shards.set(keyId, shards);
            this.keyMaterial.set(keyId, keyMaterial);
            return (0, utils_1.createSuccessResult)({ keyInfo, shards }, 'KEY_GENERATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to generate key', 'KEY_GENERATE_FAILED');
        }
    }
    distributeShard(keyId, shardId, owner) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const shards = this.shards.get(keyId);
            if (!shards) {
                return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND', traceId);
            }
            const shard = shards.find(s => s.shardId === shardId);
            if (!shard) {
                return (0, utils_1.createErrorResult)('Shard not found', 'SHARD_NOT_FOUND', traceId);
            }
            if (shard.owner) {
                return (0, utils_1.createErrorResult)('Shard already distributed', 'SHARD_ALREADY_DISTRIBUTED', traceId);
            }
            shard.owner = owner;
            return (0, utils_1.createSuccessResult)(shard, 'SHARD_DISTRIBUTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to distribute shard', 'SHARD_DISTRIBUTE_FAILED');
        }
    }
    recoverKey(shards) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            if (shards.length === 0) {
                return (0, utils_1.createErrorResult)('No shards provided', 'NO_SHARDS', traceId);
            }
            const keyId = shards[0].keyId;
            const keyInfo = this.keys.get(keyId);
            if (!keyInfo) {
                return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND', traceId);
            }
            if (shards.length < keyInfo.threshold) {
                return (0, utils_1.createErrorResult)(`Insufficient shards: need ${keyInfo.threshold}, got ${shards.length}`, 'INSUFFICIENT_SHARDS', traceId);
            }
            const uniqueIndices = new Set(shards.map(s => s.index));
            if (uniqueIndices.size !== shards.length) {
                return (0, utils_1.createErrorResult)('Duplicate shard indices detected', 'DUPLICATE_SHARDS', traceId);
            }
            const shares = shards.map(s => this.decodeShare(s.value));
            const secret = this.reconstructSecret(shares);
            const originalKey = this.keyMaterial.get(keyId);
            if (originalKey) {
                const recoveredHex = secret.toString('hex');
                const originalHex = originalKey.toString('hex');
                if (recoveredHex !== originalHex) {
                    return (0, utils_1.createErrorResult)('Key reconstruction verification failed', 'RECONSTRUCTION_FAILED', traceId);
                }
            }
            return (0, utils_1.createSuccessResult)({
                key: secret,
                keyId,
            }, 'KEY_RECOVERED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to recover key', 'KEY_RECOVER_FAILED');
        }
    }
    getKeyInfo(keyId) {
        const keyInfo = this.keys.get(keyId) || null;
        return (0, utils_1.createSuccessResult)(keyInfo, keyInfo ? 'KEY_INFO_RETRIEVED' : 'KEY_NOT_FOUND');
    }
    listKeys() {
        return (0, utils_1.createSuccessResult)(Array.from(this.keys.values()), 'KEYS_LISTED');
    }
    getShards(keyId, includeValues = false) {
        const shards = this.shards.get(keyId);
        if (!shards) {
            return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND');
        }
        const result = includeValues
            ? shards
            : shards.map(s => ({ ...s, value: (0, utils_1.sha256)(s.value) }));
        return (0, utils_1.createSuccessResult)(result, 'SHARDS_RETRIEVED');
    }
    getShardByOwner(owner) {
        const result = [];
        for (const shards of this.shards.values()) {
            for (const shard of shards) {
                if (shard.owner === owner) {
                    result.push({ ...shard, value: (0, utils_1.sha256)(shard.value) });
                }
            }
        }
        return (0, utils_1.createSuccessResult)(result, 'SHARDS_RETRIEVED');
    }
    verifyShard(shard) {
        try {
            const keyInfo = this.keys.get(shard.keyId);
            if (!keyInfo) {
                return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND');
            }
            const share = this.decodeShare(shard.value);
            const valid = share.x > BigInt(0) && share.x < PRIME;
            return (0, utils_1.createSuccessResult)(valid, valid ? 'SHARD_VERIFIED' : 'SHARD_INVALID');
        }
        catch {
            return (0, utils_1.createSuccessResult)(false, 'SHARD_INVALID');
        }
    }
    revokeKey(keyId) {
        const keyInfo = this.keys.get(keyId);
        if (!keyInfo) {
            return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND');
        }
        keyInfo.status = 'revoked';
        return (0, utils_1.createSuccessResult)(true, 'KEY_REVOKED');
    }
    rotateKey(keyId, newTotalShards, newThreshold) {
        const keyInfo = this.keys.get(keyId);
        if (!keyInfo) {
            return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND');
        }
        if (keyInfo.status !== 'active') {
            return (0, utils_1.createErrorResult)('Cannot rotate inactive key', 'INACTIVE_KEY');
        }
        const oldKeyMaterial = this.keyMaterial.get(keyId);
        if (!oldKeyMaterial) {
            return (0, utils_1.createErrorResult)('Key material not found', 'KEY_MATERIAL_NOT_FOUND');
        }
        const totalShards = newTotalShards || keyInfo.totalShards;
        const threshold = newThreshold || keyInfo.threshold;
        return this.generateKey(keyInfo.name + ' (rotated)', keyInfo.type, totalShards, threshold, { ...keyInfo.metadata, rotatedFrom: keyId });
    }
    reEncryptShard(keyId, shardId, oldOwner, newOwner) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const shards = this.shards.get(keyId);
            if (!shards) {
                return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND', traceId);
            }
            const shard = shards.find(s => s.shardId === shardId);
            if (!shard) {
                return (0, utils_1.createErrorResult)('Shard not found', 'SHARD_NOT_FOUND', traceId);
            }
            if (shard.owner !== oldOwner) {
                return (0, utils_1.createErrorResult)('Shard owner mismatch', 'OWNER_MISMATCH', traceId);
            }
            const keyInfo = this.keys.get(keyId);
            const newShardValue = this.reEncodeShare(shard.value, newOwner);
            const newShard = {
                ...shard,
                value: newShardValue,
                owner: newOwner,
                createdAt: (0, utils_1.getCurrentTimestamp)(),
            };
            const index = shards.findIndex(s => s.shardId === shardId);
            shards[index] = newShard;
            return (0, utils_1.createSuccessResult)(newShard, 'SHARD_REENCRYPTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to re-encrypt shard', 'SHARD_REENCRYPT_FAILED');
        }
    }
    deleteKey(keyId) {
        this.keys.delete(keyId);
        this.shards.delete(keyId);
        this.keyMaterial.delete(keyId);
        return (0, utils_1.createSuccessResult)(true, 'KEY_DELETED');
    }
    generateKeyMaterial(type) {
        switch (type) {
            case 'aes':
                return crypto.randomBytes(32);
            case 'rsa':
            case 'ecdsa':
            case 'ed25519':
                return crypto.randomBytes(64);
            default:
                return crypto.randomBytes(32);
        }
    }
    generateShamirShares(secret, total, threshold) {
        const secretInt = BigInt('0x' + secret.toString('hex')) % PRIME;
        const coefficients = [secretInt];
        for (let i = 1; i < threshold; i++) {
            coefficients.push(this.randomBigInt());
        }
        const shares = [];
        for (let i = 1; i <= total; i++) {
            const x = BigInt(i);
            let y = BigInt(0);
            for (let j = 0; j < threshold; j++) {
                y = (y + coefficients[j] * this.modPow(x, BigInt(j), PRIME)) % PRIME;
            }
            shares.push({ x, y });
        }
        return shares;
    }
    reconstructSecret(shares) {
        let secret = BigInt(0);
        for (let i = 0; i < shares.length; i++) {
            const xi = shares[i].x;
            const yi = shares[i].y;
            let numerator = BigInt(1);
            let denominator = BigInt(1);
            for (let j = 0; j < shares.length; j++) {
                if (i === j)
                    continue;
                const xj = shares[j].x;
                numerator = (numerator * (-xj)) % PRIME;
                denominator = (denominator * (xi - xj)) % PRIME;
            }
            const lagrange = (yi * numerator * this.modInverse(denominator, PRIME)) % PRIME;
            secret = (secret + lagrange) % PRIME;
        }
        if (secret < BigInt(0)) {
            secret = (secret + PRIME) % PRIME;
        }
        let hex = secret.toString(16);
        if (hex.length % 2 !== 0) {
            hex = '0' + hex;
        }
        return Buffer.from(hex, 'hex');
    }
    modPow(base, exp, mod) {
        let result = BigInt(1);
        base = base % mod;
        while (exp > BigInt(0)) {
            if (exp % BigInt(2) === BigInt(1)) {
                result = (result * base) % mod;
            }
            exp = exp / BigInt(2);
            base = (base * base) % mod;
        }
        return result;
    }
    modInverse(a, mod) {
        let [oldR, r] = [a, mod];
        let [oldS, s] = [BigInt(1), BigInt(0)];
        while (r !== BigInt(0)) {
            const q = oldR / r;
            [oldR, r] = [r, oldR - q * r];
            [oldS, s] = [s, oldS - q * s];
        }
        return (oldS % mod + mod) % mod;
    }
    randomBigInt() {
        const bytes = crypto.randomBytes(32);
        return BigInt('0x' + bytes.toString('hex')) % PRIME;
    }
    encodeShare(share) {
        const xHex = share.x.toString(16).padStart(64, '0');
        const yHex = share.y.toString(16).padStart(64, '0');
        return `${xHex}${yHex}`;
    }
    decodeShare(encoded) {
        const xHex = encoded.slice(0, 64);
        const yHex = encoded.slice(64, 128);
        return {
            x: BigInt('0x' + xHex),
            y: BigInt('0x' + yHex),
        };
    }
    reEncodeShare(encoded, owner) {
        const share = this.decodeShare(encoded);
        const salt = (0, utils_1.sha256)(owner);
        const newY = (share.y + BigInt('0x' + salt.slice(0, 16))) % PRIME;
        return this.encodeShare({ x: share.x, y: newY });
    }
    exportKeyShards(keyId) {
        const shards = this.shards.get(keyId);
        const keyInfo = this.keys.get(keyId);
        if (!shards || !keyInfo) {
            return (0, utils_1.createErrorResult)('Key not found', 'KEY_NOT_FOUND');
        }
        const exportData = JSON.stringify({
            keyInfo,
            shards: shards.map(s => ({ ...s, value: (0, utils_1.sha256)(s.value) })),
        });
        return (0, utils_1.createSuccessResult)(exportData, 'KEY_EXPORTED');
    }
}
exports.KeyShardingManagement = KeyShardingManagement;
//# sourceMappingURL=index.js.map