import * as Diff from 'diff';
import { isObject, isArray } from 'lodash';

export interface DiffChange {
  field: string;
  oldValue?: unknown;
  newValue?: unknown;
  operation: 'add' | 'remove' | 'replace';
}

export interface DiffResult {
  changes: DiffChange[];
  oldSnapshot: Record<string, unknown>;
  newSnapshot: Record<string, unknown>;
  patch: string;
}

function flattenObject(
  obj: Record<string, unknown>,
  prefix = ''
): Record<string, unknown> {
  const result: Record<string, unknown> = {};

  for (const [key, value] of Object.entries(obj)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;

    if (isObject(value) && !isArray(value) && value !== null) {
      Object.assign(result, flattenObject(value as Record<string, unknown>, fullKey));
    } else {
      result[fullKey] = value;
    }
  }

  return result;
}

export function computeDiff(
  oldSnapshot: Record<string, unknown>,
  newSnapshot: Record<string, unknown>
): DiffResult {
  const oldFlat = flattenObject(oldSnapshot);
  const newFlat = flattenObject(newSnapshot);
  const changes: DiffChange[] = [];

  const allKeys = new Set([...Object.keys(oldFlat), ...Object.keys(newFlat)]);

  for (const key of allKeys) {
    const oldVal = oldFlat[key];
    const newVal = newFlat[key];

    if (oldVal === undefined && newVal !== undefined) {
      changes.push({ field: key, newValue: newVal, operation: 'add' });
    } else if (newVal === undefined && oldVal !== undefined) {
      changes.push({ field: key, oldValue: oldVal, operation: 'remove' });
    } else if (JSON.stringify(oldVal) !== JSON.stringify(newVal)) {
      changes.push({ field: key, oldValue: oldVal, newValue: newVal, operation: 'replace' });
    }
  }

  const oldJson = JSON.stringify(oldSnapshot, null, 2);
  const newJson = JSON.stringify(newSnapshot, null, 2);
  const patch = Diff.createPatch('content', oldJson, newJson);

  return { changes, oldSnapshot, newSnapshot, patch };
}

export function applyPatch(
  snapshot: Record<string, unknown>,
  patch: string
): Record<string, unknown> {
  const oldJson = JSON.stringify(snapshot, null, 2);
  const patches = Diff.parsePatch(patch);
  const result = Diff.applyPatch(oldJson, patches[0]);

  if (result === false) {
    throw new Error('Failed to apply patch');
  }

  return JSON.parse(result);
}

export function generateVersionMessage(changes: DiffChange[]): string {
  if (changes.length === 0) return 'No changes';

  const fieldCount = new Set(changes.map(c => c.field.split('.')[0])).size;
  const operations = {
    add: changes.filter(c => c.operation === 'add').length,
    remove: changes.filter(c => c.operation === 'remove').length,
    replace: changes.filter(c => c.operation === 'replace').length,
  };

  const parts: string[] = [];
  if (operations.add > 0) parts.push(`+${operations.add} fields`);
  if (operations.remove > 0) parts.push(`-${operations.remove} fields`);
  if (operations.replace > 0) parts.push(`~${operations.replace} fields`);

  return `Updated ${fieldCount} field(s): ${parts.join(', ')}`;
}
