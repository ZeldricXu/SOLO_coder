import { EnclaveQuote, ModuleResult } from '../../types';
interface EnclaveInfo {
    enclaveId: string;
    name: string;
    status: 'running' | 'stopped' | 'suspended' | 'error';
    measurement: string;
    publicKey: string;
    createdAt: number;
    memorySize: number;
    cpuCores: number;
}
interface RemoteAttestationResult {
    verified: boolean;
    enclaveInfo: EnclaveInfo;
    quote: EnclaveQuote;
    timestamp: number;
    error?: string;
}
export declare class TrustedExecutionEnvironment {
    private enclaves;
    private masterPublicKey;
    private masterPrivateKey;
    private knownMeasurements;
    constructor(knownMeasurements?: string[]);
    createEnclave(name: string, config?: {
        memorySize?: number;
        cpuCores?: number;
    }): ModuleResult<EnclaveInfo>;
    destroyEnclave(enclaveId: string): ModuleResult<boolean>;
    suspendEnclave(enclaveId: string): ModuleResult<EnclaveInfo>;
    resumeEnclave(enclaveId: string): ModuleResult<EnclaveInfo>;
    generateQuote(enclaveId: string, reportData?: string): ModuleResult<EnclaveQuote>;
    verifyQuote(quote: EnclaveQuote): ModuleResult<RemoteAttestationResult>;
    executeSecure(enclaveId: string, data: Record<string, unknown>, operation: string): ModuleResult<Record<string, unknown>>;
    getEnclave(enclaveId: string): ModuleResult<EnclaveInfo | null>;
    listEnclaves(): ModuleResult<EnclaveInfo[]>;
    addTrustedMeasurement(measurement: string): ModuleResult<boolean>;
    removeTrustedMeasurement(measurement: string): ModuleResult<boolean>;
    getTrustedMeasurements(): ModuleResult<string[]>;
    getMasterPublicKey(): ModuleResult<string>;
    private calculateMeasurement;
    private processSecure;
    sealData(enclaveId: string, data: string): ModuleResult<{
        sealed: string;
        iv: string;
    }>;
    unsealData(enclaveId: string, sealed: string, iv: string): ModuleResult<string>;
}
export {};
