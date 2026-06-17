import type {
  LevelConfig,
  VictoryCondition,
  CubeCoords,
  Faction,
  ID
} from '../types';
import { toJSON, fromJSON, createChecksum } from '../utils/serialization';
import { generateId } from '../utils';

export interface SerializationOptions {
  prettyPrint?: boolean;
  includeMetadata?: boolean;
  validateBeforeSerialize?: boolean;
  compress?: boolean;
}

export interface DeserializationOptions {
  validateSchema?: boolean;
  skipUnknownFields?: boolean;
  applyDefaults?: boolean;
}

export interface SchemaValidationResult {
  valid: boolean;
  errors: SchemaError[];
  warnings: SchemaError[];
}

export interface SchemaError {
  path: string;
  message: string;
  expected?: string;
  actual?: unknown;
  severity: 'error' | 'warning';
}

export interface LevelFileHeader {
  version: string;
  type: 'DF1_LEVEL';
  created: number;
  modified: number;
  checksum: string;
  schemaVersion: number;
}

export interface SerializedLevel {
  header: LevelFileHeader;
  data: LevelConfig;
  metadata?: LevelMetadata;
}

export interface LevelMetadata {
  author?: string;
  tags?: string[];
  difficulty?: number;
  estimatedPlaytime?: number;
  notes?: string;
  custom?: Record<string, unknown>;
}

const CURRENT_SCHEMA_VERSION = 1;
const CURRENT_VERSION = '1.0.0';
const FILE_TYPE = 'DF1_LEVEL';

export class LevelSerializer {
  private schemaVersion: number;

  constructor(schemaVersion: number = CURRENT_SCHEMA_VERSION) {
    this.schemaVersion = schemaVersion;
  }

  serialize(
    config: LevelConfig,
    options: SerializationOptions = {},
    metadata?: LevelMetadata
  ): string {
    if (options.validateBeforeSerialize) {
      const validation = this.validateSchema(config);
      if (!validation.valid) {
        const errorMessages = validation.errors.map(e => `${e.path}: ${e.message}`).join('; ');
        throw new Error(`Schema validation failed: ${errorMessages}`);
      }
    }

    const header: LevelFileHeader = {
      version: CURRENT_VERSION,
      type: FILE_TYPE,
      created: Date.now(),
      modified: Date.now(),
      checksum: '',
      schemaVersion: this.schemaVersion
    };

    const serialized: SerializedLevel = {
      header,
      data: this.deepCloneConfig(config),
      metadata: options.includeMetadata ? metadata : undefined
    };

    const configJson = JSON.stringify(serialized.data);
    serialized.header.checksum = createChecksum(configJson);

    return JSON.stringify(serialized, null, options.prettyPrint ? 2 : 0);
  }

  deserialize(
    json: string,
    options: DeserializationOptions = {}
  ): LevelConfig {
    let parsed: unknown;

    try {
      parsed = JSON.parse(json);
    } catch (e) {
      throw new Error(`Invalid JSON: ${(e as Error).message}`);
    }

    if (typeof parsed !== 'object' || parsed === null) {
      throw new Error('Root element must be an object');
    }

    if ((parsed as Record<string, unknown>).header) {
      const serialized = parsed as SerializedLevel;
      this.validateHeader(serialized.header);

      if (options.validateSchema) {
        const validation = this.validateSchema(serialized.data);
        if (!validation.valid) {
          const errorMessages = validation.errors.map(e => `${e.path}: ${e.message}`).join('; ');
          throw new Error(`Schema validation failed: ${errorMessages}`);
        }
      }

      return this.applyDefaults(serialized.data, options.applyDefaults);
    }

    const config = parsed as LevelConfig;
    if (options.validateSchema) {
      const validation = this.validateSchema(config);
      if (!validation.valid) {
        const errorMessages = validation.errors.map(e => `${e.path}: ${e.message}`).join('; ');
        throw new Error(`Schema validation failed: ${errorMessages}`);
      }
    }

    return this.applyDefaults(config, options.applyDefaults);
  }

  validateSchema(config: unknown): SchemaValidationResult {
    const errors: SchemaError[] = [];
    const warnings: SchemaError[] = [];

    if (typeof config !== 'object' || config === null) {
      errors.push({
        path: 'root',
        message: 'Config must be an object',
        severity: 'error'
      });
      return { valid: false, errors, warnings };
    }

    const c = config as Record<string, unknown>;

    this.validateRequiredField(c, 'id', 'string', 'root', errors);
    this.validateRequiredField(c, 'name', 'string', 'root', errors);
    this.validateRequiredField(c, 'factions', 'object', 'root', errors);

    if (typeof c.factions === 'object' && c.factions !== null) {
      const factions = c.factions as Record<string, unknown>;
      const factionKeys = Object.keys(factions);

      if (factionKeys.length === 0) {
        errors.push({
          path: 'factions',
          message: 'At least one faction must be defined',
          severity: 'error'
        });
      }

      for (const [factionName, factionConfig] of Object.entries(factions)) {
        const path = `factions.${factionName}`;
        if (typeof factionConfig !== 'object' || factionConfig === null) {
          errors.push({
            path,
            message: `Faction config must be an object`,
            severity: 'error'
          });
          continue;
        }

        const fc = factionConfig as Record<string, unknown>;

        if (!Array.isArray(fc.units)) {
          errors.push({
            path: `${path}.units`,
            message: 'units must be an array',
            severity: 'error'
          });
        }

        if (!Array.isArray(fc.startingPositions)) {
          errors.push({
            path: `${path}.startingPositions`,
            message: 'startingPositions must be an array',
            severity: 'error'
          });
        }

        if (Array.isArray(fc.units) && Array.isArray(fc.startingPositions)) {
          if (fc.units.length !== fc.startingPositions.length) {
            warnings.push({
              path,
              message: `Unit count (${fc.units.length}) does not match starting position count (${fc.startingPositions.length})`,
              severity: 'warning'
            });
          }

          for (let i = 0; i < fc.startingPositions.length; i++) {
            const pos = fc.startingPositions[i] as Record<string, unknown>;
            const posPath = `${path}.startingPositions[${i}]`;
            this.validateCubeCoords(pos, posPath, errors);
          }
        }
      }
    }

    if (!Array.isArray(c.victoryConditions)) {
      errors.push({
        path: 'victoryConditions',
        message: 'victoryConditions must be an array',
        severity: 'error'
      });
    } else {
      (c.victoryConditions as unknown[]).forEach((vc, index) => {
        this.validateVictoryCondition(vc, `victoryConditions[${index}]`, errors, warnings);
      });
    }

    if (c.defeatConditions !== undefined && !Array.isArray(c.defeatConditions)) {
      errors.push({
        path: 'defeatConditions',
        message: 'defeatConditions must be an array if provided',
        severity: 'error'
      });
    } else if (Array.isArray(c.defeatConditions)) {
      (c.defeatConditions as unknown[]).forEach((dc, index) => {
        this.validateVictoryCondition(dc, `defeatConditions[${index}]`, errors, warnings);
      });
    }

    if (c.turnLimit !== undefined && typeof c.turnLimit !== 'number') {
      errors.push({
        path: 'turnLimit',
        message: 'turnLimit must be a number if provided',
        expected: 'number',
        actual: typeof c.turnLimit,
        severity: 'error'
      });
    }

    if (c.startingTurn !== undefined && typeof c.startingTurn !== 'number') {
      errors.push({
        path: 'startingTurn',
        message: 'startingTurn must be a number if provided',
        expected: 'number',
        actual: typeof c.startingTurn,
        severity: 'error'
      });
    }

    if (c.reinforcements !== undefined) {
      if (!Array.isArray(c.reinforcements)) {
        errors.push({
          path: 'reinforcements',
          message: 'reinforcements must be an array if provided',
          severity: 'error'
        });
      } else {
        (c.reinforcements as unknown[]).forEach((reinforcement, index) => {
          const path = `reinforcements[${index}]`;
          if (typeof reinforcement !== 'object' || reinforcement === null) {
            errors.push({ path, message: 'Reinforcement must be an object', severity: 'error' });
            return;
          }
          const r = reinforcement as Record<string, unknown>;
          this.validateRequiredField(r, 'turn', 'number', path, errors);
          this.validateRequiredField(r, 'faction', 'string', path, errors);
          if (!Array.isArray(r.unitIds)) {
            errors.push({ path: `${path}.unitIds`, message: 'unitIds must be an array', severity: 'error' });
          }
          if (!Array.isArray(r.positions)) {
            errors.push({ path: `${path}.positions`, message: 'positions must be an array', severity: 'error' });
          } else {
            (r.positions as unknown[]).forEach((pos, i) => {
              this.validateCubeCoords(pos as Record<string, unknown>, `${path}.positions[${i}]`, errors);
            });
          }
        });
      }
    }

    return {
      valid: errors.length === 0,
      errors,
      warnings
    };
  }

  async exportToFile(
    config: LevelConfig,
    filePath: string,
    options: SerializationOptions = {},
    metadata?: LevelMetadata
  ): Promise<void> {
    const json = this.serialize(config, options, metadata);
    
    if (typeof window !== 'undefined' && typeof document !== 'undefined') {
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filePath.split('/').pop() || `level_${config.id}.json`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } else {
      try {
        // @ts-ignore - Node.js dynamic import
        const fs = await import('fs');
        // @ts-ignore - Node.js dynamic import
        const path = await import('path');
        const dir = path.dirname(filePath);
        if (!fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true });
        }
        fs.writeFileSync(filePath, json, 'utf-8');
      } catch (e) {
        throw new Error('Cannot determine runtime environment for file export');
      }
    }
  }

  async importFromFile(
    filePath: string,
    options: DeserializationOptions = {}
  ): Promise<LevelConfig> {
    let json: string;

    if (typeof window !== 'undefined' && typeof fetch !== 'undefined') {
      const response = await fetch(filePath);
      if (!response.ok) {
        throw new Error(`Failed to fetch file: ${response.statusText}`);
      }
      json = await response.text();
    } else {
      try {
        // @ts-ignore - Node.js dynamic import
        const fs = await import('fs');
        json = fs.readFileSync(filePath, 'utf-8');
      } catch (e) {
        throw new Error('Cannot determine runtime environment for file import');
      }
    }

    return this.deserialize(json, options);
  }

  validateHeader(header: LevelFileHeader): void {
    if (header.type !== FILE_TYPE) {
      throw new Error(`Invalid file type: expected ${FILE_TYPE}, got ${header.type}`);
    }
    if (header.schemaVersion > CURRENT_SCHEMA_VERSION) {
      throw new Error(
        `Schema version too new: file has ${header.schemaVersion}, max supported is ${CURRENT_SCHEMA_VERSION}`
      );
    }
    if (!header.checksum) {
      console.warn('Level file missing checksum, integrity cannot be verified');
    }
  }

  verifyChecksum(serialized: SerializedLevel): boolean {
    const configJson = JSON.stringify(serialized.data);
    const expected = createChecksum(configJson);
    return expected === serialized.header.checksum;
  }

  private validateRequiredField(
    obj: Record<string, unknown>,
    field: string,
    expectedType: string,
    path: string,
    errors: SchemaError[]
  ): void {
    if (obj[field] === undefined) {
      errors.push({
        path: `${path}.${field}`,
        message: `Missing required field '${field}'`,
        expected: expectedType,
        severity: 'error'
      });
      return;
    }

    const actualType = Array.isArray(obj[field]) ? 'array' : typeof obj[field];
    if (expectedType === 'array') {
      if (!Array.isArray(obj[field])) {
        errors.push({
          path: `${path}.${field}`,
          message: `Field '${field}' must be an array`,
          expected: 'array',
          actual: actualType,
          severity: 'error'
        });
      }
    } else if (actualType !== expectedType) {
      errors.push({
        path: `${path}.${field}`,
        message: `Field '${field}' must be of type ${expectedType}`,
        expected: expectedType,
        actual: actualType,
        severity: 'error'
      });
    }
  }

  private validateCubeCoords(
    coords: Record<string, unknown> | undefined,
    path: string,
    errors: SchemaError[]
  ): void {
    if (typeof coords !== 'object' || coords === null) {
      errors.push({
        path,
        message: 'Coords must be an object',
        severity: 'error'
      });
      return;
    }

    for (const axis of ['q', 'r', 's']) {
      if (coords[axis] === undefined) {
        errors.push({
          path: `${path}.${axis}`,
          message: `Missing required axis '${axis}'`,
          severity: 'error'
        });
      } else if (typeof coords[axis] !== 'number') {
        errors.push({
          path: `${path}.${axis}`,
          message: `Axis '${axis}' must be a number`,
          actual: typeof coords[axis],
          severity: 'error'
        });
      }
    }

    const q = coords.q as number | undefined;
    const r = coords.r as number | undefined;
    const s = coords.s as number | undefined;
    if (q !== undefined && r !== undefined && s !== undefined) {
      const sum = q + r + s;
      if (Math.abs(sum) > 0.001) {
        errors.push({
          path,
          message: `Cube coords must satisfy q + r + s = 0, got ${sum}`,
          severity: 'error'
        });
      }
    }
  }

  private validateVictoryCondition(
    vc: unknown,
    path: string,
    errors: SchemaError[],
    warnings: SchemaError[]
  ): void {
    if (typeof vc !== 'object' || vc === null) {
      errors.push({ path, message: 'Condition must be an object', severity: 'error' });
      return;
    }

    const v = vc as Record<string, unknown>;
    this.validateRequiredField(v, 'id', 'string', path, errors);
    this.validateRequiredField(v, 'type', 'string', path, errors);
    this.validateRequiredField(v, 'description', 'string', path, errors);
    this.validateRequiredField(v, 'targetFaction', 'string', path, errors);

    if (v.type === 'eliminate') {
      if (typeof v.params !== 'object' || v.params === null || 
          (v.params as Record<string, unknown>).targetFaction === undefined) {
        warnings.push({
          path: `${path}.params.targetFaction`,
          message: 'eliminate type condition should have params.targetFaction',
          severity: 'warning'
        });
      }
    } else if (v.type === 'capture') {
      if (typeof v.params !== 'object' || v.params === null || 
          !Array.isArray((v.params as Record<string, unknown>).positions)) {
        warnings.push({
          path: `${path}.params.positions`,
          message: 'capture type condition should have params.positions array',
          severity: 'warning'
        });
      }
    } else if (v.type === 'survive') {
      if (typeof v.params !== 'object' || v.params === null || 
          (v.params as Record<string, unknown>).turns === undefined) {
        warnings.push({
          path: `${path}.params.turns`,
          message: 'survive type condition should have params.turns',
          severity: 'warning'
        });
      }
    } else if (v.type === 'turnLimit') {
      if (typeof v.params !== 'object' || v.params === null || 
          (v.params as Record<string, unknown>).limit === undefined) {
        warnings.push({
          path: `${path}.params.limit`,
          message: 'turnLimit type condition should have params.limit',
          severity: 'warning'
        });
      }
    }
  }

  private applyDefaults(config: LevelConfig, apply: boolean = true): LevelConfig {
    if (!apply) return config;

    return {
      ...config,
      description: config.description ?? '',
      victoryConditions: config.victoryConditions ?? [],
      defeatConditions: config.defeatConditions ?? [],
      reinforcements: config.reinforcements ?? [],
      environmentalEffects: config.environmentalEffects ?? [],
      startingTurn: config.startingTurn ?? 1,
      factions: Object.fromEntries(
        Object.entries(config.factions).map(([name, fc]) => [
          name,
          {
            ...fc,
            units: fc.units ?? [],
            startingPositions: fc.startingPositions ?? []
          }
        ])
      )
    };
  }

  private deepCloneConfig(config: LevelConfig): LevelConfig {
    return JSON.parse(JSON.stringify(config));
  }

  toJSON(): Record<string, unknown> {
    return {
      schemaVersion: this.schemaVersion
    };
  }

  static fromJSON(data: Record<string, unknown>): LevelSerializer {
    return new LevelSerializer(data.schemaVersion as number ?? CURRENT_SCHEMA_VERSION);
  }
}

export { CURRENT_SCHEMA_VERSION, CURRENT_VERSION, FILE_TYPE };
