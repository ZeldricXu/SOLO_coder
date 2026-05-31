import { QualityGate, QualityGateCondition, QualityMetrics } from './types';
import { generateId, currentDateTime, logger } from '../utils/common';

export class QualityGateManager {
  private gates: Map<string, QualityGate> = new Map();
  private defaultGateId?: string;

  constructor() {
    this.createDefaultGate();
  }

  createDefaultGate(): void {
    const defaultGate: QualityGate = {
      gateId: generateId('gate_'),
      name: 'Default Quality Gate',
      isDefault: true,
      conditions: [
        { metric: 'bugs', operator: 'GT', threshold: 0, onFail: 'ERROR' },
        { metric: 'vulnerabilities', operator: 'GT', threshold: 0, onFail: 'ERROR' },
        { metric: 'criticalIssues', operator: 'GT', threshold: 0, onFail: 'ERROR' },
        { metric: 'duplication', operator: 'GT', threshold: 10, onFail: 'WARN' },
        { metric: 'coverage', operator: 'LT', threshold: 80, onFail: 'WARN' },
        { metric: 'technicalDebtMinutes', operator: 'GT', threshold: 1000, onFail: 'WARN' },
      ],
      createdAt: currentDateTime(),
      updatedAt: currentDateTime(),
    };

    this.gates.set(defaultGate.gateId, defaultGate);
    this.defaultGateId = defaultGate.gateId;
    logger.info(`Default quality gate created`, { gateId: defaultGate.gateId });
  }

  createGate(name: string, conditions: QualityGateCondition[], isDefault: boolean = false): QualityGate {
    const gate: QualityGate = {
      gateId: generateId('gate_'),
      name,
      conditions,
      isDefault,
      createdAt: currentDateTime(),
      updatedAt: currentDateTime(),
    };

    this.gates.set(gate.gateId, gate);

    if (isDefault) {
      if (this.defaultGateId) {
        const oldDefault = this.gates.get(this.defaultGateId);
        if (oldDefault) {
          oldDefault.isDefault = false;
        }
      }
      this.defaultGateId = gate.gateId;
    }

    logger.info(`Quality gate created`, { gateId: gate.gateId, name, isDefault });
    return gate;
  }

  getGate(gateId: string): QualityGate | undefined {
    return this.gates.get(gateId);
  }

  getDefaultGate(): QualityGate | undefined {
    return this.defaultGateId ? this.gates.get(this.defaultGateId) : undefined;
  }

  updateGate(gateId: string, updates: Partial<QualityGate>): QualityGate | undefined {
    const gate = this.gates.get(gateId);
    if (!gate) return undefined;

    if (updates.isDefault && !gate.isDefault && this.defaultGateId) {
      const oldDefault = this.gates.get(this.defaultGateId);
      if (oldDefault) {
        oldDefault.isDefault = false;
      }
      this.defaultGateId = gateId;
    }

    const updated: QualityGate = {
      ...gate,
      ...updates,
      updatedAt: currentDateTime(),
    };

    this.gates.set(gateId, updated);
    logger.info(`Quality gate updated`, { gateId });
    return updated;
  }

  deleteGate(gateId: string): boolean {
    if (this.defaultGateId === gateId) {
      const otherGates = Array.from(this.gates.values()).filter(g => g.gateId !== gateId);
      if (otherGates.length > 0) {
        otherGates[0].isDefault = true;
        this.defaultGateId = otherGates[0].gateId;
      } else {
        this.defaultGateId = undefined;
      }
    }

    const deleted = this.gates.delete(gateId);
    if (deleted) {
      logger.info(`Quality gate deleted`, { gateId });
    }
    return deleted;
  }

  listGates(): QualityGate[] {
    return Array.from(this.gates.values());
  }

  evaluateGate(metrics: QualityMetrics, gateId?: string): {
    passed: boolean;
    failedConditions: QualityGateCondition[];
    warnings: QualityGateCondition[];
    details: Array<{ condition: QualityGateCondition; actualValue: number; passed: boolean }>;
  } {
    const gate = (gateId ? this.getGate(gateId) : undefined) || this.getDefaultGate();

    if (!gate) {
      return {
        passed: true,
        failedConditions: [],
        warnings: [],
        details: [],
      };
    }

    const failedConditions: QualityGateCondition[] = [];
    const warnings: QualityGateCondition[] = [];
    const details: Array<{ condition: QualityGateCondition; actualValue: number; passed: boolean }> = [];

    for (const condition of gate.conditions) {
      const actualValue = (metrics as any)[condition.metric] as number;
      if (actualValue === undefined) continue;

      const passed = this.evaluateCondition(actualValue, condition);
      details.push({ condition, actualValue, passed });

      if (!passed) {
        if (condition.onFail === 'ERROR') {
          failedConditions.push(condition);
        } else {
          warnings.push(condition);
        }
      }
    }

    const passed = failedConditions.length === 0;

    logger.info(`Quality gate evaluation completed`, {
      gateId: gate.gateId,
      passed,
      failedCount: failedConditions.length,
      warningCount: warnings.length,
    });

    return { passed, failedConditions, warnings, details };
  }

  private evaluateCondition(value: number, condition: QualityGateCondition): boolean {
    const threshold = typeof condition.threshold === 'string'
      ? parseFloat(condition.threshold)
      : condition.threshold;

    switch (condition.operator) {
      case 'LT': return value < threshold;
      case 'GT': return value > threshold;
      case 'LTE': return value <= threshold;
      case 'GTE': return value >= threshold;
      case 'EQ': return value === threshold;
      case 'NE': return value !== threshold;
      default: return true;
    }
  }

  addCondition(gateId: string, condition: QualityGateCondition): QualityGate | undefined {
    const gate = this.gates.get(gateId);
    if (!gate) return undefined;

    gate.conditions.push(condition);
    gate.updatedAt = currentDateTime();
    this.gates.set(gateId, gate);

    return gate;
  }

  removeCondition(gateId: string, index: number): QualityGate | undefined {
    const gate = this.gates.get(gateId);
    if (!gate || index < 0 || index >= gate.conditions.length) return undefined;

    gate.conditions.splice(index, 1);
    gate.updatedAt = currentDateTime();
    this.gates.set(gateId, gate);

    return gate;
  }
}

export const qualityGateManager = new QualityGateManager();
