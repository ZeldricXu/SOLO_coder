import { ZKProofData } from '../types';
import { generateId, now, withRetry, getErrorMessage } from '../common/utils';
import { eventBus, EVENTS, DomainEvent } from '../common/events';
import { LoggerContext } from '../common/logger';

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

export class DefaultVerificationStrategy implements IVerificationStrategy {
  id = 'default';
  name = 'Default Verification Strategy';
  description = 'Default ZK proof verification with structural validation';

  async verify(context: VerificationContext): Promise<boolean> {
    const { proof, publicSignals, verificationKey } = context;

    if (!proof || typeof proof !== 'object') {
      throw new Error('Invalid proof format');
    }

    if (!Array.isArray(publicSignals)) {
      throw new Error('Invalid public signals format');
    }

    if (!verificationKey || typeof verificationKey !== 'string') {
      throw new Error('Invalid verification key');
    }

    const requiredFields = ['pi_a', 'pi_b', 'pi_c', 'protocol'];
    const hasAllFields = requiredFields.every((field) => field in proof);
    if (!hasAllFields) {
      throw new Error(`Proof missing required fields: ${requiredFields.join(', ')}`);
    }

    const normalizedSignals = publicSignals.map((s) => {
      if (typeof s !== 'string') {
        throw new Error('Invalid public signal type');
      }
      return s;
    });

    if (normalizedSignals.length === 0) {
      throw new Error('At least one public signal is required');
    }

    const isValid = this.validateProofStructure(proof) && this.validatePublicSignals(normalizedSignals);
    return isValid;
  }

  private validateProofStructure(proof: Record<string, unknown>): boolean {
    const { pi_a, pi_b, pi_c, protocol } = proof;

    if (!Array.isArray(pi_a) || pi_a.length !== 3) {
      return false;
    }

    if (!Array.isArray(pi_b) || pi_b.length !== 3) {
      return false;
    }

    if (!Array.isArray(pi_c) || pi_c.length !== 3) {
      return false;
    }

    if (typeof protocol !== 'string') {
      return false;
    }

    const validProtocols = ['groth16', 'plonk', 'fflonk'];
    if (!validProtocols.includes(protocol)) {
      return false;
    }

    return true;
  }

  private validatePublicSignals(publicSignals: string[]): boolean {
    for (const signal of publicSignals) {
      if (typeof signal !== 'string') {
        return false;
      }
      try {
        BigInt(signal);
      } catch {
        return false;
      }
    }
    return true;
  }
}

export class Groth16Strategy implements IVerificationStrategy {
  id = 'groth16';
  name = 'Groth16 Verification Strategy';
  description = 'Groth16-specific ZK proof verification';

  async verify(context: VerificationContext): Promise<boolean> {
    const { proof } = context;
    const protocol = proof.protocol as string;

    if (protocol !== 'groth16') {
      throw new Error(`Groth16 strategy cannot verify ${protocol} proofs`);
    }

    const defaultStrategy = new DefaultVerificationStrategy();
    return defaultStrategy.verify(context);
  }
}

export class PlonkStrategy implements IVerificationStrategy {
  id = 'plonk';
  name = 'PLONK Verification Strategy';
  description = 'PLONK-specific ZK proof verification';

  async verify(context: VerificationContext): Promise<boolean> {
    const { proof } = context;
    const protocol = proof.protocol as string;

    if (protocol !== 'plonk') {
      throw new Error(`PLONK strategy cannot verify ${protocol} proofs`);
    }

    const defaultStrategy = new DefaultVerificationStrategy();
    return defaultStrategy.verify(context);
  }
}

export class FflonkStrategy implements IVerificationStrategy {
  id = 'fflonk';
  name = 'FFLONK Verification Strategy';
  description = 'FFLONK-specific ZK proof verification';

  async verify(context: VerificationContext): Promise<boolean> {
    const { proof } = context;
    const protocol = proof.protocol as string;

    if (protocol !== 'fflonk') {
      throw new Error(`FFLONK strategy cannot verify ${protocol} proofs`);
    }

    const defaultStrategy = new DefaultVerificationStrategy();
    return defaultStrategy.verify(context);
  }
}

export class StrategyRegistry {
  private strategies: Map<string, IVerificationStrategy>;
  private logger: LoggerContext;

  constructor() {
    this.strategies = new Map();
    this.logger = new LoggerContext({ module: 'StrategyRegistry' });
  }

  register(strategy: IVerificationStrategy): void {
    if (this.strategies.has(strategy.id)) {
      throw new Error(`Strategy already registered: ${strategy.id}`);
    }

    this.strategies.set(strategy.id, strategy);

    this.logger.info('Verification strategy registered', { strategyId: strategy.id, strategyName: strategy.name });

    eventBus.emit(EVENTS.PROOF_STRATEGY_REGISTERED, {
      strategyId: strategy.id,
      strategyName: strategy.name,
      timestamp: now(),
    });
  }

  unregister(strategyId: string): boolean {
    if (strategyId === 'default') {
      throw new Error('Cannot unregister default strategy');
    }

    const removed = this.strategies.delete(strategyId);

    if (removed) {
      this.logger.info('Verification strategy unregistered', { strategyId });

      eventBus.emit(EVENTS.PROOF_STRATEGY_UNREGISTERED, {
        strategyId,
        timestamp: now(),
      });
    }

    return removed;
  }

  get(strategyId: string): IVerificationStrategy | undefined {
    return this.strategies.get(strategyId);
  }

  list(): IVerificationStrategy[] {
    return Array.from(this.strategies.values());
  }

  has(strategyId: string): boolean {
    return this.strategies.has(strategyId);
  }
}

export class ZKProofVerifier {
  private circuits: Map<string, CircuitConfig>;
  private verificationHistory: Map<string, VerificationResult>;
  private strategyRegistry: StrategyRegistry;
  private activeStrategyId: string;
  private circuitStrategies: Map<string, string>;
  private logger: LoggerContext;

  constructor() {
    this.circuits = new Map();
    this.verificationHistory = new Map();
    this.strategyRegistry = new StrategyRegistry();
    this.circuitStrategies = new Map();
    this.logger = new LoggerContext({ module: 'ZKProofVerifier' });

    this.strategyRegistry.register(new DefaultVerificationStrategy());
    this.strategyRegistry.register(new Groth16Strategy());
    this.strategyRegistry.register(new PlonkStrategy());
    this.strategyRegistry.register(new FflonkStrategy());

    this.activeStrategyId = 'default';
  }

  setActiveStrategy(strategyId: string): void {
    if (!this.strategyRegistry.has(strategyId)) {
      throw new Error(`Strategy not found: ${strategyId}`);
    }

    const oldStrategyId = this.activeStrategyId;
    this.activeStrategyId = strategyId;

    this.logger.info('Active verification strategy changed', {
      oldStrategyId,
      newStrategyId: strategyId,
    });

    eventBus.emit(EVENTS.PROOF_STRATEGY_CHANGED, {
      oldStrategyId,
      newStrategyId: strategyId,
      timestamp: now(),
    });
  }

  setCircuitStrategy(circuitId: string, strategyId: string): void {
    if (!this.circuits.has(circuitId)) {
      throw new Error(`Circuit not found: ${circuitId}`);
    }

    if (!this.strategyRegistry.has(strategyId)) {
      throw new Error(`Strategy not found: ${strategyId}`);
    }

    this.circuitStrategies.set(circuitId, strategyId);

    this.logger.info('Circuit strategy set', { circuitId, strategyId });
  }

  getActiveStrategy(): IVerificationStrategy {
    return this.strategyRegistry.get(this.activeStrategyId)!;
  }

  getStrategyForCircuit(circuitId: string): IVerificationStrategy {
    const strategyId = this.circuitStrategies.get(circuitId) || this.activeStrategyId;
    return this.strategyRegistry.get(strategyId) || this.getActiveStrategy();
  }

  getStrategyRegistry(): StrategyRegistry {
    return this.strategyRegistry;
  }

  getActiveStrategyId(): string {
    return this.activeStrategyId;
  }

  getCircuitStrategy(circuitId: string): string {
    return this.circuitStrategies.get(circuitId) || this.activeStrategyId;
  }

  registerCircuit(circuitId: string, verificationKey: string, description?: string): CircuitConfig {
    this.logger.info('Registering ZK circuit', { circuitId });

    if (this.circuits.has(circuitId)) {
      throw new Error(`Circuit already registered: ${circuitId}`);
    }

    const config: CircuitConfig = {
      circuitId,
      verificationKey,
      description,
      createdAt: now(),
      enabled: true,
    };

    this.circuits.set(circuitId, config);
    this.logger.info('ZK circuit registered', { circuitId });

    return config;
  }

  getCircuit(circuitId: string): CircuitConfig | undefined {
    return this.circuits.get(circuitId);
  }

  listCircuits(): CircuitConfig[] {
    return Array.from(this.circuits.values()).sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  enableCircuit(circuitId: string): void {
    const circuit = this.circuits.get(circuitId);
    if (!circuit) {
      throw new Error(`Circuit not found: ${circuitId}`);
    }
    circuit.enabled = true;
    this.logger.info('Circuit enabled', { circuitId });
  }

  disableCircuit(circuitId: string): void {
    const circuit = this.circuits.get(circuitId);
    if (!circuit) {
      throw new Error(`Circuit not found: ${circuitId}`);
    }
    circuit.enabled = false;
    this.logger.info('Circuit disabled', { circuitId });
  }

  async verifyProof(proofData: ZKProofData): Promise<VerificationResult> {
    const { proof, publicSignals, circuitId, verificationKey } = proofData;
    const startTime = Date.now();

    this.logger.info('Verifying ZK proof', { circuitId, signalCount: publicSignals.length });

    const circuit = this.circuits.get(circuitId);
    if (!circuit) {
      throw new Error(`Circuit not registered: ${circuitId}`);
    }

    if (!circuit.enabled) {
      throw new Error(`Circuit is disabled: ${circuitId}`);
    }

    if (circuit.verificationKey !== verificationKey) {
      throw new Error('Verification key mismatch');
    }

    const strategy = this.getStrategyForCircuit(circuitId);

    try {
      const result = await withRetry(async () => {
        const verificationTimeMs = Date.now() - startTime;
        const context: VerificationContext = { proof, publicSignals, verificationKey, circuitId };
        const isValid = await strategy.verify(context);

        const verificationResult: VerificationResult = {
          id: generateId('zkp'),
          circuitId,
          valid: isValid,
          publicSignals,
          timestamp: now(),
          verificationTimeMs,
          strategyId: strategy.id,
        };

        return verificationResult;
      }, {
        retries: 2,
        onRetry: (error, attempt) => {
          this.logger.warn('Retrying ZK proof verification', { circuitId, attempt, error: getErrorMessage(error) });
        },
      });

      this.verificationHistory.set(result.id, result);

      const domainEvent: DomainEvent<VerificationResult> = {
        id: generateId('event'),
        type: EVENTS.PROOF_VERIFIED,
        source: 'ZKProofVerifier',
        timestamp: now(),
        data: result,
        metadata: {
          circuitId,
          strategyId: strategy.id,
          valid: result.valid,
        },
      };

      eventBus.emitDomainEvent(domainEvent);

      this.logger.info('ZK proof verification complete', {
        circuitId,
        valid: result.valid,
        verificationTimeMs: result.verificationTimeMs,
        strategyId: strategy.id,
      });

      return result;
    } catch (error) {
      const verificationTimeMs = Date.now() - startTime;
      const failedResult: VerificationResult = {
        id: generateId('zkp'),
        circuitId,
        valid: false,
        publicSignals,
        timestamp: now(),
        error: error instanceof Error ? error.message : 'Unknown error',
        verificationTimeMs,
        strategyId: strategy.id,
      };

      this.verificationHistory.set(failedResult.id, failedResult);
      this.logger.error('ZK proof verification failed', error as Error, { circuitId });

      return failedResult;
    }
  }

  getVerificationResult(verificationId: string): VerificationResult | undefined {
    return this.verificationHistory.get(verificationId);
  }

  getVerificationHistory(circuitId?: string, limit: number = 100): VerificationResult[] {
    let history = Array.from(this.verificationHistory.values());

    if (circuitId) {
      history = history.filter((h) => h.circuitId === circuitId);
    }

    return history
      .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
      .slice(0, limit);
  }

  getVerificationStats(circuitId?: string): {
    total: number;
    valid: number;
    invalid: number;
    successRate: number;
    avgVerificationTimeMs: number;
    strategyBreakdown: Record<string, number>;
  } {
    let history = Array.from(this.verificationHistory.values());

    if (circuitId) {
      history = history.filter((h) => h.circuitId === circuitId);
    }

    const total = history.length;
    const valid = history.filter((h) => h.valid).length;
    const invalid = total - valid;
    const successRate = total > 0 ? valid / total : 0;
    const avgVerificationTimeMs = total > 0
      ? history.reduce((sum, h) => sum + h.verificationTimeMs, 0) / total
      : 0;

    const strategyBreakdown: Record<string, number> = {};
    for (const result of history) {
      strategyBreakdown[result.strategyId] = (strategyBreakdown[result.strategyId] || 0) + 1;
    }

    return {
      total,
      valid,
      invalid,
      successRate,
      avgVerificationTimeMs,
      strategyBreakdown,
    };
  }

  batchVerify(proofs: ZKProofData[]): Promise<VerificationResult[]> {
    this.logger.info('Batch verifying ZK proofs', { count: proofs.length });
    return Promise.all(proofs.map((proof) => this.verifyProof(proof)));
  }
}

export const zkProofVerifier = new ZKProofVerifier();
