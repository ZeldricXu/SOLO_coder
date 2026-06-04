import { WebSocketServer, WebSocket } from 'ws';
import * as Y from 'yjs';
import { setupWSConnection } from 'y-websocket/bin/utils.js';
import * as crypto from 'crypto';

const FULL_SYNC_THRESHOLD_MS = 10000;

interface Collaborator {
  id: string;
  name: string;
  color: string;
  cursor: { x: number; y: number };
  lastSeen: number;
  disconnectedAt?: number;
}

interface Room {
  doc: Y.Doc;
  collaborators: Map<string, Collaborator>;
  createdAt: number;
}

const COLORS = [
  '#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4',
  '#FFEAA7', '#DDA0DD', '#98D8C8', '#F7DC6F',
  '#BB8FCE', '#85C1E9', '#F8B500', '#00CED1',
];

function computeStateVectorHash(doc: Y.Doc): string {
  const sv = Y.encodeStateVector(doc);
  return crypto.createHash('sha256').update(sv).digest('hex');
}

function encodeYjsMessage(encoder: any): Uint8Array {
  return encoder.toUint8Array();
}

export function setupCollaborationServer(wss: WebSocketServer) {
  const rooms = new Map<string, Room>();

  setInterval(() => {
    const now = Date.now();
    for (const [roomId, room] of rooms) {
      for (const [userId, collab] of room.collaborators) {
        if (now - collab.lastSeen > 30000) {
          if (!collab.disconnectedAt) {
            collab.disconnectedAt = now;
          }
          if (now - collab.lastSeen > 60000) {
            room.collaborators.delete(userId);
            broadcastCollaborators(room, roomId);
            console.log(`👤 User ${userId} timed out from room ${roomId}`);
          }
        }
      }
      if (room.collaborators.size === 0 && now - room.createdAt > 3600000) {
        rooms.delete(roomId);
        console.log(`🗑️ Room ${roomId} cleaned up (empty and old)`);
      }
    }
  }, 10000);

  wss.on('connection', (ws: WebSocket & { isAlive?: boolean }, req) => {
    const url = new URL(req.url || '/', `http://${req.headers.host}`);
    const roomId = url.searchParams.get('room') || 'default';
    const userId = url.searchParams.get('user') || generateUserId();
    const userName = url.searchParams.get('name') || `用户${userId.slice(0, 4)}`;

    ws.isAlive = true;

    console.log(`🔌 New connection: ${userId} (${userName}) to room ${roomId}`);

    if (!rooms.has(roomId)) {
      rooms.set(roomId, {
        doc: new Y.Doc(),
        collaborators: new Map(),
        createdAt: Date.now(),
      });
      console.log(`🏠 Created new room: ${roomId}`);
    }

    const room = rooms.get(roomId)!;

    const existingCollab = room.collaborators.get(userId);
    const wasDisconnected = existingCollab?.disconnectedAt !== undefined;
    const disconnectDuration = wasDisconnected && existingCollab?.disconnectedAt
      ? Date.now() - existingCollab.disconnectedAt
      : 0;
    const needsFullSync = wasDisconnected && disconnectDuration > FULL_SYNC_THRESHOLD_MS;

    if (needsFullSync) {
      console.log(`🔄 User ${userId} reconnecting after ${Math.round(disconnectDuration / 1000)}s - performing full sync`);
    }

    const color = existingCollab?.color || COLORS[room.collaborators.size % COLORS.length];
    const collaborator: Collaborator = {
      id: userId,
      name: userName,
      color,
      cursor: existingCollab?.cursor || { x: 0, y: 0 },
      lastSeen: Date.now(),
    };
    room.collaborators.set(userId, collaborator);

    const wsSend = ws.send.bind(ws);

    ws.send = (data: any, cb?: any) => {
      if (needsFullSync && typeof data === 'object' && data instanceof Uint8Array) {
        const view = new Uint8Array(data);
        if (view[0] === 0) {
          const serverHash = computeStateVectorHash(room.doc);
          const fullSyncMsg = JSON.stringify({
            type: 'full-sync-complete',
            serverStateVectorHash: serverHash,
            timestamp: Date.now(),
          });
          wsSend(JSON.stringify({ type: 'sync-step1', data: fullSyncMsg }), cb);
          return wsSend(data, cb);
        }
      }
      return wsSend(data, cb);
    };

    const originalOnMessage = ws.on.bind(ws);

    ws.on('message', (message: Buffer) => {
      collaborator.lastSeen = Date.now();
      if (collaborator.disconnectedAt) {
        delete collaborator.disconnectedAt;
      }

      try {
        const data = JSON.parse(message.toString());
        
        if (data.type === 'cursor') {
          collaborator.cursor = { x: data.x, y: data.y };
          broadcastCollaborators(room, roomId);
        }
        
        if (data.type === 'ping') {
          ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
        }

        if (data.type === 'full-sync-request') {
          console.log(`📤 Full sync request from ${userId}`);
          handleFullSyncRequest(ws, room, userId);
        }

        if (data.type === 'verify-hash') {
          const serverHash = computeStateVectorHash(room.doc);
          const matches = data.hash === serverHash;
          ws.send(JSON.stringify({
            type: 'hash-verification',
            matches,
            serverHash,
            clientHash: data.hash,
            timestamp: Date.now(),
          }));
          if (!matches) {
            console.warn(`⚠️ Hash mismatch for ${userId}! Client: ${data.hash.slice(0, 16)}..., Server: ${serverHash.slice(0, 16)}...`);
            ws.send(JSON.stringify({
              type: 'force-reload',
              reason: 'State hash mismatch after sync. Please reload the scene.',
              timestamp: Date.now(),
            }));
          }
        }
      } catch (e) {
      }
    });

    setupWSConnection(ws, req, {
      ...Y,
      gc: true,
    });

    ws.on('close', () => {
      console.log(`🔌 User ${userId} disconnected from room ${roomId}`);
      const collab = room.collaborators.get(userId);
      if (collab) {
        collab.disconnectedAt = Date.now();
      }
      setTimeout(() => {
        const current = room.collaborators.get(userId);
        if (current?.disconnectedAt) {
          room.collaborators.delete(userId);
          broadcastCollaborators(room, roomId);
        }
      }, 5000);
    });

    ws.on('error', (error) => {
      console.error(`❌ WebSocket error for ${userId}:`, error);
    });

    setTimeout(() => {
      if (needsFullSync) {
        ws.send(JSON.stringify({
          type: 'full-sync-recommended',
          disconnectDuration,
          threshold: FULL_SYNC_THRESHOLD_MS,
          timestamp: Date.now(),
        }));
      }
      broadcastCollaborators(room, roomId);
    }, 100);
  });

  function handleFullSyncRequest(ws: WebSocket, room: Room, userId: string) {
    try {
      const serverSV = Y.encodeStateVector(room.doc);
      const update = Y.encodeStateAsUpdate(room.doc);
      const serverHash = computeStateVectorHash(room.doc);

      ws.send(JSON.stringify({
        type: 'full-sync-response',
        hasStateVector: true,
        hasFullUpdate: true,
        stateVector: Buffer.from(serverSV).toString('base64'),
        fullUpdate: Buffer.from(update).toString('base64'),
        serverHash,
        timestamp: Date.now(),
      }));

      console.log(`📥 Full sync sent to ${userId}: ${update.length} bytes, hash: ${serverHash.slice(0, 16)}...`);
    } catch (error) {
      console.error(`❌ Failed to send full sync to ${userId}:`, error);
      ws.send(JSON.stringify({
        type: 'full-sync-error',
        error: 'Failed to generate full sync data',
        timestamp: Date.now(),
      }));
    }
  }

  function broadcastCollaborators(room: Room, roomId: string) {
    const message = JSON.stringify({
      type: 'collaborators',
      roomId,
      collaborators: Array.from(room.collaborators.values()).map(c => ({
        id: c.id,
        name: c.name,
        color: c.color,
        cursor: c.cursor,
        isOnline: !c.disconnectedAt,
      })),
    });

    for (const client of wss.clients) {
      if (client.readyState === WebSocket.OPEN) {
        client.send(message);
      }
    }
  }

  console.log('🤝 Collaboration server initialized with full sync support');
}

function generateUserId(): string {
  return 'user_' + Math.random().toString(36).substring(2, 10);
}
