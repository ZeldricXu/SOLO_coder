import type { GraphNode, GraphEdge } from '@shared/types';

const DB_NAME = 'graphLayoutDB';
const DB_VERSION = 1;
const STORE_NAME = 'graphLayouts';

interface CachedLayout {
  hash: string;
  nodePositions: Array<[string, { x: number; y: number }]>;
  timestamp: number;
  version: number;
}

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'hash' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export function computeGraphHash(nodes: GraphNode[], edges: GraphEdge[]): string {
  const sortedIds = nodes.map((n) => n.id).sort().join(',');
  const edgeCount = edges.length;
  const raw = `${sortedIds}|${edgeCount}`;
  let hash = 0;
  for (let i = 0; i < raw.length; i++) {
    const chr = raw.charCodeAt(i);
    hash = ((hash << 5) - hash + chr) | 0;
  }
  return 'gh_' + Math.abs(hash).toString(36);
}

export async function saveLayout(
  hash: string,
  positions: Map<string, { x: number; y: number }>
): Promise<void> {
  const db = await openDB();
  const tx = db.transaction(STORE_NAME, 'readwrite');
  const store = tx.objectStore(STORE_NAME);
  const entry: CachedLayout = {
    hash,
    nodePositions: Array.from(positions.entries()),
    timestamp: Date.now(),
    version: DB_VERSION,
  };
  store.put(entry);
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function loadLayout(
  hash: string
): Promise<Map<string, { x: number; y: number }> | null> {
  const db = await openDB();
  const tx = db.transaction(STORE_NAME, 'readonly');
  const store = tx.objectStore(STORE_NAME);
  const request = store.get(hash);
  return new Promise((resolve, reject) => {
    request.onsuccess = () => {
      const result = request.result as CachedLayout | undefined;
      if (result && result.version === DB_VERSION) {
        resolve(new Map(result.nodePositions));
      } else {
        resolve(null);
      }
    };
    request.onerror = () => reject(request.error);
  });
}

export async function clearOldLayouts(maxAge: number): Promise<void> {
  const db = await openDB();
  const tx = db.transaction(STORE_NAME, 'readwrite');
  const store = tx.objectStore(STORE_NAME);
  const cutoff = Date.now() - maxAge;
  const request = store.openCursor();
  return new Promise((resolve, reject) => {
    request.onsuccess = (event) => {
      const cursor = (event.target as IDBRequest).result as IDBCursorWithValue | null;
      if (cursor) {
        const entry = cursor.value as CachedLayout;
        if (entry.timestamp < cutoff) {
          cursor.delete();
        }
        cursor.continue();
      }
    };
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}
