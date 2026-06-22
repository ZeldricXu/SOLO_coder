import type {
  ID,
  Faction,
  CubeCoords,
  CombatUnit,
  SquadAI as SquadAIData,
  AIMemory,
  AIProfile,
  AIDecision,
  AIRole,
  BossAIConfig,
  BossPhaseConfig,
} from '../types';
import {
  cubeDistance,
  cubeAdd,
  cubeDirection,
  cubeNeighbors,
  cubeRing,
  getDirection,
} from '../grid/coords';
import {
  serializeMap,
  deserializeMap,
  clamp,
  normalize,
  mapRange,
  weightedRandom,
} from '../utils';

export type SquadStrategy = 'assault' | 'defend' | 'flank' | 'harass' | 'retreat' | 'skirmish' | 'balanced';
export type SquadRole = 'commander' | 'damage' | 'tank' | 'healer' | 'scout';
export type SquadFormation = 'line' | 'column' | 'wedge' | 'circle' | 'loose';

export interface AssignedRole {
  unitId: ID;
  role: SquadRole;
  priority: number;
}

export interface MovementPlan {
  unitId: ID;
  targetCoords: CubeCoords;
  priority: number;
  reason: string;
}

export interface CoordinatedAttack {
  primaryTargetId: ID;
  attackerIds: ID[];
  timing: 'simultaneous' | 'sequential' | 'opportunistic';
  focusBonus: number;
}

export class SquadAI {
  private data: SquadAIData;
  private assignedRoles: Map<ID, SquadRole>;
  private rolePriorities: Map<ID, number>;
  private currentTurn: number;
  private lastUpdateTurn: number;
  private strategyHistory: Array<{ turn: number; strategy: SquadStrategy }>;

  constructor(
    id: ID,
    faction: Faction,
    members: ID[],
    initialStrategy: SquadStrategy = 'balanced' as unknown as SquadStrategy
  ) {
    this.data = {
      id,
      faction,
      members,
      strategy: initialStrategy === 'balanced' ? 'assault' : initialStrategy,
      targetPriority: [],
      waypoints: [],
      formation: 'loose',
      memory: this.createEmptyMemory(),
      unitAIs: new Map(),
    };
    this.assignedRoles = new Map();
    this.rolePriorities = new Map();
    this.currentTurn = 0;
    this.lastUpdateTurn = -1;
    this.strategyHistory = [];
  }

  setStrategy(
    strategy: SquadStrategy,
    allUnits: Map<ID, CombatUnit>,
    reason?: string
  ): void {
    if (this.data.strategy !== strategy) {
      this.strategyHistory.push({
        turn: this.currentTurn,
        strategy: this.data.strategy,
      });
    }
    this.data.strategy = strategy;
    this.adjustFormationForStrategy(strategy);
    this.assignRoles(allUnits);
  }

  getStrategy(): SquadStrategy {
    return this.data.strategy;
  }

  getStrategyHistory(): Array<{ turn: number; strategy: SquadStrategy }> {
    return [...this.strategyHistory];
  }

  assignRoles(allUnits: Map<ID, CombatUnit>): AssignedRole[] {
    const assignments: AssignedRole[] = [];
    const aliveMembers = this.data.members
      .map(id => allUnits.get(id))
      .filter((u): u is CombatUnit => !!u && u.isAlive);

    if (aliveMembers.length === 0) return assignments;

    const sortedByStats = [...aliveMembers].sort((a, b) => {
      const aScore = this.calculateUnitCombatScore(a);
      const bScore = this.calculateUnitCombatScore(b);
      return bScore - aScore;
    });

    if (sortedByStats.length > 0 && !this.data.commanderId) {
      this.data.commanderId = sortedByStats[0].id;
    } else if (this.data.commanderId) {
      const commander = allUnits.get(this.data.commanderId);
      if (!commander || !commander.isAlive) {
        this.data.commanderId = sortedByStats[0]?.id;
      }
    }

    for (let i = 0; i < sortedByStats.length; i++) {
      const unit = sortedByStats[i];
      let role: SquadRole;
      let priority = 100 - i * 10;

      if (unit.id === this.data.commanderId) {
        role = 'commander';
        priority = 100;
      } else {
        role = this.determineRole(unit);
      }

      this.assignedRoles.set(unit.id, role);
      this.rolePriorities.set(unit.id, priority);
      assignments.push({ unitId: unit.id, role, priority });
    }

    this.ensureRoleDistribution(assignments);
    return assignments;
  }

  getAssignedRoles(): AssignedRole[] {
    const result: AssignedRole[] = [];
    for (const [unitId, role] of this.assignedRoles.entries()) {
      result.push({
        unitId,
        role,
        priority: this.rolePriorities.get(unitId) ?? 50,
      });
    }
    return result.sort((a, b) => b.priority - a.priority);
  }

  getRole(unitId: ID): SquadRole | undefined {
    return this.assignedRoles.get(unitId);
  }

  setFormation(formation: SquadFormation): void {
    this.data.formation = formation;
  }

  getFormation(): SquadFormation {
    return this.data.formation;
  }

  planMovement(
    allUnits: Map<ID, CombatUnit>,
    targetPosition?: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];
    const aliveMembers = this.getAliveMembers(allUnits);

    if (aliveMembers.length === 0) return plans;

    const center = this.calculateSquadCenter(aliveMembers);
    const anchor = targetPosition ?? center;

    switch (this.data.formation) {
      case 'wedge':
        plans.push(...this.planWedgeFormation(aliveMembers, anchor, getTileHeight, isPassable));
        break;
      case 'line':
        plans.push(...this.planLineFormation(aliveMembers, anchor, getTileHeight, isPassable));
        break;
      case 'column':
        plans.push(...this.planColumnFormation(aliveMembers, anchor, getTileHeight, isPassable));
        break;
      case 'circle':
        plans.push(...this.planCircleFormation(aliveMembers, anchor, getTileHeight, isPassable));
        break;
      case 'loose':
      default:
        plans.push(...this.planLooseFormation(aliveMembers, anchor, targetPosition, getTileHeight, isPassable));
        break;
    }

    this.applyStrategyAdjustments(plans, allUnits, targetPosition);
    return plans.sort((a, b) => b.priority - a.priority);
  }

  coordinateAttacks(
    allUnits: Map<ID, CombatUnit>,
    threatMap?: Map<ID, number>
  ): CoordinatedAttack[] {
    const attacks: CoordinatedAttack[] = [];
    const aliveMembers = this.getAliveMembers(allUnits);
    const enemies = this.getEnemies(allUnits);

    if (aliveMembers.length === 0 || enemies.length === 0) return attacks;

    const targetScores: Map<ID, number> = new Map();

    for (const enemy of enemies) {
      let score = 0;

      const hpRatio = enemy.stats.hp / Math.max(enemy.stats.maxHp, 1);
      score += (1 - hpRatio) * 40;

      if (threatMap?.has(enemy.id)) {
        score += threatMap.get(enemy.id)! * 0.3;
      }

      let reachableCount = 0;
      for (const member of aliveMembers) {
        const dist = cubeDistance(member.coords, enemy.coords);
        const totalReach = member.stats.moveRange + member.stats.attackRange;
        if (dist <= totalReach) {
          reachableCount++;
          score += 15;
        }
      }

      if (enemy.castingSkill) score += 25;
      if (reachableCount >= 2) score += reachableCount * 8;

      targetScores.set(enemy.id, score);
    }

    const sortedTargets = Array.from(targetScores.entries())
      .sort((a, b) => b[1] - a[1])
      .slice(0, Math.ceil(enemies.length / 2));

    const usedAttackers = new Set<ID>();

    for (const [targetId, score] of sortedTargets) {
      const target = allUnits.get(targetId);
      if (!target) continue;

      const availableAttackers: ID[] = [];

      for (const member of aliveMembers) {
        if (usedAttackers.has(member.id)) continue;
        if (member.hasActed) continue;

        const dist = cubeDistance(member.coords, target.coords);
        const totalReach = member.stats.moveRange + member.stats.attackRange;
        if (dist <= totalReach) {
          availableAttackers.push(member.id);
        }
      }

      if (availableAttackers.length === 0) continue;

      const minAttackers = this.data.strategy === 'assault' ? 2 : 1;
      const actualAttackers = availableAttackers.slice(
        0,
        Math.max(minAttackers, Math.min(availableAttackers.length, 3))
      );

      for (const id of actualAttackers) {
        usedAttackers.add(id);
      }

      attacks.push({
        primaryTargetId: targetId,
        attackerIds: actualAttackers,
        timing: this.data.strategy === 'assault' ? 'simultaneous' : 'opportunistic',
        focusBonus: mapRange(score, 0, 200, 0, 0.5),
      });
    }

    return attacks;
  }

  updateMemory(
    allUnits: Map<ID, CombatUnit>,
    currentTurn: number,
    decisions?: Map<ID, AIDecision>
  ): void {
    this.currentTurn = currentTurn;
    if (this.lastUpdateTurn === currentTurn) return;
    this.lastUpdateTurn = currentTurn;

    const aliveMembers = this.getAliveMembers(allUnits);
    const enemies = this.getEnemies(allUnits);

    for (const member of aliveMembers) {
      this.data.memory.allyPositions.set(member.id, { ...member.coords });

      for (const status of member.statusEffects) {
        if (status.source) {
          const observedSkills = this.data.memory.observedSkills.get(status.source) ?? new Set();
          observedSkills.add(status.id);
          this.data.memory.observedSkills.set(status.source, observedSkills);
        }
      }
    }

    for (const enemy of enemies) {
      const existing = this.data.memory.lastSeenPositions.get(enemy.id);
      if (!existing || !this.coordsEqual(existing.coords, enemy.coords)) {
        this.data.memory.lastSeenPositions.set(enemy.id, {
          coords: { ...enemy.coords },
          turn: currentTurn,
        });
      }
      this.data.memory.knownEnemyPositions.set(enemy.id, { ...enemy.coords });

      for (const status of enemy.statusEffects) {
        if (status.source) {
          const observedSkills = this.data.memory.observedSkills.get(status.source) ?? new Set();
          observedSkills.add(status.id);
          this.data.memory.observedSkills.set(status.source, observedSkills);
        }
      }
    }

    if (decisions) {
      for (const [unitId, decision] of decisions.entries()) {
        if (decision.action === 'attack' && decision.targetUnitId) {
          this.data.memory.damageDealt += 1;
        }
      }
    }

    this.updatePredictions(allUnits, currentTurn);
    this.autoAdjustStrategy(allUnits);
  }

  getMemory(): AIMemory {
    return this.data.memory;
  }

  addWaypoint(coords: CubeCoords, priority?: number): void {
    this.data.waypoints.push({ ...coords });
  }

  clearWaypoints(): void {
    this.data.waypoints = [];
  }

  getWaypoints(): CubeCoords[] {
    return [...this.data.waypoints];
  }

  setTargetPriority(enemyIds: ID[]): void {
    this.data.targetPriority = [...enemyIds];
  }

  getTargetPriority(): ID[] {
    return [...this.data.targetPriority];
  }

  getCommanderId(): ID | undefined {
    return this.data.commanderId;
  }

  addMember(unitId: ID, profile?: AIProfile): void {
    if (!this.data.members.includes(unitId)) {
      this.data.members.push(unitId);
    }
    if (profile) {
      this.data.unitAIs.set(unitId, profile);
    }
  }

  removeMember(unitId: ID): void {
    this.data.members = this.data.members.filter(id => id !== unitId);
    this.data.unitAIs.delete(unitId);
    this.assignedRoles.delete(unitId);
    this.rolePriorities.delete(unitId);
    if (this.data.commanderId === unitId) {
      this.data.commanderId = undefined;
    }
  }

  getMembers(): ID[] {
    return [...this.data.members];
  }

  setUnitAI(unitId: ID, profile: AIProfile): void {
    this.data.unitAIs.set(unitId, profile);
  }

  getUnitAI(unitId: ID): AIProfile | undefined {
    return this.data.unitAIs.get(unitId);
  }

  private createEmptyMemory(): AIMemory {
    return {
      lastSeenPositions: new Map(),
      knownEnemyPositions: new Map(),
      observedSkills: new Map(),
      allyPositions: new Map(),
      killCount: 0,
      damageDealt: 0,
      damageTaken: 0,
      predictions: new Map(),
    };
  }

  private calculateUnitCombatScore(unit: CombatUnit): number {
    const stats = unit.stats;
    let score = 0;

    score += stats.attack * 1.5;
    score += stats.magicAttack * 1.5;
    score += stats.defense * 0.8;
    score += stats.magicDefense * 0.8;
    score += stats.maxHp * 0.3;
    score += stats.speed * 2;
    score += stats.moveRange * 5;
    score += stats.attackRange * 10;
    score += stats.critRate * 20;
    score += unit.skills.length * 15;

    const hpRatio = stats.hp / Math.max(stats.maxHp, 1);
    score *= (0.5 + hpRatio * 0.5);

    return score;
  }

  private determineRole(unit: CombatUnit): SquadRole {
    const stats = unit.stats;
    const hasHealSkill = unit.skills.some(s =>
      s.tags.includes('heal') || s.effects.some(e => e.type === 'heal')
    );

    if (hasHealSkill) return 'healer';

    const defenseScore = stats.defense + stats.magicDefense + stats.maxHp * 0.1;
    const attackScore = stats.attack + stats.magicAttack;
    const speedScore = stats.speed + stats.moveRange * 5;

    if (defenseScore > attackScore * 1.3 && stats.maxHp > 150) return 'tank';
    if (speedScore > 40 && stats.attackRange <= 1) return 'scout';
    return 'damage';
  }

  private ensureRoleDistribution(assignments: AssignedRole[]): void {
    const roleCounts = new Map<SquadRole, number>();
    for (const a of assignments) {
      roleCounts.set(a.role, (roleCounts.get(a.role) ?? 0) + 1);
    }

    const hasCommander = roleCounts.has('commander');
    const hasHealer = (roleCounts.get('healer') ?? 0) > 0;
    const hasTank = (roleCounts.get('tank') ?? 0) > 0;

    if (!hasCommander && assignments.length > 0) {
      assignments[0].role = 'commander';
    }
  }

  private adjustFormationForStrategy(strategy: SquadStrategy): void {
    switch (strategy) {
      case 'assault':
        this.data.formation = 'wedge';
        break;
      case 'defend':
        this.data.formation = 'circle';
        break;
      case 'flank':
        this.data.formation = 'column';
        break;
      case 'harass':
        this.data.formation = 'loose';
        break;
      case 'retreat':
        this.data.formation = 'column';
        break;
      case 'skirmish':
        this.data.formation = 'line';
        break;
    }
  }

  private getAliveMembers(allUnits: Map<ID, CombatUnit>): CombatUnit[] {
    return this.data.members
      .map(id => allUnits.get(id))
      .filter((u): u is CombatUnit => !!u && u.isAlive);
  }

  private getEnemies(allUnits: Map<ID, CombatUnit>): CombatUnit[] {
    const result: CombatUnit[] = [];
    for (const unit of allUnits.values()) {
      if (unit.isAlive && unit.faction !== this.data.faction) {
        result.push(unit);
      }
    }
    return result;
  }

  private calculateSquadCenter(members: CombatUnit[]): CubeCoords {
    if (members.length === 0) return { q: 0, r: 0, s: 0 };
    const sum = members.reduce(
      (acc, m) => ({
        q: acc.q + m.coords.q,
        r: acc.r + m.coords.r,
        s: acc.s + m.coords.s,
      }),
      { q: 0, r: 0, s: 0 }
    );
    const n = members.length;
    return { q: sum.q / n, r: sum.r / n, s: sum.s / n };
  }

  private planWedgeFormation(
    members: CombatUnit[],
    anchor: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];
    const sorted = [...members].sort((a, b) =>
      (this.rolePriorities.get(b.id) ?? 0) - (this.rolePriorities.get(a.id) ?? 0)
    );

    for (let i = 0; i < sorted.length; i++) {
      const row = Math.floor((Math.sqrt(8 * i + 1) - 1) / 2);
      const posInRow = i - (row * (row + 1)) / 2;
      const offsetQ = posInRow - row;
      const offsetR = row;

      let target = {
        q: anchor.q + offsetQ,
        r: anchor.r + offsetR,
        s: -anchor.q - offsetQ - anchor.r - offsetR,
      };

      target = this.findNearestValid(target, getTileHeight, isPassable);

      plans.push({
        unitId: sorted[i].id,
        targetCoords: target,
        priority: 100 - i * 5,
        reason: `楔形阵第${row + 1}行`,
      });
    }

    return plans;
  }

  private planLineFormation(
    members: CombatUnit[],
    anchor: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];
    const halfLen = Math.floor(members.length / 2);

    for (let i = 0; i < members.length; i++) {
      const offset = i - halfLen;
      let target = {
        q: anchor.q + offset,
        r: anchor.r,
        s: -anchor.q - offset - anchor.r,
      };

      target = this.findNearestValid(target, getTileHeight, isPassable);

      plans.push({
        unitId: members[i].id,
        targetCoords: target,
        priority: 80 - Math.abs(offset) * 10,
        reason: '线形阵',
      });
    }

    return plans;
  }

  private planColumnFormation(
    members: CombatUnit[],
    anchor: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];

    for (let i = 0; i < members.length; i++) {
      let target = {
        q: anchor.q,
        r: anchor.r + i,
        s: -anchor.q - anchor.r - i,
      };

      target = this.findNearestValid(target, getTileHeight, isPassable);

      plans.push({
        unitId: members[i].id,
        targetCoords: target,
        priority: 100 - i * 8,
        reason: `纵列阵第${i + 1}位`,
      });
    }

    return plans;
  }

  private planCircleFormation(
    members: CombatUnit[],
    anchor: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];

    if (members.length === 1) {
      plans.push({
        unitId: members[0].id,
        targetCoords: this.findNearestValid(anchor, getTileHeight, isPassable),
        priority: 100,
        reason: '圆心位置',
      });
      return plans;
    }

    const radius = Math.ceil(Math.sqrt(members.length / 6));
    const ringTiles = cubeRing(anchor, radius);
    const centerUnit = members.find(m => this.assignedRoles.get(m.id) === 'healer') ??
      members.find(m => this.assignedRoles.get(m.id) === 'commander') ??
      members[0];

    plans.push({
      unitId: centerUnit.id,
      targetCoords: this.findNearestValid(anchor, getTileHeight, isPassable),
      priority: 100,
      reason: '圆心保护位',
    });

    const remaining = members.filter(m => m.id !== centerUnit.id);
    for (let i = 0; i < remaining.length && i < ringTiles.length; i++) {
      const target = this.findNearestValid(ringTiles[i], getTileHeight, isPassable);
      plans.push({
        unitId: remaining[i].id,
        targetCoords: target,
        priority: 80 - i * 3,
        reason: `环形阵${radius}半径`,
      });
    }

    return plans;
  }

  private planLooseFormation(
    members: CombatUnit[],
    anchor: CubeCoords,
    targetPosition?: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];
    const sorted = [...members].sort((a, b) =>
      (this.rolePriorities.get(b.id) ?? 0) - (this.rolePriorities.get(a.id) ?? 0)
    );

    const usedPositions = new Set<string>();
    const direction = targetPosition
      ? getDirection(anchor, targetPosition)
      : 0;

    for (let i = 0; i < sorted.length; i++) {
      const member = sorted[i];
      const role = this.assignedRoles.get(member.id);
      let baseDistance = 2;

      switch (role) {
        case 'tank':
          baseDistance = 1;
          break;
        case 'scout':
        case 'damage':
          baseDistance = 2 + Math.floor(i / 3);
          break;
        case 'healer':
        case 'commander':
          baseDistance = 1;
          break;
      }

      let target: CubeCoords | null = null;
      const searchRadius = 4;

      for (let r = 1; r <= searchRadius && !target; r++) {
        const ring = cubeRing(anchor, r);
        const shuffled = [...ring].sort(() => Math.random() - 0.5);

        for (const pos of shuffled) {
          const key = `${pos.q},${pos.r},${pos.s}`;
          if (usedPositions.has(key)) continue;
          if (isPassable && !isPassable(pos)) continue;

          target = pos;
          usedPositions.add(key);
          break;
        }
      }

      if (!target) {
        target = anchor;
      }

      plans.push({
        unitId: member.id,
        targetCoords: target,
        priority: (this.rolePriorities.get(member.id) ?? 50),
        reason: `${role}散阵位`,
      });
    }

    return plans;
  }

  private findNearestValid(
    target: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    if (!isPassable) return target;
    if (isPassable(target)) return target;

    for (let radius = 1; radius <= 3; radius++) {
      const ring = cubeRing(target, radius);
      for (const pos of ring) {
        if (isPassable(pos)) {
          return pos;
        }
      }
    }

    return target;
  }

  private applyStrategyAdjustments(
    plans: MovementPlan[],
    allUnits: Map<ID, CombatUnit>,
    targetPosition?: CubeCoords
  ): void {
    switch (this.data.strategy) {
      case 'assault':
        for (const plan of plans) {
          const role = this.assignedRoles.get(plan.unitId);
          if (role === 'tank' || role === 'damage') {
            plan.priority += 20;
            plan.reason += '、突击优先';
          }
        }
        break;

      case 'defend':
        for (const plan of plans) {
          const role = this.assignedRoles.get(plan.unitId);
          if (role === 'healer' || role === 'commander') {
            plan.priority += 25;
            plan.reason += '、防守核心';
          }
        }
        break;

      case 'flank':
        for (const plan of plans) {
          const role = this.assignedRoles.get(plan.unitId);
          if (role === 'scout' || role === 'damage') {
            plan.priority += 15;
            plan.reason += '、侧翼机动';
          }
        }
        break;

      case 'retreat':
        if (targetPosition) {
          for (const plan of plans) {
            const unit = allUnits.get(plan.unitId);
            if (unit) {
              const hpRatio = unit.stats.hp / Math.max(unit.stats.maxHp, 1);
              if (hpRatio < 0.4) {
                plan.priority += 30;
                plan.reason += '、低血量优先撤退';
              }
            }
          }
        }
        break;
    }
  }

  private updatePredictions(
    allUnits: Map<ID, CombatUnit>,
    currentTurn: number
  ): void {
    const enemies = this.getEnemies(allUnits);

    for (const enemy of enemies) {
      const lastSeen = this.data.memory.lastSeenPositions.get(enemy.id);
      if (lastSeen && lastSeen.turn < currentTurn) {
        const turnDiff = currentTurn - lastSeen.turn;
        const estMove = enemy.stats.moveRange * turnDiff * 0.5;

        const prediction = {
          expectedPosition: { ...enemy.coords },
          confidence: clamp(1 - turnDiff * 0.15, 0.1, 1),
        };

        this.data.memory.predictions.set(enemy.id, prediction);
      }
    }

    for (const [enemyId, prediction] of this.data.memory.predictions) {
      if (!allUnits.has(enemyId) || !allUnits.get(enemyId)?.isAlive) {
        prediction.confidence = Math.max(0, prediction.confidence - 0.1);
      }
    }
  }

  private autoAdjustStrategy(allUnits: Map<ID, CombatUnit>): void {
    const aliveMembers = this.getAliveMembers(allUnits);
    const enemies = this.getEnemies(allUnits);

    if (aliveMembers.length === 0 || enemies.length === 0) return;

    let totalHp = 0;
    let totalMaxHp = 0;
    for (const member of aliveMembers) {
      totalHp += member.stats.hp;
      totalMaxHp += member.stats.maxHp;
    }
    const squadHpRatio = totalHp / Math.max(totalMaxHp, 1);

    const powerRatio = aliveMembers.length / Math.max(enemies.length, 1);

    let newStrategy: SquadStrategy | null = null;

    if (squadHpRatio < 0.3 && this.data.strategy !== 'retreat') {
      newStrategy = 'retreat';
    } else if (powerRatio > 2 && squadHpRatio > 0.6 && this.data.strategy === 'defend') {
      newStrategy = 'assault';
    } else if (powerRatio < 0.5 && squadHpRatio < 0.7 && this.data.strategy === 'assault') {
      newStrategy = 'defend';
    } else if (enemies.length > 3 && powerRatio > 1 && squadHpRatio > 0.5) {
      if (this.data.strategy === 'defend') {
        newStrategy = 'skirmish';
      }
    }

    if (newStrategy && newStrategy !== this.data.strategy) {
      this.setStrategy(newStrategy, allUnits, '自动调整');
    }
  }

  private coordsEqual(a: CubeCoords, b: CubeCoords): boolean {
    return a.q === b.q && a.r === b.r && a.s === b.s;
  }

  assignAIRoles(allUnits: Map<ID, CombatUnit>): Map<ID, AIRole> {
    const roles = new Map<ID, AIRole>();
    const aliveMembers = this.getAliveMembers(allUnits);

    for (const unit of aliveMembers) {
      const role = this.detectUnitAIRole(unit);
      roles.set(unit.id, role);
    }

    return roles;
  }

  private detectUnitAIRole(unit: CombatUnit): AIRole {
    const hasHealSkill = unit.skills.some(s =>
      s.type === 'active' &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'heal')
    );
    if (hasHealSkill) return 'healer';

    const defenseScore = unit.stats.defense + unit.stats.magicDefense;
    const attackScore = unit.stats.attack + unit.stats.magicAttack;
    const hasTaunt = unit.skills.some(s => s.tags.includes('taunt'));
    if ((defenseScore > attackScore * 1.2 && unit.stats.maxHp > 100) || hasTaunt) {
      return 'tank';
    }

    const buffCount = unit.skills.filter(s =>
      s.type === 'active' &&
      s.canTargetAlly &&
      s.effects.some(e => e.type === 'buff')
    ).length;
    if (buffCount >= 2) return 'support';

    if (unit.stats.moveRange >= 5 && unit.stats.visionRange >= 6) {
      return 'scout';
    }

    if (unit.stats.attackRange > 1) return 'ranged';

    const hasRangedSkill = unit.skills.some(s =>
      s.type === 'active' && s.range.max > 1
    );
    if (hasRangedSkill) return 'ranged';

    return 'melee';
  }

  setFocusTarget(targetId: ID): void {
    this.data.focusTargetId = targetId;
  }

  getFocusTarget(): ID | undefined {
    return this.data.focusTargetId;
  }

  clearFocusTarget(): void {
    this.data.focusTargetId = undefined;
  }

  autoSelectFocusTarget(
    allUnits: Map<ID, CombatUnit>,
    threatMap?: Map<ID, number>
  ): ID | null {
    const enemies = this.getEnemies(allUnits);
    if (enemies.length === 0) return null;

    let bestTarget: CombatUnit | null = null;
    let bestScore = -Infinity;

    const aliveMembers = this.getAliveMembers(allUnits);

    for (const enemy of enemies) {
      let score = 0;

      const hpRatio = enemy.stats.hp / Math.max(enemy.stats.maxHp, 1);
      score += (1 - hpRatio) * 40;

      if (threatMap?.has(enemy.id)) {
        score += threatMap.get(enemy.id)! * 0.4;
      }

      let reachableCount = 0;
      for (const member of aliveMembers) {
        const dist = cubeDistance(member.coords, enemy.coords);
        const totalReach = member.stats.moveRange + member.stats.attackRange;
        if (dist <= totalReach) {
          reachableCount++;
        }
      }
      score += reachableCount * 15;

      if (enemy.castingSkill) score += 30;

      const hasHealSkill = enemy.skills.some(s =>
        s.tags.includes('heal') || s.effects.some(e => e.type === 'heal')
      );
      if (hasHealSkill) score += 25;

      if (score > bestScore) {
        bestScore = score;
        bestTarget = enemy;
      }
    }

    if (bestTarget) {
      this.setFocusTarget(bestTarget.id);
      return bestTarget.id;
    }

    return null;
  }

  planTacticalFormation(
    allUnits: Map<ID, CombatUnit>,
    targetPosition?: CubeCoords,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): MovementPlan[] {
    const plans: MovementPlan[] = [];
    const aliveMembers = this.getAliveMembers(allUnits);
    const roles = this.assignAIRoles(allUnits);

    if (aliveMembers.length === 0) return plans;

    const enemies = this.getEnemies(allUnits);
    const enemyCenter = enemies.length > 0
      ? this.calculateSquadCenter(enemies)
      : (targetPosition ?? { q: 0, r: 0, s: 0 });

    const tanks = aliveMembers.filter(u => roles.get(u.id) === 'tank');
    const healers = aliveMembers.filter(u => roles.get(u.id) === 'healer');
    const ranged = aliveMembers.filter(u => roles.get(u.id) === 'ranged');
    const meleeUnits = aliveMembers.filter(u => roles.get(u.id) === 'melee');
    const supports = aliveMembers.filter(u => roles.get(u.id) === 'support');
    const scouts = aliveMembers.filter(u => roles.get(u.id) === 'scout');

    for (const tank of tanks) {
      const targetPos = this.findTankPosition(tank, enemyCenter, allUnits, getTileHeight, isPassable);
      plans.push({
        unitId: tank.id,
        targetCoords: targetPos,
        priority: 100,
        reason: '坦克前排站位',
      });
    }

    for (const melee of meleeUnits) {
      const targetPos = this.findMeleePosition(melee, enemyCenter, allUnits, getTileHeight, isPassable);
      plans.push({
        unitId: melee.id,
        targetCoords: targetPos,
        priority: 90,
        reason: '近战输出站位',
      });
    }

    for (const rangedUnit of ranged) {
      const targetPos = this.findRangedPosition(rangedUnit, enemyCenter, allUnits, getTileHeight, isPassable);
      plans.push({
        unitId: rangedUnit.id,
        targetCoords: targetPos,
        priority: 80,
        reason: '远程输出站位',
      });
    }

    for (const healer of healers) {
      const targetPos = this.findHealerPosition(healer, tanks, allUnits, getTileHeight, isPassable);
      plans.push({
        unitId: healer.id,
        targetCoords: targetPos,
        priority: 85,
        reason: '治疗后排站位',
      });
    }

    for (const support of supports) {
      const targetPos = this.findSupportPosition(support, aliveMembers, allUnits, getTileHeight, isPassable);
      plans.push({
        unitId: support.id,
        targetCoords: targetPos,
        priority: 75,
        reason: '辅助保护站位',
      });
    }

    for (const scout of scouts) {
      const targetPos = this.findScoutPosition(scout, enemyCenter, allUnits, getTileHeight, isPassable);
      plans.push({
        unitId: scout.id,
        targetCoords: targetPos,
        priority: 70,
        reason: '侦查侧翼站位',
      });
    }

    return plans.sort((a, b) => b.priority - a.priority);
  }

  private findTankPosition(
    unit: CombatUnit,
    enemyCenter: CubeCoords,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    const direction = {
      q: Math.sign(enemyCenter.q - unit.coords.q),
      r: Math.sign(enemyCenter.r - unit.coords.r),
      s: Math.sign(enemyCenter.s - unit.coords.s),
    };

    let bestPos = unit.coords;
    let bestScore = -Infinity;

    const searchRange = unit.stats.moveRange;
    for (let dq = -searchRange; dq <= searchRange; dq++) {
      for (let dr = -searchRange; dr <= searchRange; dr++) {
        const ds = -dq - dr;
        if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > searchRange * 2) continue;

        const pos = {
          q: unit.coords.q + dq,
          r: unit.coords.r + dr,
          s: unit.coords.s + ds,
        };

        if (isPassable && !isPassable(pos)) continue;

        let score = 0;

        const distToEnemy = cubeDistance(pos, enemyCenter);
        score -= distToEnemy * 5;

        if (getTileHeight) {
          score += getTileHeight(pos) * 15;
        }

        const frontBonus = (direction.q * dq + direction.r * dr + direction.s * ds);
        score += frontBonus * 3;

        if (score > bestScore) {
          bestScore = score;
          bestPos = pos;
        }
      }
    }

    return bestPos;
  }

  private findMeleePosition(
    unit: CombatUnit,
    enemyCenter: CubeCoords,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    const searchRange = unit.stats.moveRange;
    for (let dq = -searchRange; dq <= searchRange; dq++) {
      for (let dr = -searchRange; dr <= searchRange; dr++) {
        const ds = -dq - dr;
        if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > searchRange * 2) continue;

        const pos = {
          q: unit.coords.q + dq,
          r: unit.coords.r + dr,
          s: unit.coords.s + ds,
        };

        if (isPassable && !isPassable(pos)) continue;

        let score = 0;

        const distToEnemy = cubeDistance(pos, enemyCenter);
        if (distToEnemy <= unit.stats.attackRange + 1) {
          score += 50;
        } else {
          score -= distToEnemy * 3;
        }

        if (getTileHeight) {
          score += getTileHeight(pos) * 10;
        }

        if (score > bestScore) {
          bestScore = score;
          bestPos = pos;
        }
      }
    }

    return bestPos;
  }

  private findRangedPosition(
    unit: CombatUnit,
    enemyCenter: CubeCoords,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    const optimalRange = unit.stats.attackRange * 0.8;
    const searchRange = unit.stats.moveRange;

    for (let dq = -searchRange; dq <= searchRange; dq++) {
      for (let dr = -searchRange; dr <= searchRange; dr++) {
        const ds = -dq - dr;
        if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > searchRange * 2) continue;

        const pos = {
          q: unit.coords.q + dq,
          r: unit.coords.r + dr,
          s: unit.coords.s + ds,
        };

        if (isPassable && !isPassable(pos)) continue;

        let score = 0;

        const distToEnemy = cubeDistance(pos, enemyCenter);
        const rangeDiff = Math.abs(distToEnemy - optimalRange);
        score -= rangeDiff * 5;

        if (distToEnemy <= unit.stats.attackRange) {
          score += 40;
        }

        if (getTileHeight) {
          score += getTileHeight(pos) * 20;
        }

        if (score > bestScore) {
          bestScore = score;
          bestPos = pos;
        }
      }
    }

    return bestPos;
  }

  private findHealerPosition(
    unit: CombatUnit,
    tanks: CombatUnit[],
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    const allies = this.getAliveMembers(allUnits).filter(u => u.id !== unit.id);
    const allyCenter = allies.length > 0
      ? this.calculateSquadCenter(allies)
      : unit.coords;

    let bestPos = unit.coords;
    let bestScore = -Infinity;

    const searchRange = unit.stats.moveRange;

    for (let dq = -searchRange; dq <= searchRange; dq++) {
      for (let dr = -searchRange; dr <= searchRange; dr++) {
        const ds = -dq - dr;
        if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > searchRange * 2) continue;

        const pos = {
          q: unit.coords.q + dq,
          r: unit.coords.r + dr,
          s: unit.coords.s + ds,
        };

        if (isPassable && !isPassable(pos)) continue;

        let score = 0;

        let alliesInRange = 0;
        for (const ally of allies) {
          const dist = cubeDistance(pos, ally.coords);
          if (dist <= unit.stats.attackRange) {
            alliesInRange++;
          }
        }
        score += alliesInRange * 20;

        const enemies = this.getEnemies(allUnits);
        let minEnemyDist = Infinity;
        for (const enemy of enemies) {
          const dist = cubeDistance(pos, enemy.coords);
          if (dist < minEnemyDist) minEnemyDist = dist;
        }
        score += minEnemyDist * 3;

        if (getTileHeight) {
          score += getTileHeight(pos) * 8;
        }

        if (score > bestScore) {
          bestScore = score;
          bestPos = pos;
        }
      }
    }

    return bestPos;
  }

  private findSupportPosition(
    unit: CombatUnit,
    allies: CombatUnit[],
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    const allyCenter = allies.length > 0
      ? this.calculateSquadCenter(allies.filter(a => a.id !== unit.id))
      : unit.coords;

    let bestPos = unit.coords;
    let bestScore = -Infinity;

    const searchRange = unit.stats.moveRange;

    for (let dq = -searchRange; dq <= searchRange; dq++) {
      for (let dr = -searchRange; dr <= searchRange; dr++) {
        const ds = -dq - dr;
        if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > searchRange * 2) continue;

        const pos = {
          q: unit.coords.q + dq,
          r: unit.coords.r + dr,
          s: unit.coords.s + ds,
        };

        if (isPassable && !isPassable(pos)) continue;

        let score = 0;

        const distToCenter = cubeDistance(pos, allyCenter);
        score -= distToCenter * 5;

        const enemies = this.getEnemies(allUnits);
        let minEnemyDist = Infinity;
        for (const enemy of enemies) {
          const dist = cubeDistance(pos, enemy.coords);
          if (dist < minEnemyDist) minEnemyDist = dist;
        }
        score += minEnemyDist * 2;

        if (getTileHeight) {
          score += getTileHeight(pos) * 10;
        }

        if (score > bestScore) {
          bestScore = score;
          bestPos = pos;
        }
      }
    }

    return bestPos;
  }

  private findScoutPosition(
    unit: CombatUnit,
    enemyCenter: CubeCoords,
    allUnits: Map<ID, CombatUnit>,
    getTileHeight?: (coords: CubeCoords) => number,
    isPassable?: (coords: CubeCoords) => boolean
  ): CubeCoords {
    let bestPos = unit.coords;
    let bestScore = -Infinity;

    const searchRange = unit.stats.moveRange;

    for (let dq = -searchRange; dq <= searchRange; dq++) {
      for (let dr = -searchRange; dr <= searchRange; dr++) {
        const ds = -dq - dr;
        if (Math.abs(dq) + Math.abs(dr) + Math.abs(ds) > searchRange * 2) continue;

        const pos = {
          q: unit.coords.q + dq,
          r: unit.coords.r + dr,
          s: unit.coords.s + ds,
        };

        if (isPassable && !isPassable(pos)) continue;

        let score = 0;

        const distToEnemy = cubeDistance(pos, enemyCenter);
        score += distToEnemy * 2;

        if (getTileHeight) {
          score += getTileHeight(pos) * 25;
        }

        if (score > bestScore) {
          bestScore = score;
          bestPos = pos;
        }
      }
    }

    return bestPos;
  }

  getCurrentPhase(): number {
    return this.data.currentPhase ?? 0;
  }

  updateBossPhase(bossUnit: CombatUnit): number {
    const hpRatio = bossUnit.stats.hp / Math.max(bossUnit.stats.maxHp, 1);
    const thresholds = [0.8, 0.5, 0.3];
    let phase = 0;

    for (let i = thresholds.length - 1; i >= 0; i--) {
      if (hpRatio <= thresholds[i]) {
        phase = i + 1;
        break;
      }
    }

    if (phase !== (this.data.currentPhase ?? 0)) {
      this.data.currentPhase = phase;
    }

    return phase;
  }

  getPhaseBehavior(
    bossConfig: BossAIConfig,
    currentHpRatio: number
  ): BossPhaseConfig | null {
    for (let i = bossConfig.phaseBehaviors.length - 1; i >= 0; i--) {
      if (currentHpRatio <= bossConfig.phaseThresholds[i]) {
        return bossConfig.phaseBehaviors[i];
      }
    }
    return null;
  }

  toJSON(): Record<string, unknown> {
    return {
      data: {
        ...this.data,
        memory: {
          lastSeenPositions: serializeMap(
            this.data.memory.lastSeenPositions,
            k => k as string
          ),
          knownEnemyPositions: serializeMap(
            this.data.memory.knownEnemyPositions,
            k => k as string
          ),
          observedSkills: Array.from(this.data.memory.observedSkills.entries()).map(
            ([k, v]) => ({ key: k, value: Array.from(v) })
          ),
          allyPositions: serializeMap(
            this.data.memory.allyPositions,
            k => k as string
          ),
          killCount: this.data.memory.killCount,
          damageDealt: this.data.memory.damageDealt,
          damageTaken: this.data.memory.damageTaken,
          predictions: serializeMap(
            this.data.memory.predictions,
            k => k as string
          ),
        },
        unitAIs: serializeMap(this.data.unitAIs, k => k as string),
      },
      assignedRoles: serializeMap(this.assignedRoles, k => k as string),
      rolePriorities: serializeMap(this.rolePriorities, k => k as string),
      currentTurn: this.currentTurn,
      lastUpdateTurn: this.lastUpdateTurn,
      strategyHistory: this.strategyHistory,
    };
  }

  fromJSON(data: Record<string, unknown>): void {
    if (data.data) {
      const d = data.data as Record<string, unknown>;
      this.data = {
        id: d.id as ID,
        faction: d.faction as Faction,
        members: d.members as ID[],
        commanderId: d.commanderId as ID | undefined,
        strategy: d.strategy as SquadStrategy,
        targetPriority: d.targetPriority as ID[],
        waypoints: d.waypoints as CubeCoords[],
        formation: d.formation as SquadFormation,
        memory: this.deserializeMemory(d.memory as Record<string, unknown>),
        unitAIs: deserializeMap(
          (d.unitAIs as Array<{ key: string; value: AIProfile }>) ?? [],
          k => k as ID
        ),
      };
    }
    if (data.assignedRoles) {
      this.assignedRoles = deserializeMap(
        data.assignedRoles as Array<{ key: string; value: SquadRole }>,
        k => k as ID
      );
    }
    if (data.rolePriorities) {
      this.rolePriorities = deserializeMap(
        data.rolePriorities as Array<{ key: string; value: number }>,
        k => k as ID
      );
    }
    if (typeof data.currentTurn === 'number') {
      this.currentTurn = data.currentTurn;
    }
    if (typeof data.lastUpdateTurn === 'number') {
      this.lastUpdateTurn = data.lastUpdateTurn;
    }
    if (Array.isArray(data.strategyHistory)) {
      this.strategyHistory = data.strategyHistory as typeof this.strategyHistory;
    }
  }

  private deserializeMemory(data: Record<string, unknown>): AIMemory {
    return {
      lastSeenPositions: deserializeMap(
        (data.lastSeenPositions as Array<{ key: string; value: { coords: CubeCoords; turn: number } }>) ?? [],
        k => k as ID
      ),
      knownEnemyPositions: deserializeMap(
        (data.knownEnemyPositions as Array<{ key: string; value: CubeCoords }>) ?? [],
        k => k as ID
      ),
      observedSkills: new Map(
        ((data.observedSkills as Array<{ key: string; value: string[] }>) ?? []).map(
          ({ key, value }) => [key as ID, new Set(value)]
        )
      ),
      allyPositions: deserializeMap(
        (data.allyPositions as Array<{ key: string; value: CubeCoords }>) ?? [],
        k => k as ID
      ),
      killCount: (data.killCount as number) ?? 0,
      damageDealt: (data.damageDealt as number) ?? 0,
      damageTaken: (data.damageTaken as number) ?? 0,
      predictions: deserializeMap(
        (data.predictions as Array<{ key: string; value: { expectedPosition: CubeCoords; confidence: number } }>) ?? [],
        k => k as ID
      ),
    };
  }
}
