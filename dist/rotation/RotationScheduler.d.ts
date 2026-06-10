import { Environment } from '../sources/ConfigManager';
import { RotationRecord, SecretRotationConfig, ConfigValue } from '../types';
export type KeyGenerator = (length?: number, characters?: string) => string;
export interface RotationOptions {
    onBeforeRotate?: (key: string, environment: string) => Promise<void> | void;
    onAfterRotate?: (key: string, environment: string, oldValue: ConfigValue | undefined, newValue: ConfigValue) => Promise<void> | void;
    onNotify?: (message: string) => Promise<void> | void;
    operator?: string;
    verify?: boolean;
}
export declare class RotationScheduler {
    private records;
    private scheduledRotations;
    private defaultOperator;
    constructor(defaultOperator?: string);
    private generateRandomKey;
    rotateSecret(environment: Environment, key: string, options?: RotationOptions): Promise<RotationRecord>;
    rotateBatch(environment: Environment, keys: string[], options?: RotationOptions): Promise<RotationRecord[]>;
    scheduleRotation(environment: Environment, config: SecretRotationConfig, intervalMs: number, options?: RotationOptions): string;
    cancelScheduledRotation(scheduleId: string): boolean;
    cancelAllScheduled(): void;
    getRotationHistory(filters?: {
        environment?: string;
        key?: string;
        operator?: string;
        status?: 'success' | 'failed';
        since?: number;
        until?: number;
    }): RotationRecord[];
    getLastRotation(environment: string, key: string): RotationRecord | undefined;
    getRotationAge(environment: string, key: string): number | undefined;
    needsRotation(environment: string, key: string, maxAgeMs: number): boolean;
    private generateRecordId;
    setRecords(records: RotationRecord[]): void;
    getAllRecords(): RotationRecord[];
}
