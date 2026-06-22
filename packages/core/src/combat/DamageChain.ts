import type {
  CombatUnit,
  DamageCalculationConfig,
  ElementChart,
  DamageInstance,
} from '../types';
import type { DamageType, ElementType } from '../types/common';
import type { DamageCalculatorEventMap } from './DamageCalculator';

export interface DamageContext {
  attacker: CombatUnit;
  target: CombatUnit;
  skillElement: ElementType;
  skillDamageType: DamageType;
  baseDamageOverride?: number;
  terrainBonus: number;
  heightBonus: number;
  directionBonus: number;
  skillId?: string;
  armorPenetrationOverride?: number;
  baseDamage?: number;
  elementMultiplier?: number;
  terrainMultiplier?: number;
  heightMultiplier?: number;
  directionMultiplier?: number;
  critMultiplier?: number;
  isCrit?: boolean;
  resistancePercent?: number;
  resistanceMitigation?: number;
  armorPenApplied?: number;
  shieldAbsorbed?: number;
  isHit?: boolean;
  isDodged?: boolean;
  finalDamage: number;
  skipRemaining: boolean;
  instance: Partial<DamageInstance>;
}

export type DamageHandlerResult = void | 'skip_remaining' | 'abort';

export interface IDamageHandler {
  readonly name: string;
  readonly priority: number;
  handle(ctx: DamageContext, config: DamageCalculationConfig, elementChart: ElementChart, events?: DamageCalculatorEventMap): DamageHandlerResult;
}

export class DamageChain {
  private handlers: IDamageHandler[] = [];
  private config: DamageCalculationConfig;
  private elementChart: ElementChart;
  private events: DamageCalculatorEventMap;

  constructor(
    config: DamageCalculationConfig,
    elementChart: ElementChart,
    events: DamageCalculatorEventMap = {}
  ) {
    this.config = config;
    this.elementChart = elementChart;
    this.events = events;
  }

  addHandler(handler: IDamageHandler): this {
    const index = this.handlers.findIndex(h => h.priority > handler.priority);
    if (index === -1) {
      this.handlers.push(handler);
    } else {
      this.handlers.splice(index, 0, handler);
    }
    return this;
  }

  removeHandler(name: string): boolean {
    const index = this.handlers.findIndex(h => h.name === name);
    if (index !== -1) {
      this.handlers.splice(index, 1);
      return true;
    }
    return false;
  }

  process(ctx: DamageContext): DamageContext {
    for (const handler of this.handlers) {
      if (ctx.skipRemaining) {
        break;
      }
      const result = handler.handle(ctx, this.config, this.elementChart, this.events);
      if (result === 'skip_remaining') {
        ctx.skipRemaining = true;
        break;
      }
      if (result === 'abort') {
        break;
      }
    }
    return ctx;
  }

  clear(): void {
    this.handlers = [];
  }

  getHandlers(): IDamageHandler[] {
    return [...this.handlers];
  }

  getConfig(): DamageCalculationConfig {
    return { ...this.config };
  }

  setConfig(config: Partial<DamageCalculationConfig>): void {
    this.config = { ...this.config, ...config };
  }

  getElementChart(): ElementChart {
    return { ...this.elementChart };
  }

  setElementChart(chart: ElementChart): void {
    this.elementChart = { ...chart };
  }

  setEvents(events: Partial<DamageCalculatorEventMap>): void {
    this.events = { ...this.events, ...events };
  }
}
