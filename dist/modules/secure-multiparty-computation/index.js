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
exports.SecureMultipartyComputation = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const crypto = __importStar(require("crypto"));
const MPCParticipantSchema = zod_1.z.object({
    id: zod_1.z.string(),
    endpoint: zod_1.z.string(),
    publicKey: zod_1.z.string(),
    status: zod_1.z.enum(['idle', 'computing', 'completed', 'failed']),
});
const MPCProtocolSchema = zod_1.z.object({
    id: zod_1.z.string(),
    name: zod_1.z.string(),
    type: zod_1.z.enum(['sum', 'product', 'comparison', 'join', 'custom']),
    participants: zod_1.z.array(zod_1.z.string()),
    threshold: zod_1.z.number().int().positive(),
    status: zod_1.z.enum(['pending', 'running', 'completed', 'failed']),
});
class SecureMultipartyComputation {
    participants = new Map();
    protocols = new Map();
    results = new Map();
    constructor() { }
    registerParticipant(endpoint, name) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const participantId = name || (0, utils_1.generateId)('part');
            const { publicKey, privateKey } = (0, utils_1.generateKeyPair)();
            const participant = {
                id: participantId,
                endpoint,
                publicKey,
                status: 'idle',
                privateKey,
            };
            this.participants.set(participantId, participant);
            const { privateKey: _pk, ...publicInfo } = participant;
            return (0, utils_1.createSuccessResult)(publicInfo, 'PARTICIPANT_REGISTERED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to register participant', 'PARTICIPANT_REGISTER_FAILED');
        }
    }
    removeParticipant(participantId) {
        const removed = this.participants.delete(participantId);
        return (0, utils_1.createSuccessResult)(removed, removed ? 'PARTICIPANT_REMOVED' : 'PARTICIPANT_NOT_FOUND');
    }
    getParticipant(participantId) {
        const participant = this.participants.get(participantId);
        if (!participant) {
            return (0, utils_1.createSuccessResult)(null, 'PARTICIPANT_NOT_FOUND');
        }
        const { privateKey: _pk, ...publicInfo } = participant;
        return (0, utils_1.createSuccessResult)(publicInfo, 'PARTICIPANT_RETRIEVED');
    }
    listParticipants() {
        const participants = Array.from(this.participants.values()).map(p => {
            const { privateKey: _pk, ...publicInfo } = p;
            return publicInfo;
        });
        return (0, utils_1.createSuccessResult)(participants, 'PARTICIPANTS_LISTED');
    }
    createProtocol(name, type, participantIds, threshold) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            for (const pid of participantIds) {
                if (!this.participants.has(pid)) {
                    return (0, utils_1.createErrorResult)(`Participant ${pid} not found`, 'PARTICIPANT_NOT_FOUND', traceId);
                }
            }
            const protocolId = (0, utils_1.generateId)('prot');
            const actualThreshold = threshold || Math.ceil(participantIds.length / 2) + 1;
            if (actualThreshold > participantIds.length) {
                return (0, utils_1.createErrorResult)('Threshold cannot exceed number of participants', 'INVALID_THRESHOLD', traceId);
            }
            const protocol = {
                id: protocolId,
                name,
                type,
                participants: participantIds,
                threshold: actualThreshold,
                status: 'pending',
                inputs: new Map(),
            };
            this.protocols.set(protocolId, protocol);
            return (0, utils_1.createSuccessResult)(protocol, 'PROTOCOL_CREATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to create protocol', 'PROTOCOL_CREATE_FAILED');
        }
    }
    submitInput(protocolId, participantId, value) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const protocol = this.protocols.get(protocolId);
            if (!protocol) {
                return (0, utils_1.createErrorResult)('Protocol not found', 'PROTOCOL_NOT_FOUND', traceId);
            }
            if (!protocol.participants.includes(participantId)) {
                return (0, utils_1.createErrorResult)('Participant not in protocol', 'PARTICIPANT_NOT_IN_PROTOCOL', traceId);
            }
            const participant = this.participants.get(participantId);
            if (!participant) {
                return (0, utils_1.createErrorResult)('Participant not found', 'PARTICIPANT_NOT_FOUND', traceId);
            }
            if (protocol.status !== 'pending') {
                return (0, utils_1.createErrorResult)('Protocol is not accepting inputs', 'PROTOCOL_NOT_PENDING', traceId);
            }
            if (protocol.inputs.has(participantId)) {
                return (0, utils_1.createErrorResult)('Participant already submitted input', 'INPUT_ALREADY_SUBMITTED', traceId);
            }
            participant.status = 'computing';
            const valueStr = value.toString();
            const key = crypto.createHash('sha256').update(participant.privateKey || participant.id).digest();
            const { iv, encrypted } = (0, utils_1.encrypt)(valueStr, key);
            const encryptedValue = `${iv}:${encrypted}`;
            const timestamp = Date.now();
            const signature = (0, utils_1.sha256)(`${protocolId}:${participantId}:${encryptedValue}:${timestamp}`);
            const input = {
                protocolId,
                participantId,
                encryptedValue,
                timestamp,
                signature,
            };
            protocol.inputs.set(participantId, input);
            if (protocol.inputs.size >= protocol.threshold) {
                this.runProtocol(protocol);
            }
            return (0, utils_1.createSuccessResult)(input, 'INPUT_SUBMITTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to submit input', 'INPUT_SUBMIT_FAILED');
        }
    }
    runProtocol(protocol) {
        protocol.status = 'running';
        const values = [];
        const participatingParties = [];
        for (const [pid, input] of protocol.inputs) {
            const participant = this.participants.get(pid);
            if (!participant)
                continue;
            try {
                const [iv, encrypted] = input.encryptedValue.split(':');
                const key = crypto.createHash('sha256').update(participant.privateKey || participant.id).digest();
                const decrypted = (0, utils_1.decrypt)(encrypted, iv, key);
                values.push(parseFloat(decrypted));
                participatingParties.push(pid);
                participant.status = 'completed';
            }
            catch {
                participant.status = 'failed';
            }
        }
        if (values.length < protocol.threshold) {
            protocol.status = 'failed';
            return;
        }
        let result;
        switch (protocol.type) {
            case 'sum':
                result = values.reduce((a, b) => a + b, 0);
                break;
            case 'product':
                result = values.reduce((a, b) => a * b, 1);
                break;
            case 'comparison':
                result = values[0] > values[1];
                break;
            case 'join':
                result = values.join(',');
                break;
            default:
                result = values[0];
        }
        const proof = (0, utils_1.sha256)(`${protocol.id}:${JSON.stringify(values)}:${result}:${Date.now()}`);
        const mpcResult = {
            protocolId: protocol.id,
            result,
            participants: participatingParties,
            timestamp: Date.now(),
            proof,
        };
        this.results.set(protocol.id, mpcResult);
        protocol.status = 'completed';
    }
    getProtocol(protocolId) {
        const protocol = this.protocols.get(protocolId);
        if (!protocol) {
            return (0, utils_1.createSuccessResult)(null, 'PROTOCOL_NOT_FOUND');
        }
        const { inputs: _inputs, ...publicInfo } = protocol;
        return (0, utils_1.createSuccessResult)(publicInfo, 'PROTOCOL_RETRIEVED');
    }
    listProtocols() {
        const protocols = Array.from(this.protocols.values()).map(p => {
            const { inputs: _inputs, ...publicInfo } = p;
            return publicInfo;
        });
        return (0, utils_1.createSuccessResult)(protocols, 'PROTOCOLS_LISTED');
    }
    getResult(protocolId) {
        const result = this.results.get(protocolId);
        if (!result) {
            return (0, utils_1.createSuccessResult)(null, 'RESULT_NOT_FOUND');
        }
        return (0, utils_1.createSuccessResult)(result, 'RESULT_RETRIEVED');
    }
    decryptResult(protocolId, participantId) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const result = this.results.get(protocolId);
            if (!result) {
                return (0, utils_1.createErrorResult)('Result not found', 'RESULT_NOT_FOUND', traceId);
            }
            const protocol = this.protocols.get(protocolId);
            if (!protocol) {
                return (0, utils_1.createErrorResult)('Protocol not found', 'PROTOCOL_NOT_FOUND', traceId);
            }
            if (!protocol.participants.includes(participantId)) {
                return (0, utils_1.createErrorResult)('Participant not authorized', 'NOT_AUTHORIZED', traceId);
            }
            const participant = this.participants.get(participantId);
            if (!participant) {
                return (0, utils_1.createErrorResult)('Participant not found', 'PARTICIPANT_NOT_FOUND', traceId);
            }
            const resultStr = JSON.stringify(result.result);
            const key = crypto.createHash('sha256').update(participant.privateKey || participant.id).digest();
            const { iv, encrypted } = (0, utils_1.encrypt)(resultStr, key);
            return (0, utils_1.createSuccessResult)({
                result: result.result,
                encrypted: `${iv}:${encrypted}`,
                proof: result.proof,
            }, 'RESULT_DECRYPTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to decrypt result', 'DECRYPT_FAILED');
        }
    }
    verifyResult(protocolId, proof) {
        const result = this.results.get(protocolId);
        if (!result) {
            return (0, utils_1.createErrorResult)('Result not found', 'RESULT_NOT_FOUND');
        }
        const valid = result.proof === proof;
        return (0, utils_1.createSuccessResult)(valid, valid ? 'RESULT_VERIFIED' : 'RESULT_INVALID');
    }
    cancelProtocol(protocolId) {
        const protocol = this.protocols.get(protocolId);
        if (!protocol) {
            return (0, utils_1.createErrorResult)('Protocol not found', 'PROTOCOL_NOT_FOUND');
        }
        if (protocol.status === 'running' || protocol.status === 'completed') {
            return (0, utils_1.createErrorResult)('Cannot cancel protocol in current state', 'CANNOT_CANCEL');
        }
        protocol.status = 'failed';
        for (const pid of protocol.participants) {
            const participant = this.participants.get(pid);
            if (participant && participant.status === 'computing') {
                participant.status = 'idle';
            }
        }
        return (0, utils_1.createSuccessResult)(true, 'PROTOCOL_CANCELLED');
    }
}
exports.SecureMultipartyComputation = SecureMultipartyComputation;
//# sourceMappingURL=index.js.map