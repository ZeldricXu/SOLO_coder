import * as crypto from 'crypto';
import { DifferentialPrivacyConfig, DPQueryResult } from './types';

export interface QueryContext {
  queryId: string;
  userId?: string;
  queryType: string;
  timestamp: number;
}

export interface BudgetConsumption {
  queryId: string;
  epsilonUsed: number;
  deltaUsed: number;
  timestamp: number;
}

export interface PrivacyBudgetAccount {
  userId: string;
  totalEpsilon: number;
  totalDelta: number;
  consumedEpsilon: number;
  consumedDelta: number;
  consumptionHistory: BudgetConsumption[];
}

export class DifferentialPrivacyModule {
  private configs: Map<string, DifferentialPrivacyConfig> = new Map();
  private budgetAccounts: Map<string, PrivacyBudgetAccount> = new Map();
  private defaultConfig: DifferentialPrivacyConfig;

  constructor(defaultEpsilon: number = 1.0, defaultDelta: number = 1e-5) {
    this.defaultConfig = {
      epsilon: defaultEpsilon,
      delta: defaultDelta,
      mechanism: 'laplace',
      sensitivity: 1.0,
      privacyBudget: defaultEpsilon,
      remainingBudget: defaultEpsilon
    };
  }

  public setConfig(name: string, config: Partial<DifferentialPrivacyConfig>): void {
    const existing = this.configs.get(name) || { ...this.defaultConfig };
    this.configs.set(name, { ...existing, ...config });
  }

  public getConfig(name: string): DifferentialPrivacyConfig {
    return this.configs.get(name) || { ...this.defaultConfig };
  }

  public createBudgetAccount(userId: string, totalEpsilon: number, totalDelta: number): PrivacyBudgetAccount {
    const account: PrivacyBudgetAccount = {
      userId,
      totalEpsilon,
      totalDelta,
      consumedEpsilon: 0,
      consumedDelta: 0,
      consumptionHistory: []
    };

    this.budgetAccounts.set(userId, account);
    return account;
  }

  public getBudgetAccount(userId: string): PrivacyBudgetAccount | undefined {
    return this.budgetAccounts.get(userId);
  }

  public checkBudget(userId: string, requiredEpsilon: number, requiredDelta: number): boolean {
    const account = this.budgetAccounts.get(userId);
    if (!account) return false;

    return (
      account.consumedEpsilon + requiredEpsilon <= account.totalEpsilon &&
      account.consumedDelta + requiredDelta <= account.totalDelta
    );
  }

  public addNoise(
    value: number,
    configName: string,
    context?: QueryContext
  ): DPQueryResult {
    const config = this.getConfig(configName);
    
    if (context?.userId) {
      if (!this.checkBudget(context.userId, config.epsilon, config.delta)) {
        throw new Error('Privacy budget exceeded');
      }
    }

    let noise: number;
    switch (config.mechanism) {
      case 'laplace':
        noise = this.laplaceMechanism(config.sensitivity, config.epsilon);
        break;
      case 'gaussian':
        noise = this.gaussianMechanism(config.sensitivity, config.epsilon, config.delta);
        break;
      case 'exponential':
        noise = this.exponentialMechanism(config.sensitivity, config.epsilon);
        break;
      default:
        noise = this.laplaceMechanism(config.sensitivity, config.epsilon);
    }

    const noisyValue = value + noise;

    if (context?.userId) {
      this.consumeBudget(context.userId, config.epsilon, config.delta, context.queryId);
    }

    return {
      originalValue: value,
      noisyValue,
      noiseAdded: noise,
      epsilonUsed: config.epsilon,
      deltaUsed: config.delta,
      remainingBudget: context?.userId 
        ? this.getRemainingBudget(context.userId) 
        : config.remainingBudget
    };
  }

  public addNoiseToDataset(
    values: number[],
    configName: string,
    context?: QueryContext
  ): DPQueryResult[] {
    const config = this.getConfig(configName);
    
    if (context?.userId) {
      const totalEpsilon = config.epsilon * values.length;
      const totalDelta = config.delta * values.length;
      
      if (!this.checkBudget(context.userId, totalEpsilon, totalDelta)) {
        throw new Error('Privacy budget exceeded for dataset');
      }
    }

    return values.map(value => this.addNoise(value, configName, context));
  }

  public privatizeCount(
    count: number,
    configName: string,
    context?: QueryContext
  ): DPQueryResult {
    const config = this.getConfig(configName);
    const countConfig = { ...config, sensitivity: 1.0 };
    this.configs.set(`${configName}_count`, countConfig);
    
    return this.addNoise(count, `${configName}_count`, context);
  }

  public privatizeSum(
    sum: number,
    lowerBound: number,
    upperBound: number,
    configName: string,
    context?: QueryContext
  ): DPQueryResult {
    const config = this.getConfig(configName);
    const sensitivity = upperBound - lowerBound;
    const sumConfig = { ...config, sensitivity };
    this.configs.set(`${configName}_sum`, sumConfig);
    
    const clampedSum = Math.max(lowerBound, Math.min(upperBound, sum));
    return this.addNoise(clampedSum, `${configName}_sum`, context);
  }

  public privatizeAverage(
    values: number[],
    lowerBound: number,
    upperBound: number,
    configName: string,
    context?: QueryContext
  ): DPQueryResult {
    const clampedValues = values.map(v => Math.max(lowerBound, Math.min(upperBound, v)));
    const count = clampedValues.length;
    const sum = clampedValues.reduce((acc, v) => acc + v, 0);
    const average = count > 0 ? sum / count : 0;

    const config = this.getConfig(configName);
    const sensitivity = (upperBound - lowerBound) / Math.max(1, count);
    const avgConfig = { ...config, sensitivity };
    this.configs.set(`${configName}_avg`, avgConfig);

    const result = this.addNoise(average, `${configName}_avg`, context);
    result.originalValue = sum / Math.max(1, count);
    
    return result;
  }

  public applyDifferentialPrivacy<T extends Record<string, unknown>>(
    data: T,
    numericFields: (keyof T)[],
    configName: string,
    context?: QueryContext
  ): T {
    const privatizedData = { ...data };

    for (const field of numericFields) {
      const value = privatizedData[field];
      if (typeof value === 'number') {
        const result = this.addNoise(value, configName, context);
        privatizedData[field] = result.noisyValue as T[keyof T];
      }
    }

    return privatizedData;
  }

  private laplaceMechanism(sensitivity: number, epsilon: number): number {
    const scale = sensitivity / epsilon;
    const u1 = this.randomUniform();
    const u2 = this.randomUniform();
    const z = u1 < 0.5 
      ? scale * Math.log(2 * u1) 
      : -scale * Math.log(2 * (1 - u1));
    return z * (u2 < 0.5 ? 1 : -1);
  }

  private gaussianMechanism(sensitivity: number, epsilon: number, delta: number): number {
    const sigma = (sensitivity * Math.sqrt(2 * Math.log(1.25 / delta))) / epsilon;
    const u1 = this.randomUniform();
    const u2 = this.randomUniform();
    const z0 = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    return z0 * sigma;
  }

  private exponentialMechanism(sensitivity: number, epsilon: number): number {
    const u = this.randomUniform();
    const z = -Math.log(u) * (sensitivity / epsilon);
    return z;
  }

  private randomUniform(): number {
    const buffer = crypto.randomBytes(8);
    const randomInt = buffer.readBigUInt64BE(0);
    return Number(randomInt) / Number(0xFFFFFFFFFFFFFFFFn);
  }

  private consumeBudget(userId: string, epsilon: number, delta: number, queryId: string): void {
    const account = this.budgetAccounts.get(userId);
    if (!account) return;

    account.consumedEpsilon += epsilon;
    account.consumedDelta += delta;
    account.consumptionHistory.push({
      queryId,
      epsilonUsed: epsilon,
      deltaUsed: delta,
      timestamp: Date.now()
    });

    this.budgetAccounts.set(userId, account);
  }

  public getRemainingBudget(userId: string): number {
    const account = this.budgetAccounts.get(userId);
    if (!account) return 0;
    return Math.max(0, account.totalEpsilon - account.consumedEpsilon);
  }

  public resetBudget(userId: string): boolean {
    const account = this.budgetAccounts.get(userId);
    if (!account) return false;

    account.consumedEpsilon = 0;
    account.consumedDelta = 0;
    account.consumptionHistory = [];
    this.budgetAccounts.set(userId, account);
    return true;
  }

  public getPrivacyLoss(queryResults: DPQueryResult[]): number {
    return queryResults.reduce((total, result) => total + result.epsilonUsed, 0);
  }

  public composeQueries(
    results: DPQueryResult[],
    advancedComposition: boolean = false
  ): { totalEpsilon: number; totalDelta: number } {
    if (advancedComposition) {
      const k = results.length;
      const epsilon = results.reduce((sum, r) => sum + r.epsilonUsed, 0);
      const delta = results.reduce((sum, r) => sum + r.deltaUsed, 0);
      
      const composedEpsilon = Math.sqrt(2 * k * Math.log(1 / delta)) * epsilon + k * epsilon * (Math.exp(epsilon) - 1);
      const composedDelta = k * delta + delta;
      
      return { totalEpsilon: composedEpsilon, totalDelta: composedDelta };
    } else {
      return {
        totalEpsilon: results.reduce((sum, r) => sum + r.epsilonUsed, 0),
        totalDelta: results.reduce((sum, r) => sum + r.deltaUsed, 0)
      };
    }
  }

  public calculateUtility(originalValue: number, noisyValue: number): number {
    const absoluteError = Math.abs(noisyValue - originalValue);
    const relativeError = originalValue !== 0 ? absoluteError / Math.abs(originalValue) : absoluteError;
    return 1 - Math.min(1, relativeError);
  }

  public generatePrivacyReport(userId: string): {
    userId: string;
    totalBudget: number;
    consumedBudget: number;
    remainingBudget: number;
    queryCount: number;
    consumptionHistory: BudgetConsumption[];
  } | null {
    const account = this.budgetAccounts.get(userId);
    if (!account) return null;

    return {
      userId,
      totalBudget: account.totalEpsilon,
      consumedBudget: account.consumedEpsilon,
      remainingBudget: account.totalEpsilon - account.consumedEpsilon,
      queryCount: account.consumptionHistory.length,
      consumptionHistory: account.consumptionHistory
    };
  }

  public getStats() {
    return {
      configs: this.configs.size,
      budgetAccounts: this.budgetAccounts.size,
      totalQueries: Array.from(this.budgetAccounts.values()).reduce(
        (sum, acc) => sum + acc.consumptionHistory.length, 0
      )
    };
  }
}

export const createDifferentialPrivacy = (defaultEpsilon?: number, defaultDelta?: number): DifferentialPrivacyModule => {
  return new DifferentialPrivacyModule(defaultEpsilon, defaultDelta);
};
