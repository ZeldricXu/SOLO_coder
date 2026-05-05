import {
  StartSessionMessage,
  EndSessionMessage,
  AudioChunkMessage,
  TranscribeResult,
  TranslationUpdate,
  ChunkAck,
} from '../types';

type MessageHandler = (message: unknown) => void;
type ErrorHandler = (error: Event) => void;
type ConnectionHandler = () => void;

const WS_URL =
  window.location.protocol === 'https:'
    ? `wss://${window.location.host}/ws`
    : `ws://${window.location.host}/ws`;

export class WebSocketService {
  private ws: WebSocket | null = null;
  private messageHandlers: Set<MessageHandler> = new Set();
  private errorHandlers: Set<ErrorHandler> = new Set();
  private openHandlers: Set<ConnectionHandler> = new Set();
  private closeHandlers: Set<ConnectionHandler> = new Set();
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 1000;
  private isManualClose = false;

  public connect(url: string = WS_URL): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.isManualClose = false;
        this.ws = new WebSocket(url);

        this.ws.onopen = () => {
          console.log('WebSocket connected');
          this.reconnectAttempts = 0;
          this.openHandlers.forEach(handler => handler());
          resolve();
        };

        this.ws.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data);
            this.messageHandlers.forEach(handler => handler(message));
          } catch (error) {
            console.error('Failed to parse WebSocket message:', error);
          }
        };

        this.ws.onerror = (error) => {
          console.error('WebSocket error:', error);
          this.errorHandlers.forEach(handler => handler(error));
          reject(error);
        };

        this.ws.onclose = (event) => {
          console.log('WebSocket closed:', event.code, event.reason);
          this.closeHandlers.forEach(handler => handler());

          if (!this.isManualClose && this.reconnectAttempts < this.maxReconnectAttempts) {
            this.reconnectAttempts++;
            console.log(`Attempting to reconnect (${this.reconnectAttempts}/${this.maxReconnectAttempts})...`);
            setTimeout(() => {
              this.connect(url);
            }, this.reconnectDelay * this.reconnectAttempts);
          }
        };
      } catch (error) {
        reject(error);
      }
    });
  }

  public disconnect(): void {
    this.isManualClose = true;
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }

  public isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  public send(message: StartSessionMessage | EndSessionMessage | AudioChunkMessage): void {
    if (!this.isConnected()) {
      throw new Error('WebSocket is not connected');
    }
    this.ws?.send(JSON.stringify(message));
  }

  public startSession(
    sessionId: string,
    audioLanguage: string,
    targetLanguage: string | undefined,
    enableTranslation: boolean
  ): void {
    const message: StartSessionMessage = {
      action: 'start_session',
      data: {
        sessionId,
        audioLanguage,
        targetLanguage,
        enableTranslation,
      },
    };
    this.send(message);
  }

  public endSession(sessionId: string): void {
    const message: EndSessionMessage = {
      action: 'end_session',
      data: { sessionId },
    };
    this.send(message);
  }

  public sendAudioChunk(
    audioData: string,
    sequence: number,
    metadata?: {
      isFirstChunk: boolean;
      overlapSamples: number;
      totalSamples: number;
    }
  ): void {
    const message: AudioChunkMessage = {
      action: 'audio_chunk',
      data: {
        audioData,
        sequence,
        timestamp: Date.now(),
        metadata: metadata || {
          isFirstChunk: false,
          overlapSamples: 0,
          totalSamples: 0,
        },
      },
    };
    this.send(message);
  }

  public onMessage(handler: MessageHandler): () => void {
    this.messageHandlers.add(handler);
    return () => this.messageHandlers.delete(handler);
  }

  public onTranscribeResult(handler: (result: TranscribeResult['data']) => void): () => void {
    return this.onMessage((message) => {
      const msg = message as { event?: string; data?: unknown };
      if (msg.event === 'transcribe_result' && msg.data) {
        handler(msg.data as TranscribeResult['data']);
      }
    });
  }

  public onSessionStarted(handler: (data: { sessionId: string }) => void): () => void {
    return this.onMessage((message) => {
      const msg = message as { event?: string; data?: unknown };
      if (msg.event === 'session_started' && msg.data) {
        handler(msg.data as { sessionId: string });
      }
    });
  }

  public onSessionEnded(
    handler: (data: { transcribeId: string; totalDuration: number; segmentCount: number }) => void
  ): () => void {
    return this.onMessage((message) => {
      const msg = message as { event?: string; data?: unknown };
      if (msg.event === 'session_ended' && msg.data) {
        handler(msg.data as { transcribeId: string; totalDuration: number; segmentCount: number });
      }
    });
  }

  public onTranslationUpdate(handler: (result: TranslationUpdate['data']) => void): () => void {
    return this.onMessage((message) => {
      const msg = message as { event?: string; data?: unknown };
      if (msg.event === 'translation_update' && msg.data) {
        handler(msg.data as TranslationUpdate['data']);
      }
    });
  }

  public onChunkAck(handler: (result: ChunkAck['data']) => void): () => void {
    return this.onMessage((message) => {
      const msg = message as { event?: string; data?: unknown };
      if (msg.event === 'chunk_ack' && msg.data) {
        handler(msg.data as ChunkAck['data']);
      }
    });
  }

  public onError(handler: ErrorHandler): () => void {
    this.errorHandlers.add(handler);
    return () => this.errorHandlers.delete(handler);
  }

  public onOpen(handler: ConnectionHandler): () => void {
    this.openHandlers.add(handler);
    return () => this.openHandlers.delete(handler);
  }

  public onClose(handler: ConnectionHandler): () => void {
    this.closeHandlers.add(handler);
    return () => this.closeHandlers.delete(handler);
  }
}

export const wsService = new WebSocketService();
