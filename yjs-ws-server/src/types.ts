import * as Y from 'yjs';
import { Awareness } from 'y-protocols/awareness';
import WebSocket from 'ws';

export interface CollabUser {
  id: string;
  name: string;
  email?: string;
  avatar?: string;
  color?: string;
}

export interface AwarenessState {
  user: CollabUser;
  lastActive: number;
  cursor?: {
    position: number;
    selection?: {
      anchor: number;
      head: number;
    };
  };
}

export interface DocumentPermissions {
  canView: boolean;
  canEdit: boolean;
  canComment: boolean;
}

export interface SaveOptions {
  force?: boolean;
  version?: number;
}

export interface YDocEntry {
  doc: Y.Doc;
  awareness: Awareness;
  connections: Set<WebSocket>;
  documentId: string;
  version: number;
  lastSaved: Date;
  saveTimeout: NodeJS.Timeout | null;
  isLoading: boolean;
  loadPromise: Promise<void> | null;
}

export interface ConnectionContext {
  ws: WebSocket;
  documentId: string;
  user: CollabUser;
  permissions: DocumentPermissions;
  room: YDocEntry;
  controlledIds: Set<number>;
  isAuthenticated: boolean;
}

export interface RoomInfo {
  documentId: string;
  userCount: number;
  version: number;
  lastSaved: string;
  isActive: boolean;
  onlineUsers: CollabUser[];
}

export interface ServerConfig {
  port: number;
  httpPort: number;
  saveDebounceMs: number;
  gcEnabled: boolean;
  redisUrl?: string;
  redisChannel: string;
  prismaUrl: string;
  jwtSecret: string;
}

export type BroadcastMessage =
  | {
      type: 'update';
      documentId: string;
      update: Uint8Array;
      senderId: string;
    }
  | {
      type: 'awareness';
      documentId: string;
      update: Uint8Array;
      senderId: string;
    };
