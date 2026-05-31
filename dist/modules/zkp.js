"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.zkProofVerifier = exports.ZKProofVerifier = exports.StrategyRegistry = exports.FflonkStrategy = exports.PlonkStrategy = exports.Groth16Strategy = exports.DefaultVerificationStrategy = void 0;
const utils_1 = require("../common/utils");
const events_1 = require("../common/events");
const logger_1 = require("../common/logger");
class DefaultVerificationStrategy {
    id = 'default';
    name = 'Default Verification Strategy';
    description = 'Default ZK proof verification with structural validation';
    async verify(context) {
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
    validateProofStructure(proof) {
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
    validatePublicSignals(publicSignals) {
        for (const signal of publicSignals) {
            if (typeof signal !== 'string') {
                return false;
            }
            try {
                BigInt(signal);
            }
            catch {
                return false;
            }
        }
        return true;
    }
}
exports.DefaultVerificationStrategy = DefaultVerificationStrategy;
class Groth16Strategy {
    id = 'groth16';
    name = 'Groth16 Verification Strategy';
    description = 'Groth16-specific ZK proof verification';
    async verify(context) {
        const { proof } = context;
        const protocol = proof.protocol;
        if (protocol !== 'groth16') {
            throw new Error(`Groth16 strategy cannot verify ${protocol} proofs`);
        }
        const defaultStrategy = new DefaultVerificationStrategy();
        return defaultStrategy.verify(context);
    }
}
exports.Groth16Strategy = Groth16Strategy;
class PlonkStrategy {
    id = 'plonk';
    name = 'PLONK Verification Strategy';
    description = 'PLONK-specific ZK proof verification';
    async verify(context) {
        const { proof } = context;
        const protocol = proof.protocol;
        if (protocol !== 'plonk') {
            throw new Error(`PLONK strategy cannot verify ${protocol} proofs`);
        }
        const defaultStrategy = new DefaultVerificationStrategy();
        return defaultStrategy.verify(context);
    }
}
exports.PlonkStrategy = PlonkStrategy;
class FflonkStrategy {
    id = 'fflonk';
    name = 'FFLONK Verification Strategy';
    description = 'FFLONK-specific ZK proof verification';
    async verify(context) {
        const { proof } = context;
        const protocol = proof.protocol;
        if (protocol !== 'fflonk') {
            throw new Error(`FFLONK strategy cannot verify ${protocol} proofs`);
        }
        const defaultStrategy = new DefaultVerificationStrategy();
        return defaultStrategy.verify(context);
    }
}
exports.FflonkStrategy = FflonkStrategy;
class StrategyRegistry {
    strategies;
    logger;
    constructor() {
        this.strategies = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'StrategyRegistry' });
    }
    register(strategy) {
        if (this.strategies.has(strategy.id)) {
            throw new Error(`Strategy already registered: ${strategy.id}`);
        }
        this.strategies.set(strategy.id, strategy);
        this.logger.info('Verification strategy registered', { strategyId: strategy.id, strategyName: strategy.name });
        events_1.eventBus.emit(events_1.EVENTS.PROOF_STRATEGY_REGISTERED, {
            strategyId: strategy.id,
            strategyName: strategy.name,
            timestamp: (0, utils_1.now)(),
        });
    }
    unregister(strategyId) {
        if (strategyId === 'default') {
            throw new Error('Cannot unregister default strategy');
        }
        const removed = this.strategies.delete(strategyId);
        if (removed) {
            this.logger.info('Verification strategy unregistered', { strategyId });
            events_1.eventBus.emit(events_1.EVENTS.PROOF_STRATEGY_UNREGISTERED, {
                strategyId,
                timestamp: (0, utils_1.now)(),
            });
        }
        return removed;
    }
    get(strategyId) {
        return this.strategies.get(strategyId);
    }
    list() {
        return Array.from(this.strategies.values());
    }
    has(strategyId) {
        return this.strategies.has(strategyId);
    }
}
exports.StrategyRegistry = StrategyRegistry;
class ZKProofVerifier {
    circuits;
    verificationHistory;
    strategyRegistry;
    activeStrategyId;
    circuitStrategies;
    logger;
    constructor() {
        this.circuits = new Map();
        this.verificationHistory = new Map();
        this.strategyRegistry = new StrategyRegistry();
        this.circuitStrategies = new Map();
        this.logger = new logger_1.LoggerContext({ module: 'ZKProofVerifier' });
        this.strategyRegistry.register(new DefaultVerificationStrategy());
        this.strategyRegistry.register(new Groth16Strategy());
        this.strategyRegistry.register(new PlonkStrategy());
        this.strategyRegistry.register(new FflonkStrategy());
        this.activeStrategyId = 'default';
    }
    setActiveStrategy(strategyId) {
        if (!this.strategyRegistry.has(strategyId)) {
            throw new Error(`Strategy not found: ${strategyId}`);
        }
        const oldStrategyId = this.activeStrategyId;
        this.activeStrategyId = strategyId;
        this.logger.info('Active verification strategy changed', {
            oldStrategyId,
            newStrategyId: strategyId,
        });
        events_1.eventBus.emit(events_1.EVENTS.PROOF_STRATEGY_CHANGED, {
            oldStrategyId,
            newStrategyId: strategyId,
            timestamp: (0, utils_1.now)(),
        });
    }
    setCircuitStrategy(circuitId, strategyId) {
        if (!this.circuits.has(circuitId)) {
            throw new Error(`Circuit not found: ${circuitId}`);
        }
        if (!this.strategyRegistry.has(strategyId)) {
            throw new Error(`Strategy not found: ${strategyId}`);
        }
        this.circuitStrategies.set(circuitId, strategyId);
        this.logger.info('Circuit strategy set', { circuitId, strategyId });
    }
    getActiveStrategy() {
        return this.strategyRegistry.get(this.activeStrategyId);
    }
    getStrategyForCircuit(circuitId) {
        const strategyId = this.circuitStrategies.get(circuitId) || this.activeStrategyId;
        return this.strategyRegistry.get(strategyId) || this.getActiveStrategy();
    }
    getStrategyRegistry() {
        return this.strategyRegistry;
    }
    getActiveStrategyId() {
        return this.activeStrategyId;
    }
    getCircuitStrategy(circuitId) {
        return this.circuitStrategies.get(circuitId) || this.activeStrategyId;
    }
    registerCircuit(circuitId, verificationKey, description) {
        this.logger.info('Registering ZK circuit', { circuitId });
        if (this.circuits.has(circuitId)) {
            throw new Error(`Circuit already registered: ${circuitId}`);
        }
        const config = {
            circuitId,
            verificationKey,
            description,
            createdAt: (0, utils_1.now)(),
            enabled: true,
        };
        this.circuits.set(circuitId, config);
        this.logger.info('ZK circuit registered', { circuitId });
        return config;
    }
    getCircuit(circuitId) {
        return this.circuits.get(circuitId);
    }
    listCircuits() {
        return Array.from(this.circuits.values()).sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    }
    enableCircuit(circuitId) {
        const circuit = this.circuits.get(circuitId);
        if (!circuit) {
            throw new Error(`Circuit not found: ${circuitId}`);
        }
        circuit.enabled = true;
        this.logger.info('Circuit enabled', { circuitId });
    }
    disableCircuit(circuitId) {
        const circuit = this.circuits.get(circuitId);
        if (!circuit) {
            throw new Error(`Circuit not found: ${circuitId}`);
        }
        circuit.enabled = false;
        this.logger.info('Circuit disabled', { circuitId });
    }
    async verifyProof(proofData) {
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
            const result = await (0, utils_1.withRetry)(async () => {
                const verificationTimeMs = Date.now() - startTime;
                const context = { proof, publicSignals, verificationKey, circuitId };
                const isValid = await strategy.verify(context);
                const verificationResult = {
                    id: (0, utils_1.generateId)('zkp'),
                    circuitId,
                    valid: isValid,
                    publicSignals,
                    timestamp: (0, utils_1.now)(),
                    verificationTimeMs,
                    strategyId: strategy.id,
                };
                return verificationResult;
            }, {
                retries: 2,
                onRetry: (error, attempt) => {
                    this.logger.warn('Retrying ZK proof verification', { circuitId, attempt, error: (0, utils_1.getErrorMessage)(error) });
                },
            });
            this.verificationHistory.set(result.id, result);
            const domainEvent = {
                id: (0, utils_1.generateId)('event'),
                type: events_1.EVENTS.PROOF_VERIFIED,
                source: 'ZKProofVerifier',
                timestamp: (0, utils_1.now)(),
                data: result,
                metadata: {
                    circuitId,
                    strategyId: strategy.id,
                    valid: result.valid,
                },
            };
            events_1.eventBus.emitDomainEvent(domainEvent);
            this.logger.info('ZK proof verification complete', {
                circuitId,
                valid: result.valid,
                verificationTimeMs: result.verificationTimeMs,
                strategyId: strategy.id,
            });
            return result;
        }
        catch (error) {
            const verificationTimeMs = Date.now() - startTime;
            const failedResult = {
                id: (0, utils_1.generateId)('zkp'),
                circuitId,
                valid: false,
                publicSignals,
                timestamp: (0, utils_1.now)(),
                error: error instanceof Error ? error.message : 'Unknown error',
                verificationTimeMs,
                strategyId: strategy.id,
            };
            this.verificationHistory.set(failedResult.id, failedResult);
            this.logger.error('ZK proof verification failed', error, { circuitId });
            return failedResult;
        }
    }
    getVerificationResult(verificationId) {
        return this.verificationHistory.get(verificationId);
    }
    getVerificationHistory(circuitId, limit = 100) {
        let history = Array.from(this.verificationHistory.values());
        if (circuitId) {
            history = history.filter((h) => h.circuitId === circuitId);
        }
        return history
            .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
            .slice(0, limit);
    }
    getVerificationStats(circuitId) {
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
        const strategyBreakdown = {};
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
    batchVerify(proofs) {
        this.logger.info('Batch verifying ZK proofs', { count: proofs.length });
        return Promise.all(proofs.map((proof) => this.verifyProof(proof)));
    }
}
exports.ZKProofVerifier = ZKProofVerifier;
exports.zkProofVerifier = new ZKProofVerifier();
//# sourceMappingURL=zkp.js.map