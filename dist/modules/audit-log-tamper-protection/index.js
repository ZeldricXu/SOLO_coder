"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AuditLogTamperProtection = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const AuditLogEntrySchema = zod_1.z.object({
    id: zod_1.z.string(),
    timestamp: zod_1.z.string().datetime(),
    userId: zod_1.z.string(),
    action: zod_1.z.string(),
    resourceType: zod_1.z.string(),
    resourceId: zod_1.z.string(),
    details: zod_1.z.record(zod_1.z.string(), zod_1.z.unknown()),
    status: zod_1.z.enum(['success', 'failed', 'denied']),
    ipAddress: zod_1.z.string().optional(),
    userAgent: zod_1.z.string().optional(),
});
const HashChainLinkSchema = zod_1.z.object({
    index: zod_1.z.number().int().nonnegative(),
    entryHash: zod_1.z.string(),
    previousHash: zod_1.z.string(),
    timestamp: zod_1.z.number(),
    nonce: zod_1.z.number(),
    hash: zod_1.z.string(),
});
class AuditLogTamperProtection {
    chain = [];
    difficulty;
    entries = new Map();
    constructor(config = {}) {
        this.difficulty = config.difficulty || 2;
    }
    log(entry) {
        try {
            const parsedEntry = AuditLogEntrySchema.parse(entry);
            const traceId = (0, utils_1.generateId)('trace');
            const entryHash = this.calculateEntryHash(parsedEntry);
            const previousHash = this.chain.length > 0
                ? this.chain[this.chain.length - 1].hash
                : this.getGenesisHash();
            const link = this.mineBlock(this.chain.length, entryHash, previousHash);
            this.chain.push(link);
            this.entries.set(parsedEntry.id, parsedEntry);
            return (0, utils_1.createSuccessResult)(link, 'LOG_ENTRY_ADDED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to log entry', 'LOG_FAILED');
        }
    }
    logBatch(entries) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const links = [];
            for (const entry of entries) {
                const result = this.log(entry);
                if (!result.success) {
                    return (0, utils_1.createErrorResult)(result.error, result.code, traceId);
                }
                links.push(result.data);
            }
            return (0, utils_1.createSuccessResult)(links, 'BATCH_LOG_ENTRIES_ADDED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to log batch entries', 'BATCH_LOG_FAILED');
        }
    }
    verifyChain() {
        const traceId = (0, utils_1.generateId)('trace');
        if (this.chain.length === 0) {
            return (0, utils_1.createSuccessResult)({
                valid: true,
                totalLinks: 0,
                validLinks: 0,
            }, 'CHAIN_VERIFIED', traceId);
        }
        for (let i = 0; i < this.chain.length; i++) {
            const link = this.chain[i];
            const previousHash = i === 0 ? this.getGenesisHash() : this.chain[i - 1].hash;
            if (!this.validateLink(link, previousHash)) {
                return (0, utils_1.createSuccessResult)({
                    valid: false,
                    firstInvalidIndex: i,
                    error: `Hash mismatch at index ${i}`,
                    totalLinks: this.chain.length,
                    validLinks: i,
                }, 'CHAIN_VERIFICATION_FAILED', traceId);
            }
            if (this.difficulty > 0 && !this.validateProofOfWork(link)) {
                return (0, utils_1.createSuccessResult)({
                    valid: false,
                    firstInvalidIndex: i,
                    error: `Invalid proof of work at index ${i}`,
                    totalLinks: this.chain.length,
                    validLinks: i,
                }, 'CHAIN_VERIFICATION_FAILED', traceId);
            }
        }
        return (0, utils_1.createSuccessResult)({
            valid: true,
            totalLinks: this.chain.length,
            validLinks: this.chain.length,
        }, 'CHAIN_VERIFIED', traceId);
    }
    verifyEntry(entryId) {
        const entry = this.entries.get(entryId);
        if (!entry) {
            return (0, utils_1.createSuccessResult)({ valid: false, inChain: false }, 'ENTRY_NOT_FOUND');
        }
        const entryHash = this.calculateEntryHash(entry);
        const linkIndex = this.chain.findIndex(l => l.entryHash === entryHash);
        if (linkIndex === -1) {
            return (0, utils_1.createSuccessResult)({ valid: false, inChain: false }, 'ENTRY_NOT_IN_CHAIN');
        }
        const link = this.chain[linkIndex];
        const previousHash = linkIndex === 0 ? this.getGenesisHash() : this.chain[linkIndex - 1].hash;
        const valid = this.validateLink(link, previousHash);
        return (0, utils_1.createSuccessResult)({ valid, inChain: true }, valid ? 'ENTRY_VERIFIED' : 'ENTRY_TAMPERED');
    }
    getChain() {
        return (0, utils_1.createSuccessResult)([...this.chain], 'CHAIN_RETRIEVED');
    }
    getEntry(entryId) {
        const entry = this.entries.get(entryId) || null;
        return (0, utils_1.createSuccessResult)(entry, entry ? 'ENTRY_RETRIEVED' : 'ENTRY_NOT_FOUND');
    }
    getEntriesByUser(userId, limit) {
        const entries = Array.from(this.entries.values())
            .filter(e => e.userId === userId)
            .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        const result = limit ? entries.slice(0, limit) : entries;
        return (0, utils_1.createSuccessResult)(result, 'ENTRIES_RETRIEVED');
    }
    getEntriesByResource(resourceType, resourceId) {
        const entries = Array.from(this.entries.values())
            .filter(e => e.resourceType === resourceType && e.resourceId === resourceId)
            .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        return (0, utils_1.createSuccessResult)(entries, 'ENTRIES_RETRIEVED');
    }
    getChainStats() {
        const entries = Array.from(this.entries.values());
        const timestamps = entries.map(e => new Date(e.timestamp).getTime());
        return (0, utils_1.createSuccessResult)({
            totalEntries: entries.length,
            chainLength: this.chain.length,
            lastEntryTime: timestamps.length > 0 ? new Date(Math.max(...timestamps)).toISOString() : null,
            firstEntryTime: timestamps.length > 0 ? new Date(Math.min(...timestamps)).toISOString() : null,
        }, 'STATS_RETRIEVED');
    }
    calculateEntryHash(entry) {
        const sortedDetails = Object.keys(entry.details)
            .sort()
            .map(key => `${key}:${JSON.stringify(entry.details[key])}`)
            .join('|');
        const data = `${entry.id}|${entry.timestamp}|${entry.userId}|${entry.action}|${entry.resourceType}|${entry.resourceId}|${sortedDetails}|${entry.status}|${entry.ipAddress || ''}|${entry.userAgent || ''}`;
        return (0, utils_1.sha256)(data);
    }
    getGenesisHash() {
        return (0, utils_1.sha256)('zerotrust-audit-log-genesis-block-v1');
    }
    calculateLinkHash(link) {
        const data = `${link.index}|${link.entryHash}|${link.previousHash}|${link.timestamp}|${link.nonce}`;
        return (0, utils_1.sha256)(data);
    }
    mineBlock(index, entryHash, previousHash) {
        const timestamp = Date.now();
        let nonce = 0;
        let hash = '';
        const prefix = '0'.repeat(this.difficulty);
        while (!hash.startsWith(prefix)) {
            nonce++;
            hash = this.calculateLinkHash({
                index,
                entryHash,
                previousHash,
                timestamp,
                nonce,
            });
        }
        return {
            index,
            entryHash,
            previousHash,
            timestamp,
            nonce,
            hash,
        };
    }
    validateLink(link, previousHash) {
        if (link.previousHash !== previousHash) {
            return false;
        }
        const calculatedHash = this.calculateLinkHash({
            index: link.index,
            entryHash: link.entryHash,
            previousHash: link.previousHash,
            timestamp: link.timestamp,
            nonce: link.nonce,
        });
        return calculatedHash === link.hash;
    }
    validateProofOfWork(link) {
        const prefix = '0'.repeat(this.difficulty);
        return link.hash.startsWith(prefix);
    }
    exportChain() {
        const data = JSON.stringify({
            chain: this.chain,
            entries: Object.fromEntries(this.entries),
        });
        return (0, utils_1.createSuccessResult)(data, 'CHAIN_EXPORTED');
    }
    importChain(data) {
        try {
            const parsed = JSON.parse(data);
            const chain = zod_1.z.array(HashChainLinkSchema).parse(parsed.chain);
            const entries = zod_1.z.record(AuditLogEntrySchema).parse(parsed.entries);
            this.chain = chain;
            this.entries = new Map(Object.entries(entries));
            const verifyResult = this.verifyChain();
            if (!verifyResult.data?.valid) {
                return (0, utils_1.createErrorResult)('Imported chain verification failed', 'IMPORT_VERIFICATION_FAILED');
            }
            return (0, utils_1.createSuccessResult)(true, 'CHAIN_IMPORTED');
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to import chain', 'IMPORT_FAILED');
        }
    }
    createEntry(data) {
        return {
            id: (0, utils_1.generateId)('log'),
            timestamp: (0, utils_1.getCurrentTimestamp)(),
            ...data,
        };
    }
}
exports.AuditLogTamperProtection = AuditLogTamperProtection;
//# sourceMappingURL=index.js.map