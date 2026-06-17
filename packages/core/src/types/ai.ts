import type { ID, Faction } from './common';
import type { CubeCoords } from './grid';
import type { CombatUnit } from './combat';

export type AIType = 'aggressive' | 'defensive' | 'supportive' | 'balanced' | 'custom';

export interface AIProfile {
  id: ID;
  name: string;
  type: AIType;
  description: string;
  aggression: number;
  defensiveness: number;
  supportiveness: number;
  caution: number;
  parameters: AIParameters;
  behaviorTree?: BehaviorTreeNode;
}

export interface AIParameters {
  threatWeight: {
    damage: number;
    proximity: number;
    hpPercentage: number;
    statusEffects: number;
    isHealer: number;
    isCaster: number;
  };
  targetWeight: {
    lowestHp: number;
    highestThreat: number;
    closest: number;
    isolated: number;
    taunted: number;
  };
  positioning: {
    preferHighGround: number;
    preferCover: number;
    maintainDistance: number;
    avoidClustering: number;
    flankPriority: number;
  };
  skillSelection: {
    preferAoe: number;
    preferBuff: number;
    preferDebuff: number;
    preferHeal: number;
    finishThreshold: number;
  };
  riskAssessment: {
    abandonLowHpAlly: number;
    pursueLowHpEnemy: number;
    overextendPenalty: number;
    hpSafetyThreshold: number;
  };
}

export interface AIDecision {
  unitId: ID;
  action: 'move' | 'attack' | 'skill' | 'wait' | 'delay' | 'interact';
  targetCoords?: CubeCoords;
  targetUnitId?: ID;
  skillId?: ID;
  confidence: number;
  reasoning: string;
  alternatives: AIDecision[];
}

export interface ThreatAssessment {
  sourceUnitId: ID;
  targetUnitId: ID;
  threatLevel: number;
  components: {
    damageThreat: number;
    positionalThreat: number;
    statusThreat: number;
    priorityThreat: number;
  };
  canAttack: boolean;
  canReach: number;
  estimatedDamage: number;
}

export interface PositionEvaluation {
  coords: CubeCoords;
  score: number;
  factors: {
    coverBonus: number;
    heightBonus: number;
    visionBonus: number;
    movementCost: number;
    threatExposure: number;
    attackOpportunities: number;
    supportOpportunities: number;
    distanceToTarget: number;
  };
  reachableUnits: ID[];
  visibleUnits: ID[];
}

export type BehaviorTreeNodeType = 
  | 'selector' 
  | 'sequence' 
  | 'parallel' 
  | 'decorator' 
  | 'condition' 
  | 'action'
  | 'inverter'
  | 'repeat'
  | 'untilFail'
  | 'wait';

export interface BehaviorTreeNode {
  id: ID;
  type: BehaviorTreeNodeType;
  name: string;
  children?: BehaviorTreeNode[];
  condition?: (context: AIContext) => boolean;
  action?: (context: AIContext) => BehaviorResult;
  decorator?: {
    type: 'inverter' | 'repeat' | 'untilFail' | 'wait';
    count?: number;
    waitTime?: number;
  };
  parallelConfig?: {
    successThreshold: number;
    failureThreshold: number;
  };
}

export type BehaviorResult = 'success' | 'failure' | 'running';

export interface AIContext {
  unit: CombatUnit;
  allUnits: Map<ID, CombatUnit>;
  grid: unknown;
  currentTurn: number;
  memory: Map<string, unknown>;
  globalMemory: Map<string, unknown>;
  threatMap: Map<ID, number>;
  lastDecision?: AIDecision;
}

export interface AIMemory {
  lastSeenPositions: Map<ID, { coords: CubeCoords; turn: number }>;
  knownEnemyPositions: Map<ID, CubeCoords>;
  observedSkills: Map<ID, Set<ID>>;
  allyPositions: Map<ID, CubeCoords>;
  killCount: number;
  damageDealt: number;
  damageTaken: number;
  predictions: Map<ID, { expectedPosition: CubeCoords; confidence: number }>;
}

export interface SquadAI {
  id: ID;
  faction: Faction;
  members: ID[];
  commanderId?: ID;
  strategy: 'assault' | 'defend' | 'flank' | 'harass' | 'retreat' | 'skirmish' | 'balanced';
  targetPriority: ID[];
  waypoints: CubeCoords[];
  formation: 'line' | 'column' | 'wedge' | 'circle' | 'loose';
  memory: AIMemory;
  unitAIs: Map<ID, AIProfile>;
}
