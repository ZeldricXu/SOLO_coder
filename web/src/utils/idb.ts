import { openDB, IDBPDatabase, DBSchema } from 'idb';
import type { Stroke, Shape, Layer, Comment, Version, CRDTOperation } from '../types';

interface WhiteboardDB extends DBSchema {
  strokes: {
    key: string;
    value: Stroke;
    indexes: { 'by-layer': string; 'by-user': string; 'by-created': number };
  };
  shapes: {
    key: string;
    value: Shape;
    indexes: { 'by-layer': string; 'by-user': string; 'by-created': number };
  };
  layers: {
    key: string;
    value: Layer;
    indexes: { 'by-order': number };
  };
  comments: {
    key: string;
    value: Comment;
    indexes: { 'by-thread': string; 'by-user': string };
  };
  versions: {
    key: string;
    value: Version;
    indexes: { 'by-board': string; 'by-created': number };
  };
  operations: {
    key: string;
    value: CRDTOperation;
    indexes: { 'by-board': string; 'by-timestamp': number };
  };
  boards: {
    key: string;
    value: {
      id: string;
      name: string;
      createdAt: number;
      updatedAt: number;
      lastSynced?: number;
    };
    indexes: { 'by-updated': number };
  };
}

const DB_NAME = 'whiteboard-db';
const DB_VERSION = 1;

let dbInstance: IDBPDatabase<WhiteboardDB> | null = null;

async function getDB(): Promise<IDBPDatabase<WhiteboardDB>> {
  if (dbInstance) return dbInstance;

  dbInstance = await openDB<WhiteboardDB>(DB_NAME, DB_VERSION, {
    upgrade(db) {
      if (!db.objectStoreNames.contains('strokes')) {
        const strokesStore = db.createObjectStore('strokes', { keyPath: 'id' });
        strokesStore.createIndex('by-layer', 'layerId');
        strokesStore.createIndex('by-user', 'userId');
        strokesStore.createIndex('by-created', 'createdAt');
      }

      if (!db.objectStoreNames.contains('shapes')) {
        const shapesStore = db.createObjectStore('shapes', { keyPath: 'id' });
        shapesStore.createIndex('by-layer', 'layerId');
        shapesStore.createIndex('by-user', 'userId');
        shapesStore.createIndex('by-created', 'createdAt');
      }

      if (!db.objectStoreNames.contains('layers')) {
        const layersStore = db.createObjectStore('layers', { keyPath: 'id' });
        layersStore.createIndex('by-order', 'order');
      }

      if (!db.objectStoreNames.contains('comments')) {
        const commentsStore = db.createObjectStore('comments', { keyPath: 'id' });
        commentsStore.createIndex('by-thread', 'threadId');
        commentsStore.createIndex('by-user', 'userId');
      }

      if (!db.objectStoreNames.contains('versions')) {
        const versionsStore = db.createObjectStore('versions', { keyPath: 'id' });
        versionsStore.createIndex('by-board', 'boardId');
        versionsStore.createIndex('by-created', 'createdAt');
      }

      if (!db.objectStoreNames.contains('operations')) {
        const operationsStore = db.createObjectStore('operations', { keyPath: 'id' });
        operationsStore.createIndex('by-board', 'boardId');
        operationsStore.createIndex('by-timestamp', 'timestamp');
      }

      if (!db.objectStoreNames.contains('boards')) {
        const boardsStore = db.createObjectStore('boards', { keyPath: 'id' });
        boardsStore.createIndex('by-updated', 'updatedAt');
      }
    },
  });

  return dbInstance;
}

export async function saveStroke(stroke: Stroke): Promise<void> {
  const db = await getDB();
  await db.put('strokes', stroke);
}

export async function saveStrokes(strokes: Stroke[]): Promise<void> {
  const db = await getDB();
  const tx = db.transaction('strokes', 'readwrite');
  await Promise.all([...strokes.map((s) => tx.store.put(s)), tx.done]);
}

export async function getStroke(id: string): Promise<Stroke | undefined> {
  const db = await getDB();
  return db.get('strokes', id);
}

export async function getAllStrokes(): Promise<Stroke[]> {
  const db = await getDB();
  return db.getAll('strokes');
}

export async function deleteStroke(id: string): Promise<void> {
  const db = await getDB();
  await db.delete('strokes', id);
}

export async function clearStrokes(): Promise<void> {
  const db = await getDB();
  await db.clear('strokes');
}

export async function saveShape(shape: Shape): Promise<void> {
  const db = await getDB();
  await db.put('shapes', shape);
}

export async function saveShapes(shapes: Shape[]): Promise<void> {
  const db = await getDB();
  const tx = db.transaction('shapes', 'readwrite');
  await Promise.all([...shapes.map((s) => tx.store.put(s)), tx.done]);
}

export async function getShape(id: string): Promise<Shape | undefined> {
  const db = await getDB();
  return db.get('shapes', id);
}

export async function getAllShapes(): Promise<Shape[]> {
  const db = await getDB();
  return db.getAll('shapes');
}

export async function deleteShape(id: string): Promise<void> {
  const db = await getDB();
  await db.delete('shapes', id);
}

export async function clearShapes(): Promise<void> {
  const db = await getDB();
  await db.clear('shapes');
}

export async function saveLayer(layer: Layer): Promise<void> {
  const db = await getDB();
  await db.put('layers', layer);
}

export async function getAllLayers(): Promise<Layer[]> {
  const db = await getDB();
  return db.getAllFromIndex('layers', 'by-order');
}

export async function deleteLayer(id: string): Promise<void> {
  const db = await getDB();
  await db.delete('layers', id);
}

export async function saveComment(comment: Comment): Promise<void> {
  const db = await getDB();
  await db.put('comments', comment);
}

export async function getCommentsByThread(threadId: string): Promise<Comment[]> {
  const db = await getDB();
  return db.getAllFromIndex('comments', 'by-thread', threadId);
}

export async function deleteComment(id: string): Promise<void> {
  const db = await getDB();
  await db.delete('comments', id);
}

export async function saveVersion(version: Version): Promise<void> {
  const db = await getDB();
  await db.put('versions', version);
}

export async function getVersionsByBoard(boardId: string): Promise<Version[]> {
  const db = await getDB();
  return db.getAllFromIndex('versions', 'by-board', boardId);
}

export async function saveOperation(operation: CRDTOperation): Promise<void> {
  const db = await getDB();
  await db.put('operations', operation);
}

export async function getOperationsByBoard(boardId: string): Promise<CRDTOperation[]> {
  const db = await getDB();
  return db.getAllFromIndex('operations', 'by-board', boardId);
}

export async function clearOperations(): Promise<void> {
  const db = await getDB();
  await db.clear('operations');
}

export async function saveBoard(board: {
  id: string;
  name: string;
  createdAt: number;
  updatedAt: number;
  lastSynced?: number;
}): Promise<void> {
  const db = await getDB();
  await db.put('boards', board);
}

export async function getBoard(id: string): Promise<{
  id: string;
  name: string;
  createdAt: number;
  updatedAt: number;
  lastSynced?: number;
} | undefined> {
  const db = await getDB();
  return db.get('boards', id);
}

export async function getAllBoards(): Promise<{
  id: string;
  name: string;
  createdAt: number;
  updatedAt: number;
  lastSynced?: number;
}[]> {
  const db = await getDB();
  return db.getAllFromIndex('boards', 'by-updated');
}

export async function closeDB(): Promise<void> {
  if (dbInstance) {
    dbInstance.close();
    dbInstance = null;
  }
}

export async function clearAllData(): Promise<void> {
  const db = await getDB();
  const tx = db.transaction(
    ['strokes', 'shapes', 'layers', 'comments', 'versions', 'operations'],
    'readwrite'
  );

  await Promise.all([
    tx.objectStore('strokes').clear(),
    tx.objectStore('shapes').clear(),
    tx.objectStore('layers').clear(),
    tx.objectStore('comments').clear(),
    tx.objectStore('versions').clear(),
    tx.objectStore('operations').clear(),
    tx.done,
  ]);
}

export default {
  saveStroke,
  saveStrokes,
  getStroke,
  getAllStrokes,
  deleteStroke,
  clearStrokes,
  saveShape,
  saveShapes,
  getShape,
  getAllShapes,
  deleteShape,
  clearShapes,
  saveLayer,
  getAllLayers,
  deleteLayer,
  saveComment,
  getCommentsByThread,
  deleteComment,
  saveVersion,
  getVersionsByBoard,
  saveOperation,
  getOperationsByBoard,
  clearOperations,
  saveBoard,
  getBoard,
  getAllBoards,
  closeDB,
  clearAllData,
};
