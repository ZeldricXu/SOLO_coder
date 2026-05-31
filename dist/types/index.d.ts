export interface Entity {
    id: string;
    type: string;
    status: string;
    attributes: Record<string, unknown>;
    created_at: string;
    updated_at: string;
}
export interface Config {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
}
export interface RunInstance {
    run_id: string;
    entity_id: string;
    phase: string;
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
}
export interface MetricsSnapshot {
    snapshot_id: string;
    timestamp: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
        [key: string]: number;
    };
    dimensions: Record<string, string>;
}
export interface ResourceRequest {
    type: string;
    config: Record<string, unknown>;
    labels: Record<string, string>;
}
export interface ResourceResponse {
    code: number;
    data: {
        id: string;
        status: string;
        progress?: number;
    };
}
export interface BatchOperation {
    action: string;
    id: string;
    params?: Record<string, unknown>;
}
export interface BatchRequest {
    operations: BatchOperation[];
}
export interface BatchResponse {
    code: number;
    data: {
        batch_id: string;
        results: Array<{
            id: string;
            success: boolean;
            message?: string;
        }>;
    };
}
export interface ProcessingContext {
    traceId: string;
    startTime: number;
    namespace: string;
    metadata: Record<string, unknown>;
}
export interface HandlerResult<T = unknown> {
    success: boolean;
    data?: T;
    error?: {
        code: number;
        message: string;
        details?: unknown;
    };
}
export declare enum TaskStatus {
    PENDING = "pending",
    RUNNING = "running",
    COMPLETED = "completed",
    FAILED = "failed",
    CANCELLED = "cancelled",
    TIMEOUT = "timeout"
}
export declare enum DeviceStatus {
    INACTIVE = "inactive",
    ACTIVE = "active",
    OFFLINE = "offline",
    ERROR = "error",
    DECOMMISSIONED = "decommissioned"
}
//# sourceMappingURL=index.d.ts.map