export type ConfigValue = string | number | boolean | null | ConfigObject | ConfigArray;
export interface ConfigObject {
    [key: string]: ConfigValue;
}
export type ConfigArray = ConfigValue[];
export interface ConfigData {
    [key: string]: ConfigValue;
}
export interface ConfigSourceConfig {
    type: 'vault' | 'ssm' | 'configmap' | 'env' | 'default';
    priority: number;
    options: Record<string, unknown>;
}
export interface EnvironmentConfig {
    name: string;
    sources: ConfigSourceConfig[];
    labels?: Record<string, string>;
}
export interface ValidationError {
    key: string;
    environment: string;
    message: string;
    expected: string;
    actual: string;
    schemaPath: string;
}
export interface ValidationReport {
    environment: string;
    valid: boolean;
    errors: ValidationError[];
    timestamp: number;
}
export type DiffType = 'added' | 'removed' | 'changed';
export interface DiffItem {
    type: DiffType;
    key: string;
    path: string;
    before?: ConfigValue;
    after?: ConfigValue;
    changePercent?: number;
}
export interface DiffReport {
    environmentA: string;
    environmentB: string;
    diffs: DiffItem[];
    summary: {
        added: number;
        removed: number;
        changed: number;
        total: number;
    };
    timestamp: number;
}
export interface SecretRotationConfig {
    key: string;
    environment: string;
    sourceType: 'vault' | 'ssm';
    length?: number;
    characters?: string;
    notifyWebhook?: string;
}
export interface RotationRecord {
    id: string;
    key: string;
    environment: string;
    sourceType: string;
    timestamp: number;
    operator: string;
    status: 'success' | 'failed';
    message?: string;
}
export interface RenderTemplateConfig {
    templatePath: string;
    outputPath: string;
    context: ConfigData;
    environment: string;
}
export interface SyncItem {
    key: string;
    sourceEnvironment: string;
    targetEnvironments: string[];
}
export interface SyncPreview {
    key: string;
    sourceEnvironment: string;
    targetEnvironment: string;
    currentValue: ConfigValue | undefined;
    newValue: ConfigValue | undefined;
    action: 'create' | 'update' | 'skip';
}
export interface SyncResult {
    key: string;
    targetEnvironment: string;
    status: 'success' | 'failed';
    message?: string;
    verified: boolean;
}
export interface NotificationConfig {
    type: 'slack' | 'email' | 'webhook';
    config: Record<string, unknown>;
}
export interface NotificationMessage {
    title: string;
    summary: string;
    changes: DiffItem[];
    operator: string;
    environment: string;
    timestamp: number;
}
export interface GitCommitRecord {
    hash: string;
    author: string;
    timestamp: number;
    message: string;
    changes: string[];
}
export interface KeyHistoryEntry {
    commitHash: string;
    timestamp: number;
    author: string;
    message: string;
    value: ConfigValue;
}
export interface AppConfig {
    projectRoot: string;
    storagePath: string;
    gitRepoPath: string;
    environments: EnvironmentConfig[];
    notifications?: NotificationConfig[];
    schemaPath: string;
}
