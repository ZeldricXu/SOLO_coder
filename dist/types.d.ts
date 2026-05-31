import { z } from 'zod';
export declare const EntitySchema: z.ZodObject<{
    id: z.ZodString;
    type: z.ZodString;
    status: z.ZodEnum<["pending", "running", "completed", "failed"]>;
    attributes: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    created_at: z.ZodString;
    updated_at: z.ZodString;
}, "strip", z.ZodTypeAny, {
    id: string;
    type: string;
    status: "pending" | "running" | "completed" | "failed";
    attributes: Record<string, unknown>;
    created_at: string;
    updated_at: string;
}, {
    id: string;
    type: string;
    status: "pending" | "running" | "completed" | "failed";
    attributes: Record<string, unknown>;
    created_at: string;
    updated_at: string;
}>;
export type Entity = z.infer<typeof EntitySchema>;
export declare const ConfigSchema: z.ZodObject<{
    config_id: z.ZodString;
    namespace: z.ZodString;
    version: z.ZodNumber;
    parameters: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    enabled: z.ZodBoolean;
    applied_at: z.ZodString;
}, "strip", z.ZodTypeAny, {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
}, {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
}>;
export type Config = z.infer<typeof ConfigSchema>;
export declare const RunInstanceSchema: z.ZodObject<{
    run_id: z.ZodString;
    entity_id: z.ZodString;
    phase: z.ZodString;
    progress: z.ZodNumber;
    started_at: z.ZodString;
    completed_at: z.ZodNullable<z.ZodString>;
    error_detail: z.ZodNullable<z.ZodString>;
}, "strip", z.ZodTypeAny, {
    run_id: string;
    entity_id: string;
    phase: string;
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
}, {
    run_id: string;
    entity_id: string;
    phase: string;
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
}>;
export type RunInstance = z.infer<typeof RunInstanceSchema>;
export declare const MetricsSchema: z.ZodObject<{
    throughput: z.ZodNumber;
    latency_p99: z.ZodNumber;
    error_rate: z.ZodNumber;
}, "strip", z.ZodTypeAny, {
    throughput: number;
    latency_p99: number;
    error_rate: number;
}, {
    throughput: number;
    latency_p99: number;
    error_rate: number;
}>;
export declare const StatsSnapshotSchema: z.ZodObject<{
    snapshot_id: z.ZodString;
    timestamp: z.ZodString;
    metrics: z.ZodObject<{
        throughput: z.ZodNumber;
        latency_p99: z.ZodNumber;
        error_rate: z.ZodNumber;
    }, "strip", z.ZodTypeAny, {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    }, {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    }>;
    dimensions: z.ZodRecord<z.ZodString, z.ZodString>;
}, "strip", z.ZodTypeAny, {
    snapshot_id: string;
    timestamp: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    };
    dimensions: Record<string, string>;
}, {
    snapshot_id: string;
    timestamp: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    };
    dimensions: Record<string, string>;
}>;
export type StatsSnapshot = z.infer<typeof StatsSnapshotSchema>;
export interface UserPermission {
    userId: string;
    roles: string[];
    clearances: string[];
    allowedFields: string[];
}
export interface MaskingRule {
    field: string;
    strategy: 'full' | 'partial' | 'hash' | 'encrypt' | 'nullify' | 'custom';
    visibilityRoles: string[];
    partialOptions?: {
        visibleStart?: number;
        visibleEnd?: number;
        maskChar?: string;
    };
    customMasker?: (value: unknown, context?: Record<string, unknown>) => unknown;
}
export interface ClassificationLevel {
    level: number;
    name: string;
    description: string;
    handlingRules: string[];
}
export interface AuditLogEntry {
    id: string;
    timestamp: string;
    userId: string;
    action: string;
    resourceType: string;
    resourceId: string;
    details: Record<string, unknown>;
    status: 'success' | 'failed' | 'denied';
    ipAddress?: string;
    userAgent?: string;
}
export interface HashChainLink {
    index: number;
    entryHash: string;
    previousHash: string;
    timestamp: number;
    nonce: number;
    hash: string;
}
export interface EnclaveQuote {
    enclaveId: string;
    timestamp: number;
    measurement: string;
    signer: string;
    reportData: string;
    signature: string;
}
export interface MPCParticipant {
    id: string;
    endpoint: string;
    publicKey: string;
    status: 'idle' | 'computing' | 'completed' | 'failed';
}
export interface MPCProtocol {
    id: string;
    name: string;
    type: 'sum' | 'product' | 'comparison' | 'join' | 'custom';
    participants: string[];
    threshold: number;
    status: 'pending' | 'running' | 'completed' | 'failed';
}
export interface FLTrainingTask {
    taskId: string;
    modelType: string;
    modelConfig: Record<string, unknown>;
    participants: string[];
    epochs: number;
    currentRound: number;
    status: 'pending' | 'training' | 'aggregating' | 'completed' | 'failed';
    globalModelWeights: number[] | null;
}
export interface FLClientUpdate {
    taskId: string;
    clientId: string;
    round: number;
    encryptedWeights: number[];
    sampleSize: number;
    loss: number;
    accuracy: number;
}
export interface KeyShard {
    shardId: string;
    keyId: string;
    index: number;
    value: string;
    owner: string;
    createdAt: string;
}
export interface ClassificationResult {
    field: string;
    value: unknown;
    level: number;
    category: string;
    confidence: number;
    detectedPatterns: string[];
}
export interface PrivacyBudget {
    totalEpsilon: number;
    usedEpsilon: number;
    totalDelta: number;
    usedDelta: number;
    resetInterval: 'daily' | 'weekly' | 'monthly';
    lastReset: string;
}
export type NoiseMechanism = 'laplace' | 'gaussian' | 'geometric';
export interface NoiseConfig {
    mechanism: NoiseMechanism;
    epsilon: number;
    delta?: number;
    sensitivity: number;
    lowerBound?: number;
    upperBound?: number;
}
export interface ModuleResult<T = unknown> {
    success: boolean;
    data?: T;
    error?: string;
    code: string;
    timestamp: string;
    traceId?: string;
}
export declare const ClassificationLevels: Record<number, ClassificationLevel>;
