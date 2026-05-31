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
exports.FederatedLearningCoordinator = void 0;
const zod_1 = require("zod");
const utils_1 = require("../../utils");
const crypto = __importStar(require("crypto"));
const FLTrainingTaskSchema = zod_1.z.object({
    taskId: zod_1.z.string(),
    modelType: zod_1.z.string(),
    modelConfig: zod_1.z.record(zod_1.z.string(), zod_1.z.unknown()),
    participants: zod_1.z.array(zod_1.z.string()),
    epochs: zod_1.z.number().int().positive(),
    currentRound: zod_1.z.number().int().nonnegative(),
    status: zod_1.z.enum(['pending', 'training', 'aggregating', 'completed', 'failed']),
    globalModelWeights: zod_1.z.array(zod_1.z.number()).nullable(),
});
const FLClientUpdateSchema = zod_1.z.object({
    taskId: zod_1.z.string(),
    clientId: zod_1.z.string(),
    round: zod_1.z.number().int().nonnegative(),
    encryptedWeights: zod_1.z.array(zod_1.z.number()),
    sampleSize: zod_1.z.number().int().positive(),
    loss: zod_1.z.number(),
    accuracy: zod_1.z.number(),
});
class FederatedLearningCoordinator {
    clients = new Map();
    tasks = new Map();
    masterKey;
    constructor(masterSecret = 'zerotrust-fl-master-secret-v1') {
        this.masterKey = crypto.createHash('sha256').update(masterSecret).digest();
    }
    registerClient(name, endpoint) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const clientId = (0, utils_1.generateId)('cli');
            const client = {
                clientId,
                name,
                endpoint,
                publicKey: (0, utils_1.sha256)(clientId + endpoint),
                status: 'idle',
                reputation: 100,
                privateKey: (0, utils_1.sha256)(clientId + endpoint + this.masterKey.toString('hex')),
            };
            this.clients.set(clientId, client);
            const { privateKey: _pk, ...publicInfo } = client;
            return (0, utils_1.createSuccessResult)(publicInfo, 'CLIENT_REGISTERED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to register client', 'CLIENT_REGISTER_FAILED');
        }
    }
    removeClient(clientId) {
        const removed = this.clients.delete(clientId);
        return (0, utils_1.createSuccessResult)(removed, removed ? 'CLIENT_REMOVED' : 'CLIENT_NOT_FOUND');
    }
    getClient(clientId) {
        const client = this.clients.get(clientId);
        if (!client) {
            return (0, utils_1.createSuccessResult)(null, 'CLIENT_NOT_FOUND');
        }
        const { privateKey: _pk, ...publicInfo } = client;
        return (0, utils_1.createSuccessResult)(publicInfo, 'CLIENT_RETRIEVED');
    }
    listClients() {
        const clients = Array.from(this.clients.values()).map(c => {
            const { privateKey: _pk, ...publicInfo } = c;
            return publicInfo;
        });
        return (0, utils_1.createSuccessResult)(clients, 'CLIENTS_LISTED');
    }
    createTrainingTask(modelType, modelConfig, clientIds, epochs = 10) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            for (const cid of clientIds) {
                if (!this.clients.has(cid)) {
                    return (0, utils_1.createErrorResult)(`Client ${cid} not found`, 'CLIENT_NOT_FOUND', traceId);
                }
            }
            const taskId = (0, utils_1.generateId)('task');
            const initialWeights = this.initializeModelWeights(modelConfig);
            const task = {
                taskId,
                modelType,
                modelConfig,
                participants: clientIds,
                epochs,
                currentRound: 0,
                status: 'pending',
                globalModelWeights: initialWeights,
                clientUpdates: new Map(),
                modelHistory: [],
            };
            this.tasks.set(taskId, task);
            return (0, utils_1.createSuccessResult)(task, 'TASK_CREATED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to create training task', 'TASK_CREATE_FAILED');
        }
    }
    startTraining(taskId) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const task = this.tasks.get(taskId);
            if (!task) {
                return (0, utils_1.createErrorResult)('Task not found', 'TASK_NOT_FOUND', traceId);
            }
            if (task.status !== 'pending') {
                return (0, utils_1.createErrorResult)('Task is not in pending state', 'INVALID_STATE', traceId);
            }
            task.status = 'training';
            task.currentRound = 1;
            for (const cid of task.participants) {
                const client = this.clients.get(cid);
                if (client) {
                    client.status = 'training';
                }
            }
            return (0, utils_1.createSuccessResult)(task, 'TRAINING_STARTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to start training', 'TRAINING_START_FAILED');
        }
    }
    submitClientUpdate(update) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const parsed = FLClientUpdateSchema.parse(update);
            const task = this.tasks.get(parsed.taskId);
            if (!task) {
                return (0, utils_1.createErrorResult)('Task not found', 'TASK_NOT_FOUND', traceId);
            }
            if (!task.participants.includes(parsed.clientId)) {
                return (0, utils_1.createErrorResult)('Client not in task participants', 'CLIENT_NOT_IN_TASK', traceId);
            }
            const client = this.clients.get(parsed.clientId);
            if (!client) {
                return (0, utils_1.createErrorResult)('Client not found', 'CLIENT_NOT_FOUND', traceId);
            }
            if (task.status !== 'training') {
                return (0, utils_1.createErrorResult)('Task is not in training state', 'INVALID_STATE', traceId);
            }
            if (parsed.round !== task.currentRound) {
                return (0, utils_1.createErrorResult)(`Invalid round: expected ${task.currentRound}, got ${parsed.round}`, 'INVALID_ROUND', traceId);
            }
            if (task.clientUpdates.has(parsed.clientId)) {
                return (0, utils_1.createErrorResult)('Client already submitted update for this round', 'ALREADY_SUBMITTED', traceId);
            }
            task.clientUpdates.set(parsed.clientId, parsed);
            if (task.clientUpdates.size >= task.participants.length) {
                this.aggregateUpdates(task);
            }
            return (0, utils_1.createSuccessResult)(true, 'UPDATE_SUBMITTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to submit client update', 'UPDATE_SUBMIT_FAILED');
        }
    }
    aggregateUpdates(task) {
        task.status = 'aggregating';
        const updates = Array.from(task.clientUpdates.values());
        const totalSamples = updates.reduce((sum, u) => sum + u.sampleSize, 0);
        if (task.globalModelWeights === null) {
            task.status = 'failed';
            return;
        }
        const weightLength = task.globalModelWeights.length;
        const newWeights = new Array(weightLength).fill(0);
        let totalLoss = 0;
        let totalAccuracy = 0;
        for (const update of updates) {
            const weight = update.sampleSize / totalSamples;
            totalLoss += update.loss * weight;
            totalAccuracy += update.accuracy * weight;
            for (let i = 0; i < weightLength; i++) {
                newWeights[i] += update.encryptedWeights[i] * weight;
            }
        }
        const encryptedWeights = this.encryptWeights(newWeights);
        task.globalModelWeights = encryptedWeights;
        const globalModel = {
            taskId: task.taskId,
            weights: encryptedWeights,
            round: task.currentRound,
            aggregatedAt: Date.now(),
            loss: totalLoss,
            accuracy: totalAccuracy,
            checksum: (0, utils_1.sha256)(JSON.stringify(encryptedWeights)),
        };
        task.modelHistory.push(globalModel);
        task.clientUpdates.clear();
        for (const cid of task.participants) {
            const client = this.clients.get(cid);
            if (client) {
                if (client.status === 'training') {
                    client.status = 'idle';
                    client.reputation = Math.min(100, client.reputation + 1);
                }
            }
        }
        if (task.currentRound >= task.epochs) {
            task.status = 'completed';
        }
        else {
            task.currentRound++;
            task.status = 'training';
        }
    }
    getGlobalModel(taskId, clientId) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const task = this.tasks.get(taskId);
            if (!task) {
                return (0, utils_1.createSuccessResult)(null, 'TASK_NOT_FOUND', traceId);
            }
            if (!task.participants.includes(clientId)) {
                return (0, utils_1.createErrorResult)('Client not authorized', 'NOT_AUTHORIZED', traceId);
            }
            if (!task.globalModelWeights) {
                return (0, utils_1.createSuccessResult)(null, 'MODEL_NOT_AVAILABLE', traceId);
            }
            const checksum = (0, utils_1.sha256)(JSON.stringify(task.globalModelWeights));
            return (0, utils_1.createSuccessResult)({
                weights: task.globalModelWeights,
                round: task.currentRound,
                checksum,
            }, 'MODEL_RETRIEVED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to get global model', 'MODEL_RETRIEVE_FAILED');
        }
    }
    decryptModelWeights(taskId, clientId, encryptedWeights) {
        try {
            const traceId = (0, utils_1.generateId)('trace');
            const task = this.tasks.get(taskId);
            if (!task) {
                return (0, utils_1.createErrorResult)('Task not found', 'TASK_NOT_FOUND', traceId);
            }
            if (!task.participants.includes(clientId)) {
                return (0, utils_1.createErrorResult)('Client not authorized', 'NOT_AUTHORIZED', traceId);
            }
            const client = this.clients.get(clientId);
            if (!client) {
                return (0, utils_1.createErrorResult)('Client not found', 'CLIENT_NOT_FOUND', traceId);
            }
            const key = crypto.createHash('sha256').update(client.privateKey || client.clientId).digest();
            const decrypted = this.decryptWeights(encryptedWeights, key);
            return (0, utils_1.createSuccessResult)(decrypted, 'WEIGHTS_DECRYPTED', traceId);
        }
        catch (error) {
            return (0, utils_1.createErrorResult)(error instanceof Error ? error.message : 'Failed to decrypt weights', 'DECRYPT_FAILED');
        }
    }
    getTask(taskId) {
        const task = this.tasks.get(taskId);
        if (!task) {
            return (0, utils_1.createSuccessResult)(null, 'TASK_NOT_FOUND');
        }
        const { clientUpdates: _cu, modelHistory: _mh, ...publicInfo } = task;
        return (0, utils_1.createSuccessResult)(publicInfo, 'TASK_RETRIEVED');
    }
    listTasks() {
        const tasks = Array.from(this.tasks.values()).map(t => {
            const { clientUpdates: _cu, modelHistory: _mh, ...publicInfo } = t;
            return publicInfo;
        });
        return (0, utils_1.createSuccessResult)(tasks, 'TASKS_LISTED');
    }
    getTaskHistory(taskId) {
        const task = this.tasks.get(taskId);
        if (!task) {
            return (0, utils_1.createErrorResult)('Task not found', 'TASK_NOT_FOUND');
        }
        return (0, utils_1.createSuccessResult)([...task.modelHistory], 'HISTORY_RETRIEVED');
    }
    getTaskStats(taskId) {
        const task = this.tasks.get(taskId);
        if (!task) {
            return (0, utils_1.createErrorResult)('Task not found', 'TASK_NOT_FOUND');
        }
        const latest = task.modelHistory[task.modelHistory.length - 1];
        return (0, utils_1.createSuccessResult)({
            totalClients: task.participants.length,
            completedClients: task.clientUpdates.size,
            currentRound: task.currentRound,
            totalEpochs: task.epochs,
            status: task.status,
            latestAccuracy: latest?.accuracy || 0,
            latestLoss: latest?.loss || 0,
        }, 'STATS_RETRIEVED');
    }
    cancelTask(taskId) {
        const task = this.tasks.get(taskId);
        if (!task) {
            return (0, utils_1.createErrorResult)('Task not found', 'TASK_NOT_FOUND');
        }
        if (task.status === 'completed') {
            return (0, utils_1.createErrorResult)('Cannot cancel completed task', 'CANNOT_CANCEL');
        }
        task.status = 'failed';
        for (const cid of task.participants) {
            const client = this.clients.get(cid);
            if (client && client.status === 'training') {
                client.status = 'idle';
                client.reputation = Math.max(0, client.reputation - 5);
            }
        }
        return (0, utils_1.createSuccessResult)(true, 'TASK_CANCELLED');
    }
    initializeModelWeights(config) {
        const size = config.inputSize || 10;
        const outputSize = config.outputSize || 1;
        const hiddenSize = config.hiddenSize || 20;
        const totalWeights = size * hiddenSize + hiddenSize * outputSize + hiddenSize + outputSize;
        const weights = [];
        for (let i = 0; i < totalWeights; i++) {
            weights.push((Math.random() - 0.5) * 0.1);
        }
        return this.encryptWeights(weights);
    }
    encryptWeights(weights) {
        const result = [];
        for (const w of weights) {
            const noise = (Math.random() - 0.5) * 0.001;
            result.push(w + noise);
        }
        return result;
    }
    decryptWeights(weights, _key) {
        return [...weights];
    }
    getClientReputation(clientId) {
        const client = this.clients.get(clientId);
        if (!client) {
            return (0, utils_1.createErrorResult)('Client not found', 'CLIENT_NOT_FOUND');
        }
        return (0, utils_1.createSuccessResult)(client.reputation, 'REPUTATION_RETRIEVED');
    }
}
exports.FederatedLearningCoordinator = FederatedLearningCoordinator;
//# sourceMappingURL=index.js.map