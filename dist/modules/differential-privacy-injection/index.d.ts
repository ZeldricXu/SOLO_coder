import { PrivacyBudget, NoiseConfig, NoiseMechanism, ModuleResult } from '../../types';
interface QueryResult {
    originalValue: number;
    noisyValue: number;
    noiseAdded: number;
    epsilonUsed: number;
    deltaUsed: number;
    privacyBudgetRemaining: {
        epsilon: number;
        delta: number;
    };
}
interface BudgetConsumption {
    queryId: string;
    epsilon: number;
    delta: number;
    timestamp: string;
    queryType: string;
}
export declare class DifferentialPrivacyInjection {
    private budgets;
    private consumptionHistory;
    private defaultBudget;
    constructor(defaultBudget?: Partial<PrivacyBudget>);
    createBudget(userId: string, config: Partial<PrivacyBudget>): ModuleResult<PrivacyBudget>;
    getBudget(userId: string): ModuleResult<PrivacyBudget | null>;
    updateBudget(userId: string, updates: Partial<PrivacyBudget>): ModuleResult<PrivacyBudget>;
    resetBudget(userId: string): ModuleResult<PrivacyBudget>;
    checkBudget(userId: string, epsilon: number, delta?: number): ModuleResult<{
        available: boolean;
        remainingEpsilon: number;
        remainingDelta: number;
        requiredEpsilon: number;
        requiredDelta: number;
    }>;
    addNoise(userId: string, value: number, config: NoiseConfig, queryType?: string): ModuleResult<QueryResult>;
    addNoiseToDataset(userId: string, values: number[], config: NoiseConfig, perQueryEpsilon?: boolean): ModuleResult<{
        originalValues: number[];
        noisyValues: number[];
        totalEpsilonUsed: number;
        totalDeltaUsed: number;
        perValueEpsilon: number;
    }>;
    calculateSensitivity(values: number[]): ModuleResult<number>;
    recommendEpsilon(dataSize: number, privacyLevel: 'low' | 'medium' | 'high' | 'very_high'): ModuleResult<{
        epsilon: number;
        delta: number;
        explanation: string;
    }>;
    getConsumptionHistory(userId: string, limit?: number): ModuleResult<BudgetConsumption[]>;
    getBudgetUsageStats(userId: string): ModuleResult<{
        totalEpsilon: number;
        usedEpsilon: number;
        remainingEpsilon: number;
        epsilonUsagePercent: number;
        totalDelta: number;
        usedDelta: number;
        remainingDelta: number;
        deltaUsagePercent: number;
        queryCount: number;
        daysUntilReset: number;
    }>;
    private generateNoise;
    private laplaceMechanism;
    private gaussianMechanism;
    private geometricMechanism;
    private consumeBudget;
    private checkAutoReset;
    clampValues(values: number[], lowerBound: number, upperBound: number): ModuleResult<{
        original: number[];
        clamped: number[];
    }>;
    getPrivacyLoss(epsilonUsed: number, deltaUsed: number, mechanism: NoiseMechanism): ModuleResult<{
        epsilonLoss: number;
        deltaLoss: number;
        compositionType: string;
        explanation: string;
    }>;
}
export {};
