import { RotationRecord, SecretRotationConfig } from '../types';
import { Environment } from '../sources/ConfigManager';
export declare class SecretRotator {
    private storage?;
    constructor(storage?: {
        saveRotationRecord(record: RotationRecord): Promise<void>;
        getRotationHistory(key: string, environment: string, limit?: number): Promise<RotationRecord[]>;
    });
    generateSecret(length?: number, characters?: string): string;
    rotateSecret(env: Environment, config: SecretRotationConfig, operator: string): Promise<RotationRecord>;
    rotateMultiple(env: Environment, configs: SecretRotationConfig[], operator: string): Promise<RotationRecord[]>;
    private notifyService;
    getHistory(key: string, environment: string, limit?: number): Promise<RotationRecord[]>;
    scheduleRotation(env: Environment, config: SecretRotationConfig, operator: string, intervalMs: number): Promise<NodeJS.Timeout>;
    batchRotateByPattern(env: Environment, pattern: RegExp, sourceType: 'vault' | 'ssm', operator: string, length?: number): Promise<RotationRecord[]>;
}
