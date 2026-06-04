import * as Y from 'yjs';
import { WebSocket, WebSocketServer } from 'ws';
import {
  Awareness,
  removeAwarenessStates,
  applyAwarenessUpdate,
} from 'y-protocols/awareness';
import {
  writeSync,
  readSync,
  writeYjsSyncStep2,
  writeUpdate,
} from 'y-protocols/sync';
import { encoding, decoding } from 'lib0';
import {
  YDocEntry,
  ConnectionContext,
  CollabUser,
  DocumentPermissions,
  ServerConfig,
  RoomInfo,
  BroadcastMessage,
} from './types';
import { RedisBroadcaster } from './RedisBroadcaster';
import { loadDocumentState, saveDocumentState } from './utils';

const messageSync = 0;
const messageAwareness = 1;
const messageAuth = 2;
const messageStatus = 3;

export class YjsWebSocketServer {
  private docs: Map<string, YDocEntry> = new Map();
  private connections: Map<WebSocket, ConnectionContext> = new Map();
  private config: ServerConfig;
  private broadcaster: RedisBroadcaster;
  private unsubscribeHandlers: Map<string, () => void> = new Map();

  constructor(config: ServerConfig) {
    this.config = config;
    this.broadcaster = new RedisBroadcaster(config);
  }

  async initialize(): Promise<void> {
    await this.broadcaster.connect();
  }

  attachWebSocketServer(wss: WebSocketServer): void {
    wss.on('connection', (ws, request) => {
      this.handleUpgrade(ws, request);
    });
  }

  private handleUpgrade(ws: WebSocket, request: any): void {
    try {
      const url = new URL(request.url || '/', 'http://localhost');
      const documentId = url.searchParams.get('documentId');
      const token = url.searchParams.get('token');
      const userId = url.searchParams.get('userId');
      const userName = url.searchParams.get('userName');

      if (!documentId) {
        ws.close(1008, 'Document ID is required');
        return;
      }

      const user: CollabUser = {
        id: userId || 'anonymous',
        name: userName || 'Anonymous',
      };

      const permissions: DocumentPermissions = {
        canView: true,
        canEdit: true,
        canComment: true,
      };

      this.handleConnection(ws, documentId, user, permissions);
    } catch (error) {
      console.error('[YjsWebSocketServer] Upgrade error:', error);
      ws.close(1011, 'Internal server error');
    }
  }

  async handleConnection(
    ws: WebSocket,
    documentId: string,
    user: CollabUser,
    permissions: DocumentPermissions
  ): Promise<void> {
    try {
      const room = await this.getOrCreateDoc(documentId);

      const context: ConnectionContext = {
        ws,
        documentId,
        user,
        permissions,
        room,
        controlledIds: new Set(),
        isAuthenticated: true,
      };

      this.connections.set(ws, context);
      room.connections.add(ws);

      this.setupDocListeners(room, documentId);
      this.setupConnectionHandlers(ws, context);
      this.sendInitialSync(ws, room);
      this.setupAwareness(ws, context, room);

      ws.on('close', () => this.handleClose(ws, context));
      ws.on('message', (message: Buffer) =>
        this.handleMessage(ws, message, context)
      );
      ws.on('error', (error) => this.handleError(ws, error, context));

      this.sendStatus(ws, {
        type: 'connected',
        documentId,
        user,
      });
    } catch (error) {
      console.error('[YjsWebSocketServer] Connection error:', error);
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(
          JSON.stringify({
            type: 'error',
            message:
              error instanceof Error ? error.message : 'Connection failed',
          })
        );
        ws.close();
      }
    }
  }

  private async getOrCreateDoc(documentId: string): Promise<YDocEntry> {
    let entry = this.docs.get(documentId);

    if (entry) {
      if (entry.isLoading && entry.loadPromise) {
        await entry.loadPromise;
      }
      return entry;
    }

    const doc = new Y.Doc({ gc: this.config.gcEnabled });
    const awareness = new Awareness(doc);

    entry = {
      doc,
      awareness,
      connections: new Set(),
      documentId,
      version: 1,
      lastSaved: new Date(),
      saveTimeout: null,
      isLoading: true,
      loadPromise: null,
    };

    this.docs.set(documentId, entry);

    entry.loadPromise = loadDocumentState(documentId, entry.doc)
      .then(({ version, lastSaved }) => {
        entry!.version = version;
        entry!.lastSaved = lastSaved;
      })
      .finally(() => {
        entry!.isLoading = false;
        entry!.loadPromise = null;
      });

    await entry.loadPromise;

    return entry;
  }

  private setupDocListeners(entry: YDocEntry, documentId: string): void {
    if (entry.doc._observers.has('update')) {
      return;
    }

    entry.doc.on('update', (update: Uint8Array, origin: any) => {
      entry.lastSaved = new Date();

      const encoder = encoding.createEncoder();
      encoding.writeVarUint(encoder, messageSync);
      writeUpdate(encoder, update);
      const message = encoding.toUint8Array(encoder);

      for (const conn of entry.connections) {
        if (conn !== origin && conn.readyState === WebSocket.OPEN) {
          conn.send(message);
        }
      }

      this.broadcaster.broadcast({
        type: 'update',
        documentId,
        update,
        senderId: 'local',
      });

      this.scheduleSave(entry, documentId);
    });

    entry.awareness.on(
      'update',
      ({ added, updated, removed }: any, origin: any) => {
        const changedClients = added.concat(updated).concat(removed);
        const encoder = encoding.createEncoder();
        encoding.writeVarUint(encoder, messageAwareness);
        encoding.writeVarUint8Array(
          encoder,
          entry.awareness.encodeAwarenessUpdate(changedClients)
        );
        const message = encoding.toUint8Array(encoder);

        for (const conn of entry.connections) {
          if (conn !== origin && conn.readyState === WebSocket.OPEN) {
            conn.send(message);
          }
        }

        this.broadcaster.broadcast({
          type: 'awareness',
          documentId,
          update: message,
          senderId: 'local',
        });
      }
    );

    const unsubscribe = this.broadcaster.subscribe(
      documentId,
      (message: BroadcastMessage) => {
        this.handleBroadcastMessage(documentId, message);
      }
    );
    this.unsubscribeHandlers.set(documentId, unsubscribe);
  }

  private handleBroadcastMessage(
    documentId: string,
    message: BroadcastMessage
  ): void {
    const entry = this.docs.get(documentId);
    if (!entry) return;

    if (message.type === 'update') {
      Y.applyUpdate(entry.doc, message.update, 'remote');
    } else if (message.type === 'awareness') {
      applyAwarenessUpdate(entry.awareness, message.update, 'remote');
    }
  }

  private setupConnectionHandlers(ws: WebSocket, context: ConnectionContext): void {
    const pingInterval = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.ping();
      } else {
        clearInterval(pingInterval);
      }
    }, 30000);

    ws.on('pong', () => {
      const awarenessState = context.room.awareness.getLocalState() as any;
      if (awarenessState) {
        awarenessState.lastActive = Date.now();
      }
    });

    ws.on('close', () => clearInterval(pingInterval));
  }

  private sendInitialSync(ws: WebSocket, entry: YDocEntry): void {
    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageSync);
    writeSync(encoder, entry.doc);
    ws.send(encoding.toUint8Array(encoder));
  }

  private setupAwareness(
    ws: WebSocket,
    context: ConnectionContext,
    entry: YDocEntry
  ): void {
    const clientId = entry.awareness.clientID;
    context.controlledIds.add(clientId);

    const awarenessState = {
      user: context.user,
      lastActive: Date.now(),
    };

    entry.awareness.setLocalState(awarenessState);
  }

  private handleMessage(
    ws: WebSocket,
    message: Buffer,
    context: ConnectionContext
  ): void {
    try {
      const decoder = decoding.createDecoder(new Uint8Array(message));
      const messageType = decoding.readVarUint(decoder);

      switch (messageType) {
        case messageSync:
          this.handleSyncMessage(decoder, ws, context);
          break;
        case messageAwareness:
          this.handleAwarenessMessage(decoder, ws, context);
          break;
        case messageAuth:
          this.handleAuthMessage(decoder, ws, context);
          break;
        default:
          console.warn(
            '[YjsWebSocketServer] Unknown message type:',
            messageType
          );
      }
    } catch (error) {
      console.error('[YjsWebSocketServer] Error handling message:', error);
    }
  }

  private handleSyncMessage(
    decoder: decoding.Decoder,
    ws: WebSocket,
    context: ConnectionContext
  ): void {
    if (!context.permissions.canEdit) {
      const encoder = encoding.createEncoder();
      encoding.writeVarUint(encoder, messageSync);
      writeSync(encoder, context.room.doc);
      ws.send(encoding.toUint8Array(encoder));
      return;
    }

    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageSync);
    readSync(decoder, encoder, context.room.doc, ws);

    const message = encoding.toUint8Array(encoder);
    if (message.byteLength > 1) {
      ws.send(message);
    }
  }

  private handleAwarenessMessage(
    decoder: decoding.Decoder,
    _ws: WebSocket,
    context: ConnectionContext
  ): void {
    const update = decoding.readVarUint8Array(decoder);
    applyAwarenessUpdate(context.room.awareness, update, context.ws);

    const awarenessState = context.room.awareness.getLocalState() as any;
    if (awarenessState) {
      awarenessState.lastActive = Date.now();
    }
  }

  private handleAuthMessage(
    _decoder: decoding.Decoder,
    ws: WebSocket,
    context: ConnectionContext
  ): void {
    this.sendStatus(ws, {
      type: 'auth',
      authenticated: context.isAuthenticated,
      permissions: context.permissions,
    });
  }

  private sendStatus(ws: WebSocket, status: any): void {
    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageStatus);
    const jsonStr = JSON.stringify(status);
    encoding.writeVarString(encoder, jsonStr);
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(encoding.toUint8Array(encoder));
    }
  }

  private scheduleSave(entry: YDocEntry, documentId: string): void {
    if (entry.saveTimeout) {
      clearTimeout(entry.saveTimeout);
    }

    entry.saveTimeout = setTimeout(() => {
      this.saveDocument(entry, documentId).catch((error) => {
        console.error('[YjsWebSocketServer] Save error:', error);
      });
    }, this.config.saveDebounceMs);
  }

  async saveDocument(entry: YDocEntry, documentId: string): Promise<void> {
    if (entry.isLoading) {
      return;
    }

    try {
      entry.version = entry.version + 1;
      await saveDocumentState(documentId, entry.doc, entry.version);
      entry.lastSaved = new Date();

      const statusMessage = JSON.stringify({
        type: 'saved',
        version: entry.version,
        timestamp: entry.lastSaved.toISOString(),
      });

      for (const conn of entry.connections) {
        if (conn.readyState === WebSocket.OPEN) {
          const encoder = encoding.createEncoder();
          encoding.writeVarUint(encoder, messageStatus);
          encoding.writeVarString(encoder, statusMessage);
          conn.send(encoding.toUint8Array(encoder));
        }
      }
    } catch (error) {
      console.error('[YjsWebSocketServer] Failed to save document:', error);
      throw error;
    }
  }

  private handleClose(ws: WebSocket, context: ConnectionContext): void {
    try {
      removeAwarenessStates(
        context.room.awareness,
        Array.from(context.controlledIds),
        ws
      );

      context.room.connections.delete(ws);
      this.connections.delete(ws);

      if (context.room.connections.size === 0) {
        this.saveDocument(context.room, context.documentId)
          .then(() => {
            setTimeout(() => {
              if (context.room.connections.size === 0) {
                this.cleanupRoom(context.documentId);
              }
            }, 300000);
          })
          .catch((error) => {
            console.error(
              '[YjsWebSocketServer] Error on cleanup save:',
              error
            );
          });
      }
    } catch (error) {
      console.error('[YjsWebSocketServer] Error handling close:', error);
    }
  }

  private cleanupRoom(documentId: string): void {
    const entry = this.docs.get(documentId);
    if (!entry) return;

    if (entry.saveTimeout) {
      clearTimeout(entry.saveTimeout);
    }

    const unsubscribe = this.unsubscribeHandlers.get(documentId);
    if (unsubscribe) {
      unsubscribe();
      this.unsubscribeHandlers.delete(documentId);
    }

    entry.awareness.destroy();
    entry.doc.destroy();
    this.docs.delete(documentId);
  }

  private handleError(
    _ws: WebSocket,
    error: Error,
    _context: ConnectionContext
  ): void {
    console.error('[YjsWebSocketServer] WebSocket error:', error);
  }

  getOnlineUsers(documentId: string): CollabUser[] {
    const entry = this.docs.get(documentId);
    if (!entry) return [];

    const users: CollabUser[] = [];
    const states = entry.awareness.getStates();

    states.forEach((state: any) => {
      if (state && state.user) {
        users.push(state.user);
      }
    });

    return users;
  }

  getRoomInfo(documentId: string): RoomInfo | null {
    const entry = this.docs.get(documentId);
    if (!entry) return null;

    return {
      documentId,
      userCount: entry.connections.size,
      version: entry.version,
      lastSaved: entry.lastSaved.toISOString(),
      isActive: entry.connections.size > 0,
      onlineUsers: this.getOnlineUsers(documentId),
    };
  }

  getAllRooms(): RoomInfo[] {
    const rooms: RoomInfo[] = [];
    for (const documentId of this.docs.keys()) {
      const info = this.getRoomInfo(documentId);
      if (info) {
        rooms.push(info);
      }
    }
    return rooms;
  }

  async closeRoom(documentId: string): Promise<boolean> {
    const entry = this.docs.get(documentId);
    if (!entry) return false;

    for (const conn of entry.connections) {
      conn.close(1001, 'Room closed');
    }

    await this.saveDocument(entry, documentId);
    this.cleanupRoom(documentId);
    return true;
  }

  async closeAll(): Promise<void> {
    const closePromises: Promise<void>[] = [];

    for (const [documentId, entry] of this.docs) {
      for (const conn of entry.connections) {
        conn.close(1001, 'Server shutting down');
      }
      closePromises.push(this.saveDocument(entry, documentId));
    }

    await Promise.all(closePromises);
    await this.broadcaster.disconnect();
    this.docs.clear();
    this.connections.clear();
    this.unsubscribeHandlers.clear();
  }
}

function readSync(
  decoder: decoding.Decoder,
  encoder: encoding.Encoder,
  doc: Y.Doc,
  transactionOrigin: any
): string | null {
  const stepType = decoding.readVarUint(decoder);
  switch (stepType) {
    case 0:
      const step1Encoder = encoding.createEncoder();
      encoding.writeVarUint(step1Encoder, 0);
      writeYjsSyncStep2(step1Encoder, doc);
      const step1Msg = encoding.toUint8Array(step1Encoder);
      encoding.writeVarUint8Array(encoder, step1Msg);
      return 'sync-step-1';
    case 1:
      const update = decoding.readVarUint8Array(decoder);
      Y.applyUpdate(doc, update, transactionOrigin);
      return 'sync-step-2';
    case 2:
      const update2 = decoding.readVarUint8Array(decoder);
      Y.applyUpdate(doc, update2, transactionOrigin);
      return 'update';
    default:
      throw new Error('Unknown sync message type');
  }
}
