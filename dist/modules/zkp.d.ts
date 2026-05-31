import { ZKProofData } from '../types';
export interface VerificationResult {
    id: string;
    circuitId: string;
    valid: boolean;
    publicSignals: string[];
    timestamp: string;
    error?: string;
    verificationTimeMs: number;
    strategyId: string;
}
export interface CircuitConfig {
    circuitId: string;
    verificationKey: string;
    description?: string;
    createdAt: string;
    enabled: boolean;
}
export interface VerificationContext {
    proof: Record<string, unknown>;
    publicSignals: string[];
    verificationKey: string;
    circuitId: string;
}
export interface IVerificationStrategy {
    id: string;
    name: string;
    description?: string;
    verify(context: VerificationContext): Promise<boolean>;
}
export declare class DefaultVerificationStrategy implements IVerificationStrategy {
    id: string;
    name: string;
    description: string;
    verify(context: VerificationContext): Promise<boolean>;
    private validateProofStructure;
    private validatePublicSignals;
}
export declare class Groth16Strategy implements IVerificationStrategy {
    id: string;
    name: string;
    description: string;
    verify(context: VerificationContext): Promise<boolean>;
}
export declare class PlonkStrategy implements IVerificationStrategy {
    id: string;
    name: string;
    description: string;
    verify(context: VerificationContext): Promise<boolean>;
}
export declare class FflonkStrategy implements IVerificationStrategy {
    id: string;
    name: string;
    description: string;
    verify(context: VerificationContext): Promise<boolean>;
}
export declare class StrategyRegistry {
    private strategies;
    private logger;
    constructor();
    register(strategy: IVerificationStrategy): void;
    unregister(strategyId: string): boolean;
    get(strategyId: string): IVerificationStrategy | undefined;
    list(): IVerificationStrategy[];
    has(strategyId: string): boolean;
}
export declare class ZKProofVerifier {
    private circuits;
    private verificationHistory;
    private strategyRegistry;
    private activeStrategyId;
    private circuitStrategies;
    private logger;
    constructor();
    setActiveStrategy(strategyId: string): void;
    setCircuitStrategy(circuitId: string, strategyId: string): void;
    getActiveStrategy(): IVerificationStrategy;
    getStrategyForCircuit(circuitId: string): IVerificationStrategy;
    getStrategyRegistry(): StrategyRegistry;
    getActiveStrategyId(): string;
    getCircuitStrategy(circuitId: string): string;
    registerCircuit(circuitId: string, verificationKey: string, description?: string): CircuitConfig;
    getCircuit(circuitId: string): CircuitConfig | undefined;
    listCircuits(): CircuitConfig[];
    enableCircuit(circuitId: string): void;
    disableCircuit(circuitId: string): void;
    verifyProof(proofData: ZKProofData): Promise<VerificationResult>;
    getVerificationResult(verificationId: string): VerificationResult | undefined;
    getVerificationHistory(circuitId?: string, limit?: number): VerificationResult[];
    getVerificationStats(circuitId?: string): {
        total: number;
        valid: number;
        invalid: number;
        successRate: number;
        avgVerificationTimeMs: number;
        strategyBreakdown: Record<string, number>;
    };
    batchVerify(proofs: ZKProofData[]): Promise<VerificationResult[]>;
}
export declare const zkProofVerifier: ZKProofVerifier;
//# sourceMappingURL=zkp.d.ts.map