export interface CoreEntity {
    id: string;
    type: string;
    status: 'active' | 'inactive' | 'archived';
    attributes: Record<string, unknown>;
    created_at: string;
    updated_at: string;
}
export interface ConfigDefinition {
    config_id: string;
    namespace: string;
    version: number;
    parameters: Record<string, unknown>;
    enabled: boolean;
    applied_at: string;
    rollback_from?: number;
}
export interface RunInstance {
    run_id: string;
    entity_id: string;
    phase: 'pending' | 'executing' | 'completed' | 'failed' | 'rollback';
    progress: number;
    started_at: string;
    completed_at: string | null;
    error_detail: string | null;
    metadata: Record<string, unknown>;
}
export interface StatsSnapshot {
    snapshot_id: string;
    timestamp: string;
    metrics: {
        throughput: number;
        latency_p99: number;
        error_rate: number;
        [key: string]: number;
    };
    dimensions: {
        host: string;
        region: string;
        [key: string]: string;
    };
}
export type LogLevel = 'debug' | 'info' | 'warn' | 'error' | 'fatal';
export interface LogEntry {
    timestamp: string;
    level: LogLevel;
    message: string;
    trace_id: string;
    context: Record<string, unknown>;
}
export interface ApiResponse<T = unknown> {
    code: number;
    data?: T;
    message?: string;
    error?: string;
}
export interface Task {
    id: string;
    type: string;
    config: Record<string, unknown>;
    labels: Record<string, string>;
    status: 'provisioning' | 'running' | 'completed' | 'failed' | 'stopped';
    created_at: string;
    updated_at: string;
}
export interface AuthContext {
    user_id: string;
    roles: string[];
    permissions: string[];
    tenant_id: string;
    token: string;
}
export interface RateLimitConfig {
    requests_per_minute: number;
    burst: number;
    window_ms: number;
}
//# sourceMappingURL=index.d.ts.map