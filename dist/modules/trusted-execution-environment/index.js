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
exports.TrustedExecutionEnvironment = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const crypto = __importStar(require("crypto"));
const EnclaveQuoteSchema = zod_1.z.object({
    enclaveId: zod_1.z.string(),
    timestamp: zod_1.z.number(),
    measurement: zod_1.z.string(),
    signer: zod_1.z.string(),
    reportData: zod_1.z.string(),
    signature: zod_1.z.string(),
});
class TrustedExecutionEnvironment {
    enclaves = new Map();
    masterPublicKey;
    masterPrivateKey;
    knownMeasurements = new Set();
    constructor(knownMeasurements = []) {
        const { publicKey, privateKey } = (0, utils_1.generateKeyPair)();
        this.masterPublicKey = publicKey;
        this.masterPrivateKey = privateKey;
        knownMeasurements.forEach(m => this.knownMeasurements.add(m));
    }
    createEnclave(name, config = {}) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const enclaveId = (0, utils_1.generateId)('enc');
            const timestamp = Date.now();
            const { publicKey, privateKey } = (0, utils_1.generateKeyPair)();
            const measurement = this.calculateMeasurement(enclaveId, timestamp, publicKey);
            const enclave = {
                enclaveId,
                name,
                status: 'running',
                measurement,
                publicKey,
                privateKey,
                createdAt: timestamp,
                memorySize: config.memorySize || 1024,
                cpuCores: config.cpuCores || 1,
            };
            this.enclaves.set(enclaveId, enclave);
            this.knownMeasurements.add(measurement);
            const { privateKey: _pk, ...publicInfo } = enclave;
            return (0, utils_1.createSuccessResult)(publicInfo, 'ENCLAVE_CREATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to create enclave', 'ENCLAVE_CREATE_FAILED');
        }
    }
    destroyEnclave(enclaveId) {
        if (!this.enclaves.has(enclaveId)) {
            return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND');
        }
        this.enclaves.delete(enclaveId);
        return (0, utils_1.createSuccessResult)(true, 'ENCLAVE_DESTROYED');
    }
    suspendEnclave(enclaveId) {
        const enclave = this.enclaves.get(enclaveId);
        if (!enclave) {
            return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND');
        }
        enclave.status = 'suspended';
        const { privateKey: _pk, ...publicInfo } = enclave;
        return (0, utils_1.createSuccessResult)(publicInfo, 'ENCLAVE_SUSPENDED');
    }
    resumeEnclave(enclaveId) {
        const enclave = this.enclaves.get(enclaveId);
        if (!enclave) {
            return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND');
        }
        enclave.status = 'running';
        const { privateKey: _pk, ...publicInfo } = enclave;
        return (0, utils_1.createSuccessResult)(publicInfo, 'ENCLAVE_RESUMED');
    }
    generateQuote(enclaveId, reportData = '') {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const enclave = this.enclaves.get(enclaveId);
            if (!enclave) {
                return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND', traceId);
            }
            if (enclave.status !== 'running') {
                return (0, utils_1.createErrorResult)('Enclave is not running', 'ENCLAVE_NOT_RUNNING', traceId);
            }
            const timestamp = Date.now();
            const reportDataHash = (0, utils_1.sha256)(reportData || timestamp.toString());
            const quoteData = {
                enclaveId,
                timestamp,
                measurement: enclave.measurement,
                signer: 'zerotrust-tee-v1',
                reportData: reportDataHash,
            };
            const signature = (0, utils_1.sign)(JSON.stringify(quoteData), this.masterPrivateKey);
            const quote = {
                ...quoteData,
                signature,
            };
            return (0, utils_1.createSuccessResult)(quote, 'QUOTE_GENERATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to generate quote', 'QUOTE_GENERATE_FAILED');
        }
    }
    verifyQuote(quote) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const parsed = EnclaveQuoteSchema.parse(quote);
            const quoteData = {
                enclaveId: parsed.enclaveId,
                timestamp: parsed.timestamp,
                measurement: parsed.measurement,
                signer: parsed.signer,
                reportData: parsed.reportData,
            };
            const signatureValid = (0, utils_1.verify)(JSON.stringify(quoteData), parsed.signature, this.masterPublicKey);
            if (!signatureValid) {
                return (0, utils_1.createSuccessResult)({
                    verified: false,
                    enclaveInfo: {},
                    quote: parsed,
                    timestamp: Date.now(),
                    error: 'Invalid signature',
                }, 'ATTESTATION_FAILED', traceId);
            }
            const measurementTrusted = this.knownMeasurements.has(parsed.measurement);
            if (!measurementTrusted) {
                return (0, utils_1.createSuccessResult)({
                    verified: false,
                    enclaveInfo: {},
                    quote: parsed,
                    timestamp: Date.now(),
                    error: 'Unknown enclave measurement',
                }, 'ATTESTATION_FAILED', traceId);
            }
            const enclave = this.enclaves.get(parsed.enclaveId);
            const enclaveInfo = enclave
                ? {
                    enclaveId: enclave.enclaveId,
                    name: enclave.name,
                    status: enclave.status,
                    measurement: enclave.measurement,
                    publicKey: enclave.publicKey,
                    createdAt: enclave.createdAt,
                    memorySize: enclave.memorySize,
                    cpuCores: enclave.cpuCores,
                }
                : {
                    enclaveId: parsed.enclaveId,
                    name: 'unknown',
                    status: 'running',
                    measurement: parsed.measurement,
                    publicKey: '',
                    createdAt: parsed.timestamp,
                    memorySize: 0,
                    cpuCores: 0,
                };
            return (0, utils_1.createSuccessResult)({
                verified: true,
                enclaveInfo,
                quote: parsed,
                timestamp: Date.now(),
            }, 'ATTESTATION_SUCCESS', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to verify quote', 'QUOTE_VERIFY_FAILED');
        }
    }
    executeSecure(enclaveId, data, operation) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const enclave = this.enclaves.get(enclaveId);
            if (!enclave) {
                return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND', traceId);
            }
            if (enclave.status !== 'running') {
                return (0, utils_1.createErrorResult)('Enclave is not running', 'ENCLAVE_NOT_RUNNING', traceId);
            }
            const secureKey = crypto.createHash('sha256').update(enclave.privateKey).digest();
            const encryptedInput = (0, utils_1.encrypt)(JSON.stringify(data), secureKey);
            let result;
            try {
                const processed = this.processSecure(data, operation);
                result = {
                    success: true,
                    operation,
                    processed,
                    timestamp: Date.now(),
                    enclaveSignature: (0, utils_1.sign)(JSON.stringify(processed), enclave.privateKey),
                };
            }
            catch (error) {
                result = {
                    success: false,
                    operation,
                    error: error instanceof Error ? error.message : 'Execution failed',
                    timestamp: Date.now(),
                };
            }
            return (0, utils_1.createSuccessResult)({
                ...result,
                encryptedInput: `${encryptedInput.iv}:${encryptedInput.encrypted}`,
            }, 'SECURE_EXECUTION_COMPLETE', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Secure execution failed', 'SECURE_EXECUTION_FAILED');
        }
    }
    getEnclave(enclaveId) {
        const enclave = this.enclaves.get(enclaveId);
        if (!enclave) {
            return (0, utils_1.createSuccessResult)(null, 'ENCLAVE_NOT_FOUND');
        }
        const { privateKey: _pk, ...publicInfo } = enclave;
        return (0, utils_1.createSuccessResult)(publicInfo, 'ENCLAVE_RETRIEVED');
    }
    listEnclaves() {
        const enclaves = Array.from(this.enclaves.values()).map(e => {
            const { privateKey: _pk, ...publicInfo } = e;
            return publicInfo;
        });
        return (0, utils_1.createSuccessResult)(enclaves, 'ENCLAVES_LISTED');
    }
    addTrustedMeasurement(measurement) {
        this.knownMeasurements.add(measurement);
        return (0, utils_1.createSuccessResult)(true, 'MEASUREMENT_ADDED');
    }
    removeTrustedMeasurement(measurement) {
        const removed = this.knownMeasurements.delete(measurement);
        return (0, utils_1.createSuccessResult)(removed, removed ? 'MEASUREMENT_REMOVED' : 'MEASUREMENT_NOT_FOUND');
    }
    getTrustedMeasurements() {
        return (0, utils_1.createSuccessResult)(Array.from(this.knownMeasurements), 'MEASUREMENTS_RETRIEVED');
    }
    getMasterPublicKey() {
        return (0, utils_1.createSuccessResult)(this.masterPublicKey, 'PUBLIC_KEY_RETRIEVED');
    }
    calculateMeasurement(enclaveId, timestamp, publicKey) {
        const data = `${enclaveId}|${timestamp}|${publicKey}|zerotrust-tee-v1`;
        return (0, utils_1.sha256)(data);
    }
    processSecure(data, operation) {
        switch (operation) {
            case 'hash':
                return {
                    original: data,
                    hashed: (0, utils_1.sha256)(JSON.stringify(data)),
                };
            case 'sign':
                return {
                    data,
                    signed: true,
                    timestamp: Date.now(),
                };
            case 'verify':
                return {
                    data,
                    verified: true,
                    timestamp: Date.now(),
                };
            default:
                return {
                    operation,
                    data,
                    processed: true,
                    timestamp: Date.now(),
                };
        }
    }
    sealData(enclaveId, data) {
        const enclave = this.enclaves.get(enclaveId);
        if (!enclave) {
            return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND');
        }
        const key = crypto.createHash('sha256').update(enclave.privateKey).digest();
        const result = (0, utils_1.encrypt)(data, key);
        return (0, utils_1.createSuccessResult)({ sealed: result.encrypted, iv: result.iv }, 'DATA_SEALED');
    }
    unsealData(enclaveId, sealed, iv) {
        const enclave = this.enclaves.get(enclaveId);
        if (!enclave) {
            return (0, utils_1.createErrorResult)('Enclave not found', 'ENCLAVE_NOT_FOUND');
        }
        const key = crypto.createHash('sha256').update(enclave.privateKey).digest();
        const result = (0, utils_1.decrypt)(sealed, iv, key);
        return (0, utils_1.createSuccessResult)(result, 'DATA_UNSEALED');
    }
}
exports.TrustedExecutionEnvironment = TrustedExecutionEnvironment;
//# sourceMappingURL=index.js.map