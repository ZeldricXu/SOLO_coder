import { v4 as uuidv4 } from 'uuid';
import {
  SignalingMessage,
  MessageType,
  User,
  Operation,
  CursorUpdate,
  JoinRoomMessage,
  LeaveRoomMessage,
  OperationMessage,
  CursorMessage,
  HeartbeatMessage,
  PresenceMessage,
  OperationBatchMessage,
  HeartbeatAckMessage,
  ErrorMessage,
  AckMessage,
  WebSocketClient,
  OperationBatch,
  DEFAULT_CONFIG
} from '../types';
import { RoomManager } from './RoomManager';
import { OperationBuffer } from './OperationBuffer';
import { createLogger } from '../utils/logger';

const logger = createLogger('MessageHandler');

export type SendToClientFn = (clientId: string, message: SignalingMessage) => void;
export type SendToRoomFn = (roomId: string, message: SignalingMessage, excludeUserId?: string) => void;
export type DisconnectClientFn = (clientId: string, reason?: string) => void;

interface CursorThrottleState {
  lastSendTime: number;
  pendingCursor: CursorUpdate | null;
  timer: NodeJS.Timeout | null;
}

export class MessageHandler {
  private roomManager: RoomManager;
  private operationBuffer: OperationBuffer;
  private sendToClient: SendToClientFn;
  private sendToRoom: SendToRoomFn;
  private disconnectClient: DisconnectClientFn;
  private cursorThrottleMs: number;
  private cursorStates: Map<string, CursorThrottleState> = new Map();

  constructor(
    roomManager: RoomManager,
    sendToClient: SendToClientFn,
    sendToRoom: SendToRoomFn,
    disconnectClient: DisconnectClientFn,
    cursorThrottleMs: number = DEFAULT_CONFIG.cursorThrottleMs
  ) {
    this.roomManager = roomManager;
    this.sendToClient = sendToClient;
    this.sendToRoom = sendToRoom;
    this.disconnectClient = disconnectClient;
    this.cursorThrottleMs = cursorThrottleMs;

    this.operationBuffer = new OperationBuffer(
      (roomId, operations, batchSequence) => {
        this.flushOperationBatch(roomId, operations, batchSequence);
      }
    );
  }

  handleMessage(client: WebSocketClient, rawMessage: string): void {
    try {
      const message = JSON.parse(rawMessage) as SignalingMessage;
      this.routeMessage(client, message);
    } catch (error) {
      logger.error('Failed to parse message', {
        clientId: client.id,
        error: error instanceof Error ? error.message : String(error)
      });
      this.sendError(client, 'INVALID_MESSAGE', 'Failed to parse message');
    }
  }

  private routeMessage(client: WebSocketClient, message: SignalingMessage): void {
    switch (message.type) {
      case MessageType.JOIN_ROOM:
        this.handleJoinRoom(client, message as JoinRoomMessage);
        break;
      case MessageType.LEAVE_ROOM:
        this.handleLeaveRoom(client, message as LeaveRoomMessage);
        break;
      case MessageType.OPERATION:
        this.handleOperation(client, message as OperationMessage);
        break;
      case MessageType.CURSOR:
        this.handleCursor(client, message as CursorMessage);
        break;
      case MessageType.HEARTBEAT:
        this.handleHeartbeat(client, message as HeartbeatMessage);
        break;
      default:
        logger.warn('Unhandled message type', {
          clientId: client.id,
          type: message.type
        });
        this.sendError(client, 'UNKNOWN_MESSAGE_TYPE', `Unknown message type: ${message.type}`);
    }
  }

  private handleJoinRoom(client: WebSocketClient, message: JoinRoomMessage): void {
    const { roomId, user } = message;

    if (!roomId || !user || !user.id) {
      this.sendError(client, 'INVALID_JOIN', 'Missing roomId or user information');
      return;
    }

    if (client.roomId && client.roomId !== roomId) {
      this.leaveRoomInternal(client);
    }

    client.roomId = roomId;
    client.userId = user.id;

    const presence = this.roomManager.addUser(roomId, user);
    this.roomManager.updateUserActivity(roomId, user.id);

    this.sendAck(client, MessageType.JOIN_ROOM, true, {
      users: this.roomManager.getUsers(roomId),
      lastSequence: this.roomManager.getCurrentSequence(roomId)
    });

    const presenceMessage: PresenceMessage = {
      type: MessageType.PRESENCE,
      roomId,
      userId: user.id,
      timestamp: Date.now(),
      users: this.roomManager.getUsers(roomId),
      event: 'join',
      targetUser: presence
    };
    this.sendToRoom(roomId, presenceMessage, user.id);

    logger.info('User joined room', {
      clientId: client.id,
      roomId,
      userId: user.id,
      userName: user.name
    });
  }

  private handleLeaveRoom(client: WebSocketClient, message: LeaveRoomMessage): void {
    if (!client.roomId) {
      return;
    }
    this.leaveRoomInternal(client);
    this.sendAck(client, MessageType.LEAVE_ROOM, true);
  }

  private leaveRoomInternal(client: WebSocketClient): void {
    if (!client.roomId) {
      return;
    }

    const roomId = client.roomId;
    const userId = client.userId;
    const presence = this.roomManager.removeUser(roomId, userId);

    this.clearCursorState(userId);
    this.operationBuffer.flush(roomId);

    if (presence) {
      const presenceMessage: PresenceMessage = {
        type: MessageType.PRESENCE,
        roomId,
        userId,
        timestamp: Date.now(),
        users: this.roomManager.getUsers(roomId),
        event: 'leave',
        targetUser: presence
      };
      this.sendToRoom(roomId, presenceMessage);
    }

    logger.info('User left room', { clientId: client.id, roomId, userId });
    client.roomId = null;
  }

  private handleOperation(client: WebSocketClient, message: OperationMessage): void {
    if (!client.roomId) {
      this.sendError(client, 'NOT_IN_ROOM', 'You must join a room first');
      return;
    }

    const { operation } = message;
    if (!operation || !operation.type) {
      this.sendError(client, 'INVALID_OPERATION', 'Invalid operation data');
      return;
    }

    this.roomManager.updateUserActivity(client.roomId, client.userId);

    const sequence = this.roomManager.getNextSequence(client.roomId);
    const enrichedOperation: Operation = {
      id: operation.id || uuidv4(),
      type: operation.type,
      timestamp: operation.timestamp || Date.now(),
      userId: client.userId,
      sequence,
      payload: operation.payload || {}
    };

    this.roomManager.addOperation(client.roomId, enrichedOperation);
    this.operationBuffer.addOperation(client.roomId, enrichedOperation, false);

    this.sendAck(client, MessageType.OPERATION, true, {
      sequence: enrichedOperation.sequence,
      operationId: enrichedOperation.id
    });
  }

  private handleCursor(client: WebSocketClient, message: CursorMessage): void {
    if (!client.roomId) {
      this.sendError(client, 'NOT_IN_ROOM', 'You must join a room first');
      return;
    }

    const { cursor } = message;
    if (!cursor || !cursor.position) {
      this.sendError(client, 'INVALID_CURSOR', 'Invalid cursor data');
      return;
    }

    this.roomManager.updateUserActivity(client.roomId, client.userId);

    const userPresence = this.roomManager.getUser(client.roomId, client.userId);
    if (!userPresence) {
      return;
    }

    const enrichedCursor: CursorUpdate = {
      id: userPresence.id,
      name: userPresence.name,
      avatar: userPresence.avatar,
      color: userPresence.color,
      position: cursor.position,
      timestamp: Date.now()
    };

    this.throttleCursor(client.userId, client.roomId, enrichedCursor);
  }

  private throttleCursor(userId: string, roomId: string, cursor: CursorUpdate): void {
    let state = this.cursorStates.get(userId);
    if (!state) {
      state = {
        lastSendTime: 0,
        pendingCursor: null,
        timer: null
      };
      this.cursorStates.set(userId, state);
    }

    const now = Date.now();
    const elapsed = now - state.lastSendTime;

    if (elapsed >= this.cursorThrottleMs) {
      this.sendCursorUpdate(roomId, userId, cursor, state);
      return;
    }

    state.pendingCursor = cursor;

    if (!state.timer) {
      const remaining = this.cursorThrottleMs - elapsed;
      state.timer = setTimeout(() => {
        const currentState = this.cursorStates.get(userId);
        if (currentState && currentState.pendingCursor) {
          this.sendCursorUpdate(roomId, userId, currentState.pendingCursor, currentState);
        }
      }, remaining);
    }
  }

  private sendCursorUpdate(roomId: string, userId: string, cursor: CursorUpdate, state: CursorThrottleState): void {
    state.lastSendTime = Date.now();
    state.pendingCursor = null;
    if (state.timer) {
      clearTimeout(state.timer);
      state.timer = null;
    }

    const cursorMessage: CursorMessage = {
      type: MessageType.CURSOR,
      roomId,
      userId,
      timestamp: Date.now(),
      cursor
    };

    this.sendToRoom(roomId, cursorMessage, userId);
  }

  private handleHeartbeat(client: WebSocketClient, _message: HeartbeatMessage): void {
    client.lastHeartbeat = Date.now();
    client.isAlive = true;

    if (client.roomId) {
      this.roomManager.updateUserActivity(client.roomId, client.userId);
    }

    const ack: HeartbeatAckMessage = {
      type: MessageType.HEARTBEAT_ACK,
      roomId: client.roomId || '',
      userId: client.userId,
      timestamp: Date.now(),
      serverTime: Date.now()
    };

    this.sendToClient(client.id, ack);
  }

  private flushOperationBatch(roomId: string, operations: Operation[], batchSequence: number): void {
    if (operations.length === 0) {
      return;
    }

    if (operations.length === 1) {
      const op = operations[0];
      const message: OperationMessage = {
        type: MessageType.OPERATION,
        roomId,
        userId: op.userId,
        timestamp: Date.now(),
        operation: op
      };
      this.sendToRoom(roomId, message, op.userId);
      return;
    }

    const batch: OperationBatch = {
      roomId,
      operations,
      batchSequence
    };

    const message: OperationBatchMessage = {
      type: MessageType.OPERATION_BATCH,
      roomId,
      userId: operations[0].userId,
      timestamp: Date.now(),
      batch
    };

    const uniqueUserIds = new Set(operations.map(op => op.userId));
    for (const senderId of uniqueUserIds) {
      this.sendToRoom(roomId, message, senderId);
    }

    logger.debug('Broadcasting operation batch', {
      roomId,
      operationCount: operations.length,
      batchSequence
    });
  }

  private sendError(client: WebSocketClient, code: string, message: string): void {
    const errorMessage: ErrorMessage = {
      type: MessageType.ERROR,
      roomId: client.roomId || '',
      userId: client.userId,
      timestamp: Date.now(),
      code,
      message
    };
    this.sendToClient(client.id, errorMessage);
  }

  private sendAck(client: WebSocketClient, refMessageType: MessageType, success: boolean, data?: Record<string, unknown>): void {
    const ack: AckMessage = {
      type: MessageType.ACK,
      roomId: client.roomId || '',
      userId: client.userId,
      timestamp: Date.now(),
      refMessageType,
      success,
      data
    };
    this.sendToClient(client.id, ack);
  }

  private clearCursorState(userId: string): void {
    const state = this.cursorStates.get(userId);
    if (state) {
      if (state.timer) {
        clearTimeout(state.timer);
      }
      this.cursorStates.delete(userId);
    }
  }

  handleClientDisconnect(client: WebSocketClient): void {
    logger.info('Handling client disconnect', { clientId: client.id, userId: client.userId });
    this.leaveRoomInternal(client);
    this.clearCursorState(client.userId);
  }

  getOperationBuffer(): OperationBuffer {
    return this.operationBuffer;
  }

  flushAllBuffers(): void {
    this.operationBuffer.flushAll();
  }

  destroy(): void {
    for (const [, state] of this.cursorStates) {
      if (state.timer) {
        clearTimeout(state.timer);
      }
    }
    this.cursorStates.clear();
    this.operationBuffer.destroy();
  }
}
