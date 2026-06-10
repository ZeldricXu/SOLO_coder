import * as Diff from 'diff';
import { isObject, isArray } from 'lodash';
import { Worker } from 'worker_threads';
import * as path from 'path';
import { logger } from './logger';
import { config } from '@config/index';

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
  skipped?: boolean;
  skipReason?: string;
  viaWorker?: boolean;
}

export interface DiffComputeOptions {
  useWorker?: boolean;
  workerTimeoutMs?: number;
  maxSizeBytes?: number;
  maxFieldCount?: number;
}

const DEFAULT_MAX_SIZE_BYTES = 1024 * 1024;
const DEFAULT_MAX_FIELD_COUNT = 10000;
const DEFAULT_WORKER_TIMEOUT_MS = 30000;

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

function estimateSizeBytes(snapshot: Record<string, unknown>): number {
  try {
    return Buffer.byteLength(JSON.stringify(snapshot), 'utf8');
  } catch {
    return Number.MAX_SAFE_INTEGER;
  }
}

function shouldSkipDiff(
  oldSnapshot: Record<string, unknown>,
  newSnapshot: Record<string, unknown>,
  options: DiffComputeOptions
): { skip: boolean; reason?: string; oldSize: number; newSize: number; fieldCount: number } {
  const maxSizeBytes = options.maxSizeBytes ?? DEFAULT_MAX_SIZE_BYTES;
  const maxFieldCount = options.maxFieldCount ?? DEFAULT_MAX_FIELD_COUNT;

  const oldSize = estimateSizeBytes(oldSnapshot);
  const newSize = estimateSizeBytes(newSnapshot);
  const totalSize = oldSize + newSize;

  try {
    const fieldCount =
      Object.keys(flattenObject(oldSnapshot)).length +
      Object.keys(flattenObject(newSnapshot)).length;

    if (totalSize > maxSizeBytes) {
      return {
        skip: true,
        reason: `Total snapshot size (${(totalSize / 1024).toFixed(2)} KB) exceeds threshold (${(maxSizeBytes / 1024).toFixed(2)} KB)`,
        oldSize,
        newSize,
        fieldCount,
      };
    }

    if (fieldCount > maxFieldCount) {
      return {
        skip: true,
        reason: `Total field count (${fieldCount}) exceeds threshold (${maxFieldCount})`,
        oldSize,
        newSize,
        fieldCount,
      };
    }

    return { skip: false, oldSize, newSize, fieldCount };
  } catch (error: any) {
    return {
      skip: true,
      reason: `Error during pre-check: ${error?.message || 'unknown'}`,
      oldSize,
      newSize,
      fieldCount: 0,
    };
  }
}

function computeDiffSync(
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

function computeDiffViaWorker(
  oldSnapshot: Record<string, unknown>,
  newSnapshot: Record<string, unknown>,
  timeoutMs: number = DEFAULT_WORKER_TIMEOUT_MS
): Promise<DiffResult> {
  return new Promise((resolve) => {
    let worker: Worker | null = null;
    let timeoutId: NodeJS.Timeout | null = null;

    const cleanup = () => {
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
      }
      if (worker) {
        worker.terminate().catch(() => {});
        worker = null;
      }
    };

    const fallbackToSync = (reason: string) => {
      logger.warn(
        { reason },
        `Diff worker ${reason}, falling back to sync computation`
      );
      cleanup();
      try {
        resolve(computeDiffSync(oldSnapshot, newSnapshot));
      } catch (error: any) {
        resolve({
          changes: [],
          oldSnapshot,
          newSnapshot,
          patch: '',
          skipped: true,
          skipReason: `Fallback diff failed: ${error?.message || 'unknown'}`,
        });
      }
    };

    try {
      const workerPath = path.resolve(__dirname, '..', 'workers', 'diff.worker.js');
      const workerTsPath = path.resolve(__dirname, '..', 'workers', 'diff.worker.ts');

      worker = new Worker(workerPath, {
        workerData: { oldSnapshot, newSnapshot },
      });

      timeoutId = setTimeout(() => {
        fallbackToSync(`timed out after ${timeoutMs}ms`);
      }, timeoutMs);

      worker.on('message', (result: any) => {
        cleanup();
        if (result.error) {
          logger.error(
            { error: result.error },
            'Diff worker reported error, falling back to sync'
          );
          try {
            resolve(computeDiffSync(oldSnapshot, newSnapshot));
          } catch (syncError: any) {
            resolve({
              changes: [],
              oldSnapshot,
              newSnapshot,
              patch: '',
              skipped: true,
              skipReason: `Worker and sync both failed: ${syncError?.message || result.error}`,
            });
          }
        } else {
          resolve({
            changes: result.changes,
            patch: result.patch,
            oldSnapshot: result.oldSnapshot,
            newSnapshot: result.newSnapshot,
            viaWorker: true,
          });
        }
      });

      worker.on('error', (error) => {
        fallbackToSync(`failed with error: ${error?.message || 'unknown'}`);
      });

      worker.on('exit', (code) => {
        if (code !== 0 && timeoutId) {
          fallbackToSync(`exited with code ${code}`);
        }
      });
    } catch (error: any) {
      fallbackToSync(`failed to start: ${error?.message || 'unknown'}`);
    }
  });
}

export async function computeDiff(
  oldSnapshot: Record<string, unknown>,
  newSnapshot: Record<string, unknown>,
  options: DiffComputeOptions = {}
): Promise<DiffResult> {
  const useWorker = options.useWorker ?? true;
  const maxSizeBytes = options.maxSizeBytes ?? DEFAULT_MAX_SIZE_BYTES;
  const maxFieldCount = options.maxFieldCount ?? DEFAULT_MAX_FIELD_COUNT;
  const workerTimeoutMs = options.workerTimeoutMs ?? DEFAULT_WORKER_TIMEOUT_MS;

  const preCheck = shouldSkipDiff(oldSnapshot, newSnapshot, {
    maxSizeBytes,
    maxFieldCount,
  });

  if (preCheck.skip) {
    logger.warn(
      {
        skipReason: preCheck.reason,
        oldSizeKb: (preCheck.oldSize / 1024).toFixed(2),
        newSizeKb: (preCheck.newSize / 1024).toFixed(2),
        fieldCount: preCheck.fieldCount,
      },
      'Diff computation skipped due to size threshold, storing full snapshot only'
    );

    return {
      changes: [],
      oldSnapshot,
      newSnapshot,
      patch: '',
      skipped: true,
      skipReason: preCheck.reason,
    };
  }

  const largePayloadThreshold = maxSizeBytes * 0.3;
  const shouldUseWorker =
    useWorker && (preCheck.oldSize + preCheck.newSize) > largePayloadThreshold;

  if (shouldUseWorker) {
    logger.debug(
      {
        totalSizeKb: ((preCheck.oldSize + preCheck.newSize) / 1024).toFixed(2),
        fieldCount: preCheck.fieldCount,
      },
      'Using worker thread for diff computation'
    );
    return computeDiffViaWorker(oldSnapshot, newSnapshot, workerTimeoutMs);
  }

  return computeDiffSync(oldSnapshot, newSnapshot);
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
