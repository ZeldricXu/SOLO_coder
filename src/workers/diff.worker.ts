import { parentPort, workerData } from 'worker_threads';
import * as Diff from 'diff';
import { isObject, isArray } from 'lodash';

export interface DiffWorkerInput {
  oldSnapshot: Record<string, unknown>;
  newSnapshot: Record<string, unknown>;
}

export interface DiffChange {
  field: string;
  oldValue?: unknown;
  newValue?: unknown;
  operation: 'add' | 'remove' | 'replace';
}

export interface DiffWorkerOutput {
  changes: DiffChange[];
  patch: string;
  oldSnapshot: Record<string, unknown>;
  newSnapshot: Record<string, unknown>;
  error?: string;
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

function computeDiff(input: DiffWorkerInput): DiffWorkerOutput {
  try {
    const { oldSnapshot, newSnapshot } = input;

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

    return { changes, patch, oldSnapshot, newSnapshot };
  } catch (error: any) {
    return {
      changes: [],
      patch: '',
      oldSnapshot: input.oldSnapshot,
      newSnapshot: input.newSnapshot,
      error: error?.message || 'Unknown error in diff worker',
    };
  }
}

if (parentPort) {
  const result = computeDiff(workerData as DiffWorkerInput);
  parentPort.postMessage(result);
}
