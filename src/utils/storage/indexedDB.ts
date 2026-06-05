import { openDB, IDBPDatabase } from 'idb';
import type { FloorPlan } from '@/types/floorplan';

const DB_NAME = 'archplan-studio';
const DB_VERSION = 1;
const STORE_DRAFTS = 'drafts';
const STORE_SETTINGS = 'settings';

export interface DraftMeta {
  id: string;
  name: string;
  thumbnail?: string;
  createdAt: number;
  updatedAt: number;
  description?: string;
}

export interface DraftRecord extends DraftMeta {
  data: FloorPlan;
}

let dbPromise: Promise<IDBPDatabase> | null = null;

const initDB = async (): Promise<IDBPDatabase> => {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains(STORE_DRAFTS)) {
          const store = db.createObjectStore(STORE_DRAFTS, { keyPath: 'id' });
          store.createIndex('updatedAt', 'updatedAt');
          store.createIndex('createdAt', 'createdAt');
          store.createIndex('name', 'name');
        }

        if (!db.objectStoreNames.contains(STORE_SETTINGS)) {
          db.createObjectStore(STORE_SETTINGS, { keyPath: 'key' });
        }
      },
    });
  }
  return dbPromise;
};

export const saveDraft = async (id: string, floorPlan: FloorPlan, name?: string): Promise<void> => {
  const db = await initDB();
  const existing = await db.get(STORE_DRAFTS, id);

  const record: DraftRecord = {
    id,
    name: name || existing?.name || `户型图 ${new Date().toLocaleDateString('zh-CN')}`,
    description: existing?.description,
    thumbnail: existing?.thumbnail,
    data: floorPlan,
    createdAt: existing?.createdAt || Date.now(),
    updatedAt: Date.now(),
  };

  await db.put(STORE_DRAFTS, record);
};

export const getDraft = async (id: string): Promise<DraftRecord | undefined> => {
  const db = await initDB();
  return db.get(STORE_DRAFTS, id);
};

export const getAllDrafts = async (): Promise<DraftMeta[]> => {
  const db = await initDB();
  const records = await db.getAllFromIndex(STORE_DRAFTS, 'updatedAt');

  return records
    .reverse()
    .map(({ id, name, thumbnail, createdAt, updatedAt, description }) => ({
      id,
      name,
      thumbnail,
      createdAt,
      updatedAt,
      description,
    }));
};

export const deleteDraft = async (id: string): Promise<void> => {
  const db = await initDB();
  await db.delete(STORE_DRAFTS, id);
};

export const updateDraftMeta = async (id: string, updates: Partial<DraftMeta>): Promise<void> => {
  const db = await initDB();
  const existing = await db.get(STORE_DRAFTS, id);

  if (!existing) {
    throw new Error('草稿不存在');
  }

  const updated: DraftRecord = {
    ...existing,
    ...updates,
    updatedAt: Date.now(),
  };

  await db.put(STORE_DRAFTS, updated);
};

export const duplicateDraft = async (id: string, newName?: string): Promise<string> => {
  const db = await initDB();
  const existing = await db.get(STORE_DRAFTS, id);

  if (!existing) {
    throw new Error('草稿不存在');
  }

  const newId = `draft_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  const copy: DraftRecord = {
    ...existing,
    id: newId,
    name: newName || `${existing.name} 副本`,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };

  await db.put(STORE_DRAFTS, copy);
  return newId;
};

export const exportDraft = async (id: string): Promise<DraftRecord> => {
  const draft = await getDraft(id);
  if (!draft) {
    throw new Error('草稿不存在');
  }
  return draft;
};

export const importDraft = async (record: DraftRecord): Promise<string> => {
  const db = await initDB();
  const newId = record.id || `draft_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  const importRecord: DraftRecord = {
    ...record,
    id: newId,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  await db.put(STORE_DRAFTS, importRecord);
  return newId;
};

export const saveSetting = async (key: string, value: unknown): Promise<void> => {
  const db = await initDB();
  await db.put(STORE_SETTINGS, { key, value, updatedAt: Date.now() });
};

export const getSetting = async <T = unknown>(key: string, defaultValue?: T): Promise<T | undefined> => {
  const db = await initDB();
  const result = await db.get(STORE_SETTINGS, key);
  return result?.value ?? defaultValue;
};

export const deleteSetting = async (key: string): Promise<void> => {
  const db = await initDB();
  await db.delete(STORE_SETTINGS, key);
};

export const getStorageUsage = async (): Promise<{ used: number; quota: number | null }> => {
  if ('storage' in navigator && 'estimate' in navigator.storage) {
    const estimate = await navigator.storage.estimate();
    return {
      used: estimate.usage || 0,
      quota: estimate.quota || null,
    };
  }
  return { used: 0, quota: null };
};

export const clearAllDrafts = async (): Promise<void> => {
  const db = await initDB();
  await db.clear(STORE_DRAFTS);
};

export const formatBytes = (bytes: number): string => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};

export const generateThumbnail = (floorPlan: FloorPlan, width: number = 200, height: number = 150): string => {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return '';

  ctx.fillStyle = '#1a1f2e';
  ctx.fillRect(0, 0, width, height);

  if (floorPlan.walls.length === 0) {
    return canvas.toDataURL('image/png');
  }

  let minX = Infinity,
    minY = Infinity,
    maxX = -Infinity,
    maxY = -Infinity;

  for (const wall of floorPlan.walls) {
    minX = Math.min(minX, wall.start.x, wall.end.x);
    minY = Math.min(minY, wall.start.y, wall.end.y);
    maxX = Math.max(maxX, wall.start.x, wall.end.x);
    maxY = Math.max(maxY, wall.start.y, wall.end.y);
  }

  const padding = 20;
  const scaleX = (width - padding * 2) / (maxX - minX || 1);
  const scaleY = (height - padding * 2) / (maxY - minY || 1);
  const scale = Math.min(scaleX, scaleY);

  const transformX = (x: number) => padding + (x - minX) * scale;
  const transformY = (y: number) => height - padding - (y - minY) * scale;

  for (const room of floorPlan.rooms) {
    ctx.fillStyle = 'rgba(0, 212, 255, 0.1)';
    ctx.strokeStyle = '#00d4ff';
    ctx.lineWidth = 1;
    ctx.beginPath();
    room.boundary.forEach((p, i) => {
      const x = transformX(p.x);
      const y = transformY(p.y);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
  }

  ctx.strokeStyle = '#ff6b35';
  ctx.lineWidth = 2;
  for (const wall of floorPlan.walls) {
    ctx.beginPath();
    ctx.moveTo(transformX(wall.start.x), transformY(wall.start.y));
    ctx.lineTo(transformX(wall.end.x), transformY(wall.end.y));
    ctx.stroke();
  }

  return canvas.toDataURL('image/png');
};
