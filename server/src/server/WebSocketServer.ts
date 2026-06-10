import { Server as HttpServer, IncomingMessage } from 'http';
import { Socket } from 'net';
import { Duplex } from 'stream';
import { WebSocketServer as WsServer, WebSocket, RawData } from 'ws';
import { v4 as uuidv4 } from 'uuid';
import { WebSocketClient, SignalingMessage, ServerConfig, DEFAULT_CONFIG } from '../types';
import { RoomManager } from './RoomManager';
import { MessageHandler } from './MessageHandler';
import { wsAuthMiddleware } from '../middleware/auth';
import { createLogger } from '../utils/logger';

const logger = createLogger('WebSocketServer');

export class SignalingWebSocketServer {
  private wss: WsServer;
  private clients: Map<string, WebSocketClient> = new Map();
  private roomManager: RoomManager;
  private messageHandler: MessageHandler;
  private config: ServerConfig;
  private heartbeatInterval: NodeJS.Timeout | null = null;
  private cleanupInterval: NodeJS.Timeout | null = null;
  private httpServer: HttpServer | null = null;

  constructor(config: Partial<ServerConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config };
    this.roomManager = new RoomManager(this.config.maxOperationsPerRoom);
    this.messageHandler = new MessageHandler(
      this.roomManager,
      (clientId, message) => this.sendToClient(clientId, message),
      (roomId, message, excludeUserId) => this.sendToRoom(roomId, message, excludeUserId),
      (clientId, reason) => this.disconnectClient(clientId, reason),
      this.config.cursorThrottleMs
    );
    this.wss = new WsServer({ noServer: true });
  }

  attach(server: HttpServer): void {
    this.httpServer = server;

    server.on('upgrade', (request, socket, head) => {
      this.handleUpgrade(request, socket, head);
    });

    this.wss.on('connection', (ws, request) => {
      this.handleConnection(ws, request);
    });

    this.startHeartbeatCheck();
    this.startRoomCleanup();

    logger.info('WebSocket server attached to HTTP server', {
      heartbeatInterval: this.config.heartbeatInterval,
      heartbeatTimeout: this.config.heartbeatTimeout
    });
  }

  private handleUpgrade(request: IncomingMessage, socket: Duplex, head: Buffer): void {
    const authResult = wsAuthMiddleware(request);

    if (!authResult.authorized) {
      logger.warn('WebSocket connection rejected - auth failed', { error: authResult.error });
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }

    this.wss.handleUpgrade(request, socket, head, (ws) => {
      (ws as WebSocket & { userId?: string }).userId = authResult.userId;
      this.wss.emit('connection', ws, request);
    });
  }

  private handleConnection(ws: WebSocket, request: IncomingMessage): void {
    const clientId = uuidv4();
    const userId = (ws as WebSocket & { userId?: string }).userId || `anon-${clientId.slice(0, 8)}`;

    const client: WebSocketClient = {
      id: clientId,
      userId,
      roomId: null,
      socket: ws,
      lastHeartbeat: Date.now(),
      isAlive: true
    };

    this.clients.set(clientId, client);

    const ip = request.socket.remoteAddress || 'unknown';
    logger.info('Client connected', { clientId, userId, ip, totalClients: this.clients.size });

    ws.on('message', (data: RawData) => {
      this.handleClientMessage(client, data);
    });

    ws.on('close', (code, reason) => {
      this.handleClientClose(client, code, reason);
    });

    ws.on('error', (error) => {
      this.handleClientError(client, error);
    });

    ws.on('pong', () => {
      client.isAlive = true;
      client.lastHeartbeat = Date.now();
    });
  }

  private handleClientMessage(client: WebSocketClient, data: RawData): void {
    try {
      const message = data.toString();
      logger.debug('Received message from client', {
        clientId: client.id,
        userId: client.userId,
        messageLength: message.length
      });
      this.messageHandler.handleMessage(client, message);
    } catch (error) {
      logger.error('Error handling client message', {
        clientId: client.id,
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  private handleClientClose(client: WebSocketClient, code: number, reason: Buffer): void {
    logger.info('Client disconnected', {
      clientId: client.id,
      userId: client.userId,
      code,
      reason: reason.toString()
    });

    this.messageHandler.handleClientDisconnect(client);
    this.clients.delete(client.id);
  }

  private handleClientError(client: WebSocketClient, error: Error): void {
    logger.error('Client error', {
      clientId: client.id,
      userId: client.userId,
      error: error.message
    });
  }

  private sendToClient(clientId: string, message: SignalingMessage): void {
    const client = this.clients.get(clientId);
    if (!client) {
      logger.warn('Attempted to send message to unknown client', { clientId });
      return;
    }

    if (client.socket.readyState !== WebSocket.OPEN) {
      logger.warn('Attempted to send message to non-open client', {
        clientId,
        readyState: client.socket.readyState
      });
      return;
    }

    try {
      const serialized = JSON.stringify(message);
      client.socket.send(serialized);
      logger.debug('Sent message to client', {
        clientId,
        messageType: message.type,
        messageLength: serialized.length
      });
    } catch (error) {
      logger.error('Failed to send message to client', {
        clientId,
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  private sendToRoom(roomId: string, message: SignalingMessage, excludeUserId?: string): void {
    const userIds = this.roomManager.getUserIds(roomId);
    if (userIds.length === 0) {
      return;
    }

    const excludedSet = excludeUserId ? new Set([excludeUserId]) : null;
    let sentCount = 0;

    for (const client of this.clients.values()) {
      if (client.roomId !== roomId) {
        continue;
      }
      if (excludedSet && excludedSet.has(client.userId)) {
        continue;
      }
      if (client.socket.readyState !== WebSocket.OPEN) {
        continue;
      }

      try {
        const serialized = JSON.stringify(message);
        client.socket.send(serialized);
        sentCount++;
      } catch (error) {
        logger.error('Failed to send message to room member', {
          clientId: client.id,
          roomId,
          error: error instanceof Error ? error.message : String(error)
        });
      }
    }

    logger.debug('Broadcast message to room', {
      roomId,
      messageType: message.type,
      sentCount,
      excludedUserId: excludeUserId
    });
  }

  private disconnectClient(clientId: string, reason?: string): void {
    const client = this.clients.get(clientId);
    if (!client) {
      return;
    }

    logger.info('Disconnecting client', { clientId, userId: client.userId, reason });

    if (client.socket.readyState === WebSocket.OPEN) {
      client.socket.close(1000, reason || 'Server initiated disconnect');
    } else {
      client.socket.terminate();
    }

    this.messageHandler.handleClientDisconnect(client);
    this.clients.delete(clientId);
  }

  private startHeartbeatCheck(): void {
    this.heartbeatInterval = setInterval(() => {
      const now = Date.now();
      let pingedCount = 0;
      let terminatedCount = 0;

      for (const client of this.clients.values()) {
        if (client.socket.readyState !== WebSocket.OPEN) {
          continue;
        }

        const timeSinceHeartbeat = now - client.lastHeartbeat;

        if (timeSinceHeartbeat > this.config.heartbeatTimeout) {
          logger.warn('Heartbeat timeout, terminating client', {
            clientId: client.id,
            userId: client.userId,
            timeSinceHeartbeat
          });
          client.socket.terminate();
          this.messageHandler.handleClientDisconnect(client);
          this.clients.delete(client.id);
          terminatedCount++;
          continue;
        }

        if (!client.isAlive) {
          continue;
        }

        client.isAlive = false;
        try {
          client.socket.ping();
          pingedCount++;
        } catch (error) {
          logger.error('Failed to ping client', {
            clientId: client.id,
            error: error instanceof Error ? error.message : String(error)
          });
        }
      }

      logger.debug('Heartbeat check completed', {
        totalClients: this.clients.size,
        pingedCount,
        terminatedCount
      });
    }, this.config.heartbeatInterval);
  }

  private startRoomCleanup(): void {
    this.cleanupInterval = setInterval(() => {
      const removed = this.roomManager.cleanupEmptyRooms();
      if (removed.length > 0) {
        logger.info('Cleaned up empty rooms', { count: removed.length, rooms: removed });
        for (const roomId of removed) {
          this.messageHandler.getOperationBuffer().clear(roomId);
        }
      }
    }, 60000);
  }

  getClientCount(): number {
    return this.clients.size;
  }

  getRoomManager(): RoomManager {
    return this.roomManager;
  }

  getMessageHandler(): MessageHandler {
    return this.messageHandler;
  }

  getStats(): {
    totalClients: number;
    rooms: Record<string, { userCount: number; operationCount: number; lastSequence: number }>;
    clients: Array<{ clientId: string; userId: string; roomId: string | null; isAlive: boolean }>;
  } {
    return {
      totalClients: this.clients.size,
      rooms: this.roomManager.getStats(),
      clients: Array.from(this.clients.values()).map(c => ({
        clientId: c.id,
        userId: c.userId,
        roomId: c.roomId,
        isAlive: c.isAlive
      }))
    };
  }

  close(): Promise<void> {
    return new Promise((resolve) => {
      logger.info('Closing WebSocket server');

      this.messageHandler.flushAllBuffers();

      if (this.heartbeatInterval) {
        clearInterval(this.heartbeatInterval);
        this.heartbeatInterval = null;
      }

      if (this.cleanupInterval) {
        clearInterval(this.cleanupInterval);
        this.cleanupInterval = null;
      }

      for (const client of this.clients.values()) {
        this.messageHandler.handleClientDisconnect(client);
        if (client.socket.readyState === WebSocket.OPEN) {
          client.socket.close(1001, 'Server shutting down');
        } else {
          client.socket.terminate();
        }
      }
      this.clients.clear();

      this.messageHandler.destroy();

      this.wss.close(() => {
        logger.info('WebSocket server closed');
        resolve();
      });
    });
  }
}
