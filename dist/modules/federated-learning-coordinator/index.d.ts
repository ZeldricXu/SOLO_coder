import { FLTrainingTask, FLClientUpdate, ModuleResult } from '../../types';
interface FLClient {
    clientId: string;
    name: string;
    endpoint: string;
    publicKey: string;
    status: 'idle' | 'training' | 'completed' | 'failed';
    reputation: number;
}
interface GlobalModel {
    taskId: string;
    weights: number[];
    round: number;
    aggregatedAt: number;
    loss: number;
    accuracy: number;
    checksum: string;
}
export declare class FederatedLearningCoordinator {
    private clients;
    private tasks;
    private masterKey;
    constructor(masterSecret?: string);
    registerClient(name: string, endpoint: string): ModuleResult<FLClient>;
    removeClient(clientId: string): ModuleResult<boolean>;
    getClient(clientId: string): ModuleResult<FLClient | null>;
    listClients(): ModuleResult<FLClient[]>;
    createTrainingTask(modelType: string, modelConfig: Record<string, unknown>, clientIds: string[], epochs?: number): ModuleResult<FLTrainingTask>;
    startTraining(taskId: string): ModuleResult<FLTrainingTask>;
    submitClientUpdate(update: FLClientUpdate): ModuleResult<boolean>;
    private aggregateUpdates;
    getGlobalModel(taskId: string, clientId: string): ModuleResult<{
        weights: number[];
        round: number;
        checksum: string;
    } | null>;
    decryptModelWeights(taskId: string, clientId: string, encryptedWeights: number[]): ModuleResult<number[]>;
    getTask(taskId: string): ModuleResult<FLTrainingTask | null>;
    listTasks(): ModuleResult<FLTrainingTask[]>;
    getTaskHistory(taskId: string): ModuleResult<GlobalModel[]>;
    getTaskStats(taskId: string): ModuleResult<{
        totalClients: number;
        completedClients: number;
        currentRound: number;
        totalEpochs: number;
        status: string;
        latestAccuracy: number;
        latestLoss: number;
    }>;
    cancelTask(taskId: string): ModuleResult<boolean>;
    private initializeModelWeights;
    private encryptWeights;
    private decryptWeights;
    getClientReputation(clientId: string): ModuleResult<number>;
}
export {};
