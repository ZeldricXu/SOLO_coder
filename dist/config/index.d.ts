import { EventEmitter } from 'events';
import { ConfigDefinition } from '../types';
interface ConfigVersion {
    version: number;
    config: ConfigDefinition;
    applied_at: string;
    applied_by: string;
    rollback_from?: number;
}
interface ConfigDiff {
    path: string;
    oldValue: unknown;
    newValue: unknown;
    operation: 'added' | 'removed' | 'modified';
}
interface RollbackRecord {
    rollback_id: string;
    config_id: string;
    from_version: number;
    to_version: number;
    timestamp: string;
    reason: string;
    operator: string;
}
declare class ConfigurationManager extends EventEmitter {
    private configs;
    private activeVersions;
    private rollbackHistory;
    private cache;
    private maxVersionsPerConfig;
    private maxRollbackHistory;
    constructor();
    createConfig(namespace: string, parameters: Record<string, unknown>, operator?: string, enabled?: boolean): ConfigDefinition;
    updateConfig(configId: string, parameters: Record<string, unknown>, operator?: string, enabled?: boolean): ConfigDefinition | null;
    rollbackConfig(configId: string, targetVersion: number, reason: string, operator: string): ConfigDefinition | null;
    getConfig(configId: string): ConfigDefinition | null;
    getConfigVersion(configId: string, version: number): ConfigDefinition | null;
    listConfigVersions(configId: string): ConfigDefinition[];
    listConfigs(namespace?: string): ConfigDefinition[];
    deleteConfig(configId: string): boolean;
    enableConfig(configId: string): ConfigDefinition | null;
    disableConfig(configId: string): ConfigDefinition | null;
    diffConfigs(configId: string, versionA: number, versionB: number): ConfigDiff[];
    getRollbackHistory(configId?: string): RollbackRecord[];
    validateConfig(parameters: Record<string, unknown>, schema?: (params: unknown) => boolean): boolean;
    private calculateDiff;
    private getCacheKey;
    clearCache(): void;
}
export declare const configManager: ConfigurationManager;
export { ConfigurationManager, ConfigVersion, ConfigDiff, RollbackRecord };
//# sourceMappingURL=index.d.ts.map