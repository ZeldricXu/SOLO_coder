import * as Y from 'yjs';
import { WebSocket as WSWebSocket } from 'ws';
import { Awareness, removeAwarenessStates, applyAwarenessUpdate, encodeAwarenessUpdate } from 'y-protocols/awareness';
import { writeSyncStep1, writeSyncStep2, readSyncStep1, readSyncStep2, writeUpdate } from 'y-protocols/sync';
import { encoding, decoding } from 'lib0';
import { prisma } from '../prisma';
import { encodeYDocState, decodeYDocState, debounce, uint8ArrayToBase64, base64ToUint8Array } from './utils';
import type { CollabUser, AwarenessState, DocumentPermissions, SaveOptions } from './types';

const messageSync = 0;
const messageAwareness = 1;
const messageAuth = 2;
const messageStatus = 3;

interface YDocEntry {
  doc: Y.Doc;
  awareness: Awareness;
  connections: Set<WSWebSocket>;
  documentId: string;
  version: number;
  lastSaved: Date;
  saveTimeout: NodeJS.Timeout | null;
  isLoading: boolean;
  loadPromise: Promise<void> | null;
}

interface ConnectionContext {
  ws: WSWebSocket;
  documentId: string;
  user: CollabUser;
  permissions: DocumentPermissions;
  room: YDocEntry;
  controlledIds: Set<number>;
  isAuthenticated: boolean;
}

export class YjsWebSocketServer {
  private docs: Map<string, YDocEntry> = new Map();
  private connections: Map<WSWebSocket, ConnectionContext> = new Map();
  private saveDebounceMs: number = 2000;
  private gcEnabled: boolean = true;

  constructor(options?: {
    saveDebounceMs?: number;
    gcEnabled?: boolean;
  }) {
    if (options?.saveDebounceMs !== undefined) {
      this.saveDebounceMs = options.saveDebounceMs;
    }
    if (options?.gcEnabled !== undefined) {
      this.gcEnabled = options.gcEnabled;
    }
  }

  async handleConnection(
    ws: WSWebSocket,
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
      ws.on('message', (message: Buffer) => this.handleMessage(ws, message, context));
      ws.on('error', (error) => this.handleError(ws, error, context));

      this.sendStatus(ws, { type: 'connected', documentId, user });
      
    } catch (error) {
      console.error('[YjsWebSocketServer] Connection error:', error);
      if (ws.readyState === WSWebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'error',
          message: error instanceof Error ? error.message : 'Connection failed'
        }));
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

    const doc = new Y.Doc({ gc: this.gcEnabled });
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

    entry.loadPromise = this.loadDocumentState(documentId, entry)
      .finally(() => {
        entry!.isLoading = false;
        entry!.loadPromise = null;
      });

    await entry.loadPromise;
    
    return entry;
  }

  private async loadDocumentState(documentId: string, entry: YDocEntry): Promise<void> {
    try {
      const document = await prisma.document.findUnique({
        where: { id: documentId },
        select: {
          content: true,
          yjsState: true,
          version: true,
        },
      });

      if (!document) {
        throw new Error(`Document not found: ${documentId}`);
      }

      entry.version = document.version || 1;

      if (document.yjsState) {
        try {
          const stateBytes = base64ToUint8Array(document.yjsState);
          decodeYDocState(stateBytes, entry.doc);
        } catch (e) {
          console.warn('[YjsWebSocketServer] Failed to load Yjs state, falling back to content');
          if (document.content) {
            const { markdownToYDoc } = require('./utils');
            markdownToYDoc(document.content, entry.doc);
          }
        }
      } else if (document.content) {
        const { markdownToYDoc } = require('./utils');
        markdownToYDoc(document.content, entry.doc);
      }

      entry.lastSaved = new Date();
    } catch (error) {
      console.error('[YjsWebSocketServer] Failed to load document state:', error);
      throw error;
    }
  }

  private setupDocListeners(entry: YDocEntry, documentId: string): void {
    if (entry.doc._observers.has('update')) {
      return;
    }

    entry.doc.on('update', (update: Uint8Array, origin: any) => {
      entry.lastSaved = new Date();
      
      this.sendAwarenessToAll(entry, origin);

      setImmediate(() => {
        const encoder = encoding.createEncoder();
        encoding.writeVarUint(encoder, messageSync);
        writeUpdate(encoder, update);
        const message = encoding.toUint8Array(encoder);

        for (const conn of entry.connections) {
          if (conn !== origin && conn.readyState === WSWebSocket.OPEN) {
            conn.send(message);
          }
        }
      });

      this.scheduleSave(entry, documentId);
    });

    entry.awareness.on('update', ({ added, updated, removed }: any, origin: any) => {
      const changedClients = added.concat(updated).concat(removed);
      const encoder = encoding.createEncoder();
      encoding.writeVarUint(encoder, messageAwareness);
      encoding.writeVarUint8Array(
        encoder,
        encodeAwarenessUpdate(entry.awareness, changedClients)
      );
      const message = encoding.toUint8Array(encoder);

      for (const conn of entry.connections) {
        if (conn !== origin && conn.readyState === WSWebSocket.OPEN) {
          conn.send(message);
        }
      }
    });
  }

  private sendAwarenessToAll(entry: YDocEntry, origin: any): void {
    const allClients = Array.from(entry.awareness.getStates().keys());
    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageAwareness);
    encoding.writeVarUint8Array(
      encoder,
      encodeAwarenessUpdate(entry.awareness, allClients)
    );
    const message = encoding.toUint8Array(encoder);

    for (const conn of entry.connections) {
      if (conn !== origin && conn.readyState === WSWebSocket.OPEN) {
        conn.send(message);
      }
    }
  }

  private setupConnectionHandlers(ws: WSWebSocket, context: ConnectionContext): void {
    const pingInterval = setInterval(() => {
      if (ws.readyState === WSWebSocket.OPEN) {
        ws.ping();
      } else {
        clearInterval(pingInterval);
      }
    }, 30000);

    ws.on('pong', () => {
      const awarenessState = context.room.awareness.getLocalState() as AwarenessState;
      if (awarenessState) {
        awarenessState.lastActive = Date.now();
      }
    });

    ws.on('close', () => clearInterval(pingInterval));
  }

  private sendInitialSync(ws: WSWebSocket, entry: YDocEntry): void {
    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageSync);
    writeSyncStep1(encoder, entry.doc);
    ws.send(encoding.toUint8Array(encoder));
  }

  private setupAwareness(ws: WSWebSocket, context: ConnectionContext, entry: YDocEntry): void {
    const clientId = entry.awareness.clientID;
    context.controlledIds.add(clientId);

    const awarenessState: AwarenessState = {
      user: context.user,
      lastActive: Date.now(),
    };

    entry.awareness.setLocalState(awarenessState);
  }

  private handleMessage(ws: WSWebSocket, message: Buffer, context: ConnectionContext): void {
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
          console.warn('[YjsWebSocketServer] Unknown message type:', messageType);
      }
    } catch (error) {
      console.error('[YjsWebSocketServer] Error handling message:', error);
    }
  }

  private handleSyncMessage(decoder: decoding.Decoder, ws: WSWebSocket, context: ConnectionContext): void {
    if (!context.permissions.canEdit) {
      const encoder = encoding.createEncoder();
      encoding.writeVarUint(encoder, messageSync);
      writeSyncStep1(encoder, context.room.doc);
      ws.send(encoding.toUint8Array(encoder));
      return;
    }

    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageSync);
    const syncMessageType = readSync(decoder, encoder, context.room.doc, ws);
    
    if (syncMessageType === 'sync-step-1' || syncMessageType === 'update') {
      const message = encoding.toUint8Array(encoder);
      if (message.byteLength > 1) {
        ws.send(message);
      }
    }
  }

  private handleAwarenessMessage(decoder: decoding.Decoder, _ws: WSWebSocket, context: ConnectionContext): void {
    const update = decoding.readVarUint8Array(decoder);
    applyAwarenessUpdate(context.room.awareness, update, context.ws);
    
    const awarenessState = context.room.awareness.getLocalState() as AwarenessState;
    if (awarenessState) {
      awarenessState.lastActive = Date.now();
    }
  }

  private handleAuthMessage(_decoder: decoding.Decoder, ws: WSWebSocket, context: ConnectionContext): void {
    this.sendStatus(ws, {
      type: 'auth',
      authenticated: context.isAuthenticated,
      permissions: context.permissions,
    });
  }

  private sendStatus(ws: WSWebSocket, status: any): void {
    const encoder = encoding.createEncoder();
    encoding.writeVarUint(encoder, messageStatus);
    const jsonStr = JSON.stringify(status);
    encoding.writeVarString(encoder, jsonStr);
    if (ws.readyState === WSWebSocket.OPEN) {
      ws.send(encoding.toUint8Array(encoder));
    }
  }

  private scheduleSave(entry: YDocEntry, documentId: string): void {
    if (entry.saveTimeout) {
      clearTimeout(entry.saveTimeout);
    }

    entry.saveTimeout = setTimeout(() => {
      this.saveDocument(entry, documentId).catch(error => {
        console.error('[YjsWebSocketServer] Save error:', error);
      });
    }, this.saveDebounceMs);
  }

  async saveDocument(entry: YDocEntry, documentId: string, options: SaveOptions = {}): Promise<void> {
    if (entry.isLoading) {
      if (options.force) {
        await entry.loadPromise;
      } else {
        return;
      }
    }

    try {
      const state = encodeYDocState(entry.doc);
      const stateBase64 = uint8ArrayToBase64(state);
      const { yDocToMarkdown } = require('./utils');
      const content = yDocToMarkdown(entry.doc);

      entry.version = (options.version !== undefined) ? options.version : entry.version + 1;

      await prisma.$transaction(async (tx) => {
        await tx.document.update({
          where: { id: documentId },
          data: {
            content,
            yjsState: stateBase64,
            version: entry.version,
            updatedAt: new Date(),
          },
        });
      });

      entry.lastSaved = new Date();

      const statusMessage = JSON.stringify({
        type: 'saved',
        version: entry.version,
        timestamp: entry.lastSaved.toISOString(),
      });

      for (const conn of entry.connections) {
        if (conn.readyState === WSWebSocket.OPEN) {
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

  private handleClose(ws: WSWebSocket, context: ConnectionContext): void {
    try {
      removeAwarenessStates(context.room.awareness, Array.from(context.controlledIds), ws);
      
      context.room.connections.delete(ws);
      this.connections.delete(ws);

      if (context.room.connections.size === 0) {
        this.saveDocument(context.room, context.documentId, { force: true })
          .then(() => {
            setTimeout(() => {
              if (context.room.connections.size === 0) {
                if (context.room.saveTimeout) {
                  clearTimeout(context.room.saveTimeout);
                }
                context.room.awareness.destroy();
                context.room.doc.destroy();
                this.docs.delete(context.documentId);
              }
            }, 300000);
          })
          .catch(error => {
            console.error('[YjsWebSocketServer] Error on cleanup save:', error);
          });
      }
    } catch (error) {
      console.error('[YjsWebSocketServer] Error handling close:', error);
    }
  }

  private handleError(_ws: WSWebSocket, error: Error, _context: ConnectionContext): void {
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

  getRoomInfo(documentId: string): {
    userCount: number;
    version: number;
    lastSaved: Date;
    isActive: boolean;
  } | null {
    const entry = this.docs.get(documentId);
    if (!entry) return null;

    return {
      userCount: entry.connections.size,
      version: entry.version,
      lastSaved: entry.lastSaved,
      isActive: entry.connections.size > 0,
    };
  }

  async closeAll(): Promise<void> {
    const closePromises: Promise<void>[] = [];

    for (const [documentId, entry] of this.docs) {
      for (const conn of entry.connections) {
        conn.close(1001, 'Server shutting down');
      }
      closePromises.push(this.saveDocument(entry, documentId, { force: true }));
    }

    await Promise.all(closePromises);
    this.docs.clear();
    this.connections.clear();
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
      readSyncStep1(decoder, encoder, doc);
      return 'sync-step-1';
    case 1:
      readSyncStep2(decoder, doc, transactionOrigin);
      return 'sync-step-2';
    case 2:
      const update = decoding.readVarUint8Array(decoder);
      Y.applyUpdate(doc, update, transactionOrigin);
      return 'update';
    default:
      throw new Error('Unknown sync message type');
  }
}

export const yjsServer = new YjsWebSocketServer({
  saveDebounceMs: 2000,
  gcEnabled: true,
});
