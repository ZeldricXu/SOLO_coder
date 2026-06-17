import type {
  ID,
  AIContext,
  BehaviorResult,
  BehaviorTreeNode,
  BehaviorTreeNodeType,
} from '../types';
import { deepClone, serializeMap, deserializeMap } from '../utils';

type ConditionFn = (context: AIContext) => boolean;
type ActionFn = (context: AIContext) => BehaviorResult;
type DecoratorFn = (result: BehaviorResult, context: AIContext) => BehaviorResult;

interface RunningState {
  nodeId: ID;
  childIndex: number;
  repeatCount: number;
  waitRemaining: number;
  parallelResults: BehaviorResult[];
}

export class BehaviorTree {
  private root: BehaviorTreeNode | null;
  private conditions: Map<string, ConditionFn>;
  private actions: Map<string, ActionFn>;
  private decorators: Map<string, DecoratorFn>;
  private runningStates: Map<ID, RunningState>;
  private executionLog: Array<{ nodeId: ID; nodeName: string; result: BehaviorResult }>;
  private maxLogSize: number;

  constructor(root?: BehaviorTreeNode) {
    this.root = root ?? null;
    this.conditions = new Map();
    this.actions = new Map();
    this.decorators = new Map();
    this.runningStates = new Map();
    this.executionLog = [];
    this.maxLogSize = 100;
  }

  setRoot(node: BehaviorTreeNode): void {
    this.root = node;
    this.reset();
  }

  getRoot(): BehaviorTreeNode | null {
    return this.root;
  }

  registerCondition(name: string, fn: ConditionFn): void {
    this.conditions.set(name, fn);
  }

  unregisterCondition(name: string): boolean {
    return this.conditions.delete(name);
  }

  registerAction(name: string, fn: ActionFn): void {
    this.actions.set(name, fn);
  }

  unregisterAction(name: string): boolean {
    return this.actions.delete(name);
  }

  registerDecorator(name: string, fn: DecoratorFn): void {
    this.decorators.set(name, fn);
  }

  unregisterDecorator(name: string): boolean {
    return this.decorators.delete(name);
  }

  execute(context: AIContext): BehaviorResult {
    if (!this.root) {
      this.log('__root__', 'no_root', 'failure');
      return 'failure';
    }

    this.executionLog = [];
    const result = this.executeNode(this.root, context);
    this.cleanupRunningStates();
    return result;
  }

  private executeNode(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    switch (node.type) {
      case 'selector':
        return this.executeSelector(node, context);
      case 'sequence':
        return this.executeSequence(node, context);
      case 'parallel':
        return this.executeParallel(node, context);
      case 'decorator':
      case 'inverter':
      case 'repeat':
      case 'untilFail':
      case 'wait':
        return this.executeDecorator(node, context);
      case 'condition':
        return this.executeCondition(node, context);
      case 'action':
        return this.executeAction(node, context);
      default:
        return this.executeUnknown(node, context);
    }
  }

  selector(children: BehaviorTreeNode[], name?: string): BehaviorTreeNode {
    return {
      id: this.generateId('selector'),
      type: 'selector',
      name: name ?? 'Selector',
      children,
    };
  }

  sequence(children: BehaviorTreeNode[], name?: string): BehaviorTreeNode {
    return {
      id: this.generateId('sequence'),
      type: 'sequence',
      name: name ?? 'Sequence',
      children,
    };
  }

  parallel(
    children: BehaviorTreeNode[],
    successThreshold: number,
    failureThreshold: number,
    name?: string
  ): BehaviorTreeNode {
    return {
      id: this.generateId('parallel'),
      type: 'parallel',
      name: name ?? 'Parallel',
      children,
      parallelConfig: {
        successThreshold: Math.min(successThreshold, children.length),
        failureThreshold: Math.min(failureThreshold, children.length),
      },
    };
  }

  decorator(
    child: BehaviorTreeNode,
    decoratorType: 'inverter' | 'repeat' | 'untilFail' | 'wait',
    config?: { count?: number; waitTime?: number },
    name?: string
  ): BehaviorTreeNode {
    return {
      id: this.generateId('decorator'),
      type: 'decorator',
      name: name ?? `Decorator:${decoratorType}`,
      children: [child],
      decorator: {
        type: decoratorType,
        count: config?.count,
        waitTime: config?.waitTime,
      },
    };
  }

  inverter(child: BehaviorTreeNode, name?: string): BehaviorTreeNode {
    return this.decorator(child, 'inverter', undefined, name ?? 'Inverter');
  }

  repeat(child: BehaviorTreeNode, count: number, name?: string): BehaviorTreeNode {
    return this.decorator(child, 'repeat', { count }, name ?? `Repeat(x${count})`);
  }

  untilFail(child: BehaviorTreeNode, name?: string): BehaviorTreeNode {
    return this.decorator(child, 'untilFail', undefined, name ?? 'UntilFail');
  }

  waitNode(waitTime: number, name?: string): BehaviorTreeNode {
    return {
      id: this.generateId('wait'),
      type: 'wait',
      name: name ?? `Wait(${waitTime})`,
      decorator: {
        type: 'wait',
        waitTime,
      },
    };
  }

  conditionNode(name: string, conditionName?: string): BehaviorTreeNode {
    return {
      id: this.generateId('condition'),
      type: 'condition',
      name,
      condition: this.conditions.get(conditionName ?? name),
    };
  }

  actionNode(name: string, actionName?: string): BehaviorTreeNode {
    return {
      id: this.generateId('action'),
      type: 'action',
      name,
      action: this.actions.get(actionName ?? name),
    };
  }

  reset(): void {
    this.runningStates.clear();
    this.executionLog = [];
  }

  resetNode(nodeId: ID): void {
    this.runningStates.delete(nodeId);
  }

  private executeSelector(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    if (!node.children || node.children.length === 0) {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }

    const state = this.getOrCreateState(node.id);

    for (let i = state.childIndex; i < node.children.length; i++) {
      const child = node.children[i];
      const result = this.executeNode(child, context);

      if (result === 'running') {
        state.childIndex = i;
        this.log(node.id, node.name, 'running');
        return 'running';
      }

      if (result === 'success') {
        state.childIndex = 0;
        this.runningStates.delete(node.id);
        this.log(node.id, node.name, 'success');
        return 'success';
      }

      state.childIndex = i + 1;
    }

    state.childIndex = 0;
    this.runningStates.delete(node.id);
    this.log(node.id, node.name, 'failure');
    return 'failure';
  }

  private executeSequence(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    if (!node.children || node.children.length === 0) {
      this.log(node.id, node.name, 'success');
      return 'success';
    }

    const state = this.getOrCreateState(node.id);

    for (let i = state.childIndex; i < node.children.length; i++) {
      const child = node.children[i];
      const result = this.executeNode(child, context);

      if (result === 'running') {
        state.childIndex = i;
        this.log(node.id, node.name, 'running');
        return 'running';
      }

      if (result === 'failure') {
        state.childIndex = 0;
        this.runningStates.delete(node.id);
        this.log(node.id, node.name, 'failure');
        return 'failure';
      }

      state.childIndex = i + 1;
    }

    state.childIndex = 0;
    this.runningStates.delete(node.id);
    this.log(node.id, node.name, 'success');
    return 'success';
  }

  private executeParallel(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    if (!node.children || node.children.length === 0) {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }

    const state = this.getOrCreateState(node.id);
    const config = node.parallelConfig ?? { successThreshold: 1, failureThreshold: 1 };

    if (state.parallelResults.length !== node.children.length) {
      state.parallelResults = new Array(node.children.length).fill('running');
    }

    for (let i = 0; i < node.children.length; i++) {
      if (state.parallelResults[i] === 'running') {
        const child = node.children[i];
        state.parallelResults[i] = this.executeNode(child, context);
      }
    }

    const successCount = state.parallelResults.filter(r => r === 'success').length;
    const failureCount = state.parallelResults.filter(r => r === 'failure').length;

    if (successCount >= config.successThreshold) {
      this.runningStates.delete(node.id);
      this.log(node.id, node.name, 'success');
      return 'success';
    }

    if (failureCount >= config.failureThreshold) {
      this.runningStates.delete(node.id);
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }

    this.log(node.id, node.name, 'running');
    return 'running';
  }

  private executeDecorator(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    if (!node.children || node.children.length === 0) {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }

    const decoratorConfig = node.decorator ?? { type: 'inverter' };
    const child = node.children[0];
    const state = this.getOrCreateState(node.id);

    switch (decoratorConfig.type) {
      case 'inverter': {
        const result = this.executeNode(child, context);
        if (result === 'running') {
          this.log(node.id, node.name, 'running');
          return 'running';
        }
        const inverted: BehaviorResult = result === 'success' ? 'failure' : 'success';
        this.runningStates.delete(node.id);
        this.log(node.id, node.name, inverted);
        return inverted;
      }

      case 'repeat': {
        const maxCount = decoratorConfig.count ?? 1;
        while (state.repeatCount < maxCount) {
          const result = this.executeNode(child, context);
          if (result === 'running') {
            this.log(node.id, node.name, 'running');
            return 'running';
          }
          if (result === 'failure') {
            state.repeatCount = 0;
            this.runningStates.delete(node.id);
            this.log(node.id, node.name, 'failure');
            return 'failure';
          }
          state.repeatCount++;
        }
        state.repeatCount = 0;
        this.runningStates.delete(node.id);
        this.log(node.id, node.name, 'success');
        return 'success';
      }

      case 'untilFail': {
        while (true) {
          const result = this.executeNode(child, context);
          if (result === 'running') {
            this.log(node.id, node.name, 'running');
            return 'running';
          }
          if (result === 'failure') {
            this.runningStates.delete(node.id);
            this.log(node.id, node.name, 'success');
            return 'success';
          }
        }
      }

      case 'wait': {
        if (state.waitRemaining <= 0) {
          state.waitRemaining = decoratorConfig.waitTime ?? 1;
        }
        state.waitRemaining--;
        if (state.waitRemaining <= 0) {
          this.runningStates.delete(node.id);
          this.log(node.id, node.name, 'success');
          return 'success';
        }
        this.log(node.id, node.name, 'running');
        return 'running';
      }

      default: {
        const result = this.executeNode(child, context);
        this.log(node.id, node.name, result);
        return result;
      }
    }
  }

  private executeCondition(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    let conditionFn = node.condition;

    if (!conditionFn) {
      conditionFn = this.conditions.get(node.name) ?? this.conditions.get(node.id);
    }

    if (!conditionFn) {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }

    try {
      const result = conditionFn(context);
      const behaviorResult: BehaviorResult = result ? 'success' : 'failure';
      this.log(node.id, node.name, behaviorResult);
      return behaviorResult;
    } catch {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }
  }

  private executeAction(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    let actionFn = node.action;

    if (!actionFn) {
      actionFn = this.actions.get(node.name) ?? this.actions.get(node.id);
    }

    if (!actionFn) {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }

    try {
      const result = actionFn(context);
      this.log(node.id, node.name, result);
      return result;
    } catch {
      this.log(node.id, node.name, 'failure');
      return 'failure';
    }
  }

  private executeUnknown(node: BehaviorTreeNode, context: AIContext): BehaviorResult {
    if (node.children && node.children.length > 0) {
      return this.executeSelector(node, context);
    }
    this.log(node.id, node.name ?? 'unknown', 'failure');
    return 'failure';
  }

  private getOrCreateState(nodeId: ID): RunningState {
    let state = this.runningStates.get(nodeId);
    if (!state) {
      state = {
        nodeId,
        childIndex: 0,
        repeatCount: 0,
        waitRemaining: 0,
        parallelResults: [],
      };
      this.runningStates.set(nodeId, state);
    }
    return state;
  }

  private cleanupRunningStates(): void {
    const activeIds = new Set<ID>();
    this.collectActiveNodeIds(this.root, activeIds);

    for (const id of Array.from(this.runningStates.keys())) {
      if (!activeIds.has(id)) {
        this.runningStates.delete(id);
      }
    }
  }

  private collectActiveNodeIds(node: BehaviorTreeNode | null, ids: Set<ID>): void {
    if (!node) return;
    ids.add(node.id);
    if (node.children) {
      for (const child of node.children) {
        this.collectActiveNodeIds(child, ids);
      }
    }
  }

  private log(nodeId: ID, nodeName: string, result: BehaviorResult): void {
    this.executionLog.push({ nodeId, nodeName, result });
    if (this.executionLog.length > this.maxLogSize) {
      this.executionLog.shift();
    }
  }

  getExecutionLog(): Array<{ nodeId: ID; nodeName: string; result: BehaviorResult }> {
    return [...this.executionLog];
  }

  setMaxLogSize(size: number): void {
    this.maxLogSize = Math.max(size, 10);
  }

  isNodeRunning(nodeId: ID): boolean {
    return this.runningStates.has(nodeId);
  }

  getRunningCount(): number {
    return this.runningStates.size;
  }

  getRegisteredConditions(): string[] {
    return Array.from(this.conditions.keys());
  }

  getRegisteredActions(): string[] {
    return Array.from(this.actions.keys());
  }

  private generateId(prefix: string): ID {
    return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}` as ID;
  }

  toJSON(): Record<string, unknown> {
    return {
      root: this.serializeNode(this.root),
      conditions: Array.from(this.conditions.keys()),
      actions: Array.from(this.actions.keys()),
      decorators: Array.from(this.decorators.keys()),
      runningStates: serializeMap(this.runningStates, id => id as string),
      executionLog: this.executionLog,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.root) {
      this.root = this.deserializeNode(data.root as Record<string, unknown>);
    }
    if (data.runningStates) {
      this.runningStates = deserializeMap(
        data.runningStates as Array<{ key: string; value: RunningState }>,
        key => key as ID
      );
    }
    if (Array.isArray(data.executionLog)) {
      this.executionLog = data.executionLog as typeof this.executionLog;
    }
  }

  private serializeNode(node: BehaviorTreeNode | null): Record<string, unknown> | null {
    if (!node) return null;
    return {
      id: node.id,
      type: node.type,
      name: node.name,
      children: node.children ? node.children.map(c => this.serializeNode(c)) : undefined,
      decorator: node.decorator,
      parallelConfig: node.parallelConfig,
      hasCondition: !!node.condition,
      hasAction: !!node.action,
    };
  }

  private deserializeNode(data: Record<string, unknown>): BehaviorTreeNode {
    const node: BehaviorTreeNode = {
      id: data.id as ID,
      type: data.type as BehaviorTreeNodeType,
      name: data.name as string,
    };

    if (Array.isArray(data.children)) {
      node.children = (data.children as Record<string, unknown>[]).map(c =>
        this.deserializeNode(c)
      );
    }
    if (data.decorator) {
      node.decorator = data.decorator as BehaviorTreeNode['decorator'];
    }
    if (data.parallelConfig) {
      node.parallelConfig = data.parallelConfig as BehaviorTreeNode['parallelConfig'];
    }

    return node;
  }
}
