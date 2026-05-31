import { z } from 'zod';
export declare const CoreEntitySchema: z.ZodObject<{
    id: z.ZodString;
    type: z.ZodString;
    status: z.ZodEnum<["active", "inactive", "archived"]>;
    attributes: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    created_at: z.ZodString;
    updated_at: z.ZodString;
}, "strip", z.ZodTypeAny, {
    id: string;
    created_at: string;
    type: string;
    status: "active" | "inactive" | "archived";
    attributes: Record<string, unknown>;
    updated_at: string;
}, {
    id: string;
    created_at: string;
    type: string;
    status: "active" | "inactive" | "archived";
    attributes: Record<string, unknown>;
    updated_at: string;
}>;
export declare const ConfigDefinitionSchema: z.ZodObject<{
    config_id: z.ZodString;
    namespace: z.ZodString;
    version: z.ZodNumber;
    parameters: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    enabled: z.ZodBoolean;
    applied_at: z.ZodString;
    rollback_from: z.ZodOptional<z.ZodNumber>;
}, "strip", z.ZodTypeAny, {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
    rollback_from?: number | undefined;
}, {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
    rollback_from?: number | undefined;
}>;
export declare const RunInstanceSchema: z.ZodObject<{
    run_id: z.ZodString;
    entity_id: z.ZodString;
    phase: z.ZodEnum<["pending", "executing", "completed", "failed", "rollback"]>;
    progress: z.ZodNumber;
    started_at: z.ZodString;
    completed_at: z.ZodNullable<z.ZodString>;
    error_detail: z.ZodNullable<z.ZodString>;
    metadata: z.ZodRecord<z.ZodString, z.ZodUnknown>;
}, "strip", z.ZodTypeAny, {
    run_id: string;
    entity_id: string;
    phase: "pending" | "executing" | "completed" | "failed" | "rollback";
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
    metadata: Record<string, unknown>;
}, {
    run_id: string;
    entity_id: string;
    phase: "pending" | "executing" | "completed" | "failed" | "rollback";
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
    metadata: Record<string, unknown>;
}>;
export declare const StatsSnapshotSchema: z.ZodObject<{
    snapshot_id: z.ZodString;
    timestamp: z.ZodString;
    metrics: z.ZodObject<{
        throughput: z.ZodNumber;
        latency_p99: z.ZodNumber;
        error_rate: z.ZodNumber;
    }, "strip", z.ZodNumber, z.objectOutputType<{
        throughput: z.ZodNumber;
        latency_p99: z.ZodNumber;
        error_rate: z.ZodNumber;
    }, z.ZodNumber, "strip">, z.objectInputType<{
        throughput: z.ZodNumber;
        latency_p99: z.ZodNumber;
        error_rate: z.ZodNumber;
    }, z.ZodNumber, "strip">>;
    dimensions: z.ZodObject<{
        host: z.ZodString;
        region: z.ZodString;
    }, "strip", z.ZodString, z.objectOutputType<{
        host: z.ZodString;
        region: z.ZodString;
    }, z.ZodString, "strip">, z.objectInputType<{
        host: z.ZodString;
        region: z.ZodString;
    }, z.ZodString, "strip">>;
}, "strip", z.ZodTypeAny, {
    timestamp: string;
    snapshot_id: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    } & {
        [k: string]: number;
    };
    dimensions: {
        host: string;
        region: string;
    } & {
        [k: string]: string;
    };
}, {
    timestamp: string;
    snapshot_id: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
    } & {
        [k: string]: number;
    };
    dimensions: {
        host: string;
        region: string;
    } & {
        [k: string]: string;
    };
}>;
export declare const TaskCreateSchema: z.ZodObject<{
    type: z.ZodString;
    config: z.ZodRecord<z.ZodString, z.ZodUnknown>;
    labels: z.ZodRecord<z.ZodString, z.ZodString>;
}, "strip", z.ZodTypeAny, {
    config: Record<string, unknown>;
    type: string;
    labels: Record<string, string>;
}, {
    config: Record<string, unknown>;
    type: string;
    labels: Record<string, string>;
}>;
export declare const BatchOperationSchema: z.ZodObject<{
    operations: z.ZodArray<z.ZodObject<{
        action: z.ZodEnum<["start", "stop", "restart", "delete"]>;
        id: z.ZodString;
    }, "strip", z.ZodTypeAny, {
        id: string;
        action: "delete" | "start" | "stop" | "restart";
    }, {
        id: string;
        action: "delete" | "start" | "stop" | "restart";
    }>, "many">;
}, "strip", z.ZodTypeAny, {
    operations: {
        id: string;
        action: "delete" | "start" | "stop" | "restart";
    }[];
}, {
    operations: {
        id: string;
        action: "delete" | "start" | "stop" | "restart";
    }[];
}>;
export declare const AuthTokenSchema: z.ZodObject<{
    user_id: z.ZodString;
    roles: z.ZodArray<z.ZodString, "many">;
    permissions: z.ZodArray<z.ZodString, "many">;
    tenant_id: z.ZodString;
    exp: z.ZodNumber;
}, "strip", z.ZodTypeAny, {
    user_id: string;
    roles: string[];
    permissions: string[];
    tenant_id: string;
    exp: number;
}, {
    user_id: string;
    roles: string[];
    permissions: string[];
    tenant_id: string;
    exp: number;
}>;
export declare const LogLevelSchema: z.ZodEnum<["debug", "info", "warn", "error", "fatal"]>;
export type CoreEntity = z.infer<typeof CoreEntitySchema>;
export type ConfigDefinition = z.infer<typeof ConfigDefinitionSchema>;
export type RunInstance = z.infer<typeof RunInstanceSchema>;
export type StatsSnapshot = z.infer<typeof StatsSnapshotSchema>;
export type TaskCreate = z.infer<typeof TaskCreateSchema>;
export type BatchOperation = z.infer<typeof BatchOperationSchema>;
export type AuthToken = z.infer<typeof AuthTokenSchema>;
export type LogLevel = z.infer<typeof LogLevelSchema>;
//# sourceMappingURL=schemas.d.ts.map