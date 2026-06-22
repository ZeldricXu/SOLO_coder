import type {
  ID,
  AIContext,
  BehaviorResult,
  BehaviorTreeNode,
  BehaviorTreeNodeType,
  BehaviorTreeConfig,
  BehaviorTreeNodeConfig,
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

  static fromJSON(config: Record<string, unknown>): BehaviorTree {
    const bt = new BehaviorTree();
    bt.fromJSON(config);
    return bt;
  }
}

export class BehaviorConditionBuilders {
  static hasHpBelow(percentage: number): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const hpRatio = context.unit.stats.hp / Math.max(context.unit.stats.maxHp, 1);
      return hpRatio < percentage;
    };
  }

  static hasHpAbove(percentage: number): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const hpRatio = context.unit.stats.hp / Math.max(context.unit.stats.maxHp, 1);
      return hpRatio > percentage;
    };
  }

  static everyNTurns(n: number, offset: number = 0): (context: AIContext) => boolean {
    return (context: AIContext) => {
      return (context.currentTurn + offset) % n === 0;
    };
  }

  static targetWithinRange(rangeType: 'attack' | 'move' | 'vision', rangeValue: number): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const enemies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction !== context.unit.faction
      );
      for (const enemy of enemies) {
        const dist = Math.abs(context.unit.coords.q - enemy.coords.q) +
                     Math.abs(context.unit.coords.r - enemy.coords.r) +
                     Math.abs(context.unit.coords.s - enemy.coords.s);
        if (dist / 2 <= rangeValue) return true;
      }
      return false;
    };
  }

  static hasStatusEffect(effectType: string): (context: AIContext) => boolean {
    return (context: AIContext) => {
      return context.unit.statusEffects.some(s => s.type === effectType);
    };
  }

  static enemyCountBelow(count: number): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const enemies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction !== context.unit.faction
      );
      return enemies.length < count;
    };
  }

  static allyCountBelow(count: number): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const allies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction === context.unit.faction && u.id !== context.unit.id
      );
      return allies.length < count;
    };
  }

  static isFlanked(): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const enemies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction !== context.unit.faction
      );
      let leftSide = 0;
      let rightSide = 0;
      for (const enemy of enemies) {
        const dist = Math.abs(context.unit.coords.q - enemy.coords.q) +
                     Math.abs(context.unit.coords.r - enemy.coords.r);
        if (enemy.coords.q < context.unit.coords.q) leftSide++;
        else rightSide++;
      }
      return leftSide > 0 && rightSide > 0;
    };
  }

  static hasMpAbove(percentage: number): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const mpRatio = context.unit.stats.mp / Math.max(context.unit.stats.maxMp, 1);
      return mpRatio > percentage;
    };
  }

  static canUseSkill(skillId: string): (context: AIContext) => boolean {
    return (context: AIContext) => {
      const skill = context.unit.skills.find(s => s.id === skillId);
      if (!skill) return false;
      return skill.currentCooldown === 0 && context.unit.stats.mp >= skill.mpCost;
    };
  }
}

export class BehaviorActionBuilders {
  static castSkillById(
    skillId: string,
    targetSelector: 'highestThreat' | 'lowestHpAlly' | 'closestEnemy' | 'self' = 'highestThreat'
  ): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const skill = context.unit.skills.find(s => s.id === skillId);
      if (!skill || skill.currentCooldown > 0 || context.unit.stats.mp < skill.mpCost) {
        return 'failure';
      }
      context.memory.set('selectedSkill', skillId);
      context.memory.set('targetSelector', targetSelector);
      return 'success';
    };
  }

  static moveToNearestEnemy(): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const enemies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction !== context.unit.faction
      );
      if (enemies.length === 0) return 'failure';

      let nearest = enemies[0];
      let minDist = Infinity;
      for (const enemy of enemies) {
        const dist = Math.abs(context.unit.coords.q - enemy.coords.q) +
                     Math.abs(context.unit.coords.r - enemy.coords.r) +
                     Math.abs(context.unit.coords.s - enemy.coords.s);
        if (dist < minDist) {
          minDist = dist;
          nearest = enemy;
        }
      }
      context.memory.set('moveTarget', nearest.coords);
      return 'success';
    };
  }

  static moveAwayFromAllEnemies(minDistance: number): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const enemies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction !== context.unit.faction
      );
      if (enemies.length === 0) return 'success';

      let avgQ = 0, avgR = 0, avgS = 0;
      for (const enemy of enemies) {
        avgQ += enemy.coords.q;
        avgR += enemy.coords.r;
        avgS += enemy.coords.s;
      }
      avgQ /= enemies.length;
      avgR /= enemies.length;
      avgS /= enemies.length;

      const dq = context.unit.coords.q - avgQ;
      const dr = context.unit.coords.r - avgR;
      const ds = context.unit.coords.s - avgS;
      const len = Math.sqrt(dq * dq + dr * dr + ds * ds) || 1;

      const retreatPos = {
        q: context.unit.coords.q + (dq / len) * minDistance,
        r: context.unit.coords.r + (dr / len) * minDistance,
        s: context.unit.coords.s + (ds / len) * minDistance,
      };
      context.memory.set('retreatTarget', retreatPos);
      return 'success';
    };
  }

  static attackHighestThreat(): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const threats = context.threatMap;
      if (threats.size === 0) return 'failure';

      let highestId: ID | null = null;
      let highestThreat = -1;
      for (const [id, threat] of threats.entries()) {
        if (threat > highestThreat) {
          highestThreat = threat;
          highestId = id;
        }
      }
      if (highestId) {
        context.memory.set('attackTarget', highestId);
        return 'success';
      }
      return 'failure';
    };
  }

  static healLowestAlly(): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const allies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction === context.unit.faction && u.id !== context.unit.id
      );
      if (allies.length === 0) return 'failure';

      let lowestAlly = allies[0];
      let lowestRatio = 1;
      for (const ally of allies) {
        const ratio = ally.stats.hp / Math.max(ally.stats.maxHp, 1);
        if (ratio < lowestRatio) {
          lowestRatio = ratio;
          lowestAlly = ally;
        }
      }
      context.memory.set('healTarget', lowestAlly.id);
      return 'success';
    };
  }

  static castAoeOnBestCluster(
    skillId: string,
    minTargets: number = 2,
    radius: number = 2
  ): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const skill = context.unit.skills.find(s => s.id === skillId);
      if (!skill || skill.currentCooldown > 0 || context.unit.stats.mp < skill.mpCost) {
        return 'failure';
      }

      const enemies = Array.from(context.allUnits.values()).filter(
        u => u.isAlive && u.faction !== context.unit.faction
      );
      if (enemies.length < minTargets) return 'failure';

      let bestTarget = enemies[0].coords;
      let bestCount = 0;

      for (const enemy of enemies) {
        let count = 0;
        for (const other of enemies) {
          const dist = Math.abs(enemy.coords.q - other.coords.q) +
                       Math.abs(enemy.coords.r - other.coords.r) +
                       Math.abs(enemy.coords.s - other.coords.s);
          if (dist / 2 <= radius) count++;
        }
        if (count > bestCount) {
          bestCount = count;
          bestTarget = enemy.coords;
        }
      }

      if (bestCount >= minTargets) {
        context.memory.set('aoeSkill', skillId);
        context.memory.set('aoeTarget', bestTarget);
        return 'success';
      }
      return 'failure';
    };
  }

  static spawnSummons(summonData: { templateId: string; count: number }): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      context.memory.set('summonData', summonData);
      return 'success';
    };
  }

  static taunt(): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const tauntSkill = context.unit.skills.find(s => s.tags.includes('taunt'));
      if (tauntSkill && tauntSkill.currentCooldown === 0 && context.unit.stats.mp >= tauntSkill.mpCost) {
        context.memory.set('tauntSkill', tauntSkill.id);
        return 'success';
      }
      return 'failure';
    };
  }

  static buffSelf(buffId: string): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      const buffSkill = context.unit.skills.find(s =>
        s.effects.some(e => e.type === 'buff') && s.canTargetSelf
      );
      if (buffSkill && buffSkill.currentCooldown === 0 && context.unit.stats.mp >= buffSkill.mpCost) {
        context.memory.set('buffSkill', buffSkill.id);
        context.memory.set('buffTarget', 'self');
        return 'success';
      }
      return 'failure';
    };
  }

  static wait(): (context: AIContext) => BehaviorResult {
    return (_context: AIContext) => {
      return 'success';
    };
  }

  static moveToHighGround(): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      context.memory.set('positioningGoal', 'highGround');
      return 'success';
    };
  }

  static maintainDistance(): (context: AIContext) => BehaviorResult {
    return (context: AIContext) => {
      context.memory.set('positioningGoal', 'maintainDistance');
      return 'success';
    };
  }
}

export class BossBehaviorTreeBuilder {
  private currentNode: BehaviorTreeNode | null = null;
  private nodeStack: BehaviorTreeNode[] = [];
  private rootNode: BehaviorTreeNode | null = null;
  private idCounter: number = 0;

  private generateId(prefix: string): ID {
    return `${prefix}_${Date.now()}_${this.idCounter++}` as ID;
  }

  sequence(name?: string): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('sequence'),
      type: 'sequence',
      name: name ?? 'Sequence',
      children: [],
    };
    this.addChild(node);
    this.nodeStack.push(node);
    this.currentNode = node;
    return this;
  }

  selector(name?: string): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('selector'),
      type: 'selector',
      name: name ?? 'Selector',
      children: [],
    };
    this.addChild(node);
    this.nodeStack.push(node);
    this.currentNode = node;
    return this;
  }

  parallel(
    successThreshold: number,
    failureThreshold: number,
    name?: string
  ): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('parallel'),
      type: 'parallel',
      name: name ?? 'Parallel',
      children: [],
      parallelConfig: {
        successThreshold,
        failureThreshold,
      },
    };
    this.addChild(node);
    this.nodeStack.push(node);
    this.currentNode = node;
    return this;
  }

  condition(
    name: string,
    condition: (context: AIContext) => boolean
  ): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('condition'),
      type: 'condition',
      name,
      condition,
    };
    this.addChild(node);
    return this;
  }

  action(
    name: string,
    action: (context: AIContext) => BehaviorResult
  ): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('action'),
      type: 'action',
      name,
      action,
    };
    this.addChild(node);
    return this;
  }

  inverter(name?: string): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('decorator'),
      type: 'decorator',
      name: name ?? 'Inverter',
      children: [],
      decorator: { type: 'inverter' },
    };
    this.addChild(node);
    this.nodeStack.push(node);
    this.currentNode = node;
    return this;
  }

  repeat(count: number, name?: string): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('decorator'),
      type: 'decorator',
      name: name ?? `Repeat(x${count})`,
      children: [],
      decorator: { type: 'repeat', count },
    };
    this.addChild(node);
    this.nodeStack.push(node);
    this.currentNode = node;
    return this;
  }

  untilFail(name?: string): BossBehaviorTreeBuilder {
    const node: BehaviorTreeNode = {
      id: this.generateId('decorator'),
      type: 'decorator',
      name: name ?? 'UntilFail',
      children: [],
      decorator: { type: 'untilFail' },
    };
    this.addChild(node);
    this.nodeStack.push(node);
    this.currentNode = node;
    return this;
  }

  end(): BossBehaviorTreeBuilder {
    this.nodeStack.pop();
    this.currentNode = this.nodeStack.length > 0
      ? this.nodeStack[this.nodeStack.length - 1]
      : null;
    return this;
  }

  private addChild(node: BehaviorTreeNode): void {
    if (!this.rootNode) {
      this.rootNode = node;
    } else if (this.currentNode && this.currentNode.children) {
      this.currentNode.children.push(node);
    }
  }

  build(): BehaviorTree {
    if (!this.rootNode) {
      throw new Error('Behavior tree is empty');
    }
    const bt = new BehaviorTree(this.rootNode);
    return bt;
  }

  getRoot(): BehaviorTreeNode | null {
    return this.rootNode;
  }

  reset(): void {
    this.rootNode = null;
    this.currentNode = null;
    this.nodeStack = [];
    this.idCounter = 0;
  }

  static fromConfig(config: BehaviorTreeConfig): BehaviorTree {
    const builder = new BossBehaviorTreeBuilder();
    const rootNode = builder.buildNodeFromConfig(config.root);
    const bt = new BehaviorTree(rootNode);
    return bt;
  }

  private buildNodeFromConfig(config: BehaviorTreeNodeConfig): BehaviorTreeNode {
    const node: BehaviorTreeNode = {
      id: this.generateId(config.type),
      type: config.type as BehaviorTreeNodeType,
      name: config.name ?? config.type,
    };

    if (config.children) {
      node.children = config.children.map(c => this.buildNodeFromConfig(c));
    }

    if (config.decorator) {
      node.decorator = config.decorator;
    }

    if (config.parallelConfig) {
      node.parallelConfig = config.parallelConfig;
    }

    if (config.condition) {
      node.condition = this.getConditionFromRegistry(config.condition, config.conditionParams);
    }

    if (config.action) {
      node.action = this.getActionFromRegistry(config.action, config.actionParams);
    }

    return node;
  }

  private getConditionFromRegistry(
    conditionName: string,
    params?: Record<string, unknown>
  ): ((context: AIContext) => boolean) | undefined {
    switch (conditionName) {
      case 'hasHpBelow':
        return BehaviorConditionBuilders.hasHpBelow((params?.percentage as number) ?? 0.3);
      case 'hasHpAbove':
        return BehaviorConditionBuilders.hasHpAbove((params?.percentage as number) ?? 0.7);
      case 'everyNTurns':
        return BehaviorConditionBuilders.everyNTurns(
          (params?.n as number) ?? 3,
          (params?.offset as number) ?? 0
        );
      case 'enemyCountBelow':
        return BehaviorConditionBuilders.enemyCountBelow((params?.count as number) ?? 2);
      case 'allyCountBelow':
        return BehaviorConditionBuilders.allyCountBelow((params?.count as number) ?? 2);
      case 'isFlanked':
        return BehaviorConditionBuilders.isFlanked();
      case 'hasStatusEffect':
        return BehaviorConditionBuilders.hasStatusEffect((params?.effectType as string) ?? '');
      case 'hasMpAbove':
        return BehaviorConditionBuilders.hasMpAbove((params?.percentage as number) ?? 0.5);
      case 'canUseSkill':
        return BehaviorConditionBuilders.canUseSkill((params?.skillId as string) ?? '');
      default:
        return undefined;
    }
  }

  private getActionFromRegistry(
    actionName: string,
    params?: Record<string, unknown>
  ): ((context: AIContext) => BehaviorResult) | undefined {
    switch (actionName) {
      case 'attackHighestThreat':
        return BehaviorActionBuilders.attackHighestThreat();
      case 'healLowestAlly':
        return BehaviorActionBuilders.healLowestAlly();
      case 'moveToNearestEnemy':
        return BehaviorActionBuilders.moveToNearestEnemy();
      case 'moveAwayFromAllEnemies':
        return BehaviorActionBuilders.moveAwayFromAllEnemies(
          (params?.minDistance as number) ?? 3
        );
      case 'castSkillById':
        return BehaviorActionBuilders.castSkillById(
          (params?.skillId as string) ?? '',
          (params?.targetSelector as 'highestThreat' | 'lowestHpAlly' | 'closestEnemy' | 'self') ?? 'highestThreat'
        );
      case 'castAoeOnBestCluster':
        return BehaviorActionBuilders.castAoeOnBestCluster(
          (params?.skillId as string) ?? '',
          (params?.minTargets as number) ?? 2,
          (params?.radius as number) ?? 2
        );
      case 'spawnSummons':
        return BehaviorActionBuilders.spawnSummons(
          (params?.summonData as { templateId: string; count: number }) ?? { templateId: '', count: 1 }
        );
      case 'taunt':
        return BehaviorActionBuilders.taunt();
      case 'buffSelf':
        return BehaviorActionBuilders.buffSelf((params?.buffId as string) ?? '');
      case 'wait':
        return BehaviorActionBuilders.wait();
      case 'moveToHighGround':
        return BehaviorActionBuilders.moveToHighGround();
      case 'maintainDistance':
        return BehaviorActionBuilders.maintainDistance();
      default:
        return undefined;
    }
  }
}
