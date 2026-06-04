import bondRadiusTableJson from './bond-radius-table.json';

export interface PairThreshold {
  distance: number;
  tolerance: number;
  description?: string;
}

export interface SpecialBondConfig {
  enabled: boolean;
  donorElements: string[];
  acceptorElements: string[];
  maxDistance: number;
  minAngle?: number;
  tolerance: number;
}

export interface BondRadiusTable {
  version: string;
  description: string;
  defaults: {
    tolerance: number;
    fallbackRadius: number;
    maxBondDistance: number;
  };
  elementRadii: Record<string, number>;
  pairSpecificThresholds: Record<string, PairThreshold>;
  specialBondTypes: {
    hydrogenBond: SpecialBondConfig;
    halogenBond: SpecialBondConfig & { enabled: boolean };
    piStacking: { enabled: boolean; maxDistance: number; tolerance: number };
  };
}

const _subscribers = new Set<(table: BondRadiusTable) => void>();
let _currentTable: BondRadiusTable = bondRadiusTableJson as BondRadiusTable;

function normalizePairKey(e1: string, e2: string): string {
  const sorted = [e1, e2].sort();
  return `${sorted[0]}-${sorted[1]}`;
}

export function getBondRadiusTable(): BondRadiusTable {
  return _currentTable;
}

export function getElementPairThreshold(
  e1: string,
  e2: string,
  table: BondRadiusTable = _currentTable
): { threshold: number; description?: string } {
  const key = normalizePairKey(e1, e2);
  const pairConfig = table.pairSpecificThresholds[key];

  if (pairConfig) {
    return {
      threshold: pairConfig.distance * pairConfig.tolerance,
      description: pairConfig.description,
    };
  }

  const r1 = table.elementRadii[e1] ?? table.defaults.fallbackRadius;
  const r2 = table.elementRadii[e2] ?? table.defaults.fallbackRadius;
  return {
    threshold: (r1 + r2) * table.defaults.tolerance,
  };
}

export function getPairSpecificConfig(
  e1: string,
  e2: string,
  table: BondRadiusTable = _currentTable
): PairThreshold | null {
  const key = normalizePairKey(e1, e2);
  return table.pairSpecificThresholds[key] ?? null;
}

export function getCovalentRadius(element: string, table: BondRadiusTable = _currentTable): number {
  return table.elementRadii[element] ?? table.defaults.fallbackRadius;
}

export async function loadBondRadiusTableFromJson(jsonString: string): Promise<BondRadiusTable> {
  const parsed = JSON.parse(jsonString);
  validateBondRadiusTable(parsed);
  return parsed as BondRadiusTable;
}

export async function loadBondRadiusTableFromUrl(url: string): Promise<BondRadiusTable> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to load bond radius table from ${url}: ${response.statusText}`);
  }
  const json = await response.json();
  validateBondRadiusTable(json);
  return json as BondRadiusTable;
}

export function setBondRadiusTable(table: BondRadiusTable): void {
  validateBondRadiusTable(table);
  _currentTable = table;
  _notifySubscribers();
}

export function reloadBondRadiusTable(): void {
  setBondRadiusTable(bondRadiusTableJson as BondRadiusTable);
}

export function validateBondRadiusTable(table: unknown): void {
  if (typeof table !== 'object' || table === null) {
    throw new Error('Bond radius table must be an object');
  }

  const t = table as Record<string, unknown>;

  if (typeof t.version !== 'string') {
    throw new Error('Bond radius table must have a string "version" field');
  }

  if (typeof t.defaults !== 'object' || t.defaults === null) {
    throw new Error('Bond radius table must have a "defaults" object');
  }

  const defaults = t.defaults as Record<string, unknown>;
  if (typeof defaults.tolerance !== 'number' || defaults.tolerance <= 0) {
    throw new Error('defaults.tolerance must be a positive number');
  }
  if (typeof defaults.fallbackRadius !== 'number' || defaults.fallbackRadius <= 0) {
    throw new Error('defaults.fallbackRadius must be a positive number');
  }
  if (typeof defaults.maxBondDistance !== 'number' || defaults.maxBondDistance <= 0) {
    throw new Error('defaults.maxBondDistance must be a positive number');
  }

  if (typeof t.elementRadii !== 'object' || t.elementRadii === null) {
    throw new Error('Bond radius table must have an "elementRadii" object');
  }

  const radii = t.elementRadii as Record<string, unknown>;
  for (const [el, r] of Object.entries(radii)) {
    if (typeof r !== 'number' || r <= 0) {
      throw new Error(`Invalid radius for element ${el}: must be a positive number`);
    }
  }

  if (typeof t.pairSpecificThresholds !== 'object' || t.pairSpecificThresholds === null) {
    throw new Error('Bond radius table must have a "pairSpecificThresholds" object');
  }

  const pairs = t.pairSpecificThresholds as Record<string, unknown>;
  for (const [key, value] of Object.entries(pairs)) {
    if (typeof value !== 'object' || value === null) {
      throw new Error(`Invalid pair threshold for ${key}: must be an object`);
    }
    const pt = value as Record<string, unknown>;
    if (typeof pt.distance !== 'number' || pt.distance <= 0) {
      throw new Error(`Pair threshold ${key} must have a positive "distance"`);
    }
    if (typeof pt.tolerance !== 'number' || pt.tolerance <= 0) {
      throw new Error(`Pair threshold ${key} must have a positive "tolerance"`);
    }
  }
}

export function subscribeToBondRadiusTableChanges(
  callback: (table: BondRadiusTable) => void
): () => void {
  _subscribers.add(callback);
  return () => {
    _subscribers.delete(callback);
  };
}

function _notifySubscribers(): void {
  for (const callback of _subscribers) {
    try {
      callback(_currentTable);
    } catch (e) {
      console.error('Error in bond radius table subscriber:', e);
    }
  }
}
