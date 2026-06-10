import type { User, CRDTOperation, Point } from '../types';

interface CollabClientOptions {
  roomId: string;
  userId: string;
  userName: string;
  userColor: string;
  onOperation: (operation: CRDTOperation) => void;
  onUserJoin: (user: User) => void;
  onUserLeave: (userId: string) => void;
  onUsersList: (users: User[]) => void;
  onCursorUpdate: (userId: string, position: Point) => void;
  onError: (error: Error) => void;
  onConnect: () => void;
  onDisconnect: () => void;
}

type CollabMessage =
  | { type: 'join'; payload: { user: User } }
  | { type: 'leave'; payload: { userId: string } }
  | { type: 'users'; payload: { users: User[] } }
  | { type: 'operation'; payload: { operation: CRDTOperation } }
  | { type: 'cursor'; payload: { userId: string; position: Point } }
  | { type: 'hello'; payload: { user: User } }
  | { type: 'error'; payload: { message: string } };

export class CollabClient {
  private ws: WebSocket | null = null;
  private url: string;
  private options: CollabClientOptions;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 1000;
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;

  constructor(url: string, options: CollabClientOptions) {
    this.url = url;
    this.options = options;
  }

  async connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.ws = new WebSocket(this.url);

        this.ws.onopen = () => {
          this.reconnectAttempts = 0;
          this.startHeartbeat();

          const user: User = {
            id: this.options.userId,
            name: this.options.userName,
            color: this.options.userColor,
            isOnline: true,
            lastActive: Date.now(),
          };

          this.sendMessage({
            type: 'hello',
            payload: { user },
          });

          this.options.onConnect();
          resolve();
        };

        this.ws.onmessage = (event) => {
          try {
            const message: CollabMessage = JSON.parse(event.data);
            this.handleMessage(message);
          } catch (error) {
            console.error('Failed to parse message:', error);
          }
        };

        this.ws.onerror = (event) => {
          const error = new Error(`WebSocket error: ${event.type}`);
          this.options.onError(error);
          reject(error);
        };

        this.ws.onclose = () => {
          this.stopHeartbeat();
          this.options.onDisconnect();
          this.attemptReconnect();
        };
      } catch (error) {
        reject(error instanceof Error ? error : new Error('Failed to connect'));
      }
    });
  }

  disconnect(): void {
    this.stopHeartbeat();
    this.reconnectAttempts = this.maxReconnectAttempts;

    if (this.ws) {
      if (this.ws.readyState === WebSocket.OPEN) {
        this.sendMessage({
          type: 'leave',
          payload: { userId: this.options.userId },
        });
      }
      this.ws.close();
      this.ws = null;
    }
  }

  sendOperation(operation: CRDTOperation): void {
    this.sendMessage({
      type: 'operation',
      payload: { operation },
    });
  }

  sendCursor(position: Point): void {
    this.sendMessage({
      type: 'cursor',
      payload: {
        userId: this.options.userId,
        position,
      },
    });
  }

  sendMessage(message: Record<string, unknown>): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message));
    }
  }

  private handleMessage(message: CollabMessage): void {
    switch (message.type) {
      case 'join':
        this.options.onUserJoin(message.payload.user);
        break;
      case 'leave':
        this.options.onUserLeave(message.payload.userId);
        break;
      case 'users':
        this.options.onUsersList(message.payload.users);
        break;
      case 'operation':
        this.options.onOperation(message.payload.operation);
        break;
      case 'cursor':
        this.options.onCursorUpdate(
          message.payload.userId,
          message.payload.position
        );
        break;
      case 'error':
        this.options.onError(new Error(message.payload.message));
        break;
    }
  }

  private startHeartbeat(): void {
    this.heartbeatInterval = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping' }));
      }
    }, 30000);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
  }

  private attemptReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      return;
    }

    this.reconnectAttempts++;
    const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1);

    setTimeout(() => {
      this.connect().catch((error) => {
        console.error('Reconnect failed:', error);
      });
    }, delay);
  }

  isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }
}

export default CollabClient;
