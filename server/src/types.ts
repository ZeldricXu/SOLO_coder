export enum MessageType {
  JOIN_ROOM = 'join_room',
  LEAVE_ROOM = 'leave_room',
  PRESENCE = 'presence',
  OPERATION = 'operation',
  OPERATION_BATCH = 'operation_batch',
  CURSOR = 'cursor',
  HEARTBEAT = 'heartbeat',
  HEARTBEAT_ACK = 'heartbeat_ack',
  ERROR = 'error',
  ACK = 'ack',
  YJS_SYNC = 'yjs_sync',
  YJS_AWARENESS = 'yjs_awareness',
  YJS_SYNC_STEP1 = 'yjs_sync_step1',
  YJS_SYNC_STEP2 = 'yjs_sync_step2',
  YJS_UPDATE = 'yjs_update'
}

export interface User {
  id: string;
  name: string;
  avatar?: string;
  color: string;
}

export interface UserPresence extends User {
  joinedAt: number;
  lastActive: number;
}

export interface CursorPosition {
  x: number;
  y: number;
  viewport?: {
    zoom: number;
    panX: number;
    panY: number;
  };
}

export interface CursorUpdate extends User {
  position: CursorPosition;
  timestamp: number;
}

export interface BaseOperation {
  id: string;
  type: string;
  timestamp: number;
  userId: string;
}

export interface Operation extends BaseOperation {
  sequence: number;
  payload: Record<string, unknown>;
}

export interface OperationBatch {
  roomId: string;
  operations: Operation[];
  batchSequence: number;
}

export interface Room {
  id: string;
  users: Map<string, UserPresence>;
  operations: Operation[];
  lastSequence: number;
  createdAt: number;
}

export interface BaseMessage {
  type: MessageType;
  roomId: string;
  userId: string;
  timestamp: number;
}

export interface JoinRoomMessage extends BaseMessage {
  type: MessageType.JOIN_ROOM;
  user: User;
}

export interface LeaveRoomMessage extends BaseMessage {
  type: MessageType.LEAVE_ROOM;
}

export interface PresenceMessage extends BaseMessage {
  type: MessageType.PRESENCE;
  users: UserPresence[];
  event: 'join' | 'leave' | 'update';
  targetUser?: UserPresence;
}

export interface OperationMessage extends BaseMessage {
  type: MessageType.OPERATION;
  operation: Operation;
}

export interface OperationBatchMessage extends BaseMessage {
  type: MessageType.OPERATION_BATCH;
  batch: OperationBatch;
}

export interface CursorMessage extends BaseMessage {
  type: MessageType.CURSOR;
  cursor: CursorUpdate;
}

export interface HeartbeatMessage extends BaseMessage {
  type: MessageType.HEARTBEAT;
}

export interface HeartbeatAckMessage extends BaseMessage {
  type: MessageType.HEARTBEAT_ACK;
  serverTime: number;
}

export interface ErrorMessage extends BaseMessage {
  type: MessageType.ERROR;
  code: string;
  message: string;
}

export interface AckMessage extends BaseMessage {
  type: MessageType.ACK;
  refMessageType: MessageType;
  success: boolean;
  data?: Record<string, unknown>;
}

export interface YjsSyncMessage extends BaseMessage {
  type: MessageType.YJS_SYNC;
  subType: 'step1' | 'step2' | 'update';
  data: string;
}

export interface YjsAwarenessMessage extends BaseMessage {
  type: MessageType.YJS_AWARENESS;
  clientId: number;
  awarenessUpdate: string;
}

export interface YjsSyncStep1Message extends BaseMessage {
  type: MessageType.YJS_SYNC_STEP1;
  stateVector: string;
  senderClientId: string;
}

export interface YjsSyncStep2Message extends BaseMessage {
  type: MessageType.YJS_SYNC_STEP2;
  diff: string;
  senderClientId: string;
}

export interface YjsUpdateMessage extends BaseMessage {
  type: MessageType.YJS_UPDATE;
  update: string;
  senderClientId: string;
}

export type SignalingMessage =
  | JoinRoomMessage
  | LeaveRoomMessage
  | PresenceMessage
  | OperationMessage
  | OperationBatchMessage
  | CursorMessage
  | HeartbeatMessage
  | HeartbeatAckMessage
  | ErrorMessage
  | AckMessage
  | YjsSyncMessage
  | YjsAwarenessMessage
  | YjsSyncStep1Message
  | YjsSyncStep2Message
  | YjsUpdateMessage;

export interface WebSocketClient {
  id: string;
  userId: string;
  roomId: string | null;
  socket: import('ws').WebSocket;
  lastHeartbeat: number;
  isAlive: boolean;
}

export interface ServerConfig {
  port: number;
  heartbeatInterval: number;
  heartbeatTimeout: number;
  cursorThrottleMs: number;
  operationBufferTimeoutMs: number;
  operationBufferMaxSize: number;
  maxOperationsPerRoom: number;
}

export const DEFAULT_CONFIG: ServerConfig = {
  port: 8080,
  heartbeatInterval: 30000,
  heartbeatTimeout: 60000,
  cursorThrottleMs: 50,
  operationBufferTimeoutMs: 50,
  operationBufferMaxSize: 32,
  maxOperationsPerRoom: 10000
};
