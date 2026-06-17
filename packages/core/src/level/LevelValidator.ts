import type {
  LevelConfig,
  VictoryCondition,
  CubeCoords,
  Faction,
  CombatUnit,
  ID,
  HexGridConfig,
  TerrainType
} from '../types';
import { HexGrid } from '../grid/HexGrid';
import { Pathfinder } from '../grid/Pathfinding';
import { cubeKey, cubeEquals, cubeDistance } from '../grid/coords';

export type ValidationSeverity = 'error' | 'warning' | 'info';

export interface ValidationIssue {
  id: ID;
  severity: ValidationSeverity;
  category: string;
  message: string;
  details?: Record<string, unknown>;
  suggestion?: string;
}

export interface ValidationReport {
  isValid: boolean;
  totalErrors: number;
  totalWarnings: number;
  totalInfos: number;
  issues: ValidationIssue[];
  timestamp: number;
  levelId?: ID;
  levelName?: string;
  summary: string;
}

export interface ReachabilityResult {
  start: CubeCoords;
  goal: CubeCoords;
  reachable: boolean;
  distance: number;
  pathCost: number;
  path?: CubeCoords[];
}

export class LevelValidator {
  private issues: ValidationIssue[] = [];

  constructor() {}

  validateReachability(
    grid: HexGrid,
    start: CubeCoords,
    goal: CubeCoords,
    maxMovePoints: number = Infinity
  ): ReachabilityResult {
    const pathfinder = new Pathfinder(grid);
    const result = pathfinder.findPath(start, goal, maxMovePoints, { ignoreUnits: true });

    return {
      start: { ...start },
      goal: { ...goal },
      reachable: result.reachable,
      distance: cubeDistance(start, goal),
      pathCost: result.totalCost,
      path: result.path.length > 0 ? result.path.map(p => ({ ...p })) : undefined
    };
  }

  validateStartingPositions(
    config: LevelConfig,
    grid: HexGrid
  ): {
    valid: boolean;
    overlapping: Array<{ faction: Faction; position: CubeCoords; units: ID[] }>;
    invalidTerrain: Array<{ faction: Faction; position: CubeCoords; terrain: TerrainType }>;
    outOfBounds: Array<{ faction: Faction; position: CubeCoords }>;
  } {
    const overlapping: Array<{ faction: Faction; position: CubeCoords; units: ID[] }> = [];
    const invalidTerrain: Array<{ faction: Faction; position: CubeCoords; terrain: TerrainType }> = [];
    const outOfBounds: Array<{ faction: Faction; position: CubeCoords }> = [];
    const positionMap = new Map<string, Array<{ faction: Faction; unitId: ID }>>();

    for (const [faction, factionConfig] of Object.entries(config.factions)) {
      factionConfig.startingPositions.forEach((position, index) => {
        const unitId = factionConfig.units[index];
        if (!position || !unitId) return;

        if (!grid.hasTile(position)) {
          outOfBounds.push({ faction: faction as Faction, position });
          return;
        }

        const tile = grid.getTile(position)!;
        if (grid.blocksMovement(position)) {
          invalidTerrain.push({ faction: faction as Faction, position, terrain: tile.terrain });
        }

        const key = cubeKey(position);
        if (!positionMap.has(key)) {
          positionMap.set(key, []);
        }
        positionMap.get(key)!.push({ faction: faction as Faction, unitId });
      });
    }

    for (const [key, entries] of positionMap.entries()) {
      if (entries.length > 1) {
        const factions = new Set(entries.map(e => e.faction));
        const allUnits = entries.map(e => e.unitId);
        overlapping.push({
          faction: Array.from(factions).join(',') as Faction,
          position: entries[0].faction === entries[0].faction ? 
            (config.factions[entries[0].faction]?.startingPositions.find(p => cubeKey(p) === key) ?? entries[0].faction === entries[0].faction ? 
              Object.values(config.factions).flatMap(fc => fc.startingPositions).find(p => cubeKey(p) === key) ?? { q: 0, r: 0, s: 0 } : 
              { q: 0, r: 0, s: 0 }) : 
            { q: 0, r: 0, s: 0 },
          units: allUnits
        });
      }
    }

    let valid = true;
    const positionEntries = Array.from(positionMap.entries());
    for (const [, entries] of positionEntries) {
      if (entries.length > 1) {
        valid = false;
        break;
      }
    }
    if (invalidTerrain.length > 0 || outOfBounds.length > 0) {
      valid = false;
    }

    const firstOverlap = overlapping[0];
    if (firstOverlap) {
      const pos = Object.values(config.factions)
        .flatMap(fc => fc.startingPositions)
        .find(p => {
          const matches = overlapping.filter(o => {
            const posUnits = new Set(entriesForPos(config, p).map(e => e.unitId));
            return o.units.every(u => posUnits.has(u)) && o.units.length === posUnits.size;
          });
          return matches.length > 0;
        });
      if (pos && overlapping[0]) {
        overlapping[0].position = pos;
      }
    }

    return { valid, overlapping, invalidTerrain, outOfBounds };
  }

  validateVictoryConditions(config: LevelConfig): {
    valid: boolean;
    unreachable: VictoryCondition[];
    contradictory: Array<[VictoryCondition, VictoryCondition]>;
    missingParams: VictoryCondition[];
    noConditions: boolean;
  } {
    const unreachable: VictoryCondition[] = [];
    const contradictory: Array<[VictoryCondition, VictoryCondition]> = [];
    const missingParams: VictoryCondition[] = [];

    const allConditions: Array<VictoryCondition & { _isVictory?: boolean }> = [
      ...config.victoryConditions.map(vc => ({ ...vc, _isVictory: true })),
      ...config.defeatConditions.map(dc => ({ ...dc, _isVictory: false }))
    ];

    for (const condition of allConditions) {
      if (!this.isConditionReachable(condition, config)) {
        unreachable.push({ ...condition });
      }
      if (this.isConditionMissingParams(condition)) {
        missingParams.push({ ...condition });
      }
    }

    for (let i = 0; i < allConditions.length; i++) {
      for (let j = i + 1; j < allConditions.length; j++) {
        if (this.areConditionsContradictory(allConditions[i], allConditions[j])) {
          contradictory.push([{ ...allConditions[i] }, { ...allConditions[j] }]);
        }
      }
    }

    const noConditions = config.victoryConditions.length === 0 && config.defeatConditions.length === 0;

    let valid = true;
    if (unreachable.length > 0 || contradictory.length > 0 || missingParams.length > 0 || noConditions) {
      valid = false;
    }

    return { valid, unreachable, contradictory, missingParams, noConditions };
  }

  validateFactions(config: LevelConfig): {
    valid: boolean;
    emptyFactions: Faction[];
    mismatchedPositions: Array<{ faction: Faction; unitCount: number; positionCount: number }>;
    duplicateUnits: Array<{ faction: Faction; unitId: ID; count: number }>;
    noPlayerFaction: boolean;
    noEnemyFaction: boolean;
  } {
    const emptyFactions: Faction[] = [];
    const mismatchedPositions: Array<{ faction: Faction; unitCount: number; positionCount: number }> = [];
    const duplicateUnits: Array<{ faction: Faction; unitId: ID; count: number }> = [];
    let noPlayerFaction = true;
    let noEnemyFaction = true;

    const allUnitIds = new Set<ID>();

    for (const [faction, factionConfig] of Object.entries(config.factions)) {
      if (faction === 'player') noPlayerFaction = false;
      if (faction === 'enemy') noEnemyFaction = false;

      if (factionConfig.units.length === 0) {
        emptyFactions.push(faction as Faction);
      }

      if (factionConfig.units.length !== factionConfig.startingPositions.length) {
        mismatchedPositions.push({
          faction: faction as Faction,
          unitCount: factionConfig.units.length,
          positionCount: factionConfig.startingPositions.length
        });
      }

      const unitCountMap = new Map<ID, number>();
      for (const unitId of factionConfig.units) {
        unitCountMap.set(unitId, (unitCountMap.get(unitId) ?? 0) + 1);
        allUnitIds.add(unitId);
      }
      for (const [unitId, count] of unitCountMap.entries()) {
        if (count > 1) {
          duplicateUnits.push({ faction: faction as Faction, unitId, count });
        }
      }
    }

    let valid = true;
    if (emptyFactions.length > 0 || mismatchedPositions.length > 0 || 
        duplicateUnits.length > 0 || noPlayerFaction || noEnemyFaction) {
      valid = false;
    }

    return { valid, emptyFactions, mismatchedPositions, duplicateUnits, noPlayerFaction, noEnemyFaction };
  }

  getValidationReport(
    config: LevelConfig,
    gridConfig?: HexGridConfig
  ): ValidationReport {
    this.issues = [];

    const grid = gridConfig ? new HexGrid(gridConfig) : new HexGrid({
      width: 20,
      height: 15,
      orientation: 'pointy',
      defaultTerrain: 'plain',
      tileSize: 32
    });

    this.validateFactionsReport(config);
    this.validateStartingPositionsReport(config, grid);
    this.validateVictoryConditionsReport(config);
    this.validateReinforcementsReport(config, grid);
    this.validateMapReachabilityReport(config, grid);
    this.validateTurnLimitReport(config);

    const totalErrors = this.issues.filter(i => i.severity === 'error').length;
    const totalWarnings = this.issues.filter(i => i.severity === 'warning').length;
    const totalInfos = this.issues.filter(i => i.severity === 'info').length;

    const summary = this.generateSummary(totalErrors, totalWarnings, totalInfos);

    return {
      isValid: totalErrors === 0,
      totalErrors,
      totalWarnings,
      totalInfos,
      issues: [...this.issues],
      timestamp: Date.now(),
      levelId: config.id,
      levelName: config.name,
      summary
    };
  }

  private validateFactionsReport(config: LevelConfig): void {
    const result = this.validateFactions(config);

    for (const faction of result.emptyFactions) {
      this.addIssue({
        severity: 'warning',
        category: 'factions',
        message: `阵营 ${faction} 没有配置任何单位`,
        details: { faction },
        suggestion: '为该阵营添加至少一个单位，或移除此阵营配置'
      });
    }

    for (const mismatch of result.mismatchedPositions) {
      this.addIssue({
        severity: 'error',
        category: 'factions',
        message: `阵营 ${mismatch.faction} 的单位数量(${mismatch.unitCount})与起始位置数量(${mismatch.positionCount})不匹配`,
        details: mismatch,
        suggestion: '确保每个单位都有对应的起始位置'
      });
    }

    for (const dup of result.duplicateUnits) {
      this.addIssue({
        severity: 'warning',
        category: 'factions',
        message: `阵营 ${dup.faction} 中单位模板 ${dup.unitId} 出现了 ${dup.count} 次`,
        details: dup,
        suggestion: '如果不是刻意设计，请移除重复的单位'
      });
    }

    if (result.noPlayerFaction) {
      this.addIssue({
        severity: 'error',
        category: 'factions',
        message: '缺少 player 阵营配置',
        suggestion: '添加 player 阵营并配置至少一个单位'
      });
    }

    if (result.noEnemyFaction) {
      this.addIssue({
        severity: 'error',
        category: 'factions',
        message: '缺少 enemy 阵营配置',
        suggestion: '添加 enemy 阵营并配置至少一个单位'
      });
    }
  }

  private validateStartingPositionsReport(config: LevelConfig, grid: HexGrid): void {
    const result = this.validateStartingPositions(config, grid);

    for (const overlap of result.overlapping) {
      this.addIssue({
        severity: 'error',
        category: 'positions',
        message: `位置 (${overlap.position.q},${overlap.position.r},${overlap.position.s}) 有多个单位: ${overlap.units.join(', ')}`,
        details: overlap,
        suggestion: '为每个单位分配唯一的起始位置'
      });
    }

    for (const invalid of result.invalidTerrain) {
      this.addIssue({
        severity: 'error',
        category: 'positions',
        message: `阵营 ${invalid.faction} 的单位位置 (${invalid.position.q},${invalid.position.r},${invalid.position.s}) 地形 ${invalid.terrain} 不可行走`,
        details: invalid,
        suggestion: '将单位移动到可行走的地形上'
      });
    }

    for (const oob of result.outOfBounds) {
      this.addIssue({
        severity: 'error',
        category: 'positions',
        message: `阵营 ${oob.faction} 的单位位置 (${oob.position.q},${oob.position.r},${oob.position.s}) 超出地图边界`,
        details: oob,
        suggestion: '将单位位置调整到地图范围内'
      });
    }
  }

  private validateVictoryConditionsReport(config: LevelConfig): void {
    const result = this.validateVictoryConditions(config);

    if (result.noConditions) {
      this.addIssue({
        severity: 'error',
        category: 'victory',
        message: '关卡没有配置任何胜利或失败条件',
        suggestion: '添加至少一个胜利条件'
      });
    }

    for (const condition of result.unreachable) {
      this.addIssue({
        severity: 'warning',
        category: 'victory',
        message: `条件 "${condition.description}" (${condition.type}) 可能无法达成`,
        details: condition as unknown as Record<string, unknown>,
        suggestion: '检查条件参数是否合理'
      });
    }

    for (const [c1, c2] of result.contradictory) {
      this.addIssue({
        severity: 'error',
        category: 'victory',
        message: `条件 "${c1.description}" 与 "${c2.description}" 存在矛盾`,
        details: { 
          condition1: c1 as unknown as Record<string, unknown>, 
          condition2: c2 as unknown as Record<string, unknown> 
        },
        suggestion: '修改其中一个条件的目标或参数'
      });
    }

    for (const condition of result.missingParams) {
      this.addIssue({
        severity: 'error',
        category: 'victory',
        message: `条件 "${condition.description}" (${condition.type}) 缺少必要参数`,
        details: condition as unknown as Record<string, unknown>,
        suggestion: '在 params 中添加该条件类型所需的参数'
      });
    }
  }

  private validateReinforcementsReport(config: LevelConfig, grid: HexGrid): void {
    if (!config.reinforcements || config.reinforcements.length === 0) {
      return;
    }

    for (const reinforcement of config.reinforcements) {
      if (reinforcement.unitIds.length !== reinforcement.positions.length) {
        this.addIssue({
          severity: 'error',
          category: 'reinforcements',
          message: `第 ${reinforcement.turn} 回合 ${reinforcement.faction} 增援: 单位数量(${reinforcement.unitIds.length})与位置数量(${reinforcement.positions.length})不匹配`,
          details: reinforcement,
          suggestion: '确保每个增援单位都有对应的位置'
        });
      }

      for (const pos of reinforcement.positions) {
        if (!grid.hasTile(pos)) {
          this.addIssue({
            severity: 'error',
            category: 'reinforcements',
            message: `第 ${reinforcement.turn} 回合增援位置 (${pos.q},${pos.r},${pos.s}) 超出地图边界`,
            details: { turn: reinforcement.turn, position: pos },
            suggestion: '将增援位置调整到地图范围内'
          });
        } else if (grid.blocksMovement(pos)) {
          this.addIssue({
            severity: 'warning',
            category: 'reinforcements',
            message: `第 ${reinforcement.turn} 回合增援位置 (${pos.q},${pos.r},${pos.s}) 地形不可行走，可能导致增援失败`,
            details: { turn: reinforcement.turn, position: pos },
            suggestion: '将增援位置移动到可行走的地形上'
          });
        }
      }

      if (reinforcement.turn <= 0) {
        this.addIssue({
          severity: 'warning',
          category: 'reinforcements',
          message: `增援回合 ${reinforcement.turn} 无效`,
          details: reinforcement,
          suggestion: '增援回合应大于0'
        });
      }
    }
  }

  private validateMapReachabilityReport(config: LevelConfig, grid: HexGrid): void {
    const playerPositions = config.factions['player']?.startingPositions ?? [];
    const enemyPositions = config.factions['enemy']?.startingPositions ?? [];

    if (playerPositions.length === 0 || enemyPositions.length === 0) {
      return;
    }

    const pathfinder = new Pathfinder(grid);
    let anyReachable = false;

    for (const playerPos of playerPositions) {
      for (const enemyPos of enemyPositions) {
        const result = pathfinder.findPath(playerPos, enemyPos, Infinity, { ignoreUnits: true });
        if (result.reachable) {
          anyReachable = true;
          const reach = cubeDistance(playerPos, enemyPos);
          if (reach > 30) {
            this.addIssue({
              severity: 'warning',
              category: 'map',
              message: `玩家与敌军初始位置距离过远(${reach}格)，可能导致战斗节奏过慢`,
              details: { playerPosition: playerPos, enemyPosition: enemyPos, distance: reach },
              suggestion: '考虑缩小双方起始位置的距离'
            });
          }
          break;
        }
      }
      if (anyReachable) break;
    }

    if (!anyReachable) {
      this.addIssue({
        severity: 'error',
        category: 'map',
        message: '玩家无法到达任何敌军位置，地图可能被完全阻隔',
        suggestion: '检查地图地形配置，确保存在可达路径'
      });
    }
  }

  private validateTurnLimitReport(config: LevelConfig): void {
    const surviveConditions = config.victoryConditions.filter(vc => vc.type === 'survive');
    const turnLimitDefeats = config.defeatConditions.filter(dc => dc.type === 'turnLimit');

    for (const survive of surviveConditions) {
      const turns = survive.params.turns as number;
      if (config.turnLimit && turns > config.turnLimit) {
        this.addIssue({
          severity: 'error',
          category: 'turn',
          message: `存活条件要求 ${turns} 回合，但回合限制仅为 ${config.turnLimit} 回合`,
          details: { surviveTurns: turns, turnLimit: config.turnLimit },
          suggestion: '增加回合限制或减少存活要求的回合数'
        });
      }
    }

    if (config.turnLimit !== undefined && config.turnLimit < 1) {
      this.addIssue({
        severity: 'error',
        category: 'turn',
        message: `回合限制 ${config.turnLimit} 无效`,
        suggestion: '回合限制应至少为1'
      });
    }

    const hasTurnLimit = config.turnLimit !== undefined || turnLimitDefeats.length > 0;
    if (!hasTurnLimit && surviveConditions.length === 0) {
      this.addIssue({
        severity: 'info',
        category: 'turn',
        message: '关卡未设置回合限制，理论上可能无限进行',
        suggestion: '考虑添加回合限制作为防卡死机制'
      });
    }
  }

  private isConditionReachable(condition: VictoryCondition, config: LevelConfig): boolean {
    switch (condition.type) {
      case 'eliminate': {
        const targetFaction = condition.params.targetFaction as Faction;
        return config.factions[targetFaction]?.units.length > 0;
      }
      case 'capture': {
        const positions = condition.params.positions as CubeCoords[] ?? [];
        return positions.length > 0;
      }
      case 'survive': {
        const turns = condition.params.turns as number;
        return turns !== undefined && turns > 0;
      }
      case 'turnLimit': {
        const limit = condition.params.limit as number;
        return limit !== undefined && limit > 0;
      }
      case 'custom':
      default:
        return true;
    }
  }

  private areConditionsContradictory(c1: VictoryCondition, c2: VictoryCondition): boolean {
    if (c1.type === 'survive' && c2.type === 'turnLimit') {
      const surviveTurns = c1.params.turns as number;
      const limitTurns = c2.params.limit as number;
      return surviveTurns > limitTurns;
    }
    if (c2.type === 'survive' && c1.type === 'turnLimit') {
      const surviveTurns = c2.params.turns as number;
      const limitTurns = c1.params.limit as number;
      return surviveTurns > limitTurns;
    }
    if (c1.type === 'eliminate' && c2.type === 'eliminate') {
      if (c1.targetFaction === c2.targetFaction) {
        const c1Victory = (c1 as VictoryCondition & { _isVictory?: boolean })._isVictory;
        const c2Victory = (c2 as VictoryCondition & { _isVictory?: boolean })._isVictory;
        return c1Victory !== c2Victory;
      }
    }
    return false;
  }

  private isConditionMissingParams(condition: VictoryCondition): boolean {
    switch (condition.type) {
      case 'eliminate':
        return condition.params.targetFaction === undefined;
      case 'capture':
        return !condition.params.positions || 
          !Array.isArray(condition.params.positions) || 
          (condition.params.positions as unknown[]).length === 0;
      case 'survive':
        return condition.params.turns === undefined;
      case 'turnLimit':
        return condition.params.limit === undefined;
      case 'custom':
      default:
        return false;
    }
  }

  private addIssue(issue: Omit<ValidationIssue, 'id'>): void {
    this.issues.push({
      ...issue,
      id: `issue_${this.issues.length + 1}_${Date.now().toString(36)}`
    });
  }

  private generateSummary(errors: number, warnings: number, infos: number): string {
    if (errors === 0 && warnings === 0 && infos === 0) {
      return '关卡校验通过，未发现任何问题。';
    }

    const parts: string[] = [];
    if (errors > 0) {
      parts.push(`${errors} 个错误`);
    }
    if (warnings > 0) {
      parts.push(`${warnings} 个警告`);
    }
    if (infos > 0) {
      parts.push(`${infos} 条提示`);
    }

    return `校验完成：发现${parts.join('、')}。${errors > 0 ? '请修复所有错误后再使用此关卡。' : ''}`;
  }

  toJSON(): Record<string, unknown> {
    return {
      issues: this.issues
    };
  }

  static fromJSON(data: Record<string, unknown>): LevelValidator {
    const validator = new LevelValidator();
    validator.issues = data.issues as ValidationIssue[];
    return validator;
  }
}

function entriesForPos(
  config: LevelConfig,
  pos: CubeCoords
): Array<{ faction: Faction; unitId: ID }> {
  const result: Array<{ faction: Faction; unitId: ID }> = [];
  for (const [faction, factionConfig] of Object.entries(config.factions)) {
    factionConfig.startingPositions.forEach((p, idx) => {
      if (cubeEquals(p, pos)) {
        result.push({ faction: faction as Faction, unitId: factionConfig.units[idx] });
      }
    });
  }
  return result;
}
