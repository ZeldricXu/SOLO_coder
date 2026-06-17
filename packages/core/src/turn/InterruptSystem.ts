import type { ID } from '../types/common';
import type { InterruptRequest } from '../types/turn';
import { generateId } from '../utils/id';

export type InterruptHandler = (request: InterruptRequest, state: unknown) => void;

export interface RegisterInterruptOptions {
  sourceUnitId: ID;
  targetUnitId: ID;
  skillId: ID;
  priority: number;
  condition: (state: unknown) => boolean;
  handler?: InterruptHandler;
}

export class InterruptSystem {
  private interrupts: Map<ID, InterruptRequest>;
  private handlers: Map<ID, InterruptHandler>;
  private executedInterrupts: ID[];
  private priorityBias: number;

  constructor(priorityBias: number = 0) {
    this.interrupts = new Map();
    this.handlers = new Map();
    this.executedInterrupts = [];
    this.priorityBias = priorityBias;
  }

  registerInterrupt(options: RegisterInterruptOptions): ID {
    const id = generateId();
    const interrupt: InterruptRequest = {
      id,
      sourceUnitId: options.sourceUnitId,
      targetUnitId: options.targetUnitId,
      skillId: options.skillId,
      priority: options.priority + this.priorityBias,
      condition: options.condition,
      isInserted: false,
      insertedAt: -1
    };

    this.interrupts.set(id, interrupt);

    if (options.handler) {
      this.handlers.set(id, options.handler);
    }

    return id;
  }

  unregisterInterrupt(id: ID): boolean {
    const existed = this.interrupts.has(id);
    this.interrupts.delete(id);
    this.handlers.delete(id);
    return existed;
  }

  sortByPriority(interrupts: InterruptRequest[]): InterruptRequest[] {
    return [...interrupts].sort((a, b) => {
      if (b.priority !== a.priority) {
        return b.priority - a.priority;
      }
      return a.insertedAt - b.insertedAt;
    });
  }

  checkInterrupts(state: unknown): InterruptRequest[] {
    const triggered: InterruptRequest[] = [];

    for (const interrupt of this.interrupts.values()) {
      if (this.executedInterrupts.includes(interrupt.id)) {
        continue;
      }

      try {
        if (interrupt.condition(state)) {
          triggered.push(interrupt);
        }
      } catch (e) {
        console.warn(`Interrupt condition check failed for ${interrupt.id}:`, e);
      }
    }

    return this.sortByPriority(triggered);
  }

  executeInterrupts(state: unknown, currentTurnIndex: number): InterruptRequest[] {
    const triggered = this.checkInterrupts(state);
    const executed: InterruptRequest[] = [];

    for (const interrupt of triggered) {
      const handler = this.handlers.get(interrupt.id);
      if (handler) {
        try {
          handler(interrupt, state);
          interrupt.isInserted = true;
          interrupt.insertedAt = currentTurnIndex;
          this.executedInterrupts.push(interrupt.id);
          executed.push(interrupt);
        } catch (e) {
          console.warn(`Interrupt execution failed for ${interrupt.id}:`, e);
        }
      }
    }

    return executed;
  }

  getPendingInterrupts(): InterruptRequest[] {
    const pending: InterruptRequest[] = [];
    for (const interrupt of this.interrupts.values()) {
      if (!this.executedInterrupts.includes(interrupt.id)) {
        pending.push(interrupt);
      }
    }
    return this.sortByPriority(pending);
  }

  getExecutedInterrupts(): InterruptRequest[] {
    const executed: InterruptRequest[] = [];
    for (const id of this.executedInterrupts) {
      const interrupt = this.interrupts.get(id);
      if (interrupt) {
        executed.push(interrupt);
      }
    }
    return executed;
  }

  clearExecuted(): void {
    this.executedInterrupts = [];
    for (const interrupt of this.interrupts.values()) {
      interrupt.isInserted = false;
      interrupt.insertedAt = -1;
    }
  }

  clearAll(): void {
    this.interrupts.clear();
    this.handlers.clear();
    this.executedInterrupts = [];
  }

  setPriorityBias(bias: number): void {
    this.priorityBias = bias;
  }

  getInterruptCount(): number {
    return this.interrupts.size;
  }

  hasInterrupt(id: ID): boolean {
    return this.interrupts.has(id);
  }

  getInterrupt(id: ID): InterruptRequest | undefined {
    return this.interrupts.get(id);
  }

  toJSON(): Record<string, unknown> {
    const interruptsList: Array<Record<string, unknown>> = [];
    for (const [id, interrupt] of this.interrupts.entries()) {
      interruptsList.push({
        id: interrupt.id,
        sourceUnitId: interrupt.sourceUnitId,
        targetUnitId: interrupt.targetUnitId,
        skillId: interrupt.skillId,
        priority: interrupt.priority,
        isInserted: interrupt.isInserted,
        insertedAt: interrupt.insertedAt
      });
    }

    return {
      interrupts: interruptsList,
      executedInterrupts: [...this.executedInterrupts],
      priorityBias: this.priorityBias
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    this.clearAll();
    this.priorityBias = (data.priorityBias as number) || 0;
    this.executedInterrupts = (data.executedInterrupts as ID[]) || [];

    const interruptsData = data.interrupts as Array<Record<string, unknown>> || [];
    for (const item of interruptsData) {
      const interrupt: InterruptRequest = {
        id: item.id as ID,
        sourceUnitId: item.sourceUnitId as ID,
        targetUnitId: item.targetUnitId as ID,
        skillId: item.skillId as ID,
        priority: item.priority as number,
        condition: () => false,
        isInserted: item.isInserted as boolean,
        insertedAt: item.insertedAt as number
      };
      this.interrupts.set(interrupt.id, interrupt);
    }
  }

  static fromJSON(data: Record<string, unknown>): InterruptSystem {
    const system = new InterruptSystem();
    system.fromJSON(data);
    return system;
  }
}
