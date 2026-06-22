import type { 
  ID, 
  DamageType, 
  ElementType, 
  StatusEffectType, 
  SkillTargetType, 
  SkillEffectType,
  Attribute,
  Resource,
  DamageResistance,
  ElementAffinity,
  Direction,
  Faction
} from './common';
import type { CubeCoords } from './grid';
import type { BucketedStatusStore } from '../combat/BucketedStatusStore';

export interface UnitStats {
  maxHp: number;
  hp: number;
  maxMp: number;
  mp: number;
  attack: number;
  defense: number;
  magicAttack: number;
  magicDefense: number;
  speed: number;
  accuracy: number;
  evasion: number;
  critRate: number;
  critDamage: number;
  armorPenetration: number;
  moveRange: number;
  attackRange: number;
  visionRange: number;
  height: number;
}

export interface UnitAttributes {
  hp: Resource;
  mp: Resource;
  attack: Attribute;
  defense: Attribute;
  magicAttack: Attribute;
  magicDefense: Attribute;
  speed: Attribute;
  accuracy: Attribute;
  evasion: Attribute;
  critRate: Attribute;
  critDamage: Attribute;
  armorPenetration: Attribute;
  moveRange: Attribute;
  attackRange: Attribute;
  visionRange: Attribute;
}

export interface StatusEffect {
  id: ID;
  type: StatusEffectType;
  name: string;
  description: string;
  duration: number;
  maxDuration: number;
  tickInterval: number;
  lastTick: number;
  stackCount: number;
  maxStacks: number;
  source: ID;
  isDebuff: boolean;
  effects: StatusEffectData[];
  icon?: string;
}

export interface StatusEffectData {
  stat?: keyof UnitStats;
  value: number;
  modifierType: 'add' | 'multiply' | 'set';
  damageType?: DamageType;
  element?: ElementType;
}

export interface SkillEffect {
  type: SkillEffectType;
  target: SkillTargetType;
  value: number;
  damageType?: DamageType;
  element?: ElementType;
  statusEffect?: ID;
  statusDuration?: number;
  aoeRadius?: number;
  ignoreCover?: boolean;
  armorPenetration?: number;
  terrainType?: string;
}

export interface SkillRange {
  min: number;
  max: number;
  type: 'straight' | 'diagonal' | 'any' | 'line' | 'cone' | 'area';
  requiresLineOfSight: boolean;
}

export interface Skill {
  id: ID;
  name: string;
  description: string;
  icon?: string;
  type: 'active' | 'passive' | 'reaction' | 'aura';
  targetType: SkillTargetType;
  range: SkillRange;
  effects: SkillEffect[];
  cooldown: number;
  currentCooldown: number;
  mpCost: number;
  hpCost: number;
  castTime: number;
  isDelayed: boolean;
  triggerConditions?: TriggerCondition[];
  interruptPriority: number;
  element?: ElementType;
  damageType?: DamageType;
  canTargetSelf: boolean;
  canTargetAlly: boolean;
  canTargetEnemy: boolean;
  canTargetTerrain: boolean;
  tags: string[];
}

export interface TriggerCondition {
  type: 'onTurnStart' | 'onTurnEnd' | 'onDamageTaken' | 'onDamageDealt' 
    | 'onUnitMove' | 'onUnitDeath' | 'onStatusApplied' | 'onSkillCast'
    | 'onLowHp' | 'onAllyLowHp' | 'onAllyDeath' | 'custom';
  threshold?: number;
  targetFilter?: 'self' | 'ally' | 'enemy';
  customCondition?: string;
}

export interface PassiveSkill extends Skill {
  type: 'passive' | 'aura' | 'reaction';
  auraRadius?: number;
  isActive: boolean;
  appliedEffects: Map<ID, StatusEffect>;
}

export interface DelayedSkill {
  skillId: ID;
  casterId: ID;
  targetCoords?: CubeCoords;
  targetUnitId?: ID;
  remainingTurns: number;
  totalTurns: number;
  isInterruptible: boolean;
  castProgress: number;
}

export interface DamageInstance {
  sourceId: ID;
  targetId: ID;
  skillId?: ID;
  baseDamage: number;
  finalDamage: number;
  damageType: DamageType;
  element: ElementType;
  isCrit: boolean;
  isBlocked: boolean;
  isDodged: boolean;
  armorMitigation: number;
  resistanceMitigation: number;
  terrainBonus: number;
  elementBonus: number;
  position: CubeCoords;
  timestamp: number;
}

export interface HealInstance {
  sourceId: ID;
  targetId: ID;
  skillId?: ID;
  baseHeal: number;
  finalHeal: number;
  isCrit: boolean;
  isOverheal: boolean;
  overhealAmount: number;
  position: CubeCoords;
  timestamp: number;
}

export interface HitCalculationResult {
  hit: boolean;
  accuracy: number;
  evasion: number;
  finalHitChance: number;
  isGuaranteedHit: boolean;
  isGuaranteedMiss: boolean;
  terrainBonus: number;
  heightBonus: number;
  directionBonus: number;
}

export interface DamageCalculationConfig {
  baseFormula: string;
  critMultiplier: number;
  minDamage: number;
  maxDamage: number;
  elementAdvantageMultiplier: number;
  elementDisadvantageMultiplier: number;
  armorFormula: string;
  resistanceFormula: string;
  terrainBonusFormula: string;
  heightBonusPerLevel: number;
  directionBackDamageMultiplier: number;
  directionSideDamageMultiplier: number;
  directionFrontDamageMultiplier: number;
}

export interface ElementChart {
  [attacker: string]: {
    strong: string[];
    weak: string[];
  };
}

export interface CombatUnit {
  id: ID;
  name: string;
  faction: Faction;
  templateId: string;
  coords: CubeCoords;
  direction: Direction;
  stats: UnitStats;
  attributes: UnitAttributes;
  skills: Skill[];
  passiveSkills: PassiveSkill[];
  statusEffects: StatusEffect[];
  statusEffectStore?: BucketedStatusStore;
  resistances: DamageResistance[];
  affinities: ElementAffinity[];
  equipment: ID[];
  isAlive: boolean;
  hasActed: boolean;
  hasMoved: boolean;
  isDelaying: boolean;
  delayReason?: string;
  castingSkill?: DelayedSkill;
  tags: string[];
  aiProfile?: ID;
}

export interface CombatState {
  units: Map<ID, CombatUnit>;
  turnQueue: ID[];
  currentTurn: number;
  currentUnitId?: ID;
  phase: TurnPhase;
  combatLog: CombatLogEntry[];
  delayedSkills: DelayedSkill[];
  pendingReactions: ID[];
  damageConfig: DamageCalculationConfig;
  elementChart: ElementChart;
  statusEffectTemplates: Map<ID, StatusEffect>;
  skillTemplates: Map<ID, Skill>;
  isCombatActive: boolean;
  winner?: Faction;
}

export type TurnPhase = 'start' | 'action' | 'end';

export type CombatLogType = 'damage' | 'heal' | 'status' | 'move' | 'skill' | 'death' | 'turn' | 'phase' | 'damage_entity' | 'entity_trigger' | string;

export interface CombatLogEntry {
  type: CombatLogType;
  data: Record<string, unknown>;
  timestamp: number;
  turnNumber: number;
}
